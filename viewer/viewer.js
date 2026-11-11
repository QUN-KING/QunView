// QunView viewer: video plus consent-based gesture assistance over WebRTC data channel.
const video=document.getElementById("remoteVideo");
const placeholder=document.getElementById("placeholder");
const statusText=document.getElementById("status");
const screenArea=document.getElementById("screenArea");
const backBtn=document.getElementById("backBtn"),homeBtn=document.getElementById("homeBtn"),helpBtn=document.getElementById("helpBtn");
const params=new URLSearchParams(location.search),sessionId=params.get("session"),viewerToken=params.get("key"),signalingUrl=params.get("signal");
let iceServers=[{urls:["stun:stun.l.google.com:19302"]}];
let socket,peer,controlChannel,pendingIceCandidates=[],viewerClosing=false,reconnectTimer=null,reconnectDelaySeconds=2,connectionAttempt=0,controlEnabled=false,controlPending=false,touchStart=null,peerDisconnectTimer=null;
function setStatus(s){statusText.textContent=s}
function showWaiting(s){video.srcObject=null;video.classList.remove("active");placeholder.hidden=false;setStatus(s);setControls(false)}
function setControls(ready){backBtn.disabled=!ready||!controlEnabled;homeBtn.disabled=!ready||!controlEnabled;helpBtn.disabled=!ready||!controlChannel||controlChannel.readyState!=="open"}
function sendControl(data){if(!controlEnabled||!controlChannel||controlChannel.readyState!=="open")return;controlChannel.send(JSON.stringify(data))}
function requestRemoteControl(){if(!controlChannel||controlChannel.readyState!=="open"||controlPending)return;controlPending=true;helpBtn.disabled=true;helpBtn.textContent="Waiting for phone owner";controlChannel.send(JSON.stringify({action:"request-control"}));setStatus("Waiting for the phone owner to approve remote controls…")}
function resetRemoteHelp(){controlEnabled=false;controlPending=false;helpBtn.textContent="Request remote help";setControls(false)}
async function loadIceServers(){
 try{
  const endpoint=new URL(signalingUrl);\n  endpoint.protocol="https:";\n  endpoint.pathname="/turn-credentials";\n  endpoint.search="";
  endpoint.searchParams.set("sessionId",sessionId);
  endpoint.searchParams.set("token",viewerToken);
  const response=await fetch(endpoint.toString(),{cache:"no-store",credentials:"omit"});
  if(!response.ok)throw new Error("ICE configuration unavailable");
  const config=await response.json();
  if(Array.isArray(config.iceServers)&&config.iceServers.length)iceServers=config.iceServers;
 }catch(error){console.warn("Using STUN only; TURN credentials were unavailable.",error)}
}
function createPeer(){
 if(peer)peer.close();pendingIceCandidates=[];controlChannel=null;resetRemoteHelp();
 peer=new RTCPeerConnection({iceServers});
 peer.ondatachannel=e=>{if(e.channel.label!=="qunview-control")return;controlChannel=e.channel;controlChannel.onopen=()=>{setControls(true);setStatus("Connected — screen viewing only")};controlChannel.onmessage=event=>{let msg;try{msg=JSON.parse(event.data)}catch{return}if(msg.action==="control-approved"){controlPending=false;controlEnabled=true;helpBtn.disabled=false;helpBtn.textContent="Pause remote help";setControls(true);setStatus("Remote controls approved by phone owner")}else if(msg.action==="control-denied"){controlPending=false;controlEnabled=false;helpBtn.disabled=false;helpBtn.textContent="Request remote help";setControls(true);setStatus("Phone owner did not allow remote controls")}};controlChannel.onclose=()=>{controlEnabled=false;controlPending=false;setControls(false)};};
 peer.ontrack=e=>{const stream=e.streams&&e.streams[0];if(!stream)return;video.srcObject=stream;video.classList.add("active");placeholder.hidden=true;setStatus("Live screen");setControls(true);video.play().catch(()=>setStatus("Tap the video to start playback."));};
 peer.onicecandidate=e=>{if(e.candidate&&socket&&socket.readyState===WebSocket.OPEN)socket.send(JSON.stringify({type:"ice-candidate",data:e.candidate}))};
 peer.onconnectionstatechange=()=>{if(!peer)return;const state=peer.connectionState;if(state==="connected"){if(peerDisconnectTimer){clearTimeout(peerDisconnectTimer);peerDisconnectTimer=null}reconnectDelaySeconds=2;setStatus("Connected — waiting for video…")}else if(state==="failed"){setStatus("Connection failed. Reconnecting…");if(socket&&socket.readyState===WebSocket.OPEN)socket.close(1011,"WebRTC connection failed")}else if(state==="disconnected"){setStatus("Connection interrupted. Checking connection…");if(peerDisconnectTimer)clearTimeout(peerDisconnectTimer);peerDisconnectTimer=setTimeout(()=>{peerDisconnectTimer=null;if(peer&&peer.connectionState==="disconnected"&&socket&&socket.readyState===WebSocket.OPEN)socket.close(1011,"WebRTC connection lost")},5000)}};
}
async function handleOffer(data){if(!peer||["failed","closed"].includes(peer.connectionState))createPeer();await peer.setRemoteDescription(data);const answer=await peer.createAnswer();await peer.setLocalDescription(answer);if(socket&&socket.readyState===WebSocket.OPEN)socket.send(JSON.stringify({type:"answer",data:peer.localDescription}));setStatus("Negotiating secure connection…");for(const c of pendingIceCandidates){try{await peer.addIceCandidate(c)}catch(e){console.warn(e)}}pendingIceCandidates=[]}
function scheduleViewerReconnect(){if(viewerClosing||reconnectTimer)return;resetRemoteHelp();if(peerDisconnectTimer){clearTimeout(peerDisconnectTimer);peerDisconnectTimer=null}if(peer){peer.close();peer=null}showWaiting("Connection lost. Reconnecting…");reconnectTimer=setTimeout(()=>{reconnectTimer=null;connectViewer()},reconnectDelaySeconds*1000);reconnectDelaySeconds=Math.min(reconnectDelaySeconds*2,30)}
async function connectViewer(){
 if(!sessionId||!/^[a-f0-9]{32}$/i.test(sessionId)||!viewerToken||!/^[a-f0-9]{64}$/i.test(viewerToken)){showWaiting("A valid private QunView link is required.");return}
 const expected="wss://qunview-signaling.onrender.com";if(signalingUrl!==expected){showWaiting("This QunView link is not valid.");return}
 setStatus("Loading secure connection settings…");await loadIceServers();setStatus("Connecting to QunView…");createPeer();const attempt=++connectionAttempt;const ws=new WebSocket(signalingUrl);socket=ws;
 ws.onopen=()=>{if(attempt!==connectionAttempt)return;ws.send(JSON.stringify({type:"join-session",sessionId,viewerToken}));setStatus("Joined session — waiting for sender…")};
 ws.onmessage=async e=>{let m;try{m=JSON.parse(e.data)}catch{return}try{if(m.type==="offer"&&m.data)await handleOffer(m.data);else if(m.type==="ice-candidate"&&m.data){if(!peer||!peer.remoteDescription)pendingIceCandidates.push(m.data);else await peer.addIceCandidate(m.data)}else if(m.type==="waiting-for-sender-approval"){showWaiting("Waiting for the phone owner to Allow screen viewing…")}else if(m.type==="sender-approved-viewer"){showWaiting("Approved. Connecting to the shared screen…")}else if(m.type==="viewer-denied"){resetRemoteHelp();showWaiting("The phone owner denied this viewing request.")}else if(m.type==="sender-disconnected"){resetRemoteHelp();showWaiting("The phone owner disconnected. Waiting for them to reconnect and approve again…")}else if(m.type==="sender-reconnected"){createPeer();showWaiting("Sender reconnected. Waiting for approval…")}else if(m.type==="error"){const message=m.message||"Unable to join session.";setStatus(message);if(message==="Sender is not connected yet"&&socket===ws&&ws.readyState===WebSocket.OPEN){setStatus("Sender is offline. Reconnecting to check again…");ws.close(1011,"Waiting for sender");}}else if(m.type==="viewer-left")showWaiting("Sender ended the session.")}catch(err){console.error(err);showWaiting("Connection negotiation failed.")}};
 ws.onerror=()=>setStatus("Signaling error. Reconnecting…");ws.onclose=event=>{if(!viewerClosing&&attempt===connectionAttempt&&event.code!==1000&&event.code!==1008)scheduleViewerReconnect()};
}
backBtn.onclick=()=>sendControl({action:"back"});homeBtn.onclick=()=>sendControl({action:"home"});
helpBtn.onclick=()=>{if(controlEnabled){sendControl({action:"release-control"});controlEnabled=false;controlPending=false;helpBtn.textContent="Request remote help";setStatus("Remote help paused and permission released")}else requestRemoteControl()};
screenArea.addEventListener("pointerdown",e=>{if(!controlEnabled||!video.classList.contains("active"))return;const r=video.getBoundingClientRect();touchStart={x:(e.clientX-r.left)/r.width,y:(e.clientY-r.top)/r.height,time:Date.now()}});
screenArea.addEventListener("pointerup",e=>{if(!touchStart||!controlEnabled)return;const r=video.getBoundingClientRect(),x=Math.max(0,Math.min(1,(e.clientX-r.left)/r.width)),y=Math.max(0,Math.min(1,(e.clientY-r.top)/r.height));const dx=x-touchStart.x,dy=y-touchStart.y;if(Math.abs(dx)+Math.abs(dy)<0.035)sendControl({action:"tap",x:touchStart.x,y:touchStart.y});else sendControl({action:"swipe",x1:touchStart.x,y1:touchStart.y,x2:x,y2:y});touchStart=null});
window.addEventListener("beforeunload",()=>{viewerClosing=true;if(reconnectTimer)clearTimeout(reconnectTimer);if(peerDisconnectTimer)clearTimeout(peerDisconnectTimer);if(socket&&socket.readyState===WebSocket.OPEN)socket.close();if(peer)peer.close()});
connectViewer().catch(e=>{console.error(e);showWaiting("Could not start viewer. Check the session link.")});