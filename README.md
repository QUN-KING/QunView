# QunView

QunView is an Android screen-sharing and optional remote-assistance project.

## Main behavior

- The Android owner starts sharing and approves Android's system screen-capture prompt.
- The owner separately approves each new viewer before video is sent.
- Remote controls (Back, Home, tap, swipe) are optional and require both Android Accessibility enablement and a separate in-session owner approval.
- The owner can stop sharing from the persistent Android notification or the app.
- Screen frames are not recorded or saved by QunView.
- A temporary sender signaling interruption can recover without asking the same still-connected viewer to request viewing permission again. A new viewer socket needs fresh approval.
- Remote-control approval is reset on reconnect.

Android may require a fresh screen-capture approval after the capture service/process stops or the phone restarts. QunView cannot silently bypass that Android security requirement.

## Free deployment

The included Render blueprint uses free service plans. Free hosting may sleep while idle, so initial connections can be delayed. The default networking configuration uses STUN only and has no paid relay configured; some restrictive networks may not establish a direct WebRTC connection. Do not configure usage-billed services.

## Build and test

- Android debug APK: GitHub Actions workflow `Android build check`.
- Signaling and viewer checks: GitHub Actions workflow `Signaling tests`.
- CI success confirms automated checks and APK compilation, not real-device operation.

See [DEPLOYMENT.md](DEPLOYMENT.md) for setup and the two-Android-device release checklist. Do not call the app release-ready until that checklist has been performed on physical devices.
