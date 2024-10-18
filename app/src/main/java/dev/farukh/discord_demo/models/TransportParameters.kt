package dev.farukh.discord_demo.models

data class Params(
    val id: String,
    val iceParameters: IceParameters,
    val iceCandidates: List<IceCandidate>,
    val dtlsParameters: DtlsParameters
)


data class IceParameters(
    val usernameFragment: String,
    val password: String,
    val iceLite: Boolean
)

data class IceCandidate(
    val foundation: String,
    val priority: Int,
    val ip: String,
    val address: String,
    val protocol: String,
    val port: Int,
    val type: String,
    val tcpType: String
)

data class DtlsParameters(
    val fingerprints: List<Fingerprint>,
    val role: String
)

data class Fingerprint(
    val algorithm: String,
    val value: String
)

data class TransportParameters(
    val params: Params
)