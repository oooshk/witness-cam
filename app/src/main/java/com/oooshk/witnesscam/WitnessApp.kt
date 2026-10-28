package com.oooshk.witnesscam

import android.app.Application

class WitnessApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
    }
}
