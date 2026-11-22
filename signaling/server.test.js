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

function connect(url, origin) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url, origin ? { headers: { Origin: origin } } : undefined);
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

  const viewer = await connect(wsUrl, "https://qunview-viewer.onrender.com");
  const waitingPromise = nextMessage(viewer, m => m.type === "waiting-for-sender-approval");
  const requestPromise = nextMessage(sender, m => m.type === "viewer-join-request");
  send(viewer, { type: "join-session", sessionId: created.sessionId, viewerToken: created.viewerToken });
  const [, joinRequest] = await Promise.all([waitingPromise, requestPromise]);

  const offer = { type: "offer", data: { type: "offer", sdp: "v=0\\r\\n" } };
  send(sender, offer);
  const deniedRelay = await nextMessage(sender, m => m.type === "error");
  assert.match(deniedRelay.message, /not been approved/);

  const approvedViewerPromise = nextMessage(viewer, m => m.type === "sender-approved-viewer");
  send(sender, { type: "approve-viewer", viewerId: joinRequest.viewerId });
  await approvedViewerPromise;
  const relayedOffer = nextMessage(viewer, m => m.type === "offer");
  send(sender, { ...offer, viewerId: joinRequest.viewerId });
  assert.deepEqual(await relayedOffer, { ...offer, viewerId: joinRequest.viewerId });

  const answer = { type: "answer", data: { type: "answer", sdp: "v=0\\r\\n" } };
  const relayedAnswer = nextMessage(sender, m => m.type === "answer");
  send(viewer, answer);
  assert.deepEqual(await relayedAnswer, { ...answer, viewerId: joinRequest.viewerId });

  const viewerClosed = new Promise(resolve => viewer.once("close", resolve));
  send(sender, { type: "end-session" });
  await viewerClosed;
  sender.close();
  viewer.close();
});

test("multiple viewers are independently approved and routed", async () => {
  const sender = await connect(wsUrl);
  const createdPromise = nextMessage(sender, m => m.type === "session-created");
  send(sender, { type: "create-session" });
  const created = await createdPromise;
  const viewers = await Promise.all([
    connect(wsUrl, "https://qunview-viewer.onrender.com"),
    connect(wsUrl, "https://qunview-viewer.onrender.com")
  ]);
  const firstWaiting = nextMessage(viewers[0], m => m.type === "waiting-for-sender-approval");
  const firstRequest = nextMessage(sender, m => m.type === "viewer-join-request");
  send(viewers[0], { type: "join-session", sessionId: created.sessionId, viewerToken: created.viewerToken });
  const [, firstJoin] = await Promise.all([firstWaiting, firstRequest]);

  const secondWaiting = nextMessage(viewers[1], m => m.type === "waiting-for-sender-approval");
  const secondRequest = nextMessage(sender, m => m.type === "viewer-join-request");
  send(viewers[1], { type: "join-session", sessionId: created.sessionId, viewerToken: created.viewerToken });
  const [, secondJoin] = await Promise.all([secondWaiting, secondRequest]);
  const ids = [firstJoin.viewerId, secondJoin.viewerId];
  assert.equal(new Set(ids).size, 2);

  for (let i = 0; i < viewers.length; i++) {
    const approved = nextMessage(viewers[i], m => m.type === "sender-approved-viewer");
    send(sender, { type: "approve-viewer", viewerId: ids[i] });
    assert.equal((await approved).viewerId, ids[i]);
  }

  const offerA = { type: "offer", viewerId: ids[0], data: { type: "offer", sdp: "offer-A" } };
  const offerB = { type: "offer", viewerId: ids[1], data: { type: "offer", sdp: "offer-B" } };
  const gotA = nextMessage(viewers[0], m => m.type === "offer");
  const gotB = nextMessage(viewers[1], m => m.type === "offer");
  send(sender, offerA);
  send(sender, offerB);
  assert.deepEqual(await gotA, offerA);
  assert.deepEqual(await gotB, offerB);

  const answerA = nextMessage(sender, m => m.type === "answer" && m.viewerId === ids[0]);
  const answerB = nextMessage(sender, m => m.type === "answer" && m.viewerId === ids[1]);
  send(viewers[0], { type: "answer", data: { type: "answer", sdp: "answer-A" } });
  send(viewers[1], { type: "answer", data: { type: "answer", sdp: "answer-B" } });
  assert.equal((await answerA).data.sdp, "answer-A");
  assert.equal((await answerB).data.sdp, "answer-B");

  // One viewer leaving must not interrupt another approved viewer.
  const viewerLeft = nextMessage(sender, m => m.type === "viewer-left" && m.viewerId === ids[0]);
  const firstClosed = new Promise(resolve => viewers[0].once("close", resolve));
  viewers[0].close();
  await Promise.all([viewerLeft, firstClosed]);
  const stillLiveOffer = { type: "offer", viewerId: ids[1], data: { type: "offer", sdp: "still-live" } };
  const stillLive = nextMessage(viewers[1], m => m.type === "offer");
  send(sender, stillLiveOffer);
  assert.deepEqual(await stillLive, stillLiveOffer);

  const deniedViewer = await connect(wsUrl, "https://qunview-viewer.onrender.com");
  const deniedWaiting = nextMessage(deniedViewer, m => m.type === "waiting-for-sender-approval");
  const deniedRequest = nextMessage(sender, m => m.type === "viewer-join-request");
  send(deniedViewer, { type: "join-session", sessionId: created.sessionId, viewerToken: created.viewerToken });
  const [, pending] = await Promise.all([deniedWaiting, deniedRequest]);
  const denied = nextMessage(deniedViewer, m => m.type === "viewer-denied");
  send(sender, { type: "deny-viewer", viewerId: pending.viewerId });
  await denied;

  send(sender, { type: "end-session" });
  sender.close();
  for (const viewer of [...viewers, deniedViewer]) viewer.close();
});


test("an approved connected viewer keeps consent when the sender resumes", async () => {
  const sender = await connect(wsUrl);
  const createdPromise = nextMessage(sender, m => m.type === "session-created");
  send(sender, { type: "create-session" });
  const created = await createdPromise;

  const viewer = await connect(wsUrl, "https://qunview-viewer.onrender.com");
  const waiting = nextMessage(viewer, m => m.type === "waiting-for-sender-approval");
  const approvalRequest = nextMessage(sender, m => m.type === "viewer-join-request");
  send(viewer, { type: "join-session", sessionId: created.sessionId, viewerToken: created.viewerToken });
  const [, joinRequest] = await Promise.all([waiting, approvalRequest]);
  const approvedNotice = nextMessage(viewer, m => m.type === "sender-approved-viewer");
  send(sender, { type: "approve-viewer", viewerId: joinRequest.viewerId });
  await approvedNotice;

  const disconnected = nextMessage(viewer, m => m.type === "sender-disconnected");
  sender.close();
  await disconnected;

  const resumedSender = await connect(wsUrl);
  const resumed = nextMessage(resumedSender, m => m.type === "session-resumed");
  const reconnected = nextMessage(viewer, m => m.type === "sender-reconnected");
  send(resumedSender, { type: "resume-session", sessionId: created.sessionId, resumeToken: created.resumeToken });
  assert.equal((await resumed).viewerCount, 1);
  await reconnected;

  // The retained approval authorizes signaling without another approval prompt.
  const offer = { type: "offer", data: { type: "offer", sdp: "v=0\\r\\n" } };
  const relayed = nextMessage(viewer, m => m.type === "offer");
  send(resumedSender, { ...offer, viewerId: joinRequest.viewerId });
  assert.deepEqual(await relayed, { ...offer, viewerId: joinRequest.viewerId });

  // Once that viewer socket leaves, a later viewer must obtain fresh approval.
  const viewerClosed = new Promise(resolve => viewer.once("close", resolve));
  viewer.close();
  await viewerClosed;
  const nextViewer = await connect(wsUrl, "https://qunview-viewer.onrender.com");
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
  const viewer = await connect(wsUrl, "https://qunview-viewer.onrender.com");
  const waiting = nextMessage(viewer, m => m.type === "waiting-for-sender-approval");
  const request = nextMessage(sender, m => m.type === "viewer-join-request");
  send(viewer, { type: "join-session", sessionId: created.sessionId, viewerToken: created.viewerToken });
  const [, joinRequest] = await Promise.all([waiting, request]);

  const disconnected = nextMessage(viewer, m => m.type === "sender-disconnected");
  sender.close();
  await disconnected;
  const resumedSender = await connect(wsUrl);
  const resumed = nextMessage(resumedSender, m => m.type === "session-resumed");
  const newWaiting = nextMessage(viewer, m => m.type === "waiting-for-sender-approval");
  const newRequest = nextMessage(resumedSender, m => m.type === "viewer-join-request");
  send(resumedSender, { type: "resume-session", sessionId: created.sessionId, resumeToken: created.resumeToken });
  assert.equal((await resumed).viewerCount, 1);
  await Promise.all([newWaiting, newRequest]);

  send(resumedSender, { type: "end-session" });
  resumedSender.close();
  viewer.close();
});

test("a socket already joined to one session cannot resume another session", async () => {
  const senderA = await connect(wsUrl);
  const createdAPromise = nextMessage(senderA, m => m.type === "session-created");
  send(senderA, { type: "create-session" });
  const sessionA = await createdAPromise;

  const senderB = await connect(wsUrl);
  const createdBPromise = nextMessage(senderB, m => m.type === "session-created");
  send(senderB, { type: "create-session" });
  const sessionB = await createdBPromise;

  const viewer = await connect(wsUrl, "https://qunview-viewer.onrender.com");
  const waiting = nextMessage(viewer, m => m.type === "waiting-for-sender-approval");
  const request = nextMessage(senderA, m => m.type === "viewer-join-request");
  send(viewer, { type: "join-session", sessionId: sessionA.sessionId, viewerToken: sessionA.viewerToken });
  const [, joinRequest] = await Promise.all([waiting, request]);

  const rejected = nextMessage(viewer, m => m.type === "error");
  send(viewer, { type: "resume-session", sessionId: sessionB.sessionId, resumeToken: sessionB.resumeToken });
  assert.match((await rejected).message, /already belongs to a session/);

  const approved = nextMessage(viewer, m => m.type === "sender-approved-viewer");
  send(senderA, { type: "approve-viewer", viewerId: joinRequest.viewerId });
  await approved;

  send(senderA, { type: "end-session" });
  send(senderB, { type: "end-session" });
  senderA.close(); senderB.close(); viewer.close();
});

test("viewer connections require the exact hosted viewer origin", async () => {
  const sender = await connect(wsUrl);
  const createdPromise = nextMessage(sender, m => m.type === "session-created");
  send(sender, { type: "create-session" });
  const created = await createdPromise;
  const viewer = await connect(wsUrl, "https://attacker.example");
  const closed = new Promise(resolve => viewer.once("close", (code) => resolve(code)));
  send(viewer, { type: "join-session", sessionId: created.sessionId, viewerToken: created.viewerToken });
  assert.equal(await closed, 1008);
  send(sender, { type: "end-session" });
  sender.close(); viewer.close();
});


test("session capacity is four viewers by default", async () => {
  const sender = await connect(wsUrl);
  const createdPromise = nextMessage(sender, m => m.type === "session-created");
  send(sender, { type: "create-session" });
  const created = await createdPromise;
  const viewers = [];
  for (let i = 0; i < 4; i++) {
    const viewer = await connect(wsUrl, "https://qunview-viewer.onrender.com");
    const waiting = nextMessage(viewer, m => m.type === "waiting-for-sender-approval");
    const request = nextMessage(sender, m => m.type === "viewer-join-request");
    send(viewer, { type: "join-session", sessionId: created.sessionId, viewerToken: created.viewerToken });
    await Promise.all([waiting, request]);
    viewers.push(viewer);
  }
  const fifth = await connect(wsUrl, "https://qunview-viewer.onrender.com");
  const rejected = nextMessage(fifth, m => m.type === "error");
  send(fifth, { type: "join-session", sessionId: created.sessionId, viewerToken: created.viewerToken });
  assert.match((await rejected).message, /viewer capacity/);
  send(sender, { type: "end-session" });
  sender.close();
  viewers.forEach(viewer => viewer.close());
  fifth.close();
});