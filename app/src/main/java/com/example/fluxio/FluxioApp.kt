package com.example.fluxio

import android.app.Application

class FluxioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SupabaseInstance.init(this)
    }
}
