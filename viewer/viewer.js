// QunView viewer: video plus consent-based gesture assistance over WebRTC data channel.
const video=document.getElementById("remoteVideo");
const placeholder=document.getElementById("placeholder");
const statusText=document.getElementById("status");
const screenArea=document.getElementById("screenArea");
const backBtn=document.getElementById("backBtn"),homeBtn=document.getElementById("homeBtn"),helpBtn=document.getElementById("helpBtn");
const params=new URLSearchParams(location.search),sessionId=params.get("session"),signalingUrl=params.get("signal");
let socket,peer,controlChannel,pendingIceCandidates=[],viewerClosing=false,reconnectTimer=null,reconnectDelaySeconds=2,connectionAttempt=0,controlEnabled=false,touchStart=null;
function setStatus(s){statusText.textContent=s}
function showWaiting(s){video.srcObject=null;video.classList.remove("active");placeholder.hidden=false;setStatus(s);setControls(false)}
function setControls(ready){backBtn.disabled=!ready;homeBtn.disabled=!ready;helpBtn.disabled=!ready||!controlChannel||controlChannel.readyState!=="open"}
function sendControl(data){if(!controlEnabled||!controlChannel||controlChannel.readyState!=="open")return;controlChannel.send(JSON.stringify(data))}
function createPeer(){
 if(peer)peer.close();pendingIceCandidates=[];controlChannel=null;setControls(false);
 peer=new RTCPeerConnection({iceServers:[{urls:["stun:stun.l.google.com:19302"]}]});
 peer.ondatachannel=e=>{if(e.channel.label!=="qunview-control")return;controlChannel=e.channel;controlChannel.onopen=()=>{setControls(true);setStatus("Connected — remote help channel ready")};controlChannel.onclose=()=>{controlEnabled=false;setControls(false)};};
 peer.ontrack=e=>{const stream=e.streams&&e.streams[0];if(!stream)return;video.srcObject=stream;video.classList.add("active");placeholder.hidden=true;setStatus("Live screen");setControls(true);video.play().catch(()=>setStatus("Tap the video to start playback."));};
 peer.onicecandidate=e=>{if(e.candidate&&socket&&socket.readyState===WebSocket.OPEN)socket.send(JSON.stringify({type:"ice-candidate",data:e.candidate}))};
 peer.onconnectionstatechange=()=>{if(!peer)return;if(["failed","disconnected"].includes(peer.connectionState))setStatus("Connection interrupted. Reconnecting…");else if(peer.connectionState==="connected")setStatus("Connected — waiting for video…")};
}
async function handleOffer(data){if(!peer||["failed","closed"].includes(peer.connectionState))createPeer();await peer.setRemoteDescription(data);const answer=await peer.createAnswer();await peer.setLocalDescription(answer);if(socket&&socket.readyState===WebSocket.OPEN)socket.send(JSON.stringify({type:"answer",data:peer.localDescription}));setStatus("Negotiating secure connection…");for(const c of pendingIceCandidates){try{await peer.addIceCandidate(c)}catch(e){console.warn(e)}}pendingIceCandidates=[]}
function scheduleViewerReconnect(){if(viewerClosing||reconnectTimer)return;if(peer){peer.close();peer=null}showWaiting("Connection lost. Reconnecting…");reconnectTimer=setTimeout(()=>{reconnectTimer=null;connectViewer()},reconnectDelaySeconds*1000);reconnectDelaySeconds=Math.min(reconnectDelaySeconds*2,30)}
async function connectViewer(){
 if(!sessionId||!/^[a-f0-9]{32}$/i.test(sessionId)){showWaiting("A valid session link is required.");return}
 const expected="wss://qunview-signaling.onrender.com";if(signalingUrl!==expected){showWaiting("This QunView link is not valid.");return}
 setStatus("Connecting to QunView…");createPeer();const attempt=++connectionAttempt;const ws=new WebSocket(signalingUrl);socket=ws;
 ws.onopen=()=>{if(attempt!==connectionAttempt)return;reconnectDelaySeconds=2;ws.send(JSON.stringify({type:"join-session",sessionId}));setStatus("Joined session — waiting for sender…")};
 ws.onmessage=async e=>{let m;try{m=JSON.parse(e.data)}catch{return}try{if(m.type==="offer"&&m.data)await handleOffer(m.data);else if(m.type==="ice-candidate"&&m.data){if(!peer||!peer.remoteDescription)pendingIceCandidates.push(m.data);else await peer.addIceCandidate(m.data)}else if(m.type==="sender-reconnected"){createPeer();showWaiting("Sender reconnected…")}else if(m.type==="error")setStatus(m.message||"Unable to join session.");else if(m.type==="viewer-left")showWaiting("Sender ended the session.")}catch(err){console.error(err);showWaiting("Connection negotiation failed.")}};
 ws.onerror=()=>setStatus("Signaling error. Reconnecting…");ws.onclose=()=>{if(!viewerClosing&&attempt===connectionAttempt)scheduleViewerReconnect()};
}
backBtn.onclick=()=>sendControl({action:"back"});homeBtn.onclick=()=>sendControl({action:"home"});
helpBtn.onclick=()=>{controlEnabled=!controlEnabled;helpBtn.textContent=controlEnabled?"Pause remote help":"Enable remote help";setStatus(controlEnabled?"Remote help enabled":"Remote help paused")};
screenArea.addEventListener("pointerdown",e=>{if(!controlEnabled||!video.classList.contains("active"))return;const r=video.getBoundingClientRect();touchStart={x:(e.clientX-r.left)/r.width,y:(e.clientY-r.top)/r.height,time:Date.now()}});
screenArea.addEventListener("pointerup",e=>{if(!touchStart||!controlEnabled)return;const r=video.getBoundingClientRect(),x=Math.max(0,Math.min(1,(e.clientX-r.left)/r.width)),y=Math.max(0,Math.min(1,(e.clientY-r.top)/r.height));const dx=x-touchStart.x,dy=y-touchStart.y;if(Math.abs(dx)+Math.abs(dy)<0.035)sendControl({action:"tap",x:touchStart.x,y:touchStart.y});else sendControl({action:"swipe",x1:touchStart.x,y1:touchStart.y,x2:x,y2:y});touchStart=null});
window.addEventListener("beforeunload",()=>{viewerClosing=true;if(reconnectTimer)clearTimeout(reconnectTimer);if(socket&&socket.readyState===WebSocket.OPEN)socket.close();if(peer)peer.close()});
connectViewer().catch(e=>{console.error(e);showWaiting("Could not start viewer. Check the session link.")});