# QunView deployment guide

This repository contains two separate deployable parts:

1. `signaling/` — Node.js WebSocket signaling service.
2. `viewer/` — static browser viewer website.

## 1. Signaling service

A Render blueprint is included in `render.yaml`. Connect this repository to Render and create the service from the blueprint. The service health endpoint is `/health`.

After deployment, copy the public service hostname and use its secure WebSocket URL:

`wss://YOUR-SERVICE-HOST`

Important: a free hosting instance may sleep when idle, so the first connection can be delayed. WebRTC video travels peer-to-peer when possible; the signaling service only exchanges connection messages.

## 2. Viewer website

Deploy the `viewer/` directory as a static site using a static hosting provider that supports HTTPS. Copy its public HTTPS base URL.

## 3. Configure the Android sender

In `android/app/src/main/java/com/qunking/qunview/WebRtcScreenStreamer.kt`, replace:

- `SIGNALING_URL` with the deployed `wss://` URL.
- `VIEWER_BASE_URL` with the deployed viewer `https://` URL.
- In `viewer/viewer.js`, set its fixed `signalingUrl` constant to the same trusted `wss://` host. The viewer intentionally does not accept a signaling host from the share link, so a crafted link cannot redirect the private viewer token to an attacker-controlled WebSocket server.

Then build and test the Android app.

## 4. Important before public use

- The included free deployment uses Google STUN only. No paid TURN provider or billing credentials are configured. Direct peer-to-peer video works on many networks, but some restrictive networks require a TURN relay and may not connect in this free-only setup. Do not enable a service that can incur charges; if a genuinely no-cost TURN option is not available, treat those networks as unsupported rather than risking a bill.
- The signaling service uses random private session/viewer/resume tokens, timing-safe token comparison, a viewer-origin allowlist, a 64 KB WebSocket payload limit, an 80-message-per-10-second per-socket limit, a 60-connection-per-minute per-client-IP handshake limit, and a 10-session-per-10-minute per-client-IP creation limit. The IP limits use Cloudflare’s `CF-Connecting-IP` header only when `TRUST_CF_CONNECTING_IP=true`; otherwise the server falls back to the direct peer address. The Render blueprint enables this setting because Render routes public traffic through Cloudflare. These in-memory limits apply per running instance and are not a substitute for a distributed limiter when scaling to multiple instances.
- Use only HTTPS/WSS. Do not put long-lived secrets in the Android app or viewer URL.
- Verify Android MediaProjection consent, foreground notification behavior, stop/revoke handling, and app uninstall behavior on real devices.
- Do not treat a successful APK build as proof that live screen sharing works. Test on two separate Android phones and different networks.

QunView does not record or save screen video. Optional remote assistance (Back, Home, tap, and swipe) is supported only when the device owner separately enables the QunView Accessibility service in Android settings. Screen viewing does not require enabling remote assistance.


## Consent and operational notes

- Android MediaProjection consent is shown for each new capture session; it cannot be silently reused for a new session.
- While the same viewer WebSocket remains connected, a temporary sender signaling interruption can recover without restarting screen capture or asking for viewing approval again. If the viewer browser connection itself closes and rejoins, the owner must approve that new viewer connection.
- The sender receives a single Allow/Deny notification for a pending viewer request and a separate ongoing Stop sharing notification.
- Android notification permission, channel settings, Do Not Disturb, and manufacturer battery restrictions can affect notification delivery.
- The free-only configuration is STUN-only. Network compatibility is therefore not guaranteed on mobile carriers, corporate Wi-Fi, VPNs, or firewalls that block direct peer-to-peer WebRTC. Do not configure a provider with usage-based or possible overage charges.
- Do not distribute as production-ready until two-device testing covers approval, denial, background notifications, stop, reconnect, and optional remote assistance.


## Two-device release checklist

Complete this checklist on two physical Android phones before calling a release ready. Record the Android version and device model for each phone.

- [ ] Install the same newly built APK on the sender phone; open the viewer URL on the second phone.
- [ ] Start a share and verify Android displays its system MediaProjection consent prompt.
- [ ] Request to view from the viewer phone; verify the sender can Allow and Deny independently.
- [ ] Deny once and confirm no screen frames are shown; request again and Allow.
- [ ] Confirm the viewer sees the live screen only after the sender allows it.
- [ ] Request remote assistance; confirm it remains disabled until the sender separately approves it.
- [ ] Verify Back, Home, tap, and swipe only work after remote-assistance approval.
- [ ] Disable remote assistance from the sender notification and verify commands stop immediately.
- [ ] Stop screen sharing from the persistent Android notification and verify the viewer loses the stream.
- [ ] Repeat with the sender app backgrounded, screen locked/unlocked, and viewer browser closed/reopened.
- [ ] Test a Wi-Fi-to-mobile-data or other network change and reconnect behavior.
- [ ] Test on a network where direct peer-to-peer WebRTC is blocked; verify QunView reports that a direct connection could not be established. The free-only release remains STUN-only and does not add TURN or paid relay services.
- [ ] Disable the QunView Accessibility service in Android Settings and verify remote commands are revoked.

Do not mark the release ready based on CI or APK creation alone.

## Signed Android release APK

The signed release workflow is available under GitHub Actions as **Signed Android release APK**. It can be started manually or by pushing a version tag beginning with `v`. Before running it, create a release keystore locally and keep a secure offline backup. Never commit the keystore or its passwords to this repository.

Create a keystore with the alias `qunview-release`, then add these four repository Actions secrets in GitHub Settings → Secrets and variables → Actions:

- `QUNVIEW_KEYSTORE_BASE64` — base64-encoded keystore file contents.
- `QUNVIEW_KEYSTORE_PASSWORD` — keystore password.
- `QUNVIEW_KEY_ALIAS` — `qunview-release`.
- `QUNVIEW_KEY_PASSWORD` — key password.

On Linux or Termux, encode the keystore without line breaks using `base64 -w 0 qunview-release.jks`. Do not paste the keystore or passwords into chat, issues, commits, or logs. The workflow checks the APK signature and removes its temporary keystore after the job. Without all four secrets, signed release creation intentionally fails with a clear missing-secret message; the debug APK workflow remains independent.
