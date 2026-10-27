package com.mitv.player

import org.json.JSONArray
import org.json.JSONObject

/** Loads the active playlist into ContentStore, following the Windows
 *  PlaylistLoader.cs logic (Xtream player_api.php + M3U #EXTINF parsing). */
object PlaylistLoader {

    data class Result(val ok: Boolean, val message: String)

    fun refresh(includeEpg: Boolean = true): Result {
        val pl = PlaylistStore.active ?: return Result(false, "No hay lista activa")
        return try {
            if (pl.kind == "xtream") loadXtream(pl) else loadM3u(pl)
            ContentStore.sourceName = pl.name
            ContentStore.updatedUtc = System.currentTimeMillis()
            ContentStore.saveCache()
            if (includeEpg) {
                try { EpgStore.refresh(pl) } catch (e: Exception) { /* EPG is best-effort */ }
            }
            Result(true, "${ContentStore.channels.size} canales, ${ContentStore.movies.size} películas, ${ContentStore.series.size} series")
        } catch (e: Exception) {
            Result(false, "Error: ${e.message}")
        }
    }

    // ---------- Xtream Codes ----------

    private fun loadXtream(pl: Playlist) {
        val srv = pl.server.trim().trimEnd('/')
        val u = Net.esc(pl.username); val p = Net.esc(pl.password)
        val auth = "username=$u&password=$p"
        val api = "$srv/player_api.php?$auth"

        val liveCats = categories("$api&action=get_live_categories")
        val vodCats = categories("$api&action=get_vod_categories")
        val seriesCats = categories("$api&action=get_series_categories")

        val channels = ArrayList<LiveChannel>()
        val liveArr = Net.getJsonArray("$api&action=get_live_streams")
        for (i in 0 until liveArr.length()) {
            val el = liveArr.optJSONObject(i) ?: continue
            val id = el.str("stream_id"); if (id.isEmpty()) continue
            val name = el.str("name")
            if (isJunk(name)) continue
            channels.add(LiveChannel(
                name = name,
                logo = el.str("stream_icon"),
                streamUrl = "$srv/live/$u/$p/$id.m3u8",
                group = liveCats[el.str("category_id")] ?: "",
                tvgId = el.str("epg_channel_id"),
                providerNum = el.str("num"),
                hasCatchup = el.str("tv_archive") == "1",
                streamId = id,
                archiveDays = (el.lng("tv_archive_duration") / 24).toInt()
            ))
        }

        val movies = ArrayList<VodMovie>()
        val vodArr = Net.getJsonArray("$api&action=get_vod_streams")
        for (i in 0 until vodArr.length()) {
            val el = vodArr.optJSONObject(i) ?: continue
            val id = el.str("stream_id"); if (id.isEmpty()) continue
            var ext = el.str("container_extension"); if (ext.isEmpty()) ext = "mp4"
            val name = el.str("name")
            val added = el.lng("added")
            movies.add(VodMovie(
                name = name,
                logo = el.str("stream_icon"),
                streamUrl = "$srv/movie/$u/$p/$id.$ext",
                category = vodCats[el.str("category_id")] ?: "",
                added = added,
                streamId = id,
                year = extractYear(name, added),
                rating = el.str("rating"),
                ext = ext
            ))
        }

        val series = ArrayList<SerieItem>()
        val serArr = Net.getJsonArray("$api&action=get_series")
        for (i in 0 until serArr.length()) {
            val el = serArr.optJSONObject(i) ?: continue
            val id = el.str("series_id"); if (id.isEmpty()) continue
            series.add(SerieItem(
                name = el.str("name"),
                cover = el.str("cover"),
                serieId = id,
                category = seriesCats[el.str("category_id")] ?: "",
                added = el.lng("added")
            ))
        }

        ContentStore.channels = channels
        ContentStore.movies = movies
        ContentStore.series = series
    }

    private fun categories(url: String): Map<String, String> {
        val map = HashMap<String, String>()
        try {
            val arr = Net.getJsonArray(url)
            for (i in 0 until arr.length()) {
                val el = arr.optJSONObject(i) ?: continue
                val id = el.str("category_id"); val name = el.str("category_name")
                if (id.isNotEmpty() && !map.containsKey(id)) map[id] = name
            }
        } catch (e: Exception) { }
        return map
    }

    private val yearRegex = Regex("""\b(19\d{2}|20[0-2]\d|2030)\b""")
    private fun extractYear(name: String, added: Long): String {
        yearRegex.find(name)?.let { return it.value }
        if (added > 0) {
            return try {
                java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                    .apply { timeInMillis = added * 1000 }.get(java.util.Calendar.YEAR).toString()
            } catch (e: Exception) { "" }
        }
        return ""
    }

    /** Junk separator channels from providers, e.g. "##### PRIME #####" (Windows rule). */
    private fun isJunk(name: String) = name.contains("###")

    // ---------- Xtream detail info (lazy) ----------

    data class VodInfo(val plot: String = "", val cast: String = "", val rating: String = "",
                       val genre: String = "", val duration: String = "", val director: String = "",
                       val releaseDate: String = "", val country: String = "")

    fun getVodInfo(streamId: String): VodInfo {
        val pl = PlaylistStore.active
        if (pl == null || pl.kind != "xtream" || streamId.isEmpty()) return VodInfo()
        return try {
            val srv = pl.server.trim().trimEnd('/')
            val url = "$srv/player_api.php?username=${Net.esc(pl.username)}&password=${Net.esc(pl.password)}&action=get_vod_info&vod_id=${Net.esc(streamId)}"
            val root = Net.getJsonObject(url)
            val inf = root.optJSONObject("info") ?: return VodInfo()
            var dur = inf.str("duration"); if (dur.isEmpty()) dur = inf.str("episode_run_time")
            var rel = inf.str("releaseDate"); if (rel.isEmpty()) rel = inf.str("releasedate")
            VodInfo(inf.str("plot"), inf.str("cast"), inf.str("rating"), inf.str("genre"),
                dur, inf.str("director"), rel, inf.str("country"))
        } catch (e: Exception) { VodInfo() }
    }

    data class SeriesInfo(val plot: String = "", val cast: String = "", val rating: String = "",
                          val genre: String = "", val releaseDate: String = "", val director: String = "")

    fun getSeriesInfo(seriesId: String): Pair<SeriesInfo, List<Episode>> {
        val pl = PlaylistStore.active
        if (pl == null || pl.kind != "xtream" || seriesId.isEmpty()) return SeriesInfo() to emptyList()
        val srv = pl.server.trim().trimEnd('/')
        val u = Net.esc(pl.username); val p = Net.esc(pl.password)
        return try {
            val url = "$srv/player_api.php?username=$u&password=$p&action=get_series_info&series_id=${Net.esc(seriesId)}"
            val root = Net.getJsonObject(url)
            val inf = root.optJSONObject("info")
            var rating = inf?.str("rating") ?: ""
            if (rating.isEmpty()) rating = inf?.str("rating_5based") ?: ""
            val info = SeriesInfo(
                inf?.str("plot") ?: "", inf?.str("cast") ?: "", rating,
                inf?.str("genre") ?: "", inf?.str("releaseDate") ?: "", inf?.str("director") ?: ""
            )
            val eps = ArrayList<Episode>()
            val epsObj = root.optJSONObject("episodes")
            if (epsObj != null) {
                val seasonKeys = ArrayList<String>()
                val it = epsObj.keys(); while (it.hasNext()) seasonKeys.add(it.next())
                seasonKeys.sortBy { k -> k.toIntOrNull() ?: Int.MAX_VALUE }
                for (season in seasonKeys) {
                    val arr = epsObj.optJSONArray(season) ?: continue
                    for (i in 0 until arr.length()) {
                        val el = arr.optJSONObject(i) ?: continue
                        val id = el.str("id"); if (id.isEmpty()) continue
                        var ext = el.str("container_extension"); if (ext.isEmpty()) ext = "mp4"
                        val einf = el.optJSONObject("info")
                        eps.add(Episode(
                            season = season,
                            num = el.str("episode_num"),
                            title = el.str("title"),
                            url = "$srv/series/$u/$p/$id.$ext",
                            plot = einf?.str("plot") ?: "",
                            duration = einf?.str("duration") ?: "",
                            image = einf?.str("movie_image") ?: "",
                            id = id
                        ))
                    }
                }
            }
            info to eps
        } catch (e: Exception) { SeriesInfo() to emptyList() }
    }

    // ---------- M3U ----------

    private fun loadM3u(pl: Playlist) {
        val text = Net.getText(pl.url)
        val channels = ArrayList<LiveChannel>()
        val movies = ArrayList<VodMovie>()
        val series = ArrayList<SerieItem>()

        var name: String? = null; var logo = ""; var group = ""; var tvgId = ""
        for (raw in text.split('\n')) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    name = extInfName(line)
                    logo = attr(line, "tvg-logo"); group = attr(line, "group-title"); tvgId = attr(line, "tvg-id")
                }
                name != null && line.isNotEmpty() && !line.startsWith("#") -> {
                    val url = line
                    val g = group.lowercase()
                    when {
                        g.contains("series") ->
                            series.add(SerieItem(name = name, cover = logo, category = group, streamUrl = url))
                        g.contains("movie") || g.contains("film") || g.contains("vod") ||
                            g.contains("pelicula") || g.contains("película") || g.contains("cine") ||
                            url.endsWith(".mp4", true) || url.endsWith(".mkv", true) ->
                            movies.add(VodMovie(name = name, logo = logo, streamUrl = url, category = group))
                        !isJunk(name) ->
                            channels.add(LiveChannel(name = name, logo = logo, streamUrl = url, group = group, tvgId = tvgId))
                    }
                    name = null; logo = ""; group = ""; tvgId = ""
                }
            }
        }
        ContentStore.channels = channels
        ContentStore.movies = movies
        ContentStore.series = series
    }

    /** Name after the first comma outside quotes (Windows ExtInfName). */
    private fun extInfName(line: String): String {
        var inQ = false
        for (i in 8 until line.length) {
            val c = line[i]
            if (c == '"') inQ = !inQ
            else if (c == ',' && !inQ) return line.substring(i + 1).trim()
        }
        return ""
    }

    private val attrCache = HashMap<String, Regex>()
    private fun attr(extinf: String, key: String): String {
        val re = attrCache.getOrPut(key) { Regex("""$key="([^"]*)"""") }
        return re.find(extinf)?.groupValues?.get(1) ?: ""
    }
}
