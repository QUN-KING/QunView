# QunView Architecture

QunView consists of:

- **Android sender app:** requests Android MediaProjection consent and streams the screen while the device owner chooses to share.
- **Viewer website:** displays the live stream in a browser. The viewer can request optional Back, Home, tap, and swipe assistance.
- **Signaling service:** creates private sessions, authenticates the sender and viewer with random tokens, and relays WebRTC setup messages.
- **WebRTC:** carries the live screen peer-to-peer using encrypted transport. Google STUN is configured; a TURN relay is not currently configured.

## Consent and notifications

- Android screen-capture consent is required for each new capture session. The app does not attempt to bypass Android's permission dialog.
- The active capture session remains alive while a viewer disconnects and reconnects, when Android keeps the sender service running.
- A new viewer connection requires the sender's Allow decision. If the same viewer WebSocket stays connected while the sender briefly reconnects, its existing viewing approval is retained; remote-control approval is always reset.
- Viewer requests appear as one Android notification with Allow and Deny actions, including when the app UI is not open.
- A separate ongoing foreground-service notification provides Stop sharing.
- Notification delivery depends on Android notification permission and device notification settings.

## Privacy and remote assistance

- Screen frames are streamed; QunView contains no screen-recording or screen-file-saving feature.
- The sender can stop sharing at any time from the foreground notification.
- Optional remote actions require the sender to enable QunView Accessibility in Android settings. Screen-only sharing works without it.
- The Accessibility service performs only the supported navigation and gesture actions; it does not retrieve window content.

## Current limitations

- STUN-only connectivity may fail on some carrier or restrictive NAT networks. A TURN service would be needed for broader connectivity.
- Render free services may sleep while idle, delaying initial signaling.
- Real-device testing on two Android phones is required before describing the app as production-ready.

## Build sequence

1. Build the Android sender app.
2. Deploy the signaling service and viewer website.
3. Grant Android screen-capture permission and start a sender session.
4. Open the private viewer link and approve the request on the sender.
5. Test stop, deny, reconnect, notification, and optional accessibility behavior.
