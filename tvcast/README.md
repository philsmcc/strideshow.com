# PanelCast

**A whiteboard for a big touch panel, with live phone cameras and screen
shares as floating windows you can write over.**

The panel boots straight into a dark, graph-paper whiteboard. Tap **+** to
show a QR code and a 6-character code; each phone camera or computer screen
that joins becomes its own window on the board. Windows are moved with one
finger on their title strip, resized and rotated with a two-finger pinch,
sent fullscreen or closed from the strip, and tiled with one button. Ink is
drawn *over* the windows, so you can annotate a live document camera or a
shared slide.

## Whiteboard features (v1)

Chosen after surveying the SMART iQ, Promethean ActivPanel and Newline
Whiteboard apps; these are the features all three share.

| Feature | Notes |
|---|---|
| Infinite canvas | Two-finger pinch pans and zooms; **Lock canvas** disables it (SMART-style) |
| Pen, highlighter | 9 high-contrast colours, 5 widths; highlighter renders under ink |
| Eraser | Object eraser; **palm/fist erase** works in any tool via touch size |
| Laser pointer | Fading red trail, never saved |
| Shapes | Line, arrow, rectangle, ellipse, triangle, optional fill |
| Ink-to-shape | Toggle in the pen pop-over: rough sketches snap to clean shapes |
| Text | Tap to place typed text |
| Select | Marquee-select and drag objects; delete via eraser |
| Undo / redo / clear | Per page, 200 steps |
| Pages | Thumbnail strip; add, switch, long-press to delete |
| Backgrounds | Graph paper, dot grid, ruled lines, Cartesian axes, music staff, plain |
| Multi-user inking | Every finger is its own stroke: several people can write at once |
| Autosave + export | Board saved to app storage on every edit; **Save** exports PNG to Pictures/PanelCast |
| Live sources | Up to 8 per room (`PANELCAST_MAX_SOURCES`); windows named by the sender |

The original single-window receiver is still available as **Classic mode**
in Settings, for the weak panels this project started on.

### Layout of the receiver

```
android/app/src/main/java/com/strideshow/panelcast/
├── LaunchActivity.kt        routes to whiteboard or classic
├── BoardActivity.kt         the whiteboard screen
├── MainActivity.kt          classic lobby + fullscreen video
└── board/
    ├── BoardModel.kt        pure-Kotlin document: pages, strokes, shapes, undo
    ├── InkGeometry.kt       simplification, shape recognition, palm detection
    ├── BoardView.kt         paper + ink rendering, touch arbitration
    ├── SourceWindow.kt      one floating live-video window
    ├── SourceWindowLayer.kt window manager: drag/pinch/rotate/fullscreen/tile
    ├── TextureViewRenderer.kt WebRTC sink on a TextureView (layerable, unlike SurfaceView)
    ├── SourceSession.kt     one PeerConnection per source + shared RtcEngine
    ├── Toolbar.kt           floating tool strip and pop-overs
    ├── AddSourceSheet.kt    the "+" pairing card (QR + code)
    ├── PageStrip.kt         page thumbnails
    └── BoardStore.kt        autosave (atomic) and PNG export
```

**Why TextureView:** a `SurfaceView` is a hole punched in the window; it
cannot be layered under ink, rotated or overlapped predictably. `TextureView`
is a normal view, at the cost of one extra GPU composite per frame - fine on
the newer hardware the whiteboard targets. Classic mode keeps SurfaceView.

**Signaling protocol change:** a room now holds many sources. Every relayed
message carries `from`; the host addresses replies with `to`. Un-addressed
host replies go to the newest source so the classic receiver still works.
New: `kick` (host closes one window), `label` on `join`, `maxSources` in
`hosted`. `node signaling/test-protocol.js` runs 75 assertions.

---

## Original notes (classic mode)

Screen sharing and phone-camera casting to old Android TV panels, over WebRTC.

**This is a standalone test project.** It shares nothing with the StrideShow
app in the parent directory - separate code, separate port, separate process,
no database.

## What it does

A repurposed Android touch panel or TV box runs the PanelCast APK. It shows a
QR code and a 6-character pairing code. Then either:

- **Phone camera** - scan the QR code, and the phone's camera appears
  fullscreen on the panel. Usable as a live viewer or a document camera
  (torch, autofocus nudge, front/rear flip). Quality: 1080p or 720p.
- **Computer screen** - open the join page on a laptop and share a window, a
  browser tab, or the whole desktop. Resolution (1080p/720p/540p), frame rate
  (30/15/8 fps) and a bitrate ceiling are all selectable.

**Audio** is shared from the chosen tab/window (Chrome and Edge; not
available for whole-screen capture on macOS). The receiver plays audio but
**never captures** it - the APK requests no microphone permission at all, so
there is no echo path and no privacy surface. Opus is negotiated in stereo at
a configurable bitrate (default 128 kbps), because WebRTC's speech-oriented
mono default sounds poor for video soundtracks.

**Resolutions are gated by a capability handshake.** The receiver queries
`MediaCodecList` for the largest size its *hardware* decoders accept and
reports it through signaling; the sender then hides options the display
cannot handle. This exists because offering 4K to a 1080p decoder produces a
black screen - a failure this project has already debugged once. The server
clamps the reported value (360-4320) and rejects `caps` from anyone who is
not the room host.

**4K is worth it for a document camera** and for static presentations: the
subject does not move, so there is no motion to smear, and the extra detail
is exactly what makes small print readable. 4K therefore defaults to 15fps
and a much higher bitrate ceiling. It is a poor choice for video playback on
Wi-Fi, where packet loss will make it look worse than 1080p.

**One sender page, not two.** `share.html` handles both camera and screen
sharing and asks which you want. Having separate `/j/` and `/pc/` pages meant
a user who scanned the QR landed on the camera page with no obvious way to
share a screen instead. The page also hides options the device cannot do -
screen sharing does not appear on a phone, so there is no button that leads
to an error.

Routes: `/s/CODE` is canonical and what the QR encodes. `/j/CODE` and
`/pc/CODE` still work and jump straight into camera or screen mode
respectively, so older QR codes and typed links keep working.

**Tuning for a slow display:** lower the **frame rate** first. For slides and
documents, 15fps at 1080p looks far better than 30fps the decoder cannot keep
up with, because fewer frames means more bits per frame. Drop resolution to
720p only if that is not enough.

Media flows peer-to-peer via WebRTC. The server only brokers the handshake and
never sees video.

## How it is deployed

PanelCast mounts under a path on the existing StrideShow domain:

```
https://www.strideshow.com/panelcast
```

No new DNS record and no new certificate - it reuses the cert already on the
box. nginx routes `/panelcast/*` to this app on port 3100; everything else on
the domain continues to hit the StrideShow app untouched. The signaling server
is a **separate process** with no database access and no cookie/session
handling, so sharing a domain does not couple the two apps.

The server derives its mount prefix from `PANELCAST_PUBLIC_BASE` and also
accepts un-prefixed paths, so the same build runs behind a path proxy or at a
domain root. The web senders detect their own base path at runtime.

## Layout

```
tvcast/
├── android/              Android TV receiver app (Kotlin)
│   └── app/src/main/
│       ├── java/com/strideshow/panelcast/
│       │   ├── MainActivity.kt      lobby + fullscreen video
│       │   ├── SettingsActivity.kt  on-device server config
│       │   ├── RtcReceiver.kt       WebRTC answerer, hardware decode
│       │   ├── SignalingClient.kt   WebSocket + auto-reconnect
│       │   ├── SdpUtils.kt          H.264 codec preference
│       │   ├── QrGen.kt             QR rendering
│       │   ├── Prefs.kt             persisted settings
│       │   └── Adapters.kt          no-op WebRTC interface adapters
│       └── res/                     layouts, colors, vector icon/banner
├── signaling/
│   ├── server.js         WebSocket signaling + static file hosting
│   └── test-protocol.js  32 end-to-end protocol assertions
└── web/
    ├── index.html        code entry
    ├── phone.html        phone camera sender
    ├── pc.html           desktop screen share sender
    └── js/               shared sender + SDP logic
```

## Design decisions worth knowing

**Native WebRTC, not a WebView.** Old panels frequently ship an ancient,
non-updatable System WebView with broken or missing WebRTC. Bundling
`libwebrtc` (~20 MB) makes the app self-sufficient and, critically, lets us
control the decoder.

**H.264 is preferred only when the device actually has a decoder.** The
original design forced H.264 everywhere, on the theory that it is the only
codec these SoCs hardware-decode. That was a mistake: the bundled WebRTC
build contains **no software H.264 decoder** (only libvpx VP8/VP9), so on a
device whose MediaCodec H.264 is missing or fails to initialise there is no
fallback at all and every frame is dropped.

Now the receiver enumerates its real decoders at startup and only reorders
the SDP when H.264 is genuinely present. The web senders never reorder codecs
at all - the receiver's answer selects the codec, which is how WebRTC
negotiation is supposed to work and cannot strand the display.

**The TV is always the answerer.** The sender owns the media and creates the
offer, so the panel never has to renegotiate - the operation most likely to
fail on a weak CPU.

**`maintain-resolution` + `contentHint="detail"`.** By default WebRTC protects
framerate by shrinking resolution, which turns shared text to mush. For slides
and documents we do the opposite: hold resolution and let framerate dip.

**minSdk 21.** Android 5.0 and up, so Android 9 panels are well covered.
Also the floor supported by the WebRTC prebuilt.

**The video surface is always visible.** `SurfaceViewRenderer` is a
`SurfaceView`, and a `SurfaceView` owns a `Surface` only while it is
`VISIBLE` - `GONE` *and* `INVISIBLE` both destroy it, after which EGL has
nothing to attach to and `EglRenderer` silently discards every frame
("Dropping frame - No surface"). So the renderer is never toggled; the opaque
lobby is simply drawn over it and hidden to reveal the video.

Related trap: `RendererEvents.onFirstFrameRendered()` is misnamed. It fires
from `updateFrameDimensionsAndReportEvents()` *before* the frame is drawn, so
it means "a frame arrived", not "a frame was painted", and it fires at most
once per renderer lifetime (the flag resets only in `init()`). Revealing the
video is therefore driven by `framesDecoded` from `getStats`, which is
per-session and works on reconnects.

**Overscan is user-calibrated.** Many TVs crop the outer few percent of the
signal. An app cannot detect or defeat this, so Settings exposes a 0-12%
inset (applied to both the video and the lobby) with a dashed calibration
box: turn it up until the whole border is visible.

**Sizing is derived from pixels, not dp.** A panel and an HDMI stick can
report very different densities for the same physical 1080p screen, so
identical `dp` values render at different physical sizes. Lobby text and the
QR code are sized as a fraction of the screen's shortest side in pixels.

**Installs on both launcher types.** `LEANBACK_LAUNCHER` *and* normal
`LAUNCHER` intents are registered, with leanback and touchscreen both marked
not-required, so the same APK appears on a real Android TV home screen and on
a plain-Android tablet.

**No camera/microphone permissions.** The receiver only displays video. Audio
is disabled in the WebRTC audio device module too, which sidesteps the
`AudioRecord` init crashes common on AOSP panel builds.

## Building the APK

The Android SDK is not installed on the app server, and a Gradle build wants
~2 GB of RAM. Two options:

### GitHub Actions (recommended)

`.github/workflows/panelcast-apk.yml` builds on every push touching
`tvcast/android/**`. Download the `panelcast-debug-apks` artifact from the run.

Trigger manually from the Actions tab via **workflow_dispatch**.

### Local build

Needs JDK 17 + Android SDK (platform 34, build-tools 34):

```bash
cd tvcast/android
gradle assembleDebug          # or ./gradlew if a wrapper jar is present
```

Outputs land in `app/build/outputs/apk/debug/`. Per-ABI APKs plus a universal
one are produced:

| APK | Use |
|---|---|
| `app-universal-debug.apk` | **Use this if unsure** - contains all ABIs |
| `app-armeabi-v7a-debug.apk` | 32-bit ARM (most older panels) |
| `app-arm64-v8a-debug.apk` | 64-bit ARM (newer panels) |
| `app-x86*-debug.apk` | Intel-based boxes |

## Installing on a panel

```bash
adb connect <panel-ip>:5555          # or plug in USB
adb install -r app-universal-debug.apk
```

If the panel has no ADB, copy the APK to a USB stick and use its file
manager - "install from unknown sources" must be enabled.

## Running the server

```bash
cd tvcast/signaling
npm install
PANELCAST_PORT=3100 \
PANELCAST_PUBLIC_BASE=https://cast.example.com \
node server.js
```

| Env var | Default | Purpose |
|---|---|---|
| `PANELCAST_PORT` | `3100` | Listen port |
| `PANELCAST_HOST` | `127.0.0.1` | Bind address (behind nginx) |
| `PANELCAST_PUBLIC_BASE` | `https://www.strideshow.com/panelcast` | Base URL encoded in the QR |
| `PANELCAST_TURN_URL` | - | Optional TURN, comma-separated |
| `PANELCAST_TURN_USER` | - | TURN username |
| `PANELCAST_TURN_PASS` | - | TURN credential |

Run the protocol tests with:

```bash
node test-protocol.js
```

See `docs/DEPLOY.md` for nginx, TLS, pm2, and TURN setup.

## HTTPS is mandatory

Browsers only expose `getUserMedia` / `getDisplayMedia` on a secure origin.
The sender pages **must** be served over HTTPS (or `localhost`). Both pages
detect an insecure origin and show an explanatory message rather than failing
silently.

## Known limitation: NAT traversal

On a normal shared Wi-Fi network, phone and panel connect directly and quality
is excellent. But if the network uses **client isolation** (common on guest
Wi-Fi) or the two devices are on different subnets, WebRTC cannot form a
direct path and STUN alone is not enough - it needs a **TURN relay**.

Symptom: pairing succeeds, status reaches "connecting", then fails.

Fix: deploy `coturn` and set the `PANELCAST_TURN_*` variables. Note that TURN
relays all media through your server, so it consumes real bandwidth - worth
enabling deliberately rather than by default. See `docs/DEPLOY.md`.

## Enabling the CI workflow

The build workflow lives at `tvcast/ci/panelcast-apk.yml` rather than
`.github/workflows/`, because the GitHub App used to push this branch is not
granted the `workflows` permission and the push is rejected outright.

To activate it, copy the file into place locally and push with your own
credentials:

```bash
mkdir -p .github/workflows
cp tvcast/ci/panelcast-apk.yml .github/workflows/
git add .github/workflows/panelcast-apk.yml
git commit -m "ci: enable PanelCast APK workflow"
git push
```

The workflow then runs on any push touching `tvcast/android/**` and publishes
the APKs as downloadable artifacts.
