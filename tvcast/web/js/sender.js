/**
 * Shared sender logic for both the phone camera and the desktop screen share.
 *
 * The sender is always the WebRTC *offerer*: it has the media, so it builds the
 * offer, applies our quality tuning, and the TV just answers. That keeps the
 * Android side simpler (no renegotiation dance on a weak CPU).
 */

import { setVideoBitrate, setOpusQuality } from './sdp.js';

/**
 * Derive the mount prefix from our own URL. The pages live at
 * <base>/j/CODE and <base>/pc/CODE, so stripping the last two segments
 * yields the base - which lets the same files work at a domain root or
 * under a path like /panelcast.
 */
function detectBasePath() {
  // The server injects this into every HTML page - authoritative, and avoids
  // guessing from a URL shape that varies (/mount, /mount/, /mount/j/CODE).
  if (typeof window.PANELCAST_BASE === 'string') return window.PANELCAST_BASE;

  // Fallbacks for opening a page directly during development.
  const m = location.pathname.match(/^(.*?)\/(?:s|j|pc)\/[A-Za-z0-9]{4,12}\/?$/);
  if (m) return m[1];
  return location.pathname.replace(/\/[^/]*$/, '');
}

const BASE_PATH = detectBasePath();
const WS_PATH = `${BASE_PATH}/ws`;
const CONFIG_URL = `${BASE_PATH}/config.json`;

export class Sender {
  /**
   * @param {object} opts
   * @param {string} opts.room       6-char pairing code
   * @param {'camera'|'screen'} opts.role
   * @param {(state:string, detail?:string)=>void} opts.onState
   */
  constructor(opts) {
    this.room = opts.room;
    this.role = opts.role;
    this.onState = opts.onState || (() => {});
    this.ws = null;
    this.pc = null;
    this.stream = null;
    this.iceServers = [{ urls: 'stun:stun.l.google.com:19302' }];
    this.statsTimer = null;
    this.closed = false;
    this.pendingIce = [];
  }

  async connect(stream, bitrateKbps, maxFramerate, opts = {}) {
    this.stream = stream;
    this.bitrateKbps = bitrateKbps;
    // Cap the encoder's framerate too, not just capture: a lower rate leaves
    // more bits per frame, which is what keeps text legible on a slow decoder.
    this.maxFramerate = maxFramerate || 30;
    this.audioKbps = opts.audioKbps || 128;
    this.stereo = opts.stereo !== false;
    this.caps = null;

    // Pull ICE config before opening the socket so the PC is ready immediately.
    try {
      const res = await fetch(CONFIG_URL, { cache: 'no-store' });
      if (res.ok) {
        const cfg = await res.json();
        if (Array.isArray(cfg.iceServers) && cfg.iceServers.length) {
          this.iceServers = cfg.iceServers;
        }
      }
    } catch (_) { /* fall back to default STUN */ }

    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    this.ws = new WebSocket(`${proto}//${location.host}${WS_PATH}`);

    this.ws.onopen = () => {
      this.onState('signaling');
      this._send({ type: 'join', room: this.room, role: this.role });
    };

    this.ws.onmessage = async (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch (_) { return; }
      await this._onMessage(msg);
    };

    this.ws.onclose = () => {
      if (!this.closed) this.onState('disconnected', 'Connection closed');
    };
    this.ws.onerror = () => {
      if (!this.closed) this.onState('error', 'Signaling error');
    };
  }

  async _onMessage(msg) {
    switch (msg.type) {
      case 'joined':
        if (Array.isArray(msg.iceServers) && msg.iceServers.length) {
          this.iceServers = msg.iceServers;
        }
        if (msg.caps) {
          this.caps = msg.caps;
          this.onCaps && this.onCaps(msg.caps);
        }
        await this._startOffer();
        break;

      case 'caps':
        this.caps = msg.caps;
        this.onCaps && this.onCaps(msg.caps);
        break;

      case 'answer':
        if (!this.pc) return;
        await this.pc.setRemoteDescription({ type: 'answer', sdp: msg.sdp });
        // Flush ICE that arrived before the remote description was set.
        for (const c of this.pendingIce) {
          try { await this.pc.addIceCandidate(c); } catch (_) {}
        }
        this.pendingIce = [];
        break;

      case 'ice':
        if (!msg.candidate) return;
        if (!this.pc || !this.pc.remoteDescription) {
          this.pendingIce.push(msg.candidate);
        } else {
          try { await this.pc.addIceCandidate(msg.candidate); } catch (_) {}
        }
        break;

      case 'error':
        this.onState('error', errorText(msg.code));
        if (['no_room', 'busy', 'host_gone', 'host_ended', 'rate_limited'].includes(msg.code)) {
          this.close();
        }
        break;
    }
  }

  async _startOffer() {
    this.pc = new RTCPeerConnection({
      iceServers: this.iceServers,
      bundlePolicy: 'max-bundle',
      rtcpMuxPolicy: 'require',
    });

    this.pc.onicecandidate = (ev) => {
      if (ev.candidate) this._send({ type: 'ice', candidate: ev.candidate.toJSON() });
    };

    this.pc.onconnectionstatechange = () => {
      const st = this.pc.connectionState;
      if (st === 'connected') {
        this.onState('live');
        this._startStats();
      } else if (st === 'failed') {
        // Almost always a symmetric-NAT / client-isolation network with no TURN.
        this.onState('error', 'Could not establish a direct connection. Try the same Wi-Fi network as the display.');
      } else if (st === 'disconnected') {
        this.onState('reconnecting');
      }
    };

    // Add tracks, then constrain the senders.
    for (const track of this.stream.getTracks()) {
      this.pc.addTrack(track, this.stream);
    }
    this._tuneVideoSender();
    this._tuneAudioSender();

    const offer = await this.pc.createOffer({
      offerToReceiveAudio: false,
      offerToReceiveVideo: false,
    });
    // Only raise the bitrate ceiling here. We deliberately do NOT reorder
    // codecs in the offer: the receiver knows which decoders it actually has,
    // and its answer selects the codec. Forcing H.264 from the sender broke
    // panels whose MediaCodec H.264 was unavailable, because this WebRTC
    // build has no software H.264 fallback.
    offer.sdp = setVideoBitrate(offer.sdp, this.bitrateKbps);
    // Opus defaults to mono at speech bitrates; ask for stereo/full-band.
    offer.sdp = setOpusQuality(offer.sdp, this.audioKbps, this.stereo !== false);
    await this.pc.setLocalDescription(offer);

    this._send({ type: 'offer', sdp: this.pc.localDescription.sdp });
    this.onState('connecting');
  }

  /**
   * Quality knobs that matter most for legibility on a big screen.
   */
  _tuneVideoSender() {
    const sender = this.pc.getSenders().find((s) => s.track && s.track.kind === 'video');
    if (!sender) return;

    const track = sender.track;

    // contentHint tells the encoder what to protect when bandwidth is tight.
    // 'detail' => keep resolution/sharpness (right for text, slides, documents)
    // 'motion' => keep framerate (right for video playback)
    if ('contentHint' in track) {
      track.contentHint = this.role === 'screen' ? 'detail' : 'detail';
    }

    const params = sender.getParameters();
    if (!params.encodings || !params.encodings.length) params.encodings = [{}];
    params.encodings[0].maxBitrate = this.bitrateKbps * 1000;
    params.encodings[0].maxFramerate = this.maxFramerate;
    // Never let the encoder shrink the picture to protect framerate - blurry
    // text is worse than occasional judder for our use cases.
    params.encodings[0].scaleResolutionDownBy = 1;
    if ('degradationPreference' in params) {
      params.degradationPreference = 'maintain-resolution';
    }
    sender.setParameters(params).catch(() => { /* older browsers: best effort */ });
  }

  /**
   * Audio deserves its own ceiling. Music and video soundtracks need far more
   * than the ~32kbps WebRTC defaults to for speech, and stereo matters when
   * sharing a video or a music-bearing presentation.
   */
  _tuneAudioSender() {
    const sender = this.pc.getSenders().find((s) => s.track && s.track.kind === 'audio');
    if (!sender) return;
    try {
      const params = sender.getParameters();
      if (!params.encodings || !params.encodings.length) params.encodings = [{}];
      params.encodings[0].maxBitrate = (this.audioKbps || 128) * 1000;
      // Audio is tiny next to video; never let congestion control drop it.
      params.encodings[0].priority = 'high';
      params.encodings[0].networkPriority = 'high';
      sender.setParameters(params).catch(() => {});
    } catch (_) { /* best effort */ }
  }

  /** Report live bitrate/resolution so the user can see what they're sending. */
  _startStats() {
    if (this.statsTimer) clearInterval(this.statsTimer);
    let lastBytes = 0;
    let lastTs = 0;

    this.statsTimer = setInterval(async () => {
      if (!this.pc || this.pc.connectionState !== 'connected') return;
      let stats;
      try { stats = await this.pc.getStats(); } catch (_) { return; }

      stats.forEach((r) => {
        if (r.type === 'outbound-rtp' && r.kind === 'video' && !r.isRemote) {
          const now = r.timestamp;
          const bytes = r.bytesSent || 0;
          let kbps = 0;
          if (lastTs && now > lastTs) {
            kbps = Math.round(((bytes - lastBytes) * 8) / (now - lastTs));
          }
          lastBytes = bytes;
          lastTs = now;
          this.onStats && this.onStats({
            kbps,
            width: r.frameWidth || 0,
            height: r.frameHeight || 0,
            fps: Math.round(r.framesPerSecond || 0),
          });
        }
      });
    }, 1000);
  }

  _send(obj) {
    if (this.ws && this.ws.readyState === 1) this.ws.send(JSON.stringify(obj));
  }

  close() {
    this.closed = true;
    if (this.statsTimer) clearInterval(this.statsTimer);
    this._send({ type: 'bye' });
    if (this.pc) { try { this.pc.close(); } catch (_) {} this.pc = null; }
    if (this.ws) { try { this.ws.close(); } catch (_) {} }
    if (this.stream) {
      for (const t of this.stream.getTracks()) { try { t.stop(); } catch (_) {} }
    }
  }
}

export function errorText(code) {
  switch (code) {
    case 'no_room':   return 'That code is not on screen any more. Check the display and try again.';
    case 'busy':      return 'Someone else is already sharing to this display.';
    case 'host_gone': return 'The display disconnected.';
    case 'host_ended':return 'The display ended the session.';
    case 'rate_limited': return 'Too many attempts. Wait a few minutes and retry.';
    default:          return 'Something went wrong. Please try again.';
  }
}

/** Pull the room code out of <base>/s|j|pc/CODE, or the ?room= query. */
export function roomFromLocation() {
  const m = location.pathname.match(/\/(?:s|j|pc)\/([A-Za-z0-9]{4,12})/);
  if (m) return m[1].toUpperCase();
  const q = new URLSearchParams(location.search).get('room');
  return q ? q.toUpperCase() : '';
}
