package dev.farukh.discord_demo.models

import com.google.gson.annotations.SerializedName

data class Room(
    @SerializedName("roomID")
    val roomID: String,
    @SerializedName("initiatorId")
    val initiatorId: String? = null,
    @SerializedName("initiator")
    val initiator: String = "Создатель не найден",
    @SerializedName("subscribersCount")
    val subscribersCount: Int = 0,
    @SerializedName("roomLabel")
    val roomLabel: String = "Название отсутствует"
)
