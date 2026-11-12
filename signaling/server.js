"use strict";

const http = require("node:http");
const { randomBytes, timingSafeEqual } = require("node:crypto");
const net = require("node:net");
const { WebSocketServer, WebSocket } = require("ws");

const PORT = Number(process.env.PORT || 8080);
const sessions = new Map();
const MAX_CLIENTS_PER_SESSION = 2;
const SESSION_TTL_MS = 30 * 60 * 1000;
const VIEWER_ORIGIN = "https://qunview-viewer.onrender.com";

const server = http.createServer(async (req, res) => {
  const pathname = new URL(req.url, "http://localhost").pathname;
  if (pathname === "/health") {
    res.writeHead(200, { "content-type": "application/json" });
    return res.end(JSON.stringify({ ok: true, service: "qunview-signaling" }));
  }
  res.writeHead(404);
  res.end("Not found");
});

// Render routes public traffic through Cloudflare. Trust its single-address
// CF-Connecting-IP header only when explicitly enabled for that deployment.
const TRUST_CF_CONNECTING_IP = process.env.TRUST_CF_CONNECTING_IP === "true";
const CONNECTION_WINDOW_MS = 60 * 1000;
const MAX_CONNECTIONS_PER_IP_PER_WINDOW = 60;
const SESSION_WINDOW_MS = 10 * 60 * 1000;
const MAX_SESSIONS_PER_IP_PER_WINDOW = 10;
const IP_RATE_LIMIT_STATE_TTL_MS = 10 * 60 * 1000;
const MAX_IP_RATE_LIMIT_ENTRIES = 20000;
const ipRateLimits = new Map();

function normalizeIp(value) {
  if (typeof value !== "string") return null;
  const ip = value.trim();
  if (!net.isIP(ip)) return null;
  return ip.toLowerCase().startsWith("::ffff:") ? ip.slice(7) : ip.toLowerCase();
}

function clientIpForRequest(request) {
  if (TRUST_CF_CONNECTING_IP) {
    const cloudflareIp = normalizeIp(request.headers["cf-connecting-ip"]);
    if (cloudflareIp) return cloudflareIp;
  }
  return normalizeIp(request.socket.remoteAddress) || "unknown";
}

function pruneIpRateLimits(now = Date.now()) {
  for (const [ip, state] of ipRateLimits) {
    if (now - state.lastSeenAt > IP_RATE_LIMIT_STATE_TTL_MS) ipRateLimits.delete(ip);
  }
}

function getIpRateLimitState(ip, now = Date.now()) {
  let state = ipRateLimits.get(ip);
  if (!state) {
    if (ipRateLimits.size >= MAX_IP_RATE_LIMIT_ENTRIES) pruneIpRateLimits(now);
    if (ipRateLimits.size >= MAX_IP_RATE_LIMIT_ENTRIES) return null;
    state = {
      lastSeenAt: now,
      connectionWindowStartedAt: now,
      connectionCount: 0,
      sessionWindowStartedAt: now,
      sessionCount: 0
    };
    ipRateLimits.set(ip, state);
  }
  state.lastSeenAt = now;
  return state;
}

const ipRateLimitCleanupTimer = setInterval(pruneIpRateLimits, 60 * 1000);
if (typeof ipRateLimitCleanupTimer.unref === "function") ipRateLimitCleanupTimer.unref();

const wss = new WebSocketServer({ noServer: true, maxPayload: 64 * 1024 });
server.on("upgrade", (request, socket, head) => {
  let pathname;
  try { pathname = new URL(request.url, "http://localhost").pathname; }
  catch {
    socket.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
    socket.destroy();
    return;
  }
  if (pathname !== "/") {
    socket.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
    socket.destroy();
    return;
  }

  const ip = clientIpForRequest(request);
  const state = getIpRateLimitState(ip);
  if (!state) {
    socket.write("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nRetry-After: 60\r\nContent-Length: 0\r\n\r\n");
    socket.destroy();
    return;
  }
  const now = Date.now();
  if (now - state.connectionWindowStartedAt >= CONNECTION_WINDOW_MS) {
    state.connectionWindowStartedAt = now;
    state.connectionCount = 0;
  }
  state.connectionCount += 1;
  if (state.connectionCount > MAX_CONNECTIONS_PER_IP_PER_WINDOW) {
    socket.write("HTTP/1.1 429 Too Many Requests\r\nConnection: close\r\nRetry-After: 60\r\nContent-Length: 0\r\n\r\n");
    socket.destroy();
    return;
  }

  wss.handleUpgrade(request, socket, head, (webSocket) => {
    webSocket.clientIp = ip;
    wss.emit("connection", webSocket, request);
  });
});

function send(socket, payload) {
  if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(payload));
}
function tokenMatches(expected, supplied) {
  if (typeof expected !== "string" || typeof supplied !== "string") return false;
  const a = Buffer.from(expected);
  const b = Buffer.from(supplied);
  return a.length === b.length && timingSafeEqual(a, b);
}
function touchSession(id, session) {
  if (!session || sessions.get(id) !== session) return;
  session.lastActivityAt = Date.now();
  clearTimeout(session.timer);
  session.timer = setTimeout(() => cleanSession(id), SESSION_TTL_MS);
  if (typeof session.timer.unref === "function") session.timer.unref();
}
function cleanSession(id) {
  const session = sessions.get(id);
  if (!session) return;
  sessions.delete(id);
  clearTimeout(session.timer);
  for (const client of session.clients) {
    client.sessionId = undefined;
    if (client.readyState === WebSocket.OPEN) client.close(1000, "Session ended");
  }
}
function requestViewerApproval(session) {
  session.approved = false;
  send(session.viewer, { type: "waiting-for-sender-approval" });
  send(session.sender, { type: "viewer-join-request" });
}

wss.on("connection", (socket, request) => {
  const origin = request.headers.origin;
  if (origin && origin !== "https://qunview-viewer.onrender.com") {
    socket.close(1008, "Origin not allowed");
    return;
  }
  socket.isAlive = true;
  socket.on("pong", () => {
    socket.isAlive = true;
    const id = socket.sessionId;
    const session = id && sessions.get(id);
    // A live sender keeps its sharing session alive. Viewer-only tabs do not.
    if (session && socket.role === "sender" && session.sender === socket) touchSession(id, session);
  });
  socket.messageWindowStartedAt = Date.now();
  socket.messageCount = 0;

  socket.on("message", (raw) => {
    const now = Date.now();
    if (now - socket.messageWindowStartedAt >= 10000) {
      socket.messageWindowStartedAt = now;
      socket.messageCount = 0;
    }
    socket.messageCount += 1;
    if (socket.messageCount > 80) {
      socket.close(1008, "Rate limit exceeded");
      return;
    }

    let msg;
    try { msg = JSON.parse(raw.toString()); }
    catch { return send(socket, { type: "error", message: "Invalid JSON" }); }
    if (!msg || typeof msg !== "object" || Array.isArray(msg) || typeof msg.type !== "string") {
      return send(socket, { type: "error", message: "Invalid message format" });
    }

    if (msg.type === "create-session") {
      if (socket.sessionId) return send(socket, { type: "error", message: "This connection already has a session" });
      const ipState = ipRateLimits.get(socket.clientIp);
      if (!ipState) return send(socket, { type: "error", message: "Rate limit state unavailable; reconnect and try again" });
      const now = Date.now();
      if (now - ipState.sessionWindowStartedAt >= SESSION_WINDOW_MS) {
        ipState.sessionWindowStartedAt = now;
        ipState.sessionCount = 0;
      }
      if (ipState.sessionCount >= MAX_SESSIONS_PER_IP_PER_WINDOW) {
        return send(socket, { type: "error", message: "Session creation rate limit exceeded; try again later" });
      }
      ipState.sessionCount += 1;
      const id = randomBytes(16).toString("hex");
      const resumeToken = randomBytes(32).toString("hex");
      const viewerToken = randomBytes(32).toString("hex");
      const session = { clients: new Set([socket]), createdAt: Date.now(), lastActivityAt: Date.now(), sender: socket, viewer: null, approved: false, resumeToken, viewerToken };
      sessions.set(id, session);
      socket.sessionId = id;
      socket.role = "sender";
      touchSession(id, session);
      return send(socket, { type: "session-created", sessionId: id, resumeToken, viewerToken, expiresInSeconds: SESSION_TTL_MS / 1000 });
    }

    if (msg.type === "resume-session") {
      if (socket.sessionId) return send(socket, { type: "error", message: "This connection already belongs to a session" });
      const id = typeof msg.sessionId === "string" ? msg.sessionId : "";
      const session = sessions.get(id);
      if (!session || !tokenMatches(session.resumeToken, msg.resumeToken) || Date.now() - session.lastActivityAt > SESSION_TTL_MS) {
        return send(socket, { type: "error", message: "Session cannot be resumed" });
      }
      if (session.sender && session.sender.readyState === WebSocket.OPEN) {
        return send(socket, { type: "error", message: "Sender is already connected" });
      }
      session.sender = socket;
      const viewerStillConnected = Boolean(session.viewer && session.viewer.readyState === WebSocket.OPEN);
      if (!viewerStillConnected) session.approved = false;
      touchSession(id, session);
      session.clients.add(socket);
      socket.sessionId = id;
      socket.role = "sender";
      send(socket, { type: "session-resumed", sessionId: id, viewerApproved: Boolean(session.approved && viewerStillConnected) });
      if (viewerStillConnected) {
        if (session.approved) send(session.viewer, { type: "sender-reconnected" });
        else requestViewerApproval(session);
      }
      return;
    }

    if (msg.type === "join-session") {
      if (request.headers.origin !== VIEWER_ORIGIN) return send(socket, { type: "error", message: "Viewer origin not allowed" });
      if (socket.sessionId) return send(socket, { type: "error", message: "This connection already joined a session" });
      const id = typeof msg.sessionId === "string" ? msg.sessionId : "";
      const session = sessions.get(id);
      if (!session || !tokenMatches(session.viewerToken, msg.viewerToken) || Date.now() - session.lastActivityAt > SESSION_TTL_MS) {
        return send(socket, { type: "error", message: "Session not found or viewer link is invalid" });
      }
      if (!session.sender || session.sender.readyState !== WebSocket.OPEN) {
        return send(socket, { type: "error", message: "Sender is not connected yet" });
      }
      if (session.viewer && session.viewer.readyState === WebSocket.OPEN) {
        return send(socket, { type: "error", message: "Session already has a viewer" });
      }
      // A session is strictly one sender plus at most one viewer.
      if (session.clients.size >= MAX_CLIENTS_PER_SESSION) {
        return send(socket, { type: "error", message: "Session is full" });
      }
      session.viewer = socket;
      session.approved = false;
      session.clients.add(socket);
      socket.sessionId = id;
      socket.role = "viewer";
      requestViewerApproval(session);
      return;
    }

    const id = socket.sessionId;
    const session = id && sessions.get(id);
    if (!session || !session.clients.has(socket)) return send(socket, { type: "error", message: "Join a session first" });
    if (socket.role === "sender" && session.sender === socket) touchSession(id, session);

    if (msg.type === "approve-viewer" || msg.type === "deny-viewer") {
      if (socket.role !== "sender") return send(socket, { type: "error", message: "Only the sender can decide viewer access" });
      if (!session.viewer || session.viewer.readyState !== WebSocket.OPEN) return send(socket, { type: "error", message: "No viewer is waiting" });
      if (msg.type === "approve-viewer") {
        session.approved = true;
        send(session.viewer, { type: "sender-approved-viewer" });
        send(socket, { type: "viewer-approved" });
      } else {
        send(session.viewer, { type: "viewer-denied" });
        session.clients.delete(session.viewer);
        session.viewer.sessionId = undefined;
        session.viewer.close(1008, "Sender denied access");
        session.viewer = null;
        session.approved = false;
        send(socket, { type: "viewer-denied-by-sender" });
      }
      return;
    }

    if (msg.type === "end-session") {
      if (socket.role !== "sender") return send(socket, { type: "error", message: "Only the sender can end this session" });
      return cleanSession(id);
    }

    if (!["offer", "answer", "ice-candidate"].includes(msg.type)) {
      return send(socket, { type: "error", message: "Unsupported message type" });
    }
    if (!session.approved || !session.viewer || session.viewer.readyState !== WebSocket.OPEN) {
      return send(socket, { type: "error", message: "Viewer access has not been approved" });
    }
    if (msg.type === "offer" && socket.role !== "sender") return send(socket, { type: "error", message: "Only the sender can send an offer" });
    if (msg.type === "answer" && socket.role !== "viewer") return send(socket, { type: "error", message: "Only the viewer can send an answer" });
    if (!["sender", "viewer"].includes(socket.role)) return send(socket, { type: "error", message: "Invalid session role" });

    if (!msg.data || typeof msg.data !== "object" || Array.isArray(msg.data)) {
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
    const peer = socket.role === "sender" ? session.viewer : session.sender;
    send(peer, { type: msg.type, data: msg.data });
  });

  socket.on("close", () => {
    const id = socket.sessionId;
    const session = id && sessions.get(id);
    if (!session) return;
    session.clients.delete(socket);
    if (socket.role === "sender") {
      if (session.sender === socket) {
        session.sender = null;
        // Preserve viewing consent only while the exact same viewer socket remains connected.
        if (!session.viewer || session.viewer.readyState !== WebSocket.OPEN) session.approved = false;
        if (session.viewer) send(session.viewer, { type: "sender-disconnected" });
      }
      if (session.clients.size === 0) cleanSession(id);
      return;
    }
    if (socket.role === "viewer" && session.viewer === socket) {
      session.viewer = null;
      session.approved = false;
      if (session.sender) send(session.sender, { type: "viewer-left" });
    }
    if (session.clients.size === 0) cleanSession(id);
  });
});

const heartbeatTimer = setInterval(() => {
  for (const socket of wss.clients) {
    if (socket.isAlive === false) {
      socket.terminate();
      continue;
    }
    socket.isAlive = false;
    socket.ping();
  }
}, 25000);
if (typeof heartbeatTimer.unref === "function") heartbeatTimer.unref();

server.listen(PORT, "0.0.0.0", () => {
  console.log(`QunView signaling server listening on ${PORT}`);
});
