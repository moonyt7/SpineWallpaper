package com.spine.wallpaper

import android.app.Application
import com.spine.wallpaper.loader.SpineModelLoader
import com.spine.wallpaper.v36.SpineV36Adapter
import com.spine.wallpaper.v37.SpineV37Adapter
import com.spine.wallpaper.v38.SpineV38Adapter
import com.spine.wallpaper.v40.SpineV40Adapter
import com.spine.wallpaper.v41.SpineV41Adapter
import com.spine.wallpaper.v42.SpineV42Adapter
import com.spine.wallpaper.v43.SpineV43Adapter
import com.spine.wallpaper.v43b.SpineV43bAdapter

class SpineApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 1. Initialize libGDX native platform shared libraries (16KB aligned)
        SpineModelLoader.ensureNativesLoaded()

        // 2. Register all isolated Spine runtime modules (3.6, 3.7, 3.8, 4.0, 4.1, 4.2, 4.3, 4.3.39-beta)
        SpineV36Adapter.register()
        SpineV37Adapter.register()
        SpineV38Adapter.register()
        SpineV40Adapter.register()
        SpineV41Adapter.register()
        SpineV42Adapter.register()
        SpineV43Adapter.register()
        // 4.3.39-beta 是独立窗口：只有它读得了再导入的 4.3 beta 中间格式模型
        SpineV43bAdapter.register()
    }
}