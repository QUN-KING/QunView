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

function setStatus(message) { statusText.textContent = message; }
function showWaiting(message) {
  video.srcObject = null;
  video.classList.remove("active");
  placeholder.hidden = false;
  setStatus(message);
}

async function connectViewer() {
  if (!sessionId || !/^[a-f0-9]{32}$/i.test(sessionId)) {
    showWaiting("A valid session link is required.");
    return;
  }
  const expectedSignalingUrl = "wss://qunview-signaling.onrender.com";
  if (signalingUrl !== expectedSignalingUrl) {
    showWaiting("This QunView link is not valid.");
    return;
  }

  setStatus("Connecting to QunView…");
  socket = new WebSocket(signalingUrl);
  peer = new RTCPeerConnection({
    iceServers: [{ urls: ["stun:stun.l.google.com:19302"] }]
  });

  peer.ontrack = (event) => {
    const stream = event.streams && event.streams[0];
    if (stream) {
      video.srcObject = stream;
      video.classList.add("active");
      placeholder.hidden = true;
      setStatus("Live screen");
      video.play().catch(() => setStatus("Tap play to view the live screen."));
    }
  };

  peer.onicecandidate = (event) => {
    if (event.candidate && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: "ice-candidate", data: event.candidate }));
    }
  };

  peer.onconnectionstatechange = () => {
    if (peer.connectionState === "failed") setStatus("Connection failed. The sender may need a TURN relay.");
    else if (peer.connectionState === "disconnected") setStatus("Connection interrupted.");
    else if (peer.connectionState === "connected") setStatus("Connected — waiting for video…");
  };

  socket.onopen = () => {
    socket.send(JSON.stringify({ type: "join-session", sessionId }));
    setStatus("Joined session — waiting for sender…");
  };

  socket.onmessage = async (event) => {
    let message;
    try { message = JSON.parse(event.data); } catch { return; }
    if (message.type === "offer" && message.data) {
      await peer.setRemoteDescription(message.data);
      const answer = await peer.createAnswer();
      await peer.setLocalDescription(answer);
      socket.send(JSON.stringify({ type: "answer", data: peer.localDescription }));
      setStatus("Negotiating secure video connection…");
      for (const candidate of pendingIceCandidates) {
        try { await peer.addIceCandidate(candidate); } catch (error) { console.error("Queued ICE candidate rejected", error); }
      }
      pendingIceCandidates = [];
    } else if (message.type === "ice-candidate" && message.data) {
      if (!peer.remoteDescription) {
        pendingIceCandidates.push(message.data);
      } else {
        try { await peer.addIceCandidate(message.data); } catch (error) { console.error("ICE candidate rejected", error); }
      }
    } else if (message.type === "error") {
      setStatus(message.message || "Unable to join session.");
    } else if (message.type === "viewer-left") {
      showWaiting("Sender ended the session.");
    }
  };

  socket.onerror = () => setStatus("Signaling connection error.");
  socket.onclose = () => {
    if (peer) peer.close();
    showWaiting("Session disconnected.");
  };
}

window.addEventListener("beforeunload", () => {
  if (socket && socket.readyState === WebSocket.OPEN) socket.close();
  if (peer) peer.close();
});

connectViewer().catch((error) => {
  console.error(error);
  showWaiting("Could not start the viewer. Check the session link.");
});
