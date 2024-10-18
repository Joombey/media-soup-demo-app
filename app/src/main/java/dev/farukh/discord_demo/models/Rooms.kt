package dev.farukh.discord_demo.models

import com.google.gson.annotations.SerializedName

data class Rooms(
    @SerializedName("rooms")
    val rooms: List<Room>
)
