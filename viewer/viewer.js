// WebRTC signaling will be connected in a later implementation step.
const video=document.getElementById("remoteVideo");
const placeholder=document.getElementById("placeholder");
const statusText=document.getElementById("status");
const sessionId=new URLSearchParams(location.search).get("session");
statusText.textContent=sessionId?"Session link detected — signaling not connected yet":"Not connected — no session link";
window.qunViewViewer={
 showStream(stream){video.srcObject=stream;video.classList.add("active");placeholder.hidden=true;statusText.textContent="Live";},
 showWaiting(message="Waiting for sender…"){video.srcObject=null;video.classList.remove("active");placeholder.hidden=false;statusText.textContent=message;}
};