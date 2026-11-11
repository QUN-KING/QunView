"use strict";

const { test, before, after } = require("node:test");
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const net = require("node:net");
const WebSocket = require("ws");

let serverProcess;
let baseUrl;
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

function connect(url) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    socket.once("open", () => resolve(socket));
    socket.once("error", reject);
  });
}

function nextMessage(socket, predicate = () => true, timeoutMs = 4000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      socket.off("message", onMessage);
      reject(new Error("Timed out waiting for signaling message"));
    }, timeoutMs);
    function onMessage(raw) {
      let message;
      try { message = JSON.parse(raw.toString()); } catch { return; }
      if (!predicate(message)) return;
      clearTimeout(timer);
      socket.off("message", onMessage);
      resolve(message);
    }
    socket.on("message", onMessage);
  });
}

function send(socket, message) {
  socket.send(JSON.stringify(message));
}

before(async () => {
  const port = await freePort();
  baseUrl = `http://127.0.0.1:${port}`;
  wsUrl = `ws://127.0.0.1:${port}`;
  serverProcess = spawn(process.execPath, ["server.js"], {
    cwd: __dirname,
    env: { ...process.env, PORT: String(port), TURN_URLS: "", TURN_SHARED_SECRET: "", CLOUDFLARE_TURN_KEY_ID: "", CLOUDFLARE_TURN_API_TOKEN: "" },
    stdio: "ignore"
  });
  const deadline = Date.now() + 8000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${baseUrl}/health`);
      if (response.ok) return;
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("Signaling server did not start");
});

after(() => {
  if (serverProcess) serverProcess.kill("SIGTERM");
});

test("health endpoint reports the signaling service", async () => {
  const response = await fetch(`${baseUrl}/health`);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true, service: "qunview-signaling" });
});

test("session creation, viewer approval and signaling relay are enforced", async () => {
  const sender = await connect(wsUrl);
  const createdPromise = nextMessage(sender, m => m.type === "session-created");
  send(sender, { type: "create-session" });
  const created = await createdPromise;
  assert.match(created.sessionId, /^[a-f0-9]{32}$/);
  assert.match(created.resumeToken, /^[a-f0-9]{64}$/);
  assert.match(created.viewerToken, /^[a-f0-9]{64}$/);
  const removedTurnEndpoint = await fetch(`${baseUrl}/turn-credentials?sessionId=${created.sessionId}`);
  assert.equal(removedTurnEndpoint.status, 404);

  const viewer = await connect(wsUrl);
  const waitingPromise = nextMessage(viewer, m => m.type === "waiting-for-sender-approval");
  const requestPromise = nextMessage(sender, m => m.type === "viewer-join-request");
  send(viewer, { type: "join-session", sessionId: created.sessionId, viewerToken: created.viewerToken });
  await Promise.all([waitingPromise, requestPromise]);

  const offer = { type: "offer", data: { type: "offer", sdp: "v=0\\r\\n" } };
  send(sender, offer);
  const deniedRelay = await nextMessage(sender, m => m.type === "error");
  assert.match(deniedRelay.message, /not been approved/);

  const approvedViewerPromise = nextMessage(viewer, m => m.type === "sender-approved-viewer");
  send(sender, { type: "approve-viewer" });
  await approvedViewerPromise;
  const relayedOffer = nextMessage(viewer, m => m.type === "offer");
  send(sender, offer);
  assert.deepEqual(await relayedOffer, offer);

  const answer = { type: "answer", data: { type: "answer", sdp: "v=0\\r\\n" } };
  const relayedAnswer = nextMessage(sender, m => m.type === "answer");
  send(viewer, answer);
  assert.deepEqual(await relayedAnswer, answer);

  const viewerClosed = new Promise(resolve => viewer.once("close", resolve));
  send(sender, { type: "end-session" });
  await viewerClosed;
  sender.close();
  viewer.close();
});

test("a second viewer cannot occupy an already-used session", async () => {
  const sender = await connect(wsUrl);
  const createdPromise = nextMessage(sender, m => m.type === "session-created");
  send(sender, { type: "create-session" });
  const created = await createdPromise;
  const first = await connect(wsUrl);
  const firstWaiting = nextMessage(first, m => m.type === "waiting-for-sender-approval");
  const senderRequest = nextMessage(sender, m => m.type === "viewer-join-request");
  send(first, { type: "join-session", sessionId: created.sessionId, viewerToken: created.viewerToken });
  await Promise.all([firstWaiting, senderRequest]);

  const second = await connect(wsUrl);
  const rejected = nextMessage(second, m => m.type === "error");
  send(second, { type: "join-session", sessionId: created.sessionId, viewerToken: created.viewerToken });
  assert.match((await rejected).message, /already has a viewer/);
  send(sender, { type: "end-session" });
  sender.close(); first.close(); second.close();
});


test("an approved connected viewer keeps consent when the sender resumes", async () => {
  const sender = await connect(wsUrl);
  const createdPromise = nextMessage(sender, m => m.type === "session-created");
  send(sender, { type: "create-session" });
  const created = await createdPromise;

  const viewer = await connect(wsUrl);
  const waiting = nextMessage(viewer, m => m.type === "waiting-for-sender-approval");
  const approvalRequest = nextMessage(sender, m => m.type === "viewer-join-request");
  send(viewer, { type: "join-session", sessionId: created.sessionId, viewerToken: created.viewerToken });
  await Promise.all([waiting, approvalRequest]);
  const approvedNotice = nextMessage(viewer, m => m.type === "sender-approved-viewer");
  send(sender, { type: "approve-viewer" });
  await approvedNotice;

  const disconnected = nextMessage(viewer, m => m.type === "sender-disconnected");
  sender.close();
  await disconnected;

  const resumedSender = await connect(wsUrl);
  const resumed = nextMessage(resumedSender, m => m.type === "session-resumed");
  const reconnected = nextMessage(viewer, m => m.type === "sender-reconnected");
  send(resumedSender, { type: "resume-session", sessionId: created.sessionId, resumeToken: created.resumeToken });
  assert.equal((await resumed).viewerApproved, true);
  await reconnected;

  // The retained approval authorizes signaling without another approval prompt.
  const offer = { type: "offer", data: { type: "offer", sdp: "v=0\\r\\n" } };
  const relayed = nextMessage(viewer, m => m.type === "offer");
  send(resumedSender, offer);
  assert.deepEqual(await relayed, offer);

  // Once that viewer socket leaves, a later viewer must obtain fresh approval.
  const viewerClosed = new Promise(resolve => viewer.once("close", resolve));
  viewer.close();
  await viewerClosed;
  const nextViewer = await connect(wsUrl);
  const freshWaiting = nextMessage(nextViewer, m => m.type === "waiting-for-sender-approval");
  const freshRequest = nextMessage(resumedSender, m => m.type === "viewer-join-request");
  send(nextViewer, { type: "join-session", sessionId: created.sessionId, viewerToken: created.viewerToken });
  await Promise.all([freshWaiting, freshRequest]);

  send(resumedSender, { type: "end-session" });
  resumedSender.close();
  nextViewer.close();
});

test("an unapproved viewer still needs approval after sender resumes", async () => {
  const sender = await connect(wsUrl);
  const createdPromise = nextMessage(sender, m => m.type === "session-created");
  send(sender, { type: "create-session" });
  const created = await createdPromise;
  const viewer = await connect(wsUrl);
  const waiting = nextMessage(viewer, m => m.type === "waiting-for-sender-approval");
  const request = nextMessage(sender, m => m.type === "viewer-join-request");
  send(viewer, { type: "join-session", sessionId: created.sessionId, viewerToken: created.viewerToken });
  await Promise.all([waiting, request]);

  const disconnected = nextMessage(viewer, m => m.type === "sender-disconnected");
  sender.close();
  await disconnected;
  const resumedSender = await connect(wsUrl);
  const resumed = nextMessage(resumedSender, m => m.type === "session-resumed");
  const newWaiting = nextMessage(viewer, m => m.type === "waiting-for-sender-approval");
  const newRequest = nextMessage(resumedSender, m => m.type === "viewer-join-request");
  send(resumedSender, { type: "resume-session", sessionId: created.sessionId, resumeToken: created.resumeToken });
  assert.equal((await resumed).viewerApproved, false);
  await Promise.all([newWaiting, newRequest]);

  send(resumedSender, { type: "end-session" });
  resumedSender.close();
  viewer.close();
});
