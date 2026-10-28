package com.dirtyredz.gvmled

import android.app.Application

/** Builds the [AppContainer] once per process. */
class GvmApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
