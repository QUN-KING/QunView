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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/** Sender-side WebRTC pipeline with one independent peer connection per approved viewer. */
class WebRtcScreenStreamer(
    private val context: Context,
    private val projectionPermission: Intent,
    private val allowRemoteAssistance: Boolean,
    private val onSessionReady: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onViewerRequest: (String) -> Unit,
    private val onRemoteControlRequest: () -> Unit,
    private val onViewerLeft: (String) -> Unit,
    private val onRemoteControlRevoked: () -> Unit,
    private val onStopped: () -> Unit
) {
    companion object {
        private const val TAG = "QunViewWebRTC"
        private const val SIGNALING_URL = "wss://qunview-signaling.onrender.com"
        private const val VIEWER_BASE_URL = "https://qunview-viewer.onrender.com"
    }

    private data class ViewerConnection(
        val peer: PeerConnection,
        var controlChannel: DataChannel? = null,
        val pendingRemoteIce: MutableList<IceCandidate> = mutableListOf(),
        @Volatile var approved: Boolean = true,
        @Volatile var remoteControlApproved: Boolean = false,
        @Volatile var remoteControlRequestPending: Boolean = false,
        @Volatile var approvedAccessibilityService: QunViewAccessibilityService? = null,
        @Volatile var recoveryPending: Boolean = false
    )

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    @Volatile private var stopped = false
    private var socket: WebSocket? = null
    private var factory: PeerConnectionFactory? = null
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
    private val retryExecutor = Executors.newSingleThreadScheduledExecutor()
    private val viewers = ConcurrentHashMap<String, ViewerConnection>()
    private val pendingViewerIds = ConcurrentHashMap.newKeySet<String>()

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
        closeAllPeers()
        retryExecutor.schedule({
            synchronized(this@WebRtcScreenStreamer) {
                retryPending = false
                if (!stopped) connectSignaling()
            }
        }, retryDelay, TimeUnit.SECONDS)
        retryDelay = (retryDelay * 2).coerceAtMost(30L)
    }

    private fun schedulePeerRecovery(viewerId: String, state: PeerConnection.IceConnectionState) {
        val connection = viewers[viewerId] ?: return
        if (stopped || connection.recoveryPending) return
        connection.recoveryPending = true
        retryExecutor.schedule({
            synchronized(this@WebRtcScreenStreamer) {
                connection.recoveryPending = false
                if (stopped || viewers[viewerId] !== connection) return@synchronized
                val currentState = connection.peer.iceConnectionState()
                if (currentState != PeerConnection.IceConnectionState.DISCONNECTED &&
                    currentState != PeerConnection.IceConnectionState.FAILED) return@synchronized
                Log.w(TAG, "Viewer $viewerId WebRTC ICE $state persisted; rebuilding peer")
                closeViewer(viewerId)
                createOffer(viewerId)
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

    private fun createPeer(viewerId: String): ViewerConnection? {
        if (stopped) return null
        viewers[viewerId]?.let { return it }
        lateinit var connection: ViewerConnection
        val peer = factory!!.createPeerConnection(PeerConnection.RTCConfiguration(fetchIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                when (state) {
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> schedulePeerRecovery(viewerId, state)
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                sendSignal(JSONObject().put("type", "ice-candidate").put("viewerId", viewerId).put("data", JSONObject()
                    .put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex).put("candidate", candidate.sdp)))
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: org.webrtc.MediaStream) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {}
        }) ?: run {
            onError("Could not create a connection for one viewer.")
            return null
        }
        connection = ViewerConnection(peer)
        viewers[viewerId] = connection
        peer.addTrack(videoTrack, listOf("QunViewScreenStream"))
        val observedControlChannel = peer.createDataChannel("qunview-control", DataChannel.Init())
        connection.controlChannel = observedControlChannel
        observedControlChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (viewers[viewerId] === connection &&
                    (observedControlChannel.state() == DataChannel.State.CLOSED ||
                     observedControlChannel.state() == DataChannel.State.CLOSING)) {
                    revokeRemoteControlApproval(connection)
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (!buffer.binary && buffer.data.remaining() <= 1024 && viewers[viewerId] === connection) {
                    try {
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        val command = JSONObject(String(bytes, Charsets.UTF_8))
                        when (command.optString("action")) {
                            "request-control" -> {
                                val accessibilityService = QunViewAccessibilityService.instance
                                if (RemoteAssistancePolicy.canRequestControl(allowRemoteAssistance, connection.approved, accessibilityService != null)) {
                                    connection.remoteControlRequestPending = false
                                    connection.approvedAccessibilityService = accessibilityService
                                    connection.remoteControlApproved = true
                                    sendControlReply(connection, "control-approved")
                                } else {
                                    revokeRemoteControlApproval(connection)
                                    sendControlReply(connection, "control-denied")
                                }
                            }
                            "release-control" -> revokeRemoteControlApproval(connection)
                            "back", "home", "tap", "swipe" -> {
                                val accessibilityService = QunViewAccessibilityService.instance
                                if (RemoteAssistancePolicy.canExecuteControl(allowRemoteAssistance, connection.approved,
                                        connection.remoteControlApproved, accessibilityService != null) &&
                                    accessibilityService != null && connection.approvedAccessibilityService === accessibilityService) {
                                    accessibilityService.performRemoteCommand(command)
                                } else if (connection.remoteControlApproved) {
                                    revokeRemoteControlApproval(connection)
                                }
                            }
                        }
                    } catch (error: Exception) { Log.w(TAG, "Rejected control message from $viewerId", error) }
                }
            }
        })
        return connection
    }

    @Synchronized fun approveViewer(viewerId: String) {
        if (stopped || viewerId !in pendingViewerIds) return
        pendingViewerIds.remove(viewerId)
        sendSignal(JSONObject().put("type", "approve-viewer").put("viewerId", viewerId))
    }

    @Synchronized fun denyViewer(viewerId: String) {
        if (stopped || viewerId !in pendingViewerIds) return
        pendingViewerIds.remove(viewerId)
        sendSignal(JSONObject().put("type", "deny-viewer").put("viewerId", viewerId))
    }

    private fun revokeRemoteControlApproval(connection: ViewerConnection) {
        val hadRemoteControlState = connection.remoteControlApproved || connection.remoteControlRequestPending
        connection.remoteControlApproved = false
        connection.remoteControlRequestPending = false
        connection.approvedAccessibilityService = null
        if (hadRemoteControlState) onRemoteControlRevoked()
    }

    private fun sendControlReply(connection: ViewerConnection, action: String) {
        val bytes = ByteBuffer.wrap(JSONObject().put("action", action).toString().toByteArray(Charsets.UTF_8))
        connection.controlChannel?.send(DataChannel.Buffer(bytes, false))
    }

    private fun createOffer(viewerId: String) {
        val connection = createPeer(viewerId) ?: return
        if (!connection.approved) return
        connection.peer.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                if (viewers[viewerId] !== connection) return
                connection.peer.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        sendSignal(JSONObject().put("type", "offer").put("viewerId", viewerId).put("data", JSONObject()
                            .put("type", description.type.canonicalForm()).put("sdp", description.description)))
                    }
                    override fun onSetFailure(error: String) { onError("Could not set local video description for a viewer.") }
                    override fun onCreateSuccess(description: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) { onError("Could not create a viewer video connection.") }
            override fun onSetFailure(error: String) {}
        }, MediaConstraints())
    }

    private fun closeViewer(viewerId: String) {
        val connection = viewers.remove(viewerId) ?: return
        revokeRemoteControlApproval(connection)
        connection.controlChannel?.unregisterObserver()
        connection.controlChannel?.close()
        connection.controlChannel?.dispose()
        connection.peer.close()
        connection.peer.dispose()
    }

    private fun closeAllPeers() {
        viewers.keys.toList().forEach { closeViewer(it) }
    }

    private fun handleSignal(raw: String) {
        try {
            val message = JSONObject(raw)
            val viewerId = message.optString("viewerId")
            when (message.optString("type")) {
                "session-created" -> {
                    sessionId = message.getString("sessionId")
                    resumeToken = message.getString("resumeToken")
                    viewerToken = message.getString("viewerToken")
                    retryDelay = 2L
                    retryPending = false
                    onSessionReady("$VIEWER_BASE_URL/?session=$sessionId&key=$viewerToken")
                }
                "session-resumed" -> {
                    retryDelay = 2L
                    retryPending = false
                }
                "viewer-join-request" -> {
                    if (viewerId.isNotBlank() && pendingViewerIds.add(viewerId)) onViewerRequest(viewerId)
                }
                "viewer-approved" -> {
                    pendingViewerIds.remove(viewerId)
                    val connection = viewers[viewerId]
                    if (connection != null) connection.approved = true
                    createOffer(viewerId)
                }
                "viewer-denied-by-sender" -> {
                    pendingViewerIds.remove(viewerId)
                    closeViewer(viewerId)
                    onViewerLeft(viewerId)
                }
                "viewer-left" -> {
                    pendingViewerIds.remove(viewerId)
                    closeViewer(viewerId)
                    onViewerLeft(viewerId)
                }
                "sender-reconnected" -> {
                    val connection = viewers[viewerId]
                    if (connection != null) {
                        connection.approved = true
                        createOffer(viewerId)
                    } else createOffer(viewerId)
                }
                "answer" -> {
                    val connection = viewers[viewerId] ?: return
                    val data = message.getJSONObject("data")
                    val description = SessionDescription(SessionDescription.Type.fromCanonicalForm(data.getString("type")), data.getString("sdp"))
                    connection.peer.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            synchronized(connection.pendingRemoteIce) {
                                connection.pendingRemoteIce.forEach { connection.peer.addIceCandidate(it) }
                                connection.pendingRemoteIce.clear()
                            }
                        }
                        override fun onSetFailure(error: String) { onError("A viewer answer was rejected.") }
                        override fun onCreateSuccess(description: SessionDescription) {}
                        override fun onCreateFailure(error: String) {}
                    }, description)
                }
                "ice-candidate" -> {
                    val connection = viewers[viewerId] ?: return
                    val data = message.getJSONObject("data")
                    val candidate = IceCandidate(data.optString("sdpMid"), data.optInt("sdpMLineIndex"), data.getString("candidate"))
                    if (connection.peer.remoteDescription == null) {
                        synchronized(connection.pendingRemoteIce) { connection.pendingRemoteIce.add(candidate) }
                    } else connection.peer.addIceCandidate(candidate)
                }
                "error" -> {
                    val msg = message.optString("message", "Signaling error.")
                    if (msg == "Session cannot be resumed" && socket != null) {
                        sessionId = null
                        resumeToken = null
                        viewerToken = null
                        closeAllPeers()
                        pendingViewerIds.clear()
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
        socket?.send(JSONObject().put("type", "end-session").toString())
        socket?.close(1000, "Sender stopped")
        socket = null
        retryExecutor.shutdownNow()
        closeAllPeers()
        pendingViewerIds.clear()
        try { capturer?.stopCapture() } catch (error: Exception) { Log.w(TAG, "Capture already stopped", error) }
        capturer?.dispose(); capturer = null
        videoTrack?.dispose(); videoTrack = null
        videoSource?.dispose(); videoSource = null
        textureHelper?.dispose(); textureHelper = null
        factory?.dispose(); factory = null
        egl?.release(); egl = null
        client.dispatcher.executorService.shutdown()
        onStopped()
    }
}
