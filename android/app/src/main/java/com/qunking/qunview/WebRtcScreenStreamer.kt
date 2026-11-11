package com.qunking.qunview

import android.content.Context
import android.content.Intent
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/** Sender-side WebRTC pipeline. Screen frames are never recorded or saved; networking is free STUN-only. */
class WebRtcScreenStreamer(
    private val context: Context,
    private val projectionPermission: Intent,
    private val onSessionReady: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onViewerRequest: () -> Unit,
    private val onRemoteControlRequest: () -> Unit,
    private val onViewerLeft: () -> Unit,
    private val onRemoteControlRevoked: () -> Unit,
    private val onStopped: () -> Unit
) {
    companion object {
        private const val TAG = "QunViewWebRTC"
        private const val SIGNALING_URL = "wss://qunview-signaling.onrender.com"
        private const val VIEWER_BASE_URL = "https://qunview-viewer.onrender.com"
    }

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    @Volatile private var stopped = false
    @Volatile private var viewerApproved = false
    @Volatile private var remoteControlApproved = false
    @Volatile private var remoteControlRequestPending = false
    @Volatile private var approvedAccessibilityService: QunViewAccessibilityService? = null
    private var socket: WebSocket? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var controlChannel: DataChannel? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var egl: EglBase? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var sessionId: String? = null
    private var resumeToken: String? = null
    private var viewerToken: String? = null
    private var retryDelay = 2L
    private var retryPending = false
    @Volatile private var peerRecoveryPending = false
    private val retryExecutor = Executors.newSingleThreadScheduledExecutor()
    private val pendingRemoteIce = mutableListOf<IceCandidate>()

    fun start() {
        if (stopped) return
        initializeWebRtc()
        connectSignaling()
    }

    @Synchronized private fun connectSignaling() {
        if (stopped || socket != null) return
        val newSocket = client.newWebSocket(Request.Builder().url(SIGNALING_URL).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (stopped) { webSocket.close(1000, "Sender stopped"); return }
                val id = sessionId
                val token = resumeToken
                val payload = if (id != null && token != null)
                    JSONObject().put("type", "resume-session").put("sessionId", id).put("resumeToken", token)
                else JSONObject().put("type", "create-session")
                webSocket.send(payload.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) { if (!stopped) handleSignal(text) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Signaling interrupted; retrying", t)
                synchronized(this@WebRtcScreenStreamer) { if (socket === webSocket) socket = null }
                scheduleReconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(this@WebRtcScreenStreamer) { if (socket === webSocket) socket = null }
                if (!stopped) scheduleReconnect()
            }
        })
        socket = newSocket
    }

    @Synchronized private fun scheduleReconnect() {
        if (stopped || retryPending) return
        retryPending = true
        stopPeerOnly()
        pendingRemoteIce.clear()
        retryExecutor.schedule({
            synchronized(this@WebRtcScreenStreamer) {
                retryPending = false
                if (!stopped) connectSignaling()
            }
        }, retryDelay, TimeUnit.SECONDS)
        retryDelay = (retryDelay * 2).coerceAtMost(30L)
    }

    private fun schedulePeerRecovery(state: PeerConnection.IceConnectionState) {
        if (stopped || !viewerApproved || peerRecoveryPending) return
        peerRecoveryPending = true
        retryExecutor.schedule({
            synchronized(this@WebRtcScreenStreamer) {
                peerRecoveryPending = false
                if (stopped || !viewerApproved) return@synchronized
                val current = peer ?: return@synchronized
                val currentState = current.iceConnectionState()
                if (currentState != PeerConnection.IceConnectionState.DISCONNECTED &&
                    currentState != PeerConnection.IceConnectionState.FAILED) return@synchronized
                Log.w(TAG, "WebRTC ICE $state persisted; rebuilding peer connection")
                stopPeerOnly()
                pendingRemoteIce.clear()
                createOffer()
            }
        }, if (state == PeerConnection.IceConnectionState.DISCONNECTED) 5L else 0L, TimeUnit.SECONDS)
    }

    private fun initializeWebRtc() {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false).createInitializationOptions())
        egl = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl!!.eglBaseContext))
            .createPeerConnectionFactory()
        videoSource = factory!!.createVideoSource(true)
        capturer = ScreenCapturerAndroid(projectionPermission, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() { stop() }
        })
        textureHelper = SurfaceTextureHelper.create("QunViewScreenCapture", egl!!.eglBaseContext)
        capturer!!.initialize(textureHelper, context, videoSource!!.capturerObserver)
        capturer!!.startCapture(720, 1280, 15)
        videoTrack = factory!!.createVideoTrack("QunViewScreenTrack", videoSource)
        videoTrack!!.setEnabled(true)
    }

    private fun fetchIceServers(): List<PeerConnection.IceServer> =
        listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())

    private fun createPeer() {
        if (stopped || peer != null) return
        val ice = fetchIceServers()
        peer = factory!!.createPeerConnection(PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> peerRecoveryPending = false
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> schedulePeerRecovery(state)
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                sendSignal(JSONObject().put("type", "ice-candidate").put("data", JSONObject()
                    .put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex).put("candidate", candidate.sdp)))
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: org.webrtc.MediaStream) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {}
        })
        peer?.addTrack(videoTrack, listOf("QunViewScreenStream"))
        val observedControlChannel = peer?.createDataChannel("qunview-control", DataChannel.Init())
        controlChannel = observedControlChannel
        observedControlChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (observedControlChannel != null && controlChannel === observedControlChannel &&
                    (observedControlChannel.state() == DataChannel.State.CLOSED ||
                     observedControlChannel.state() == DataChannel.State.CLOSING)) {
                    revokeRemoteControlApproval()
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (!buffer.binary && buffer.data.remaining() <= 1024) {
                    try {
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        val command = JSONObject(String(bytes, Charsets.UTF_8))
                        when (command.optString("action")) {
                            "request-control" -> {
                                val accessibilityService = QunViewAccessibilityService.instance
                                if (viewerApproved && accessibilityService != null) {
                                    if (remoteControlApproved && approvedAccessibilityService === accessibilityService) {
                                        sendControlReply("control-approved")
                                    } else {
                                        revokeRemoteControlApproval()
                                        if (!remoteControlRequestPending) {
                                            remoteControlRequestPending = true
                                            onRemoteControlRequest()
                                        }
                                    }
                                } else {
                                    revokeRemoteControlApproval()
                                    sendControlReply("control-denied")
                                }
                            }
                            "release-control" -> revokeRemoteControlApproval()
                            "back", "home", "tap", "swipe" -> {
                                val accessibilityService = QunViewAccessibilityService.instance
                                if (viewerApproved && remoteControlApproved &&
                                    accessibilityService != null &&
                                    approvedAccessibilityService === accessibilityService) {
                                    accessibilityService.performRemoteCommand(command)
                                } else if (remoteControlApproved) {
                                    revokeRemoteControlApproval()
                                }
                            }
                        }
                    } catch (error: Exception) { Log.w(TAG, "Rejected control message", error) }
                }
            }
        })
    }

    @Synchronized fun approveViewer() {
        if (stopped || viewerApproved) return
        viewerApproved = true
        sendSignal(JSONObject().put("type", "approve-viewer"))
    }

    @Synchronized fun approveRemoteControl(): Boolean {
        val accessibilityService = QunViewAccessibilityService.instance
        if (stopped || !viewerApproved || accessibilityService == null) {
            revokeRemoteControlApproval()
            sendControlReply("control-denied")
            return false
        }
        remoteControlRequestPending = false
        approvedAccessibilityService = accessibilityService
        remoteControlApproved = true
        sendControlReply("control-approved")
        return true
    }

    @Synchronized fun denyRemoteControl() {
        revokeRemoteControlApproval()
        sendControlReply("control-denied")
    }

    private fun revokeRemoteControlApproval() {
        val hadRemoteControlState = remoteControlApproved || remoteControlRequestPending
        remoteControlApproved = false
        remoteControlRequestPending = false
        approvedAccessibilityService = null
        if (hadRemoteControlState) onRemoteControlRevoked()
    }

    private fun revokeRemoteControlAndNotify() {
        revokeRemoteControlApproval()
        sendControlReply("control-denied")
    }

    private fun sendControlReply(action: String) {
        val bytes = ByteBuffer.wrap(JSONObject().put("action", action).toString().toByteArray(Charsets.UTF_8))
        controlChannel?.send(DataChannel.Buffer(bytes, false))
    }

    @Synchronized fun denyViewer() {
        if (stopped || viewerApproved) return
        sendSignal(JSONObject().put("type", "deny-viewer"))
    }

    private fun createOffer() {
        if (!viewerApproved) return
        createPeer()
        peer?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                peer?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        sendSignal(JSONObject().put("type", "offer").put("data", JSONObject()
                            .put("type", description.type.canonicalForm()).put("sdp", description.description)))
                    }
                    override fun onSetFailure(error: String) { onError("Could not set local video description.") }
                    override fun onCreateSuccess(description: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) { onError("Could not create video connection.") }
            override fun onSetFailure(error: String) {}
        }, MediaConstraints())
    }

    private fun handleSignal(raw: String) {
        try {
            val message = JSONObject(raw)
            when (message.optString("type")) {
                "session-created" -> {
                    sessionId = message.getString("sessionId")
                    resumeToken = message.getString("resumeToken")
                    viewerToken = message.getString("viewerToken")
                    retryDelay = 2L
                    retryPending = false
                    onSessionReady("$VIEWER_BASE_URL/?session=$sessionId&key=$viewerToken&signal=$SIGNALING_URL")
                }
                "session-resumed" -> {
                    retryDelay = 2L
                    retryPending = false
                    viewerApproved = message.optBoolean("viewerApproved", false)
                    // Remote-control permission is always requested again after reconnect.
                    revokeRemoteControlAndNotify()
                    pendingRemoteIce.clear()
                    if (viewerApproved) createOffer()
                }
                "viewer-join-request" -> {
                    viewerApproved = false
                    revokeRemoteControlApproval()
                    stopPeerOnly()
                    pendingRemoteIce.clear()
                    onViewerRequest()
                }
                "viewer-approved" -> {
                    viewerApproved = true
                    createOffer()
                }
                "viewer-denied-by-sender" -> {
                    viewerApproved = false
                    revokeRemoteControlApproval()
                    stopPeerOnly()
                    pendingRemoteIce.clear()
                }
                "viewer-left" -> {
                    viewerApproved = false
                    revokeRemoteControlApproval()
                    stopPeerOnly()
                    pendingRemoteIce.clear()
                    onViewerLeft()
                }
                "answer" -> {
                    val data = message.getJSONObject("data")
                    val description = SessionDescription(SessionDescription.Type.fromCanonicalForm(data.getString("type")), data.getString("sdp"))
                    peer?.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() { pendingRemoteIce.forEach { peer?.addIceCandidate(it) }; pendingRemoteIce.clear() }
                        override fun onSetFailure(error: String) { onError("Viewer answer was rejected.") }
                        override fun onCreateSuccess(description: SessionDescription) {}
                        override fun onCreateFailure(error: String) {}
                    }, description)
                }
                "ice-candidate" -> {
                    val data = message.getJSONObject("data")
                    val candidate = IceCandidate(data.optString("sdpMid"), data.optInt("sdpMLineIndex"), data.getString("candidate"))
                    if (peer?.remoteDescription == null) pendingRemoteIce.add(candidate) else peer?.addIceCandidate(candidate)
                }
                "error" -> {
                    val msg = message.optString("message", "Signaling error.")
                    if (msg == "Session cannot be resumed" && socket != null) {
                        sessionId = null; resumeToken = null; viewerToken = null; stopPeerOnly(); pendingRemoteIce.clear()
                        socket?.send(JSONObject().put("type", "create-session").toString())
                    } else onError(msg)
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Invalid signaling message", error)
            onError("Received an invalid connection message.")
        }
    }

    private fun sendSignal(message: JSONObject) { if (!stopped) socket?.send(message.toString()) }

    @Synchronized fun stop() {
        if (stopped) return
        stopped = true
        peerRecoveryPending = false
        viewerApproved = false
        revokeRemoteControlApproval()
        socket?.send(JSONObject().put("type", "end-session").toString())
        socket?.close(1000, "Sender stopped")
        socket = null
        retryExecutor.shutdownNow()
        stopPeerOnly()
        try { capturer?.stopCapture() } catch (error: Exception) { Log.w(TAG, "Capture already stopped", error) }
        capturer?.dispose(); capturer = null
        videoTrack?.dispose(); videoTrack = null
        videoSource?.dispose(); videoSource = null
        textureHelper?.dispose(); textureHelper = null
        factory?.dispose(); factory = null
        egl?.release(); egl = null
        pendingRemoteIce.clear()
        client.dispatcher.executorService.shutdown()
        onStopped()
    }

    private fun stopPeerOnly() {
        val hadRemoteControlState = remoteControlApproved || remoteControlRequestPending
        revokeRemoteControlApproval()
        if (hadRemoteControlState) onRemoteControlRevoked()
        controlChannel?.unregisterObserver()
        controlChannel?.close()
        controlChannel?.dispose()
        controlChannel = null
        peer?.close()
        peer?.dispose()
        peer = null
    }
}