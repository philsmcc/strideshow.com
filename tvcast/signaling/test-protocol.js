#!/usr/bin/env node
'use strict';

/**
 * End-to-end signaling protocol test. Spawns the real server on a scratch port
 * and drives it with WebSocket clients that stand in for the TV and senders.
 *
 * Run: node test-protocol.js
 */

const { spawn } = require('child_process');
const WebSocket = require('ws');

const PORT = 39117;
// Exercise the mounted-under-a-path deployment, which is how this runs in
// production (nginx proxies https://www.strideshow.com/panelcast/* here).
const MOUNT = '/panelcast';
const BASE = `http://127.0.0.1:${PORT}`;
const WS_URL = `ws://127.0.0.1:${PORT}${MOUNT}/ws`;

let passed = 0;
let failed = 0;

function ok(name, cond, extra) {
  if (cond) { passed++; console.log(`  PASS  ${name}`); }
  else { failed++; console.log(`  FAIL  ${name}${extra ? ' :: ' + extra : ''}`); }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** WebSocket wrapper that queues messages so tests can await them in order. */
function client() {
  const ws = new WebSocket(WS_URL);
  const queue = [];
  const waiters = [];

  ws.on('message', (raw) => {
    const msg = JSON.parse(raw);
    if (waiters.length) waiters.shift()(msg);
    else queue.push(msg);
  });

  return {
    ws,
    open: () => new Promise((res, rej) => {
      ws.once('open', res);
      ws.once('error', rej);
    }),
    send: (o) => ws.send(JSON.stringify(o)),
    next: (timeout = 3000) => new Promise((res, rej) => {
      if (queue.length) return res(queue.shift());
      const t = setTimeout(() => rej(new Error('timeout waiting for message')), timeout);
      waiters.push((m) => { clearTimeout(t); res(m); });
    }),
    close: () => ws.close(),
  };
}

async function main() {
  console.log('Starting signaling server on port', PORT);
  const srv = spawn('node', [require('path').join(__dirname, 'server.js')], {
    env: {
      ...process.env,
      PANELCAST_PORT: String(PORT),
      PANELCAST_HOST: '127.0.0.1',
      PANELCAST_PUBLIC_BASE: `https://example.test${MOUNT}`,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  srv.stdout.on('data', (d) => process.stdout.write('    [srv] ' + d));
  srv.stderr.on('data', (d) => process.stderr.write('    [srv:err] ' + d));

  // Wait for the listener to come up.
  for (let i = 0; i < 50; i++) {
    try {
      const r = await fetch(BASE + MOUNT + '/healthz');
      if (r.ok) break;
    } catch (_) {}
    await sleep(100);
  }

  try {
    // --- HTTP surface -----------------------------------------------------
    console.log('\nHTTP endpoints');
    const health = await (await fetch(BASE + MOUNT + '/healthz')).json();
    ok('healthz reports ok (mounted path)', health.ok === true);

    // Bare paths must also work: nginx may or may not strip the prefix.
    const healthBare = await (await fetch(BASE + '/healthz')).json();
    ok('healthz reports ok (bare path)', healthBare.ok === true);

    const cfg = await (await fetch(BASE + MOUNT + '/config.json')).json();
    ok('config.json exposes iceServers', Array.isArray(cfg.iceServers) && cfg.iceServers.length > 0);
    ok('config.json reports basePath', cfg.basePath === MOUNT, JSON.stringify(cfg.basePath));

    const idx = await fetch(BASE + MOUNT + '/');
    ok('landing page served at mount root', idx.status === 200 && (await idx.text()).includes('PanelCast'));

    // The canonical share link, plus the two legacy aliases, must all serve
    // the unified page - old QR codes and typed links keep working.
    for (const prefix of ['s', 'j', 'pc']) {
      const r = await fetch(`${BASE}${MOUNT}/${prefix}/ABC123`);
      const body = await r.text();
      ok(`<mount>/${prefix}/<code> serves the share page`,
         r.status === 200 && body.includes('Share to the display'), `status ${r.status}`);
      ok(`/${prefix}/ page uses relative asset paths`,
         body.includes('../css/app.css') && body.includes('../js/sender.js'));
      ok(`/${prefix}/ page offers both camera and screen`,
         body.includes('pickCamera') && body.includes('pickScreen'));
    }

    // Assets resolve where the relative paths point.
    const css = await fetch(BASE + MOUNT + '/css/app.css');
    ok('stylesheet resolves under mount', css.status === 200);
    const js = await fetch(BASE + MOUNT + '/js/sender.js');
    const jsText = await js.text();
    ok('sender.js resolves under mount', js.status === 200);
    ok('sender.js derives base path dynamically', jsText.includes('detectBasePath'));

    const trav = await fetch(BASE + MOUNT + '/../signaling/server.js');
    ok('path traversal blocked', trav.status === 403 || trav.status === 404, 'status ' + trav.status);

    // Regression: the bare mount must 301 to the trailing-slash form.
    // Without it, relative assets resolved one level too high and the
    // landing page's navigation built /pc/CODE instead of /panelcast/pc/CODE,
    // which 404'd - the "desktop share goes to not found" bug.
    const bare = await fetch(BASE + MOUNT, { redirect: 'manual' });
    ok('bare mount redirects to trailing slash',
       bare.status === 301 && (bare.headers.get('location') || '').endsWith(MOUNT + '/'),
       `${bare.status} ${bare.headers.get('location')}`);

    // Regression: every HTML page must carry the authoritative base path so
    // clients never infer it from an ambiguous location.pathname.
    for (const p of ['/', '/s/ABC123', '/j/ABC123', '/pc/ABC123']) {
      const body = await (await fetch(BASE + MOUNT + p)).text();
      ok(`base path injected into ${p}`,
         body.includes(`window.PANELCAST_BASE=${JSON.stringify(MOUNT)}`), p);
    }

    // A wrong WS path must be refused, not left hanging.
    const badWs = await new Promise((res) => {
      const w = new WebSocket(`ws://127.0.0.1:${PORT}/nope`);
      w.on('open', () => { w.close(); res('opened'); });
      w.on('error', () => res('refused'));
    });
    ok('unknown websocket path refused', badWs === 'refused', badWs);

    // --- Hosting a room ---------------------------------------------------
    console.log('\nRoom hosting');
    const tv = client();
    await tv.open();
    tv.send({ type: 'host' });
    const hosted = await tv.next();
    ok('host receives hosted', hosted.type === 'hosted');
    ok('room code is 6 chars', /^[A-Z0-9]{6}$/.test(hosted.room || ''), hosted.room);
    ok('no ambiguous chars in code', !/[01OIL]/.test(hosted.room || ''), hosted.room);
    ok('joinUrl is the unified /s/ link',
       hosted.joinUrl === `https://example.test${MOUNT}/s/${hosted.room}`, hosted.joinUrl);
    ok('QR url contains the mount path (regression: path was dropped)',
       hosted.joinUrl.includes(`${MOUNT}/s/`), hosted.joinUrl);
    ok('iceServers sent to host', Array.isArray(hosted.iceServers) && hosted.iceServers.length > 0);

    const code = hosted.room;

    // --- Joining ----------------------------------------------------------
    console.log('\nSender join + relay');
    const phone = client();
    await phone.open();
    phone.send({ type: 'join', room: code, role: 'camera' });

    const joined = await phone.next();
    ok('sender receives joined', joined.type === 'joined' && joined.room === code);
    ok('sender gets iceServers', Array.isArray(joined.iceServers) && joined.iceServers.length > 0);

    const peerJoin = await tv.next();
    ok('host notified of peer-join', peerJoin.type === 'peer-join' && peerJoin.role === 'camera');

    // Offer -> TV
    phone.send({ type: 'offer', sdp: 'v=0-fake-offer' });
    const offer = await tv.next();
    ok('offer relayed to host', offer.type === 'offer' && offer.sdp === 'v=0-fake-offer');
    ok('offer carries from id', typeof offer.from === 'string' && offer.from.length > 0);

    // Answer -> phone
    tv.send({ type: 'answer', sdp: 'v=0-fake-answer' });
    const answer = await phone.next();
    ok('answer relayed to sender', answer.type === 'answer' && answer.sdp === 'v=0-fake-answer');

    // ICE both directions
    phone.send({ type: 'ice', candidate: { candidate: 'cand-from-phone', sdpMid: '0' } });
    const ice1 = await tv.next();
    ok('ice relayed sender->host', ice1.type === 'ice' && ice1.candidate.candidate === 'cand-from-phone');

    tv.send({ type: 'ice', candidate: { candidate: 'cand-from-tv', sdpMid: '0' } });
    const ice2 = await phone.next();
    ok('ice relayed host->sender', ice2.type === 'ice' && ice2.candidate.candidate === 'cand-from-tv');

    // --- Capability handshake ---------------------------------------------
    console.log('\nDecode capability relay');

    // A sender must not be able to spoof what the display can decode.
    phone.send({ type: 'caps', caps: { maxHeight: 4320 } });
    const spoof = await phone.next();
    ok('non-host cannot set caps', spoof.type === 'error' && spoof.code === 'not_host', JSON.stringify(spoof));

    // Host sets caps; a live sender is notified immediately.
    tv.send({ type: 'caps', caps: { maxHeight: 2160, codecs: ['H264', 'VP8'], model: 'TestStick' } });
    const liveCaps = await phone.next();
    ok('caps pushed to live sender',
       liveCaps.type === 'caps' && liveCaps.caps.maxHeight === 2160, JSON.stringify(liveCaps));
    ok('caps carry codec list',
       Array.isArray(liveCaps.caps.codecs) && liveCaps.caps.codecs.includes('H264'));

    // Absurd values must be clamped: these drive what a sender transmits.
    tv.send({ type: 'caps', caps: { maxHeight: 99999, codecs: ['H264'] } });
    const clamped = await phone.next();
    ok('absurd maxHeight clamped to <=4320', clamped.caps.maxHeight <= 4320, String(clamped.caps.maxHeight));

    tv.send({ type: 'caps', caps: { maxHeight: 1 } });
    const floored = await phone.next();
    ok('tiny maxHeight floored to >=360', floored.caps.maxHeight >= 360, String(floored.caps.maxHeight));

    // Set a realistic value, then verify a NEW joiner receives it on join.
    tv.send({ type: 'caps', caps: { maxHeight: 1080, codecs: ['H264'] } });
    await phone.next();
    phone.close();
    await tv.next();                     // peer-leave

    const phone2 = client();
    await phone2.open();
    phone2.send({ type: 'join', room: code, role: 'camera' });
    const rejoin = await phone2.next();
    ok('caps delivered in join reply', !!rejoin.caps && rejoin.caps.maxHeight === 1080,
       JSON.stringify(rejoin.caps));
    await tv.next();                     // peer-join
    // --- Occupancy --------------------------------------------------------
    console.log('\nOccupancy + error handling');
    const second = client();
    await second.open();
    second.send({ type: 'join', room: code, role: 'screen' });
    const busy = await second.next();
    ok('second sender rejected as busy', busy.type === 'error' && busy.code === 'busy', JSON.stringify(busy));
    second.close();

    const wrong = client();
    await wrong.open();
    wrong.send({ type: 'join', room: 'ZZZZZZ', role: 'camera' });
    const noRoom = await wrong.next();
    ok('unknown code rejected', noRoom.type === 'error' && noRoom.code === 'no_room');
    wrong.close();

    const junk = client();
    await junk.open();
    junk.ws.send('this-is-not-json');
    const badJson = await junk.next();
    ok('malformed json handled', badJson.type === 'error' && badJson.code === 'bad_json');
    junk.send({ type: 'frobnicate' });
    const unknown = await junk.next();
    ok('unknown type handled', unknown.type === 'error' && unknown.code === 'unknown_type');
    junk.close();

    // --- Sender leaving frees the slot -----------------------------------
    console.log('\nSender churn');
    phone2.close();
    const peerLeave = await tv.next();
    ok('host notified of peer-leave', peerLeave.type === 'peer-leave');

    const third = client();
    await third.open();
    third.send({ type: 'join', room: code, role: 'screen' });
    const rejoined = await third.next();
    ok('slot freed for next sender', rejoined.type === 'joined' && rejoined.role === 'screen');
    await tv.next(); // consume peer-join

    // --- Host disappearing tears down the room ---------------------------
    console.log('\nHost teardown');
    tv.close();
    const kicked = await third.next();
    ok('sender told host is gone', kicked.type === 'error' && kicked.code === 'host_gone', JSON.stringify(kicked));

    await sleep(200);
    const afterHealth = await (await fetch(BASE + MOUNT + '/healthz')).json();
    ok('room reclaimed after host left', afterHealth.rooms === 0, 'rooms=' + afterHealth.rooms);

    const orphan = client();
    await orphan.open();
    orphan.send({ type: 'join', room: code, role: 'camera' });
    const gone = await orphan.next();
    ok('old code no longer joinable', gone.type === 'error' && gone.code === 'no_room');
    orphan.close();
    third.close();

    // --- Room codes are unique -------------------------------------------
    console.log('\nCode uniqueness');
    const hosts = [];
    const codes = new Set();
    for (let i = 0; i < 12; i++) {
      const c = client();
      await c.open();
      c.send({ type: 'host' });
      const h = await c.next();
      codes.add(h.room);
      hosts.push(c);
    }
    ok('12 concurrent rooms get unique codes', codes.size === 12, 'unique=' + codes.size);
    const manyHealth = await (await fetch(BASE + MOUNT + '/healthz')).json();
    ok('server tracks all rooms', manyHealth.rooms === 12, 'rooms=' + manyHealth.rooms);
    hosts.forEach((h) => h.close());

    await sleep(300);
    const finalHealth = await (await fetch(BASE + MOUNT + '/healthz')).json();
    ok('all rooms cleaned up', finalHealth.rooms === 0, 'rooms=' + finalHealth.rooms);

  } catch (err) {
    failed++;
    console.error('\n  ERROR during tests:', err && err.message);
  } finally {
    srv.kill('SIGTERM');
    await sleep(300);
    srv.kill('SIGKILL');
  }

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed === 0 ? 0 : 1);
}

main();
