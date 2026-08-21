package com.spine.wallpaper

import android.app.Application
import com.spine.wallpaper.loader.SpineModelLoader

class SpineApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SpineModelLoader.ensureNativesLoaded()
    }
}