package dev.farukh.discord_demo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.activity.result.registerForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dev.farukh.discord_demo.models.ConsumeApiModel
import dev.farukh.discord_demo.models.Credentials
import dev.farukh.discord_demo.models.JoinRoomApiModel
import dev.farukh.discord_demo.models.Room
import dev.farukh.discord_demo.models.dtls2.DtlsParameters
import dev.farukh.discord_demo.models.dtls2.DtlsParametersWrapper
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.IO.Options
import io.socket.client.Socket
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mediasoup.droid.Device
import org.mediasoup.droid.MediasoupClient
import org.mediasoup.droid.PeerConnection
import org.mediasoup.droid.Producer
import org.mediasoup.droid.SendTransport
import org.mediasoup.droid.Transport
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoEncoderFactory
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordErrorCallback
import org.webrtc.audio.JavaAudioDeviceModule.AudioTrackErrorCallback

typealias WebRTCPeerConnection = org.webrtc.PeerConnection
typealias IceServers = org.webrtc.PeerConnection.IceServer
typealias RTCConfiguration = org.webrtc.PeerConnection.RTCConfiguration

class MainActivity : ComponentActivity() {
    private val connected = MutableStateFlow(false)
    private var _socket: Socket? = null
    private val api = Api()
    private val socket get() = _socket!!
    private val eagleContext = EglBase.create()

    private val peerFactory by lazy { createPeerConnectionFactory(this) }

    private val videoSource by lazy { peerFactory.createVideoSource(true) }

    private val videoTrack by lazy {
        initScreenCapture(screenCapture)
        peerFactory.createVideoTrack("share", videoSource)
    }

    lateinit var sendTransport: SendTransport

    private val device by lazy { Device() }

    private val screenCapture by lazy { getScreenCapture(data!!) }

    private var data: Intent? = null
    private var producer: Producer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            getScreenCastIntentData()
        }
        connectToServer()
        startService()
        MediasoupClient.initialize(this.applicationContext)
        setContent {
            var value by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            Column {
                Button(
                    {
                        scope.launch {
                            val response = api.service.webRTCGetClientRooms()
                            println("room ${response.body()?.rooms?.size}")
                            val room = response.body()!!.rooms.first()
                            println("room $room")
                            videoTrack.setEnabled(true)

                            val rtpCapabilities = joinRoom(room.roomID, room.roomLabel)!!
                            println("capabilities ${JSONObject(rtpCapabilities).optString("rtpCapabilities")}")
                            val creds2 = api.service.getCredentials().body()!!
                            if (!device.isLoaded) {
                                device.load(
                                    JSONObject(rtpCapabilities).optString("rtpCapabilities"),
                                    PeerConnection.Options().apply {
                                        setFactory(peerFactory)
                                        setRTCConfig(
                                            RTCConfiguration(
                                                listOf(
                                                    IceServers.builder(listOf("turn:turn.stormapi.su:3479"))
                                                        .setUsername(creds2.username)
                                                        .setPassword(creds2.password)
                                                        .createIceServer()
                                                )
                                            )
                                        )
                                    })
                            }

                            val transport = JSONObject(getWebRTCTransport())
                            println("tranport $transport")

                            val creds = api.service.getCredentials()
                            sendTransport = createSendTransport(
                                transport.getJSONObject("params"),
                                room,
                                creds.body()
                            )
                            try {
                                producer = sendTransport.produce(
                                    { },
                                    videoTrack,
                                    null,
                                    null,
                                    null
                                )
                            } catch (e: Exception) {
                                println(e.stackTraceToString())
                                null
                            }

                        }
                    },
                    Modifier.fillMaxWidth()
                ) { Text("test") }
                OutlinedTextField(value, {value = it})
            }
        }
    }

    private fun start() {

    }

    private fun startService() {
        val serviceIntent = Intent(this, StreamService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {

            }

            override fun onServiceDisconnected(name: ComponentName?) {

            }

        }, Context.BIND_AUTO_CREATE)
    }

    private suspend fun getWebRTCTransport() = suspendCancellableCoroutine { cont ->
        val listener = Ack {
            if (cont.isActive) {
                cont.resumeWith(
                    Result.success(it.first().toString())
                )
            }
        }
        val obj = JSONObject(api.gson.toJson(ConsumeApiModel(false)))
        socket.emit("createWebRtcTransport", obj, listener)
    }

    private fun sendOnConnect(
        dtlsParameters: String,
        transportId: String,
        roomID: String,
        dtls: DtlsParametersWrapper
    ) {
        socket.emit("transport-connect", dtls.asJsonObject(transportId))
    }

    private fun createSendTransport(
        json: JSONObject,
        room: Room,
        creds: Credentials?
    ): SendTransport {

        val id: String = json.optString("id")
        val iceParameters: String = json.optString("iceParameters")
        val iceCandidates: String = json.optString("iceCandidates")
        val dtlsParameters: String = json.optString("dtlsParameters")
        val sctpParameters: String? = json.optString("sctpParameters").ifEmpty { null }

        return device.createSendTransport(
            object : SendTransport.Listener {
                override fun onConnect(transport: Transport, dtlsParameters: String) {
                    val dtls = api.gson.fromJson(dtlsParameters, DtlsParameters::class.java)
                    val obj = DtlsParametersWrapper(dtls)
                    runBlocking {
                        sendOnConnect(dtlsParameters, transport.id, room.roomID, obj)
                        delay(2000)
                    }
                }

                override fun onConnectionStateChange(
                    transport: Transport?,
                    connectionState: String?
                ) {
                }

                override fun onProduce(
                    transport: Transport,
                    kind: String,
                    rtpParameters: String,
                    appData: String
                ): String? {
                    return runBlocking {
                        println("onProduce: ")
                        withTimeoutOrNull(5_000) {
                            sendNewProducer(
                                transport,
                                kind,
                                rtpParameters,
                                appData,
                                dtlsParameters
                            ).also { println(it) }
                        }
                    }
                }

                override fun onProduceData(
                    transport: Transport,
                    sctpStreamParameters: String,
                    label: String,
                    protocol: String,
                    appData: String
                ): String? {
                    println("onProduceData")
                    return runBlocking {
                        println("onProduce: ")
                        withTimeoutOrNull(5_000) {
                            sendOnProduceData(
                                sctpStreamParameters,
                                label,
                                protocol,
                                appData,
                            )
                        }
                    }
                }

            },
            id,
            iceParameters,
            iceCandidates,
            dtlsParameters,
            sctpParameters,
            PeerConnection.Options().apply {
                setFactory(peerFactory)
                setRTCConfig(
                    RTCConfiguration(
                        listOf(
                            IceServers.builder(listOf("turn:turn.stormapi.su:3479"))
                                .setUsername(creds!!.username)
                                .setPassword(creds.password)
                                .createIceServer()
                        )
                    )
                )
            },
            null
        )
    }

    private fun sendOnProduceData(
        sctpStreamParameters: String,
        label: String,
        protocol: String,
        appData: String
    ): String? {
        println(
            """
                sctpStreamParameters: $sctpStreamParameters
                label: $label
                protocol: $protocol
                appData: $appData
            """.trimIndent()
        )
        return null
//        socket.emit("transport-produce-data")
    }

    // PeerConnection factory creation.
    private fun createPeerConnectionFactory(context: Context): PeerConnectionFactory {
        val builder = PeerConnectionFactory.builder()
        builder.setOptions(null)

        val adm: AudioDeviceModule = createJavaAudioDevice(context)
        val encoderFactory: VideoEncoderFactory =
            DefaultVideoEncoderFactory(
                eagleContext.eglBaseContext,
                true,  /* enableIntelVp8Encoder */
                true
            )
        val decoderFactory: VideoDecoderFactory =
            DefaultVideoDecoderFactory(eagleContext.eglBaseContext)

        return builder
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun createJavaAudioDevice(appContext: Context): AudioDeviceModule {
        // Enable/disable OpenSL ES playback.
        // Set audio record error callbacks.
        val audioRecordErrorCallback: AudioRecordErrorCallback =
            object : AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                    Log.i("err", "onWebRtcAudioRecordInitError: $errorMessage")
                }

                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode, errorMessage: String
                ) {
                    Log.i("err", "onWebRtcAudioRecordInitError: $errorMessage")
                }

                override fun onWebRtcAudioRecordError(errorMessage: String) {
                    Log.i("err", "onWebRtcAudioRecordInitError: $errorMessage")
                }
            }

        val audioTrackErrorCallback: AudioTrackErrorCallback =
            object : AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String) {
                    Log.e(
                        "err",
                        "onWebRtcAudioTrackInitError: $errorMessage"
                    )
                }

                override fun onWebRtcAudioTrackStartError(
                    errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode, errorMessage: String
                ) {
                    Log.e(
                        "err",
                        "onWebRtcAudioTrackInitError: $errorMessage"
                    )
                }

                override fun onWebRtcAudioTrackError(errorMessage: String) {
                    Log.e(
                        "err",
                        "onWebRtcAudioTrackInitError: $errorMessage"
                    )
                }
            }

        return JavaAudioDeviceModule.builder(appContext)
            .setAudioRecordErrorCallback(audioRecordErrorCallback)
            .setAudioTrackErrorCallback(audioTrackErrorCallback)
            .createAudioDeviceModule()
    }

    private suspend fun sendNewProducer(
        transport: Transport,
        kind: String,
        rtpParameters: String,
        appData: String,
        dtlsParameters: String
    ) = suspendCancellableCoroutine { cont ->
        println(
            """
                kind: $kind,
                rtp: $rtpParameters,
                appdata: $appData
            """.trimIndent()
        )
        val obj = JSONObject().apply {
            put("kind", kind)
            put("rtpParameters", JSONObject(rtpParameters))
            put("appData", JSONObject(appData))
        }
        socket.emit(
            "transport-produce",
            obj,
            Ack {
                val json = JSONObject(it.first().toString())
                cont.resumeWith(Result.success(json.optString("id")))
            }
        )
    }

    private fun initScreenCapture(screenCapture: ScreenCapturerAndroid) {
        val surfaceTextureHelper =
            SurfaceTextureHelper.create("CaptureThread", eagleContext.eglBaseContext)
        screenCapture.initialize(surfaceTextureHelper, this, videoSource.capturerObserver)
        screenCapture.startCapture(1920, 1080, 30)
    }

    private fun getScreenCapture(data: Intent) =
        ScreenCapturerAndroid(data, object : MediaProjection.Callback() {})

    private suspend fun getScreenCastIntentData() = suspendCancellableCoroutine { cont ->
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
            getScreenCastIntent()
        ) {
            data = it.data!!
            cont.resumeWith(Result.success(it.data!!))
        }.launch()
    }

    private fun getScreenCastIntent(): Intent {
        val mediaManager = getSystemService(MediaProjectionManager::class.java)
        return mediaManager.createScreenCaptureIntent()
    }

    private suspend fun joinRoom(roomId: String, roomLabel: String) = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine { cont ->
            val listener = Ack {
                println("type ${it.first()::class.simpleName}")
                if (cont.isActive) {
                    cont.resumeWith(Result.success(it.first().toString()))
                }
            }
            println("json ${api.gson.toJson(JoinRoomApiModel(roomId, roomLabel))}")
            val obj = JSONObject(api.gson.toJson(JoinRoomApiModel(roomId, roomLabel)))
            socket.emit("joinRoom", obj, listener)
        }
    }

    private fun connectToServer() {
        val options = Options.builder()
            .setQuery("userId=e503a9d1e4b221f5")
            .build()
        _socket = IO.socket("https://manage.stormapi.su/", options)
        socket.connect()
        socket.on(Socket.EVENT_CONNECT) { connected.update { true } }
        socket.on(Socket.EVENT_CONNECT_ERROR) { connected.update { false } }
        socket.on(Socket.EVENT_DISCONNECT) { connected.update { false } }
    }

    override fun onStop() {
        socket.emit("webrtc_leave")
        socket.disconnect()
        producer?.close()
        val innerObj = JSONObject().apply {
            put("id", producer!!.id)
        }
        val obj = JSONObject().apply { put("remoteProducerId", innerObj) }
        socket.emit("producer-closed", obj)
        super.onStop()
    }
}