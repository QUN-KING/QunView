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
import java.util.concurrent.TimeUnit

/**
 * Sender-side WebRTC pipeline.
 *
 * No screen frames are recorded or saved.
 */
class WebRtcScreenStreamer(
    private val context: Context,
    private val projectionPermission: Intent,
    private val onSessionReady: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onStopped: () -> Unit
) {
    companion object {
        private const val TAG = "QunViewWebRTC"
        private const val SIGNALING_URL = "wss://qunview-signaling.onrender.com"
        private const val VIEWER_BASE_URL = "https://qunview-viewer.onrender.com"
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    @Volatile private var stopped = false
    private var socket: WebSocket? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var egl: EglBase? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var sessionId: String? = null
    private val pendingRemoteIce = mutableListOf<IceCandidate>()

    fun start() {
        if (stopped) return
        initializeWebRtc()
        val request = Request.Builder().url(SIGNALING_URL).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (stopped) {
                    webSocket.close(1000, "Sender stopped")
                    return
                }
                webSocket.send(JSONObject().put("type", "create-session").toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!stopped) handleSignal(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (stopped) return
                Log.e(TAG, "Signaling connection failed", t)
                onError("Could not connect to the signaling server.")
                stop()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stop()
            }
        })
    }

    private fun initializeWebRtc() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        egl = EglBase.create()
        val encoder = DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(egl!!.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()

        videoSource = factory!!.createVideoSource(true)
        capturer = ScreenCapturerAndroid(projectionPermission, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() {
                stop()
            }
        })
        textureHelper = SurfaceTextureHelper.create("QunViewScreenCapture", egl!!.eglBaseContext)
        capturer!!.initialize(textureHelper, context, videoSource!!.capturerObserver)
        capturer!!.startCapture(720, 1280, 15)
        videoTrack = factory!!.createVideoTrack("QunViewScreenTrack", videoSource)
        videoTrack!!.setEnabled(true)
    }

    private fun createPeer() {
        if (stopped || peer != null) return
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peer = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                sendSignal(JSONObject().put("type", "ice-candidate").put("data", JSONObject()
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp)))
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: org.webrtc.MediaStream) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {}
        })
        peer?.addTrack(videoTrack, listOf("QunViewScreenStream"))
    }

    private fun createOffer() {
        createPeer()
        peer?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                peer?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        sendSignal(JSONObject().put("type", "offer").put("data", JSONObject()
                            .put("type", description.type.canonicalForm())
                            .put("sdp", description.description)))
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
                    val link = "$VIEWER_BASE_URL/?session=$sessionId&signal=$SIGNALING_URL"
                    onSessionReady(link)
                }
                "viewer-joined" -> createOffer()
                "answer" -> {
                    val data = message.getJSONObject("data")
                    val description = SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(data.getString("type")),
                        data.getString("sdp")
                    )
                    peer?.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            pendingRemoteIce.forEach { peer?.addIceCandidate(it) }
                            pendingRemoteIce.clear()
                        }
                        override fun onSetFailure(error: String) { onError("Viewer answer was rejected.") }
                        override fun onCreateSuccess(description: SessionDescription) {}
                        override fun onCreateFailure(error: String) {}
                    }, description)
                }
                "ice-candidate" -> {
                    val data = message.getJSONObject("data")
                    val candidate = IceCandidate(
                        data.optString("sdpMid"),
                        data.optInt("sdpMLineIndex"),
                        data.getString("candidate")
                    )
                    if (peer?.remoteDescription == null) pendingRemoteIce.add(candidate) else peer?.addIceCandidate(candidate)
                }
                "error" -> onError(message.optString("message", "Signaling error."))
            }
        } catch (error: Exception) {
            Log.e(TAG, "Invalid signaling message", error)
            onError("Received an invalid connection message.")
        }
    }

    private fun sendSignal(message: JSONObject) {
        if (!stopped) socket?.send(message.toString())
    }

    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        socket?.send(JSONObject().put("type", "end-session").toString())
        socket?.close(1000, "Sender stopped")
        socket = null
        stopPeerOnly()
        try { capturer?.stopCapture() } catch (error: Exception) {
            Log.w(TAG, "Screen capture already stopped", error)
        }
        capturer?.dispose()
        capturer = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        textureHelper?.dispose()
        textureHelper = null
        factory?.dispose()
        factory = null
        egl?.release()
        egl = null
        pendingRemoteIce.clear()
        client.dispatcher.executorService.shutdown()
        onStopped()
    }

    private fun stopPeerOnly() {
        peer?.close()
        peer?.dispose()
        peer = null
    }
}