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

- Configure a TURN relay with credentials for networks where direct WebRTC connectivity fails. The current app only has a public STUN server.
- Add authentication/session access protection, origin checks, rate limiting, and abuse monitoring before public launch.
- Use only HTTPS/WSS. Do not put long-lived secrets in the Android app or viewer URL.
- Verify Android MediaProjection consent, foreground notification behavior, stop/revoke handling, and app uninstall behavior on real devices.
- Do not treat a successful APK build as proof that live screen sharing works. Test on two separate Android phones and different networks.

No screen recording or remote-control feature is part of QunView.


## Consent and operational notes

- Android MediaProjection consent is shown for each new capture session; it cannot be silently reused for a new session.
- While a capture session remains active, a viewer can reconnect without restarting screen capture. The sender must approve the viewer connection again.
- The sender receives a single Allow/Deny notification for a pending viewer request and a separate ongoing Stop sharing notification.
- Android notification permission, channel settings, Do Not Disturb, and manufacturer battery restrictions can affect notification delivery.
- Current WebRTC configuration uses STUN only. Some networks may require a TURN relay, which is not configured in this free deployment.
- Do not distribute as production-ready until two-device testing covers approval, denial, background notifications, stop, reconnect, and optional remote assistance.
