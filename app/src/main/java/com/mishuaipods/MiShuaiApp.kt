package com.mishuaipods

import android.app.Application

class MiShuaiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: MiShuaiApp
            private set
    }
}
