#!/usr/bin/env node
'use strict';

/**
 * PanelCast signaling server.
 *
 * Rooms are ephemeral and in-memory. A "host" is an Android TV panel showing a
 * pairing code; a "sender" is a phone camera or a desktop screen share that
 * joins that code. The server only relays SDP/ICE - it never touches media.
 *
 * Deliberately has zero dependencies on the StrideShow app: own port, own
 * process, no database.
 */

const http = require('http');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = parseInt(process.env.PANELCAST_PORT || '3100', 10);
const HOST = process.env.PANELCAST_HOST || '127.0.0.1';
const WEB_ROOT = path.resolve(__dirname, '..', 'web');

// Public base URL embedded in the QR code shown on the TV. This mounts under a
// path on the existing StrideShow domain, so no extra DNS record or TLS cert
// is needed - nginx proxies /panelcast/* here.
const PUBLIC_BASE = (process.env.PANELCAST_PUBLIC_BASE || 'https://www.strideshow.com/panelcast').replace(/\/+$/, '');

// Path prefix this app is mounted under, derived from PUBLIC_BASE so the two
// can never drift apart. '' when served at a domain root.
const BASE_PATH = (() => {
  const m = PUBLIC_BASE.match(/^https?:\/\/[^/]+(\/.*)$/);
  return m ? m[1].replace(/\/+$/, '') : '';
})();

// ICE servers handed to every peer. TURN is optional (see docs/DEPLOY.md).
const ICE_SERVERS = buildIceServers();

// Room codes: no 0/O/1/I/L to keep them readable from across a room.
const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
const CODE_LEN = 6;

const ROOM_TTL_MS = 12 * 60 * 60 * 1000; // reclaim abandoned rooms after 12h
const HEARTBEAT_MS = 25000;              // keep NAT/proxy paths alive
const JOIN_FAIL_LIMIT = 20;              // per-IP wrong-code attempts...
const JOIN_FAIL_WINDOW_MS = 5 * 60 * 1000; // ...within this window

/** @type {Map<string, Room>} */
const rooms = new Map();
/** @type {Map<string, {count:number, first:number}>} */
const joinFails = new Map();

function buildIceServers() {
  const list = [
    { urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] },
  ];
  if (process.env.PANELCAST_TURN_URL) {
    list.push({
      urls: process.env.PANELCAST_TURN_URL.split(',').map((s) => s.trim()).filter(Boolean),
      username: process.env.PANELCAST_TURN_USER || undefined,
      credential: process.env.PANELCAST_TURN_PASS || undefined,
    });
  }
  return list;
}

function log(...args) {
  console.log(new Date().toISOString(), ...args);
}

function newCode() {
  for (let attempt = 0; attempt < 500; attempt++) {
    const bytes = crypto.randomBytes(CODE_LEN);
    let code = '';
    for (let i = 0; i < CODE_LEN; i++) code += CODE_ALPHABET[bytes[i] % CODE_ALPHABET.length];
    if (!rooms.has(code)) return code;
  }
  throw new Error('code space exhausted');
}

function peerId() {
  return crypto.randomBytes(8).toString('hex');
}

class Room {
  constructor(code, host) {
    this.code = code;
    this.host = host;       // the TV socket
    this.sender = null;     // at most one active sender
    this.createdAt = Date.now();
    // What the display can actually decode. Sent by the TV, forwarded to
    // senders so their UI only offers modes the panel can handle - offering
    // 4K to a 1080p decoder is how you get a black screen.
    this.caps = null;
  }
}

function send(ws, obj) {
  if (ws && ws.readyState === 1) {
    try { ws.send(JSON.stringify(obj)); } catch (_) { /* closing */ }
  }
}

function clientIp(req) {
  // nginx sets X-Forwarded-For; trust the left-most entry behind our own proxy.
  const xff = req.headers['x-forwarded-for'];
  if (xff) return String(xff).split(',')[0].trim();
  return req.socket.remoteAddress || 'unknown';
}

function tooManyJoinFailures(ip) {
  const rec = joinFails.get(ip);
  if (!rec) return false;
  if (Date.now() - rec.first > JOIN_FAIL_WINDOW_MS) { joinFails.delete(ip); return false; }
  return rec.count >= JOIN_FAIL_LIMIT;
}

function noteJoinFailure(ip) {
  const now = Date.now();
  const rec = joinFails.get(ip);
  if (!rec || now - rec.first > JOIN_FAIL_WINDOW_MS) joinFails.set(ip, { count: 1, first: now });
  else rec.count++;
}

// ---------------------------------------------------------------------------
// Static file serving for the sender pages
// ---------------------------------------------------------------------------

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json',
};

function serveFile(res, filePath, extraHeaders = {}) {
  fs.readFile(filePath, (err, buf) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      return res.end('Not found');
    }
    const ext = path.extname(filePath).toLowerCase();

    // Tell the page where it is mounted, so client code never has to infer
    // the base path from location.pathname (which is ambiguous for /mount
    // vs /mount/ and for /mount/j/CODE).
    if (ext === '.html') {
      const inject = `<script>window.PANELCAST_BASE=${JSON.stringify(BASE_PATH)};</script>`;
      buf = Buffer.from(buf.toString('utf8').replace('</head>', `${inject}\n</head>`), 'utf8');
    }

    res.writeHead(200, {
      'Content-Type': MIME[ext] || 'application/octet-stream',
      'Content-Length': buf.length,
      'Cache-Control': ext === '.html' ? 'no-cache' : 'public, max-age=300',
      ...extraHeaders,
    });
    res.end(buf);
  });
}

const httpServer = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  let pathname = decodeURIComponent(url.pathname);

  // Strip the mount prefix if nginx forwards the full path. Supporting both
  // forms means the same build works behind a path-based proxy and at a
  // domain root, and survives an nginx config that does not rewrite.
  if (BASE_PATH && pathname.startsWith(BASE_PATH)) {
    pathname = pathname.slice(BASE_PATH.length) || '/';
  }

  // Health check for pm2 / uptime monitoring.
  if (pathname === '/healthz') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({
      ok: true,
      rooms: rooms.size,
      uptime: Math.round(process.uptime()),
    }));
  }

  // Runtime config so the web senders learn the ICE servers and where to
  // open the WebSocket (which depends on the mount path).
  if (pathname === '/config.json') {
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-cache' });
    return res.end(JSON.stringify({ iceServers: ICE_SERVERS, basePath: BASE_PATH }));
  }

  // Join links. A single page now handles both camera and screen sharing and
  // lets the user choose, which removes the "which page am I on?" confusion.
  //
  // /s/CODE  - the canonical link, encoded in the QR
  // /j/CODE  - legacy camera link; still works, opens straight into camera
  // /pc/CODE - legacy screen link; still works, opens straight into screen
  //
  // The legacy forms are kept because a QR code may already be on a screen
  // somewhere, and because they are a genuinely useful shortcut.
  if (/^\/(?:s|j|pc)\/[A-Za-z0-9]{4,12}\/?$/.test(pathname)) {
    return serveFile(res, path.join(WEB_ROOT, 'share.html'));
  }

  // Redirect the bare mount to a trailing slash. Without this, relative asset
  // paths on the landing page resolve one level too high (/panelcast ->
  // /css/app.css instead of /panelcast/css/app.css), so the page loads
  // unstyled and navigation targets the wrong path.
  if (BASE_PATH && (pathname === '/' || pathname === '') && !url.pathname.endsWith('/')) {
    res.writeHead(301, { Location: `${BASE_PATH}/${url.search || ''}` });
    return res.end();
  }

  if (pathname === '/' || pathname === '') pathname = '/index.html';

  // Contain traversal: resolve and verify the result stays under WEB_ROOT.
  const target = path.resolve(path.join(WEB_ROOT, pathname));
  if (target !== WEB_ROOT && !target.startsWith(WEB_ROOT + path.sep)) {
    res.writeHead(403, { 'Content-Type': 'text/plain' });
    return res.end('Forbidden');
  }
  serveFile(res, target);
});

// ---------------------------------------------------------------------------
// WebSocket signaling
// ---------------------------------------------------------------------------

// Accept the WebSocket on both the bare and prefixed path, for the same
// reason the HTTP router strips the prefix: work either side of a rewrite.
const WS_PATHS = new Set(['/ws', `${BASE_PATH}/ws`]);
const wss = new WebSocketServer({
  noServer: true,
  maxPayload: 256 * 1024,
});

httpServer.on('upgrade', (req, socket, head) => {
  let pathname;
  try {
    pathname = new URL(req.url, `http://${req.headers.host || 'localhost'}`).pathname;
  } catch (_) {
    return socket.destroy();
  }
  if (!WS_PATHS.has(pathname.replace(/\/+$/, '') || '/ws')) {
    // Not our endpoint: refuse rather than leaving the socket hanging.
    socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
    return socket.destroy();
  }
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
});

wss.on('connection', (ws, req) => {
  ws.id = peerId();
  ws.ip = clientIp(req);
  ws.role = null;      // 'host' | 'camera' | 'screen'
  ws.room = null;
  ws.isAlive = true;

  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch (_) {
      return send(ws, { type: 'error', code: 'bad_json' });
    }
    if (!msg || typeof msg.type !== 'string') {
      return send(ws, { type: 'error', code: 'bad_message' });
    }
    try { handleMessage(ws, msg); } catch (err) {
      log('handler error', err && err.message);
      send(ws, { type: 'error', code: 'server_error' });
    }
  });

  ws.on('close', () => handleClose(ws));
  ws.on('error', () => { /* close follows */ });
});

function handleMessage(ws, msg) {
  switch (msg.type) {
    case 'host': return onHost(ws);
    case 'caps': return onCaps(ws, msg);
    case 'join': return onJoin(ws, msg);
    case 'offer':
    case 'answer':
    case 'ice':
      return onRelay(ws, msg);
    case 'bye':
      return onBye(ws);
    case 'ping':
      return send(ws, { type: 'pong' });
    default:
      return send(ws, { type: 'error', code: 'unknown_type' });
  }
}

/** TV panel claims a fresh room code. */
function onHost(ws) {
  if (ws.room) return send(ws, { type: 'error', code: 'already_in_room' });

  const code = newCode();
  const room = new Room(code, ws);
  rooms.set(code, room);
  ws.role = 'host';
  ws.room = code;

  send(ws, {
    type: 'hosted',
    room: code,
    // Everything the TV needs to render the QR code without hardcoding a URL.
    // Single URL for the QR code and for reading aloud. The page asks what
    // the user wants to share, so one link covers both cases.
    joinUrl: `${PUBLIC_BASE}/s/${code}`,
    // Retained for older receivers that still show two separate hints.
    pcUrl: `${PUBLIC_BASE}/s/${code}`,
    iceServers: ICE_SERVERS,
  });
  log(`room ${code} hosted by ${ws.ip}`);
}

/**
 * TV reports its decode capabilities. Sanitised, because these values drive
 * what a sender will try to transmit.
 */
function onCaps(ws, msg) {
  const room = ws.role === 'host' && ws.room && rooms.get(ws.room);
  if (!room) return send(ws, { type: 'error', code: 'not_host' });

  const c = msg.caps || {};
  const maxHeight = Number(c.maxHeight);
  room.caps = {
    maxHeight: Number.isFinite(maxHeight) ? Math.min(Math.max(maxHeight, 360), 4320) : 1080,
    codecs: Array.isArray(c.codecs) ? c.codecs.slice(0, 8).map(String) : [],
    model: typeof c.model === 'string' ? c.model.slice(0, 64) : '',
  };
  log(`room ${room.code}: caps maxHeight=${room.caps.maxHeight} codecs=${room.caps.codecs.join('/')}`);

  // If a sender is already connected, let it know.
  if (room.sender && room.sender.readyState === 1) {
    send(room.sender, { type: 'caps', caps: room.caps });
  }
}

/** Phone or desktop joins an existing room. */
function onJoin(ws, msg) {
  if (ws.room) return send(ws, { type: 'error', code: 'already_in_room' });

  if (tooManyJoinFailures(ws.ip)) {
    send(ws, { type: 'error', code: 'rate_limited' });
    return ws.close(4029, 'rate limited');
  }

  const code = String(msg.room || '').toUpperCase().trim();
  const role = msg.role === 'screen' ? 'screen' : 'camera';
  const room = rooms.get(code);

  if (!room) {
    noteJoinFailure(ws.ip);
    return send(ws, { type: 'error', code: 'no_room' });
  }
  // One sender at a time keeps the TV's decode path simple and predictable.
  if (room.sender && room.sender.readyState === 1) {
    return send(ws, { type: 'error', code: 'busy' });
  }

  room.sender = ws;
  ws.role = role;
  ws.room = code;

  send(ws, { type: 'joined', room: code, role, iceServers: ICE_SERVERS, caps: room.caps });
  send(room.host, { type: 'peer-join', peerId: ws.id, role });
  log(`room ${code}: ${role} joined from ${ws.ip}`);
}

/** Relay SDP/ICE to the other party in the room. */
function onRelay(ws, msg) {
  const room = ws.room && rooms.get(ws.room);
  if (!room) return send(ws, { type: 'error', code: 'not_in_room' });

  const target = ws.role === 'host' ? room.sender : room.host;
  if (!target || target.readyState !== 1) {
    return send(ws, { type: 'error', code: 'no_peer' });
  }
  send(target, { ...msg, from: ws.id });
}

/** Sender hangs up, or TV drops the current sender. */
function onBye(ws) {
  const room = ws.room && rooms.get(ws.room);
  if (!room) return;

  if (ws.role === 'host') {
    if (room.sender) {
      send(room.sender, { type: 'error', code: 'host_ended' });
      room.sender.close(4000, 'host ended');
      room.sender = null;
    }
  } else if (room.sender === ws) {
    room.sender = null;
    send(room.host, { type: 'peer-leave', peerId: ws.id });
  }
}

function handleClose(ws) {
  const room = ws.room && rooms.get(ws.room);
  if (!room) return;

  if (ws.role === 'host') {
    // TV went away: tear the room down so the code can't be reused.
    if (room.sender && room.sender.readyState === 1) {
      send(room.sender, { type: 'error', code: 'host_gone' });
      room.sender.close(4001, 'host gone');
    }
    rooms.delete(room.code);
    log(`room ${room.code} closed`);
  } else if (room.sender === ws) {
    room.sender = null;
    send(room.host, { type: 'peer-leave', peerId: ws.id });
    log(`room ${room.code}: sender left`);
  }
}

// Drop sockets that stop responding, and reclaim stale rooms.
setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!ws.isAlive) return ws.terminate();
    ws.isAlive = false;
    try { ws.ping(); } catch (_) { /* ignore */ }
  });

  const now = Date.now();
  for (const [code, room] of rooms) {
    if (now - room.createdAt > ROOM_TTL_MS && (!room.sender || room.sender.readyState !== 1)) {
      if (room.host && room.host.readyState === 1) room.host.close(4002, 'room expired');
      rooms.delete(code);
      log(`room ${code} expired`);
    }
  }
  for (const [ip, rec] of joinFails) {
    if (now - rec.first > JOIN_FAIL_WINDOW_MS) joinFails.delete(ip);
  }
}, HEARTBEAT_MS).unref();

httpServer.listen(PORT, HOST, () => {
  log(`PanelCast signaling on http://${HOST}:${PORT}`);
  log(`public base: ${PUBLIC_BASE}`);
  log(`web root: ${WEB_ROOT}`);
  log(`TURN configured: ${ICE_SERVERS.length > 1 ? 'yes' : 'no (STUN only)'}`);
});

function shutdown(signal) {
  log(`${signal} received, shutting down`);
  wss.clients.forEach((ws) => { try { ws.close(1001, 'server restart'); } catch (_) {} });
  httpServer.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000).unref();
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
