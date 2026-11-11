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

Then build and test the Android app.

## 4. Important before public use

- Configure a TURN relay for networks where direct WebRTC connectivity fails. Set Render environment variables `TURN_URLS` (comma-separated `turn:`/`turns:` URLs) and `TURN_SHARED_SECRET` (the TURN REST API shared secret configured on your coturn/provider). The signaling service issues session-token-protected, 10-minute HMAC-SHA1 credentials to the sender and viewer. Session tokens are sent in an Authorization header, not in the TURN configuration URL. If these variables are absent, the app safely falls back to STUN only. Do not put the shared secret in the Android app or viewer website.
- The signaling service currently uses random private session/viewer/resume tokens, timing-safe token comparison, a viewer-origin allowlist, an 80-message-per-10-second per-socket limit, and a 64 KB WebSocket payload limit. These controls reduce casual misuse but are not a substitute for a full production security review and monitoring.
- Use only HTTPS/WSS. Do not put long-lived secrets in the Android app or viewer URL.
- Verify Android MediaProjection consent, foreground notification behavior, stop/revoke handling, and app uninstall behavior on real devices.
- Do not treat a successful APK build as proof that live screen sharing works. Test on two separate Android phones and different networks.

QunView does not record or save screen video. Optional remote assistance (Back, Home, tap, and swipe) is supported only when the device owner separately enables the QunView Accessibility service in Android settings. Screen viewing does not require enabling remote assistance.


## Consent and operational notes

- Android MediaProjection consent is shown for each new capture session; it cannot be silently reused for a new session.
- While a capture session remains active, a viewer can reconnect without restarting screen capture. The sender must approve the viewer connection again.
- The sender receives a single Allow/Deny notification for a pending viewer request and a separate ongoing Stop sharing notification.
- Android notification permission, channel settings, Do Not Disturb, and manufacturer battery restrictions can affect notification delivery.
- TURN support is implemented but remains optional until a TURN provider/server is configured. The current free Render blueprint does not provision a TURN relay; without the two environment variables, connections use STUN only.
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
- [ ] Test on a network where direct peer-to-peer WebRTC is blocked; verify TURN relay works after a real TURN provider is configured.
- [ ] Disable the QunView Accessibility service in Android Settings and verify remote commands are revoked.

Do not mark the TURN test complete while only the STUN fallback is configured. Do not mark the release ready based on CI or APK creation alone.
