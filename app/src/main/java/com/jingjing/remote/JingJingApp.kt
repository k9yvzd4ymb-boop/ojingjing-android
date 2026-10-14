package com.jingjing.remote

import android.app.Application

class JingJingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCtx.init(this)
        KeepAliveService.ensureChannel(this)
    }
}