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
- This starter has no account authentication, abuse controls, or TURN credentials yet.
- Production deployment must use HTTPS/WSS, rate limiting, origin restrictions, and short-lived authenticated session tokens.
- WebRTC media transport and Android integration are not implemented by this signaling server.
