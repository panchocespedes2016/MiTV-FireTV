package com.mitv.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Tiny JSON-file persistence helpers (internal storage), mirroring the
 *  Windows app's portable JSON files (playlists.json, favorites, history). */

object JsonFiles {
    lateinit var dir: File
    fun init(context: Context) { dir = context.filesDir }

    fun read(name: String): String? = try {
        val f = File(dir, name); if (f.exists()) f.readText() else null
    } catch (e: Exception) { null }

    fun write(name: String, text: String) {
        try {
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(dir, name))
        } catch (e: Exception) { /* storage full etc. — app must not crash */ }
    }

    fun delete(name: String) { try { File(dir, name).delete() } catch (e: Exception) {} }
}

object PlaylistStore {
    private const val FILE = "playlists.json"
    val items = mutableListOf<Playlist>()

    val active: Playlist? get() = items.firstOrNull { it.isActive } ?: items.firstOrNull()

    fun load() {
        items.clear()
        val txt = JsonFiles.read(FILE) ?: return
        try {
            val arr = JSONArray(txt)
            for (i in 0 until arr.length()) items.add(Playlist.fromJson(arr.getJSONObject(i)))
        } catch (e: Exception) { }
    }

    fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        JsonFiles.write(FILE, arr.toString())
    }

    fun setActive(p: Playlist) {
        items.forEach { it.isActive = it === p }
        save()
    }

    fun add(p: Playlist) {
        if (items.isEmpty()) p.isActive = true
        items.add(p); save()
    }

    fun remove(p: Playlist) {
        val wasActive = p.isActive
        items.remove(p)
        if (wasActive && items.isNotEmpty()) items[0].isActive = true
        save()
    }
}

object ContentStore {
    private const val FILE = "content-cache.json"
    @Volatile var channels: List<LiveChannel> = emptyList()
    @Volatile var movies: List<VodMovie> = emptyList()
    @Volatile var series: List<SerieItem> = emptyList()
    @Volatile var sourceName: String = ""
    @Volatile var updatedUtc: Long = 0

    val hasData: Boolean get() = channels.isNotEmpty() || movies.isNotEmpty() || series.isNotEmpty()

    fun loadCache() {
        val txt = JsonFiles.read(FILE) ?: return
        try {
            val o = JSONObject(txt)
            channels = parseArray(o.optJSONArray("channels")) { LiveChannel.fromJson(it) }
            movies = parseArray(o.optJSONArray("movies")) { VodMovie.fromJson(it) }
            series = parseArray(o.optJSONArray("series")) { SerieItem.fromJson(it) }
            sourceName = o.optString("sourceName")
            updatedUtc = o.optLong("updatedUtc")
        } catch (e: Exception) { }
    }

    fun saveCache() {
        try {
            val o = JSONObject()
            o.put("channels", JSONArray().apply { channels.forEach { put(it.toJson()) } })
            o.put("movies", JSONArray().apply { movies.forEach { put(it.toJson()) } })
            o.put("series", JSONArray().apply { series.forEach { put(it.toJson()) } })
            o.put("sourceName", sourceName)
            o.put("updatedUtc", updatedUtc)
            JsonFiles.write(FILE, o.toString())
        } catch (e: Exception) { }
    }

    fun clear() {
        channels = emptyList(); movies = emptyList(); series = emptyList()
        sourceName = ""; updatedUtc = 0
        JsonFiles.delete(FILE)
    }

    private fun <T> parseArray(arr: JSONArray?, f: (JSONObject) -> T): List<T> {
        if (arr == null) return emptyList()
        val out = ArrayList<T>(arr.length())
        for (i in 0 until arr.length()) try { out.add(f(arr.getJSONObject(i))) } catch (e: Exception) {}
        return out
    }
}

/** Favorites: set of item keys, persisted like Windows mitv-favorites.json. */
object Favorites {
    private const val FILE = "mitv-favorites.json"
    private val keys = mutableSetOf<String>()

    fun load() {
        keys.clear()
        val txt = JsonFiles.read(FILE) ?: return
        try {
            val arr = JSONArray(txt)
            for (i in 0 until arr.length()) keys.add(arr.getString(i))
        } catch (e: Exception) { }
    }

    fun save() {
        val arr = JSONArray(); keys.forEach { arr.put(it) }
        JsonFiles.write(FILE, arr.toString())
    }

    fun isFav(key: String) = keys.contains(key)
    fun toggle(key: String): Boolean {
        val now = if (keys.contains(key)) { keys.remove(key); false } else { keys.add(key); true }
        save(); return now
    }
}

/** Watch history + playback progress (Windows watch-history.json concept).
 *  History = last 15 opened per section; progress powers Continuar viendo. */
object HistoryStore {
    private const val FILE = "watch-history.json"
    private const val MAX_PER_SECTION = 15
    private val map = LinkedHashMap<String, WatchEntry>()

    fun load() {
        map.clear()
        val txt = JsonFiles.read(FILE) ?: return
        try {
            val arr = JSONArray(txt)
            for (i in 0 until arr.length()) {
                val e = WatchEntry.fromJson(arr.getJSONObject(i))
                if (e.key.isNotEmpty()) map[e.key] = e
            }
        } catch (e: Exception) { }
    }

    @Synchronized fun save() {
        val arr = JSONArray(); map.values.forEach { arr.put(it.toJson()) }
        JsonFiles.write(FILE, arr.toString())
    }

    @Synchronized
    fun recordPlay(entry: WatchEntry) {
        val old = map[entry.key]
        val merged = entry.copy(
            plays = (old?.plays ?: 0) + 1,
            positionMs = old?.positionMs ?: 0,
            durationMs = old?.durationMs ?: 0,
            updatedUtc = System.currentTimeMillis()
        )
        map.remove(entry.key); map[entry.key] = merged
        save()
    }

    @Synchronized
    fun updateProgress(key: String, positionMs: Long, durationMs: Long) {
        val e = map[key] ?: return
        e.positionMs = positionMs; e.durationMs = durationMs
        e.updatedUtc = System.currentTimeMillis()
        // do not move position in history ordering for progress ticks
        save()
    }

    fun entry(key: String): WatchEntry? = map[key]

    /** Last [MAX_PER_SECTION] opened in a section, most recent first. */
    fun recent(section: String): List<WatchEntry> =
        map.values.filter { it.section == section }
            .sortedByDescending { it.updatedUtc }.take(MAX_PER_SECTION)

    fun continueWatching(): List<WatchEntry> =
        map.values.filter { (it.kind == "movie" || it.kind == "episode") && it.inProgress }
            .sortedByDescending { it.updatedUtc }

    fun mostPlayedChannels(): List<WatchEntry> =
        map.values.filter { it.kind == "live" && it.plays > 0 }
            .sortedByDescending { it.plays }

    /** Watched = opened and played to >=95% (Windows rule for the ✓ mark). */
    fun isWatched(key: String): Boolean {
        val e = map[key] ?: return false
        return e.durationMs > 0 && e.fraction >= 0.95
    }
}
