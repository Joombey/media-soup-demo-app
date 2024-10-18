package dev.farukh.discord_demo.models.dtls2

import org.json.JSONArray
import org.json.JSONObject

// Data class for the fingerprint
data class Fingerprint(
    val algorithm: String,
    val value: String
)

// Data class for dtlsParameters
data class DtlsParameters(
    val fingerprints: List<Fingerprint>,
    val role: String
)

// Data class for the top-level object containing dtlsParameters
data class DtlsParametersWrapper(
    val dtlsParameters: DtlsParameters
) {
    // Method to convert the data class to JSONObject
    fun asJsonObject(transportId: String): JSONObject {
        // Create the JSONObject for fingerprints
        val fingerprintsJsonArray = JSONArray().apply {
            for (fingerprint in dtlsParameters.fingerprints) {
                put(JSONObject().apply {
                    put("algorithm", fingerprint.algorithm)
                    put("value", fingerprint.value)
                })
            }
        }

        // Create the JSONObject for dtlsParameters
        val dtlsJsonObject = JSONObject().apply {
            put("fingerprints", fingerprintsJsonArray)
            put("role", dtlsParameters.role)
        }

        // Create the final JSONObject
        return JSONObject().apply {
            put("dtlsParameters", dtlsJsonObject)
            put("serverConsumerTransportId", transportId)
        }
    }
}