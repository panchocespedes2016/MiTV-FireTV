package com.mitv.player

import android.app.Application
import android.content.Context
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.cache.DiskLruCacheFactory
import com.bumptech.glide.module.AppGlideModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MiTVApp : Application() {
    override fun onCreate() {
        super.onCreate()
        JsonFiles.init(this)
        PlaylistStore.load()
        ContentStore.loadCache()
        Favorites.load()
        HistoryStore.load()
        EpgStore.loadCache()
        // Refresh content in background on start, like the Windows app.
        if (PlaylistStore.active != null && ContentStore.hasData) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                PlaylistLoader.refresh(includeEpg = true)
            }
        }
    }
}

/** Glide module: image disk cache hard-capped at 500 MB (Stick has 8 GB total). */
@GlideModule
class MiTVGlideModule : AppGlideModule() {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        val cacheDir = java.io.File(context.cacheDir, "glide_images")
        builder.setDiskCache(DiskLruCacheFactory(cacheDir.absolutePath, 500L * 1024 * 1024))
    }
    override fun isManifestParsingEnabled() = false
}
