"use strict";

const http = require("node:http");
const { randomBytes } = require("node:crypto");
const { WebSocketServer, WebSocket } = require("ws");

const PORT = Number(process.env.PORT || 8080);
const sessions = new Map();
const MAX_CLIENTS_PER_SESSION = 2;
const SESSION_TTL_MS = 30 * 60 * 1000;

const server = http.createServer((req, res) => {
  const pathname = new URL(req.url, "http://localhost").pathname;
  if (pathname === "/health") {
    res.writeHead(200, { "content-type": "application/json" });
    return res.end(JSON.stringify({ ok: true, service: "qunview-signaling" }));
  }
  res.writeHead(404);
  res.end("Not found");
});

const wss = new WebSocketServer({ server, maxPayload: 64 * 1024 });

function send(socket, payload) {
  if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(payload));
}

function cleanSession(id) {
  const session = sessions.get(id);
  if (!session) return;
  // Remove first so close events cannot recursively clean the same session.
  sessions.delete(id);
  clearTimeout(session.timer);
  for (const client of session.clients) {
    client.sessionId = undefined;
    if (client.readyState === WebSocket.OPEN) client.close(1000, "Session ended");
  }
}

wss.on("connection", (socket, request) => {
  const origin = request.headers.origin;
  if (origin && origin !== "https://qunview-viewer.onrender.com") {
    socket.close(1008, "Origin not allowed");
    return;
  }

  socket.on("message", (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch { return send(socket, { type: "error", message: "Invalid JSON" }); }

    if (msg.type === "create-session") {
      if (socket.sessionId) return send(socket, { type: "error", message: "This connection already has a session" });
      const id = randomBytes(16).toString("hex");
      const resumeToken = randomBytes(32).toString("hex");
      const session = { clients: new Set([socket]), createdAt: Date.now(), sender: socket, resumeToken };
      sessions.set(id, session);
      socket.sessionId = id;
      socket.role = "sender";
      session.timer = setTimeout(() => cleanSession(id), SESSION_TTL_MS);
      return send(socket, { type: "session-created", sessionId: id, resumeToken, expiresInSeconds: SESSION_TTL_MS / 1000 });
    }

    if (msg.type === "resume-session") {
      const id = typeof msg.sessionId === "string" ? msg.sessionId : "";
      const token = typeof msg.resumeToken === "string" ? msg.resumeToken : "";
      const session = sessions.get(id);
      if (!session || session.resumeToken !== token || Date.now() - session.createdAt > SESSION_TTL_MS) {
        return send(socket, { type: "error", message: "Session cannot be resumed" });
      }
      if (session.sender && session.sender.readyState === WebSocket.OPEN) {
        return send(socket, { type: "error", message: "Sender is already connected" });
      }
      session.sender = socket;
      session.clients.add(socket);
      socket.sessionId = id;
      socket.role = "sender";
      send(socket, { type: "session-resumed", sessionId: id });
      for (const client of session.clients) {
        if (client !== socket && client.role === "viewer") {
          send(client, { type: "sender-reconnected" });
          send(socket, { type: "viewer-joined" });
        }
      }
      return;
    }

    if (msg.type === "join-session") {
      if (socket.sessionId) return send(socket, { type: "error", message: "This connection already joined a session" });
      const id = typeof msg.sessionId === "string" ? msg.sessionId : "";
      const session = sessions.get(id);
      if (!session || Date.now() - session.createdAt > SESSION_TTL_MS) {
        return send(socket, { type: "error", message: "Session not found or expired" });
      }
      if (session.clients.size >= MAX_CLIENTS_PER_SESSION) {
        return send(socket, { type: "error", message: "Session already has a viewer" });
      }
      session.clients.add(socket);
      socket.sessionId = id;
      socket.role = "viewer";
      send(socket, { type: "viewer-joined" });
      if (session.sender) send(session.sender, { type: "viewer-joined" });
      return;
    }

    const id = socket.sessionId;
    const session = id && sessions.get(id);
    if (!session || !session.clients.has(socket)) return send(socket, { type: "error", message: "Join a session first" });
    if (!["offer", "answer", "ice-candidate", "end-session"].includes(msg.type)) {
      return send(socket, { type: "error", message: "Unsupported message type" });
    }
    if (msg.type === "end-session") {
      if (socket.role !== "sender") return send(socket, { type: "error", message: "Only the sender can end this session" });
      return cleanSession(id);
    }
    if (msg.type === "offer" && socket.role !== "sender") {
      return send(socket, { type: "error", message: "Only the sender can send an offer" });
    }
    if (msg.type === "answer" && socket.role !== "viewer") {
      return send(socket, { type: "error", message: "Only the viewer can send an answer" });
    }
    if (msg.type === "ice-candidate" && !["sender", "viewer"].includes(socket.role)) {
      return send(socket, { type: "error", message: "Invalid session role" });
    }
    if (["offer", "answer", "ice-candidate"].includes(msg.type) &&
        (!msg.data || typeof msg.data !== "object" || Array.isArray(msg.data))) {
      return send(socket, { type: "error", message: "Invalid signaling data" });
    }
    if (["offer", "answer"].includes(msg.type) &&
        (msg.data.type !== msg.type || typeof msg.data.sdp !== "string" || msg.data.sdp.length > 48 * 1024)) {
      return send(socket, { type: "error", message: "Invalid session description" });
    }
    if (msg.type === "ice-candidate" &&
        (typeof msg.data.candidate !== "string" || msg.data.candidate.length > 4 * 1024)) {
      return send(socket, { type: "error", message: "Invalid ICE candidate" });
    }

    for (const peer of session.clients) {
      if (peer !== socket) send(peer, { type: msg.type, data: msg.data });
    }
  });

  socket.on("close", () => {
    const id = socket.sessionId;
    const session = id && sessions.get(id);
    if (!session) return;
    session.clients.delete(socket);
    if (socket.role === "sender") {
      session.sender = null;
      return;
    }
    if (session.clients.size === 0) cleanSession(id);
    else if (session.sender) send(session.sender, { type: "viewer-left" });
  });
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`QunView signaling server listening on ${PORT}`);
});
