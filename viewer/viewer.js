// QunView browser receiver. Requires a deployed WSS signaling endpoint.
const video = document.getElementById("remoteVideo");
const placeholder = document.getElementById("placeholder");
const statusText = document.getElementById("status");
const params = new URLSearchParams(location.search);
const sessionId = params.get("session");
const signalingUrl = params.get("signal");

let socket;
let peer;
let pendingIceCandidates = [];
let viewerClosing = false;

function setStatus(message) { statusText.textContent = message; }
function showWaiting(message) { video.srcObject = null; video.classList.remove("active"); placeholder.hidden = false; setStatus(message); }

function createPeer() {
  if (peer) peer.close();
  pendingIceCandidates = [];
  peer = new RTCPeerConnection({ iceServers: [{ urls: ["stun:stun.l.google.com:19302"] }] });
  peer.ontrack = (event) => {
    const stream = event.streams && event.streams[0]; if (!stream) return;
    video.srcObject = stream; video.classList.add("active"); placeholder.hidden = true; setStatus("Live screen");
    video.play().catch(() => setStatus("Video playback was blocked by the browser. Refresh the viewer page and try again."));
  };
  peer.onicecandidate = (event) => { if (event.candidate && socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: "ice-candidate", data: event.candidate })); };
  peer.onconnectionstatechange = () => {
    if (!peer) return;
    if (peer.connectionState === "failed" || peer.connectionState === "disconnected") setStatus("Connection interrupted. Reconnecting automatically…");
    else if (peer.connectionState === "connected") setStatus("Connected — waiting for video…");
  };
}

async function handleOffer(data) {
  if (!peer || peer.connectionState === "failed" || peer.connectionState === "closed") createPeer();
  await peer.setRemoteDescription(data);
  const answer = await peer.createAnswer(); await peer.setLocalDescription(answer);
  if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: "answer", data: peer.localDescription }));
  setStatus("Negotiating secure video connection…");
  for (const candidate of pendingIceCandidates) { try { await peer.addIceCandidate(candidate); } catch (error) { console.error("Queued ICE candidate rejected", error); } }
  pendingIceCandidates = [];
}

async function connectViewer() {
  if (!sessionId || !/^[a-f0-9]{32}$/i.test(sessionId)) { showWaiting("A valid session link is required."); return; }
  const expectedSignalingUrl = "wss://qunview-signaling.onrender.com";
  if (signalingUrl !== expectedSignalingUrl) { showWaiting("This QunView link is not valid."); return; }
  setStatus("Connecting to QunView…"); createPeer(); socket = new WebSocket(signalingUrl);
  socket.onopen = () => { socket.send(JSON.stringify({ type: "join-session", sessionId })); setStatus("Joined session — waiting for sender…"); };
  socket.onmessage = async (event) => {
    let message; try { message = JSON.parse(event.data); } catch { return; }
    try {
      if (message.type === "offer" && message.data) await handleOffer(message.data);
      else if (message.type === "ice-candidate" && message.data) {
        if (!peer || !peer.remoteDescription) pendingIceCandidates.push(message.data);
        else { try { await peer.addIceCandidate(message.data); } catch (error) { console.error("ICE candidate rejected", error); } }
      } else if (message.type === "sender-reconnected") { createPeer(); showWaiting("Sender reconnected. Reconnecting automatically…"); }
      else if (message.type === "error") setStatus(message.message || "Unable to join session.");
      else if (message.type === "viewer-left") showWaiting("Sender ended the session.");
    } catch (error) { console.error("Viewer negotiation failed", error); showWaiting("Connection interrupted. Waiting for automatic retry…"); }
  };
  socket.onerror = () => setStatus("Signaling connection error.");
  socket.onclose = () => { if (viewerClosing) return; if (peer) peer.close(); showWaiting("Session disconnected."); };
}
window.addEventListener("beforeunload", () => {
  viewerClosing = true;
  if (socket && socket.readyState === WebSocket.OPEN) socket.close();
  if (peer) peer.close();
});

connectViewer().catch((error) => {
  console.error(error);
  showWaiting("Could not start the viewer. Check the session link.");
});
