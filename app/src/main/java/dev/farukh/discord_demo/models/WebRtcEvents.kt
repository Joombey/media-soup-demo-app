package dev.farukh.discord_demo.models

sealed class WebRtcEvents{
    data class ConnectWebRtcTransport(val dtlsParameters: String? = null): WebRtcEvents()
    data class ProduceWebRtcTransport(val kind: String, val rtpParameters: String, val appData: String) : WebRtcEvents()
}