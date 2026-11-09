# QunView Architecture

QunView consists of:

- **Android sender app:** requests explicit Android screen-capture consent using MediaProjection and streams the screen while the user chooses to share.
- **Viewer website:** displays the live stream in a browser; viewers have no remote-control capability.
- **Signaling service:** coordinates WebRTC session setup between sender and viewer.
- **STUN/TURN:** supports connection establishment across different networks when direct peer-to-peer connectivity is unavailable.

## Privacy and safety

- The sender must explicitly approve each screen-sharing session.
- The sender can stop sharing at any time using Android's system controls and the app's stop action.
- The service does not provide recording, saving, chat, calls, or remote control.
- Use encrypted WebRTC transport and authenticated, short-lived viewing links.

## Build sequence

1. Create the Android app skeleton and viewer website.
2. Add session signaling and WebRTC streaming.
3. Add permission, stop-sharing, and access-control handling.
4. Test with two Android phones and a browser.