"use strict";

const { test, before, after } = require("node:test");
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const net = require("node:net");
const WebSocket = require("ws");

let serverProcess;
let wsUrl;

async function freePort() {
  return new Promise((resolve, reject) => {
    const probe = net.createServer();
    probe.once("error", reject);
    probe.listen(0, "127.0.0.1", () => {
      const port = probe.address().port;
      probe.close(error => error ? reject(error) : resolve(port));
    });
  });
}

function connect(headers) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(wsUrl, { headers });
    socket.once("open", () => resolve(socket));
    socket.once("error", reject);
  });
}

function nextMessage(socket) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Timed out waiting for rate-limit response")), 3000);
    socket.once("message", raw => {
      clearTimeout(timer);
      resolve(JSON.parse(raw.toString()));
    });
  });
}

function rejectedStatus(headers) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(wsUrl, { headers });
    const timer = setTimeout(() => {
      socket.terminate();
      reject(new Error("Timed out waiting for rejected WebSocket handshake"));
    }, 3000);
    socket.once("open", () => {
      clearTimeout(timer);
      socket.close();
      reject(new Error("WebSocket handshake unexpectedly succeeded"));
    });
    socket.once("unexpected-response", (_request, response) => {
      clearTimeout(timer);
      const status = response.statusCode;
      response.resume();
      resolve(status);
    });
    socket.once("error", () => {});
  });
}

before(async () => {
  const port = await freePort();
  wsUrl = `ws://127.0.0.1:${port}`;
  serverProcess = spawn(process.execPath, ["server.js"], {
    cwd: __dirname,
    env: {
      ...process.env,
      PORT: String(port),
      TRUST_CF_CONNECTING_IP: "true"
    },
    stdio: "ignore"
  });
  const deadline = Date.now() + 8000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/health`);
      if (response.ok) return;
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("Signaling server did not start");
});

after(() => {
  if (serverProcess) serverProcess.kill("SIGTERM");
});

test("per-IP WebSocket connection limit rejects the 61st connection in one minute", async () => {
  const headers = { "CF-Connecting-IP": "198.51.100.10" };
  const sockets = [];
  try {
    for (let i = 0; i < 60; i += 1) sockets.push(await connect(headers));
    assert.equal(await rejectedStatus(headers), 429);
  } finally {
    for (const socket of sockets) socket.close();
  }
});

test("per-IP session creation limit rejects the 11th session in ten minutes", async () => {
  const headers = { "CF-Connecting-IP": "198.51.100.11" };
  for (let i = 0; i < 10; i += 1) {
    const socket = await connect(headers);
    const response = nextMessage(socket);
    socket.send(JSON.stringify({ type: "create-session" }));
    assert.equal((await response).type, "session-created");
    const closed = new Promise(resolve => socket.once("close", resolve));
    socket.send(JSON.stringify({ type: "end-session" }));
    await closed;
  }

  const socket = await connect(headers);
  const response = nextMessage(socket);
  socket.send(JSON.stringify({ type: "create-session" }));
  const rejected = await response;
  assert.equal(rejected.type, "error");
  assert.match(rejected.message, /Session creation rate limit exceeded/);
  socket.close();
});
