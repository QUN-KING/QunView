# QunView Signaling Server

A small WebSocket relay for WebRTC session negotiation. It relays offer, answer, and ICE-candidate messages only; it does not receive or store screen video.

## Run locally

```sh
cd signaling
npm install
npm start
```

The health endpoint is `/health`. WebSocket clients connect to the same host using `ws://` locally or `wss://` behind a TLS-enabled deployment.

## Important current limitations

- Sessions are held in server memory and disappear on restart.
- Session links are random and expire after 30 minutes.
- This repository does not implement a TURN-credentials endpoint or TURN relay configuration. The Android sender and viewer currently use Google STUN only; some restrictive networks will fail to connect. Do not assume setting TURN-related environment variables enables TURN.
- Production deployment must use HTTPS/WSS, rate limiting, origin restrictions, monitoring, and a separate security review. This free-only project intentionally remains STUN-only; TURN support is not implemented.
- The server relays signaling only; WebRTC media is peer-to-peer or routed through the separately configured TURN relay.

## Tests

Run `npm install` and `npm test` to exercise the health endpoint, session creation, viewer approval, signaling relay, session teardown, and one-viewer limit.
