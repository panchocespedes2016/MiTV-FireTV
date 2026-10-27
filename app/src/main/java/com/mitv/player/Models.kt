package com.mitv.player

import org.json.JSONObject

/** Data models — concepts mirror the Windows MiTV (ContentStore.cs). */

data class Playlist(
    var kind: String = "m3u",          // "m3u" | "xtream"
    var name: String = "",
    var url: String = "",              // M3U
    var epgUrl: String = "",           // M3U (optional)
    var server: String = "",           // Xtream
    var username: String = "",
    var password: String = "",
    var isActive: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind); put("name", name); put("url", url); put("epgUrl", epgUrl)
        put("server", server); put("username", username); put("password", password)
        put("isActive", isActive)
    }
    companion object {
        fun fromJson(o: JSONObject) = Playlist(
            o.optString("kind", "m3u"), o.optString("name"), o.optString("url"),
            o.optString("epgUrl"), o.optString("server"), o.optString("username"),
            o.optString("password"), o.optBoolean("isActive")
        )
    }
}

data class LiveChannel(
    val name: String = "",
    val logo: String = "",
    val streamUrl: String = "",
    val group: String = "",
    val tvgId: String = "",
    val providerNum: String = "",
    val hasCatchup: Boolean = false,
    val streamId: String = "",
    val archiveDays: Int = 0
) {
    /** Stable key used for favorites/history (name+url, like Windows uses stream URL + title). */
    val key: String get() = "live|$streamUrl|$name"

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("logo", logo); put("streamUrl", streamUrl); put("group", group)
        put("tvgId", tvgId); put("providerNum", providerNum); put("hasCatchup", hasCatchup)
        put("streamId", streamId); put("archiveDays", archiveDays)
    }
    companion object {
        fun fromJson(o: JSONObject) = LiveChannel(
            o.optString("name"), o.optString("logo"), o.optString("streamUrl"), o.optString("group"),
            o.optString("tvgId"), o.optString("providerNum"), o.optBoolean("hasCatchup"),
            o.optString("streamId"), o.optInt("archiveDays")
        )
    }
}

data class VodMovie(
    val name: String = "",
    val logo: String = "",
    val streamUrl: String = "",
    val category: String = "",
    val added: Long = 0,
    val streamId: String = "",
    val year: String = "",
    val rating: String = "",
    val ext: String = "mp4"
) {
    val key: String get() = "movie|$streamUrl|$name"
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("logo", logo); put("streamUrl", streamUrl); put("category", category)
        put("added", added); put("streamId", streamId); put("year", year); put("rating", rating); put("ext", ext)
    }
    companion object {
        fun fromJson(o: JSONObject) = VodMovie(
            o.optString("name"), o.optString("logo"), o.optString("streamUrl"), o.optString("category"),
            o.optLong("added"), o.optString("streamId"), o.optString("year"), o.optString("rating"),
            o.optString("ext", "mp4")
        )
    }
}

data class SerieItem(
    val name: String = "",
    val cover: String = "",
    val serieId: String = "",
    val category: String = "",
    val streamUrl: String = "",   // M3U direct episode URL; Xtream uses serieId
    val added: Long = 0
) {
    val key: String get() = "series|${serieId.ifEmpty { streamUrl }}|$name"
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("cover", cover); put("serieId", serieId); put("category", category)
        put("streamUrl", streamUrl); put("added", added)
    }
    companion object {
        fun fromJson(o: JSONObject) = SerieItem(
            o.optString("name"), o.optString("cover"), o.optString("serieId"), o.optString("category"),
            o.optString("streamUrl"), o.optLong("added")
        )
    }
}

data class Episode(
    val season: String = "",
    val num: String = "",
    val title: String = "",
    val url: String = "",
    val plot: String = "",
    val duration: String = "",
    val image: String = "",
    val id: String = ""
) {
    val key: String get() = "ep|$url|$title"
    fun displayName(seriesName: String): String {
        val n = num.toIntOrNull()
        val ep = if (n != null) "E$n" else "E$num"
        val s = season.toIntOrNull()
        val se = if (s != null) "T$s" else "T$season"
        return "$seriesName $se$ep"
    }
}

/** One entry in watch history / progress (mirrors WatchHistory.cs WatchEntry). */
data class WatchEntry(
    val key: String = "",
    val title: String = "",
    val sub: String = "",
    val logo: String = "",
    val kind: String = "",        // "live" | "movie" | "series" | "episode"
    val section: String = "",     // "tv" | "movies" | "series" | "sports"
    val category: String = "",
    val streamUrl: String = "",
    var positionMs: Long = 0,
    var durationMs: Long = 0,
    var plays: Int = 0,
    var updatedUtc: Long = 0
) {
    val fraction: Double get() = if (durationMs > 0) positionMs.toDouble() / durationMs else 0.0
    val inProgress: Boolean get() = durationMs > 0 && fraction in 0.03..0.95

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key); put("title", title); put("sub", sub); put("logo", logo); put("kind", kind)
        put("section", section); put("category", category); put("streamUrl", streamUrl)
        put("positionMs", positionMs); put("durationMs", durationMs); put("plays", plays)
        put("updatedUtc", updatedUtc)
    }
    companion object {
        fun fromJson(o: JSONObject) = WatchEntry(
            o.optString("key"), o.optString("title"), o.optString("sub"), o.optString("logo"),
            o.optString("kind"), o.optString("section"), o.optString("category"), o.optString("streamUrl"),
            o.optLong("positionMs"), o.optLong("durationMs"), o.optInt("plays"), o.optLong("updatedUtc")
        )
    }
}

data class EpgProgramme(
    val channelId: String = "",
    val startUtc: Long = 0,
    val stopUtc: Long = 0,
    val title: String = "",
    val desc: String = ""
)
