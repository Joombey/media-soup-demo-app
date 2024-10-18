package dev.farukh.discord_demo

import android.app.Application
import org.mediasoup.droid.MediasoupClient

class App: Application() {
    override fun onCreate() {
        MediasoupClient.initialize(applicationContext)
        super.onCreate()
    }
}