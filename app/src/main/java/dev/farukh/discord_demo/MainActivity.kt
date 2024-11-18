package dev.farukh.discord_demo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.activity.result.registerForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import dev.farukh.discord_demo.models.dtls2.DtlsParameters
import dev.farukh.discord_demo.models.dtls2.DtlsParametersWrapper
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.IO.Options
import io.socket.client.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.json.JSONObject
import org.mediasoup.droid.Consumer
import org.mediasoup.droid.Device
import org.mediasoup.droid.PeerConnection
import org.mediasoup.droid.Producer
import org.mediasoup.droid.RecvTransport
import org.mediasoup.droid.SendTransport
import org.mediasoup.droid.Transport
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon.RendererEvents
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordErrorCallback
import org.webrtc.audio.JavaAudioDeviceModule.AudioTrackErrorCallback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import java.util.concurrent.TimeUnit

typealias WebRTCPeerConnection = org.webrtc.PeerConnection
typealias IceServers = org.webrtc.PeerConnection.IceServer
typealias RTCConfiguration = org.webrtc.PeerConnection.RTCConfiguration

class MainActivity : ComponentActivity() {
    private val connected = MutableStateFlow(false)

    private var _socket: Socket? = null
    private val socket get() = _socket!!

    private var eagleContext: EglBase? = null
    private var peerFactory: PeerConnectionFactory? = null
    private var adm: AudioDeviceModule? = null
    private var videSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var sendTransport: SendTransport? = null
    private var recvTransport: RecvTransport? = null
    private var device: Device? = Device()
    private var screenCapture: ScreenCapturerAndroid? = null
    private var data: Intent? = null
    private var producer: Producer? = null
    private var consumer: Consumer? = null
    private var track: MediaStreamTrack? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { getScreenCastIntentData() }
        peerFactory = createPeerConnectionFactory(this)
        connectToServer()
        startService()
        lifecycleScope.launch(Dispatchers.IO) { test() }
        setContent {
            var value by remember { mutableStateOf("") }
            Column {
                Button(
                    onClick = ::produce,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Porduce") }

                Button(
                    onClick = ::consume,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Consume") }
                OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth())

                AndroidView(
                    factory = {
                        val view = SurfaceViewRenderer(it)
                        if (eagleContext == null) {
                            eagleContext = EglBase.create()
                        }
                        view.clipToOutline = true
                        view.addFrameListener({
                            with(Canvas(it)) {
                                drawLine(
                                    0f,
                                    height/2f,
                                    width.toFloat(),
                                    height/2f, Paint().apply {
                                        this.style = Paint.Style.STROKE
                                        color = Color.BLUE
                                        strokeWidth = 12f
                                    }
                                )
                            }
                        }, 1f)
                        view.init(eagleContext!!.eglBaseContext, object : RendererEvents {
                            override fun onFirstFrameRendered() {
                            }

                            override fun onFrameResolutionChanged(p0: Int, p1: Int, p2: Int) {
                            }
                        })
                        view
                    },
                    update = {
                        if (track != null) {
                            lifecycleScope.launch {
                                (track as VideoTrack).addSink(it)
                            }
                            Log.i("mediasoup", "onCreate: track: $track")
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    private fun produce() {
        lifecycleScope.launch {
            if (!socket.connected()) {
                connectToServer()
                delay(1000)
            }
            if (videoTrack == null) {
                if (screenCapture == null) {
                    screenCapture = getScreenCapture(data!!)
                }
                if (peerFactory == null) {
                    peerFactory = createPeerConnectionFactory(applicationContext)
                }
                if (videSource == null) {
                    videSource = peerFactory!!.createVideoSource(true)
                }
                initScreenCapture(screenCapture!!)
                videoTrack = peerFactory!!.createVideoTrack("share", videSource)
            }
            videoTrack!!.setEnabled(true)
            if (device == null) {
                device = Device()
            }

            if (device?.isLoaded == false) {
                val rtpCapabilities = getRtpCapabilities()

                val options = PeerConnection.Options().also { it.setFactory(peerFactory) }
                device?.load(rtpCapabilities, options)
            }
            Log.i("mediasoup", "start: before create transport")
            val params: JSONObject = createWebRtcTransport(true)
            sendTransport = createSendTransport(params)
            Log.i("mediasoup", "start: transport created $sendTransport")
            producer = try {
                sendTransport!!.produce(
                    { },
                    videoTrack,
                    null,
                    null,
                    null
                )
            } catch (e: Exception) {
                Log.e("mediasoup", "", e)
                Toast.makeText(this@MainActivity, "couldn't create producer", Toast.LENGTH_SHORT)
                    .show()
                null
            }
        }
    }

    private suspend fun getRtpCapabilities(): String = suspendCancellableCoroutine { cont ->
        socket.emit("createRoom", arrayOf()) {
            Log.i("mediasoup", "getRtpCapabilities: ${it.contentToString()}")
            if (cont.isActive) {
                cont.resumeWith(Result.success((it.first() as JSONObject).optString("rtpCapabilities")))
            }
        }
    }

    private suspend fun sendOnDisconnect(producer: Producer) {
        val innerObj = JSONObject().apply { put("id", producer.id) }
        val obj = JSONObject().apply { put("remoteProducerId", innerObj) }

        socket.emit("producer-closed", obj)
        socket.emit("webrtc_leave")
        socket.emit("pageUnmount")

        delay(2000)

//        socket.disconnect()

        println("send close")
    }

    private fun consume() = lifecycleScope.launch {
        if (device == null) {
            device = Device()
        }

        if (device?.isLoaded == false) {
            val rtpCapabilities = getRtpCapabilities()

            val options = PeerConnection.Options().also { it.setFactory(peerFactory) }
            device?.load(rtpCapabilities, options)
        }
        Log.i("mediasoup", "consume: before create transport")
        val params: JSONObject = createWebRtcTransport(false)
        recvTransport = createRecvTransport(params)
        Log.i("mediasoup", "consume: transport created $recvTransport")
        val paramsConsume = consume(device!!.rtpCapabilities)
        Log.i("mediasoup", "consume: params = $paramsConsume")
        val id = paramsConsume.optString("id")
        val producerId = paramsConsume.optString("producerId")
        val kind = paramsConsume.optString("kind")
        val rtpParameters = paramsConsume.optString("rtpParameters")
        Log.i("mediasoup", "consume: id: $id")
        Log.i("mediasoup", "consume: producerId: $producerId")
        Log.i("mediasoup", "consume: kind: $kind")
        Log.i("mediasoup", "consume: rtpParameters: $rtpParameters")
        consumer = recvTransport!!.consume(
            {},
            id,
            producerId,
            kind,
            rtpParameters,
        )
        socket.emit("consumer-resume")
        track = consumer?.track
    }

    private suspend fun consume(rtpCapabilities: String) = suspendCancellableCoroutine { cont ->
        socket.emit(
            "consume",
            JSONObject().apply {
                put("rtpCapabilities", JSONObject(rtpCapabilities))
            },
            Ack {
                Log.i("mediasoup", "consume: ${it.contentToString()}")
                if (cont.isActive) {
                    cont.resumeWith(Result.success((it.first() as JSONObject).getJSONObject("params")))
                }
            }
        )
    }

    private fun createRecvTransport(json: JSONObject): RecvTransport {
        val id: String = json.optString("id")
        val iceParameters: String = json.optString("iceParameters")
        val iceCandidates: String = json.optString("iceCandidates")
        val dtlsParameters: String = json.optString("dtlsParameters")
        val sctpParameters: String? = json.optString("sctpParameters").ifEmpty { null }

        Log.i("mediasoup", "createRecvTransport: id: $id")
        Log.i("mediasoup", "createRecvTransport: iceParameters: $iceParameters")
        Log.i("mediasoup", "createRecvTransport: iceCandidates: $iceCandidates")
        Log.i("mediasoup", "createRecvTransport: dtlsParameters: $dtlsParameters")
        Log.i("mediasoup", "createRecvTransport: sctpParameters: $sctpParameters")

        return device!!.createRecvTransport(
            object : RecvTransport.Listener {
                override fun onConnect(transport: Transport, dtlsParameters: String) {
                    Log.i("mediasoup", ("on Connect $dtlsParameters"))
                    socket.emit(
                        "transport-recv-connect",
                        DtlsParametersWrapper(
                            Gson().fromJson(
                                dtlsParameters,
                                DtlsParameters::class.java
                            )
                        ).asJsonObject()
                    )
                }

                override fun onConnectionStateChange(
                    transport: Transport?,
                    connectionState: String?
                ) {
                    Log.i("mediasoup", connectionState.toString())
                }
            },
            id,
            iceParameters,
            iceCandidates,
            dtlsParameters,
            sctpParameters,
            PeerConnection.Options().apply { setFactory(peerFactory) },
            null
        )
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

    private suspend fun createWebRtcTransport(produce: Boolean) =
        suspendCancellableCoroutine { cont ->
            val obj = JSONObject().apply {
                put("sender", produce)
            }
            Log.i("mediasoup", "createWebRtcTransport: emit")
            socket.emit("createWebRtcTransport", obj, Ack {
                Log.i("mediasoup", "createWebRtcTransport: ${it.contentToString()}")
                if (cont.isActive) {
                    cont.resumeWith(Result.success((it.first() as JSONObject).getJSONObject("params")))
                }
            })
        }

    private fun createSendTransport(
        json: JSONObject,
    ): SendTransport {

        val id: String = json.optString("id")
        val iceParameters: String = json.optString("iceParameters")
        val iceCandidates: String = json.optString("iceCandidates")
        val dtlsParameters: String = json.optString("dtlsParameters")
        val sctpParameters: String? = json.optString("sctpParameters").ifEmpty { null }

        return device!!.createSendTransport(
            object : SendTransport.Listener {
                override fun onConnect(transport: Transport, dtlsParameters: String) {
                    Log.i("mediasoup", ("on Connect $dtlsParameters"))
                    socket.emit(
                        "transport-connect",
                        DtlsParametersWrapper(
                            Gson().fromJson(
                                dtlsParameters,
                                DtlsParameters::class.java
                            )
                        ).asJsonObject()
                    )
                }

                override fun onConnectionStateChange(
                    transport: Transport?,
                    connectionState: String?
                ) {
                    Log.i("mediasoup", connectionState.toString())
                }

                override fun onProduce(
                    transport: Transport,
                    kind: String,
                    rtpParameters: String,
                    appData: String
                ): String {
                    val id = runBlocking {
                        Log.i("mediasoup", ("on Produce $dtlsParameters"))
                        withTimeoutOrNull(5_000) {
                            sendNewProducer(
                                transport,
                                kind,
                                rtpParameters,
                                appData,
                                dtlsParameters
                            )
                        }
                    }
                    Log.i("mediasoup", "onProduce: id: $id")
                    return id ?: ""
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
            PeerConnection.Options().apply { setFactory(peerFactory) },
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

        if (adm == null) {
            adm = createJavaAudioDevice(context)
        }
        if (eagleContext == null) {
            eagleContext = EglBase.create()
        }
        val encoderFactory: VideoEncoderFactory =
            DefaultVideoEncoderFactory(
                eagleContext!!.eglBaseContext,
                true,  /* enableIntelVp8Encoder */
                true
            )
        val decoderFactory: VideoDecoderFactory =
            DefaultVideoDecoderFactory(eagleContext!!.eglBaseContext)
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
        }
        socket.emit(
            "transport-produce",
            obj,
            Ack {
                println(it.contentToString())
                val json = JSONObject(it.first().toString())
                cont.resumeWith(Result.success(json.optString("id")))
            }
        )
    }

    private fun initScreenCapture(screenCapture: ScreenCapturerAndroid) {
        val surfaceTextureHelper =
            SurfaceTextureHelper.create("CaptureThread", eagleContext!!.eglBaseContext)
        screenCapture.initialize(surfaceTextureHelper, this, videSource!!.capturerObserver)
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

    private fun connectToServer() {
        val options = Options.builder()
            .setTransports(arrayOf("websocket"))
            .build()
        _socket = IO.socket("https://192.168.31.128:3000/mediasoup", options)
        socket.connect()
        socket.on(Socket.EVENT_CONNECT) {
            connected.update { true }
            Log.i("mediasoup", "EVENT_CONNECT")
        }
        socket.on(Socket.EVENT_CONNECT_ERROR) {
            connected.update { false }
            Log.i("mediasoup", it.contentToString())
        }
        socket.on(Socket.EVENT_DISCONNECT) {
            connected.update { false }
            Log.i("mediasoup", "EVENT_DISCONNECT")
        }
    }

    private suspend fun test() {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://192.168.31.128:3000/")
            .client(
                OkHttpClient.Builder()
                    .callTimeout(5, TimeUnit.SECONDS)
                    .build()
            )
            .build()
        val api = retrofit.create(Test::class.java)
        try {
            val result = api.hello()
            Log.i("mediasoup", "test: ${result.code()}")
        } catch (e: Exception) {
            Log.e("mediasoup", "test: ", e)
        }
    }
}

interface Test {
    @GET("/")
    suspend fun hello(): Response<ResponseBody>
}