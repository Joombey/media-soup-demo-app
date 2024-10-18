package dev.farukh.discord_demo

import android.net.wifi.hotspot2.pps.Credential.UserCredential
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import dev.farukh.discord_demo.models.Credentials
import dev.farukh.discord_demo.models.RoomsApiModel
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers

class Api {
    val service: ApiService
    val gson = GsonBuilder()
        .setStrictness(Strictness.LENIENT)
        .setPrettyPrinting()
        .create()

    init {
        service = Retrofit.Builder()
            .baseUrl("https://manage.stormapi.su/")
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(OkHttpClient.Builder()
                .addInterceptor(
                    HttpLoggingInterceptor {
                        Log.i("api", it)
                    }.apply { level = HttpLoggingInterceptor.Level.BODY }
                )
                .addInterceptor { chain ->
                    val newRequest = chain.request().newBuilder()
                        .build()
                    chain.proceed(newRequest)
                }.build()
            ).build().create(ApiService::class.java)
    }
}

const val TEST_API_KEY = "apikey: \$2a\$10\$ArWWw82tPibTNPaSqzxYC.TJI63GjP5XGALgFHsPw3diNxsf7NJby"
const val TEST_AUTH = "authorization: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOjcxLCJpYXQiOjE3Mjc2OTkzOTUsImV4cCI6MTcyNzY5OTM5NX0.L49DpdSxQN7HPZ4CzJuE-eRBDBdzwEIcXzM7OPRz17Q"
const val TEST_TURN = "apikey:615399d4eaa2752574b7c21d28b5cde1db27c9b4bed6cccc4735076e9a470eab"

const val PROD_API_KEY = "apikey: $2a$12\$aM/1k3rEnPinpCzrogOS..qqtgr8/RNR1iDQk/KX40CdDU7L9a1oW\""
const val PROD_AUTH = "authorization: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOjI5NCwiaWF0IjoxNzEzNzM4NzgxLCJleHAiOjE3MTM3Mzg3ODF9.eZ3B5DyDkn30rW8jUylNokLbLJmjK_jqk_4q4hMF2kI"
const val PROD_TURN = "apikey:615399d4eaa2752574b7c21d28b5cde1db27c9b4bed6cccc4735076e9a470eab"

interface ApiService {
    @Headers(
        PROD_API_KEY,
        PROD_AUTH,
    )
    @GET("api/calls/webrtc_getClientRooms")
    suspend fun webRTCGetClientRooms(): Response<RoomsApiModel>

    @Headers(
        PROD_AUTH,
        PROD_TURN
    )
    @GET("/api/turnInfo/getCredentials")
    suspend fun getCredentials(): Response<Credentials>
}