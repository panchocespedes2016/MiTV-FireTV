package com.mitv.player

import android.content.Context
import android.content.Intent

/** Central place that records a play in history and opens the player. */
object Playback {

    fun playChannel(context: Context, ch: LiveChannel, section: String = "tv") {
        HistoryStore.recordPlay(WatchEntry(
            key = ch.key, title = ch.name, sub = ch.group, logo = ch.logo,
            kind = "live", section = section, category = ch.group, streamUrl = ch.streamUrl
        ))
        val (now, _) = EpgStore.nowNext(ch)
        context.startActivity(Intent(context, PlayerActivity::class.java).apply {
            putExtra("url", ch.streamUrl)
            putExtra("title", ch.name)
            putExtra("sub", now?.title ?: ch.group)
            putExtra("key", ch.key)
            putExtra("live", true)
        })
    }

    fun playMovie(context: Context, m: VodMovie, resume: Boolean) {
        val existing = HistoryStore.entry(m.key)
        HistoryStore.recordPlay(WatchEntry(
            key = m.key, title = m.name, sub = m.category, logo = m.logo,
            kind = "movie", section = "movies", category = m.category, streamUrl = m.streamUrl
        ))
        context.startActivity(Intent(context, PlayerActivity::class.java).apply {
            putExtra("url", m.streamUrl)
            putExtra("title", m.name)
            putExtra("sub", listOf(m.year, m.category).filter { it.isNotEmpty() }.joinToString(" · "))
            putExtra("key", m.key)
            putExtra("live", false)
            putExtra("startMs", if (resume) (existing?.positionMs ?: 0) else 0L)
        })
    }

    fun playEpisode(context: Context, series: SerieItem, ep: Episode, resume: Boolean) {
        val key = ep.key
        val existing = HistoryStore.entry(key)
        HistoryStore.recordPlay(WatchEntry(
            key = key, title = ep.displayName(series.name), sub = series.name, logo = ep.image.ifEmpty { series.cover },
            kind = "episode", section = "series", category = series.category, streamUrl = ep.url
        ))
        context.startActivity(Intent(context, PlayerActivity::class.java).apply {
            putExtra("url", ep.url)
            putExtra("title", ep.displayName(series.name))
            putExtra("sub", ep.title)
            putExtra("key", key)
            putExtra("live", false)
            putExtra("startMs", if (resume) (existing?.positionMs ?: 0) else 0L)
        })
    }

    /** For M3U series entries, which are direct episode URLs. */
    fun playSeriesUrl(context: Context, s: SerieItem) {
        HistoryStore.recordPlay(WatchEntry(
            key = s.key, title = s.name, sub = s.category, logo = s.cover,
            kind = "episode", section = "series", category = s.category, streamUrl = s.streamUrl
        ))
        context.startActivity(Intent(context, PlayerActivity::class.java).apply {
            putExtra("url", s.streamUrl)
            putExtra("title", s.name)
            putExtra("sub", s.category)
            putExtra("key", s.key)
            putExtra("live", false)
            putExtra("startMs", HistoryStore.entry(s.key)?.positionMs ?: 0)
        })
    }
}
