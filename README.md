# QunView

QunView is an Android screen-sharing and optional remote-assistance project.

## Main behavior

- The Android owner starts sharing and approves Android's system screen-capture prompt.
- The owner approves each viewer separately; each viewer gets an independent WebRTC connection and can be denied or disconnected without affecting other viewers.
- The signaling service initially allows up to four concurrent viewers per session (configurable up to eight); actual performance depends on the phone's upload bandwidth, CPU, battery and network.
- Remote controls (Back, Home, tap, swipe) require the owner's one-time Android Accessibility enablement. Approving a viewer also permits that approved viewer to use those controls; no second per-viewer control prompt is shown. If Accessibility is off, that viewer is view-only.
- The owner can stop sharing from the persistent Android notification or the app.
- Screen frames are not recorded or saved by QunView.
- A temporary sender signaling interruption can recover without asking the same still-connected viewer to request viewing permission again. A new viewer socket needs fresh approval.
- A viewer's viewing approval is retained only for that same connected viewer socket during a temporary sender signaling interruption; new viewer connections require fresh owner approval.

Android may require a fresh screen-capture approval after the capture service/process stops or the phone restarts. QunView cannot silently bypass that Android security requirement.

## Free deployment

The included Render blueprint uses free service plans. Free hosting may sleep while idle, so initial connections can be delayed. Networking is permanently STUN-only in this free-only plan; no TURN provider, paid relay, or billing integration is included. Some restrictive networks may not establish a direct WebRTC connection.

## Build and test

- Android lint, unit tests, debug APK build, and a signed-release preflight using a disposable CI-only key: GitHub Actions workflow `Android build check`.
- The preflight verifies that the Release variant builds and its APK signature is valid; its temporary key is deleted and no release APK is published from that step.
- Signaling and viewer checks: GitHub Actions workflow `Signaling tests`.
- CI success confirms automated checks and APK compilation, not real-device operation or the final permanent signing key.

See [DEPLOYMENT.md](DEPLOYMENT.md) for setup and the two-Android-device release checklist. The [Android-only permanent signing guide](RELEASE_SIGNING_ANDROID.md) explains how the owner can create the release key in Codespaces without Termux. Do not call the app release-ready until the physical-device checklist has been performed.
