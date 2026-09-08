# PanelCast deployment

PanelCast mounts under a **path on the existing StrideShow domain**:

```
https://www.strideshow.com/panelcast
```

That means **no new DNS record and no new TLS certificate** - it reuses the
`www.strideshow.com` cert already on the box. The only change to the live site
is one additional `location` block in nginx; the StrideShow app itself is
untouched and keeps serving everything else.

## 1. Node service

```bash
cd /home/ec2-user/webapp/tvcast/signaling
npm install --omit=dev
```

Start under pm2 as its own process, separate from the main site:

```bash
cd /home/ec2-user/webapp/tvcast
pm2 start ecosystem.config.js
pm2 save
```

Verify locally before touching nginx:

```bash
curl -s localhost:3100/healthz          # {"ok":true,"rooms":0,...}
curl -s localhost:3100/panelcast/healthz # same - both forms work
pm2 logs panelcast --nostream --lines 20
```

## 2. nginx

> **Status: applied.** These blocks are live on this server as of the initial
> deploy. A snapshot of the deployed file is kept at
> `tvcast/deploy/strideshow.conf.deployed`, and a timestamped backup of the
> pre-change config is at `/etc/nginx/conf.d/strideshow.conf.bak-*`.

Add these two blocks to the **existing** `www.strideshow.com` server block in
`/etc/nginx/conf.d/strideshow.conf`, *above* the current `location / {}`.

nginx matches the longest prefix first, so `/panelcast` wins over `/` and the
rest of StrideShow is unaffected.

```nginx
    # --- PanelCast: WebSocket signaling ---
    # Must come before the /panelcast block: nginx prefers the longer prefix,
    # and this needs the Upgrade headers plus a long timeout, because a
    # wall-mounted panel holds the socket open for days between casts.
    location /panelcast/ws {
        proxy_pass http://127.0.0.1:3100;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
    }

    # --- PanelCast: pages and assets ---
    location /panelcast {
        proxy_pass http://127.0.0.1:3100;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
```

Note there is **no trailing slash** on `proxy_pass`, so the full
`/panelcast/...` path is forwarded intact. The server strips the prefix itself
(and also accepts bare paths), so it works whether or not a rewrite happens.

Apply:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

`nginx -t` before reloading is important here - a syntax error in a shared
config file would take StrideShow down too. Reload (not restart) keeps
existing connections alive.

Confirm both apps still work:

```bash
curl -s -o /dev/null -w "strideshow: %{http_code}\n" https://www.strideshow.com/
curl -s https://www.strideshow.com/panelcast/healthz
```

## 3. Point the app at the server

The APK default is already:

```
wss://www.strideshow.com/panelcast/ws
```

To override on a panel without rebuilding, use **Settings** in the app. To
change the baked-in default, edit `DEFAULT_SIGNALING_URL` in
`app/build.gradle.kts` and rebuild.

## 4. Verify end to end

1. Launch PanelCast on the panel. It should read
   *"Ready - waiting for a connection"* with a QR code visible.
2. Scan the QR with a phone on the **same Wi-Fi**. Grant camera access.
3. Tap **Start sharing**. Video should appear within a few seconds; the phone
   shows live resolution, framerate and bitrate.
4. On a laptop, open `https://www.strideshow.com/panelcast`, enter the code,
   choose **Computer screen**, and share a window.

## 5. TURN (only if direct connection fails)

Skip unless pairing succeeds but video never starts. That means ICE could not
find a path - typically guest Wi-Fi with client isolation, or the phone and
panel on different subnets.

TURN cannot be path-mounted (it is not HTTP), so this step *does* need a
hostname - either a new subdomain or the server's bare IP.

```bash
sudo dnf install -y coturn
```

`/etc/coturn/turnserver.conf`:

```
listening-port=3478
tls-listening-port=5349
fingerprint
lt-cred-mech
realm=strideshow.com
user=panelcast:CHANGE_THIS_TO_A_LONG_RANDOM_SECRET
# Required on EC2: coturn must advertise the public IP, not the private one.
external-ip=<public-ip>/<private-ip>
min-port=49152
max-port=65535
no-cli
no-tlsv1
no-tlsv1_1
```

Open UDP 3478 and UDP 49152-65535 in the EC2 security group, then:

```bash
sudo systemctl enable --now coturn
```

Tell the signaling server about it:

```bash
pm2 delete panelcast
PANELCAST_TURN_URL="turn:<public-ip>:3478" \
PANELCAST_TURN_USER="panelcast" \
PANELCAST_TURN_PASS="CHANGE_THIS_TO_A_LONG_RANDOM_SECRET" \
pm2 start ecosystem.config.js
pm2 save
```

Panels and browsers pick this up automatically - the server hands out ICE
servers at handshake time.

**Bandwidth warning:** relayed media transits your server both ways. One
1080p session at 8 Mbps costs ~16 Mbps of server bandwidth. Budget for it, or
lower the bitrate slider on the sender page.

## Operations

| Task | Command |
|---|---|
| Status | `pm2 status panelcast` |
| Logs | `pm2 logs panelcast --nostream --lines 50` |
| Restart | `pm2 restart panelcast` |
| Active rooms | `curl -s localhost:3100/healthz` |
| Protocol tests | `cd tvcast/signaling && node test-protocol.js` |

Rooms are in-memory, so a restart drops active sessions; every panel
reconnects and shows a fresh code within seconds. Restarting `panelcast` does
**not** affect the StrideShow process.

## Rolling back

PanelCast is entirely additive. To remove it:

```bash
pm2 delete panelcast && pm2 save
# then delete the two location blocks from strideshow.conf
sudo nginx -t && sudo systemctl reload nginx
```

StrideShow is unaffected at every step.

## Security notes

- Pairing codes are 6 characters from a 31-symbol alphabet (~887 million
  combinations), generated with `crypto.randomBytes`, valid only while the
  panel is connected.
- Wrong-code attempts are throttled per IP (20 per 5 minutes).
- One sender per room, so nobody can hijack a live session.
- The server relays SDP/ICE only - it never holds media, and WebRTC media is
  DTLS-SRTP encrypted end to end.
- The signaling server shares a domain with StrideShow but is a **separate
  process** with no database access and no session/cookie handling. It does
  not read StrideShow cookies; a `/panelcast` request never reaches the
  StrideShow app.
- Anyone who can read the code off the screen can cast to it. That is the
  intended model for a shared display, not an access-control boundary.
