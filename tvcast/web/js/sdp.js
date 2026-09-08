/**
 * SDP munging helpers.
 *
 * The Android TV panels we target are old (2015-2019 era, often quad-core
 * Cortex-A53). Their only *reliable hardware* video decoder is H.264 Baseline
 * or Main. VP8 at 1080p and anything VP9/AV1 falls back to software decode and
 * turns into a slideshow. So we force H.264 to the front of the m-line and,
 * where possible, prefer a packetization-mode=1 profile the hardware likes.
 */

/** Reorder the video m-line so H.264 payload types come first. */
export function preferH264(sdp) {
  const lines = sdp.split(/\r\n|\n/);
  const mIndex = lines.findIndex((l) => l.startsWith('m=video'));
  if (mIndex === -1) return sdp;

  // Collect payload types whose rtpmap says H264.
  const h264Payloads = [];
  for (const line of lines) {
    const m = line.match(/^a=rtpmap:(\d+)\s+H264\/90000/i);
    if (m) h264Payloads.push(m[1]);
  }
  if (!h264Payloads.length) return sdp;

  // Among those, prefer profiles with packetization-mode=1 (widely supported
  // in hardware, allows fragmented NALs) over mode 0.
  const modeOne = new Set();
  for (const line of lines) {
    const m = line.match(/^a=fmtp:(\d+)\s+(.*)$/);
    if (m && h264Payloads.includes(m[1]) && /packetization-mode=1/.test(m[2])) {
      modeOne.add(m[1]);
    }
  }
  const ordered = [
    ...h264Payloads.filter((p) => modeOne.has(p)),
    ...h264Payloads.filter((p) => !modeOne.has(p)),
  ];

  const parts = lines[mIndex].split(' ');
  const header = parts.slice(0, 3);            // m=video <port> <proto>
  const payloads = parts.slice(3);
  const rest = payloads.filter((p) => !ordered.includes(p));
  lines[mIndex] = [...header, ...ordered, ...rest].join(' ');

  return lines.join('\r\n');
}

/**
 * Raise the bitrate ceiling. WebRTC's defaults are tuned for video calls
 * (~2 Mbps) which makes shared text look mushy. We set b=AS on the video
 * m-line as a belt-and-braces companion to the sender-side encoding params,
 * because some older receivers honour b=AS but ignore REMB.
 */
export function setVideoBitrate(sdp, kbps) {
  const lines = sdp.split(/\r\n|\n/);
  const out = [];
  let inVideo = false;

  for (const line of lines) {
    if (line.startsWith('m=')) inVideo = line.startsWith('m=video');
    if (inVideo && (line.startsWith('b=AS:') || line.startsWith('b=TIAS:'))) continue;
    out.push(line);
    // b= must come directly after c= within the media section.
    if (inVideo && line.startsWith('c=')) {
      out.push(`b=AS:${kbps}`);
      out.push(`b=TIAS:${Math.round(kbps * 1000)}`);
    }
  }
  return out.join('\r\n');
}

/** Apply every tweak we want on an outgoing offer. */
export function tuneOffer(sdp, kbps) {
  return setVideoBitrate(preferH264(sdp), kbps);
}
