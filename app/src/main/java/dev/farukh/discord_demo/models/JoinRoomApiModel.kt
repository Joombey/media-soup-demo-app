package dev.farukh.discord_demo.models

import org.json.JSONObject

data class JoinRoomApiModel(
    val roomName: String,
    val fullRoomName: String,
    val canUseVideo: Boolean = true,
    val canUseAudio: Boolean = true,
) {
    fun asJsonObject() = JSONObject().apply {
        put("roomName", roomName)
        put("fullRoomName", fullRoomName)
    }
}