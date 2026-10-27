package com.mitv.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Detail screens for movies and series.
 *  Movies: header currently in-memory; Xtream info comes lazily from
 *  PlaylistLoader in Holders. */
object VodDetailRegistry {
    var movie: VodMovie? = null
    var series: SerieItem? = null
}

class VodDetailActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isSeries = false
    private var movieFavBtn: TextView? = null
    private var movieWatchedBtn: TextView? = null
    private var seriesFavBtn: TextView? = null

    companion object {
        fun startMovie(ctx: Context, m: VodMovie) {
            VodDetailRegistry.movie = m; VodDetailRegistry.series = null
            ctx.startActivity(Intent(ctx, VodDetailActivity::class.java).apply { putExtra("series", false) })
        }
        fun startSeries(ctx: Context, s: SerieItem) {
            VodDetailRegistry.series = s; VodDetailRegistry.movie = null
            ctx.startActivity(Intent(ctx, VodDetailActivity::class.java).apply { putExtra("series", true) })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isSeries = intent.getBooleanExtra("series", false)
        if (isSeries) buildSeries() else buildMovie()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { showOptionsMenu(); return true }
        return super.onKeyDown(keyCode, event)
    }

    /** The ☰ key opens a real options menu (before v1.0.8 it did nothing here). */
    private fun showOptionsMenu() {
        if (isSeries) {
            val s = VodDetailRegistry.series ?: return
            Ui.showMenuDialog(this, s.name, listOf(
                (if (Favorites.isFav(s.key)) "☆ Quitar de Favoritos" else "★ Favorito") to {
                    val now = Favorites.toggle(s.key)
                    seriesFavBtn?.text = if (now) "★ Favorito" else "☆ Favorito"
                }
            ))
        } else {
            val m = VodDetailRegistry.movie ?: return
            val entry = HistoryStore.entry(m.key)
            val resume = entry?.inProgress == true
            Ui.showMenuDialog(this, m.name, listOf(
                (if (resume) "⏵ Continuar (${Util.durationText(entry.positionMs)})" else "▶ Reproducir") to
                    { Playback.playMovie(this, m, resume = resume) },
                (if (Favorites.isFav(m.key)) "☆ Quitar de Favoritos" else "★ Favorito") to {
                    val now = Favorites.toggle(m.key)
                    movieFavBtn?.text = if (now) "★ Favorito" else "☆ Favorito"
                },
                (if (HistoryStore.isWatched(m.key)) "✓ Visto (quitar)" else "✓ Marcar como visto") to {
                    toggleWatched(m.key, m)
                    movieWatchedBtn?.text = if (HistoryStore.isWatched(m.key)) "✓ Visto (quitar)" else "✓ Marcar como visto"
                }
            ))
        }
    }

    // ---------------- Movie ----------------

    private fun buildMovie() {
        val m = VodDetailRegistry.movie ?: run { finish(); return }
        val ctx = this
        val root = detailRoot()
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(poster(m.logo, 190, 265))
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(ctx, 22) }
        }
        info.addView(Ui.text(ctx, m.name, 30f, R.color.text, true))
        val meta = listOf(m.year, m.category, if (m.rating.isNotEmpty()) "★ ${m.rating}" else "", m.ext.uppercase())
            .filter { it.isNotEmpty() }.joinToString(" · ")
        val metaTv = Ui.text(ctx, meta, 16f, R.color.text_dim)
        metaTv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8) }
        info.addView(metaTv)
        val plotTv = Ui.text(ctx, "", 16f, R.color.text_dim)
        plotTv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 12) }
        info.addView(plotTv)
        val extraTv = Ui.text(ctx, "", 14f, R.color.text_faint)
        extraTv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8) }
        info.addView(extraTv)
        header.addView(info)
        root.addView(header)

        val entry = HistoryStore.entry(m.key)
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 22) }
        }
        if (entry?.inProgress == true) {
            buttons.addView(actionBtn("⏵ Continuar (${Util.durationText(entry.positionMs)})") {
                Playback.playMovie(ctx, m, resume = true)
            })
            buttons.addView(actionBtn("↺ Desde el inicio") { Playback.playMovie(ctx, m, resume = false) })
        } else {
            buttons.addView(actionBtn("▶ Reproducir") { Playback.playMovie(ctx, m, resume = false) })
        }
        val favBtn = actionBtn(if (Favorites.isFav(m.key)) "★ Favorito" else "☆ Favorito") {
            val now = Favorites.toggle(m.key)
            (it as TextView).text = if (now) "★ Favorito" else "☆ Favorito"
        }
        buttons.addView(favBtn)
        movieFavBtn = favBtn
        val watched = HistoryStore.isWatched(m.key)
        val watchedBtn = actionBtn(if (watched) "✓ Visto (quitar)" else "✓ Marcar como visto") {
            toggleWatched(m.key, m)
            (it as TextView).text = if (HistoryStore.isWatched(m.key)) "✓ Visto (quitar)" else "✓ Marcar como visto"
        }
        buttons.addView(watchedBtn)
        movieWatchedBtn = watchedBtn
        root.addView(buttons)
        setContentView(ScrollView(ctx).apply { addView(root) })
        (buttons.getChildAt(0) as View).requestFocus()

        if (m.streamId.isNotEmpty()) {
            plotTv.text = "Cargando información…"
            scope.launch {
                val vi = withContext(Dispatchers.IO) { PlaylistLoader.getVodInfo(m.streamId) }
                plotTv.text = vi.plot
                val parts = ArrayList<String>()
                if (vi.duration.isNotEmpty()) parts.add("Duración: ${vi.duration}")
                if (vi.genre.isNotEmpty()) parts.add("Género: ${vi.genre}")
                if (vi.director.isNotEmpty()) parts.add("Director: ${vi.director}")
                if (vi.cast.isNotEmpty()) parts.add("Reparto: ${vi.cast}")
                if (vi.releaseDate.isNotEmpty()) parts.add("Fecha: ${vi.releaseDate}")
                if (vi.country.isNotEmpty()) parts.add("País: ${vi.country}")
                extraTv.text = parts.joinToString("\n")
            }
        }
    }

    private fun toggleWatched(key: String, m: VodMovie) {
        if (HistoryStore.isWatched(key)) {
            HistoryStore.updateProgress(key, 0, HistoryStore.entry(key)?.durationMs ?: 0)
        } else {
            if (HistoryStore.entry(key) == null) {
                HistoryStore.recordPlay(WatchEntry(key = key, title = m.name, sub = m.category,
                    logo = m.logo, kind = "movie", section = "movies", category = m.category, streamUrl = m.streamUrl))
            }
            HistoryStore.updateProgress(key, 1000, 1000)
        }
    }

    // ---------------- Series ----------------

    private fun buildSeries() {
        val s = VodDetailRegistry.series ?: run { finish(); return }
        val ctx = this
        val root = detailRoot()
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(poster(s.cover, 190, 265))
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(ctx, 22) }
        }
        info.addView(Ui.text(ctx, s.name, 30f, R.color.text, true))
        val metaTv = Ui.text(ctx, s.category, 16f, R.color.text_dim)
        metaTv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8) }
        info.addView(metaTv)
        val plotTv = Ui.text(ctx, "", 16f, R.color.text_dim)
        plotTv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 12) }
        info.addView(plotTv)
        val favBtn = actionBtn(if (Favorites.isFav(s.key)) "★ Favorito" else "☆ Favorito") {
            val now = Favorites.toggle(s.key)
            (it as TextView).text = if (now) "★ Favorito" else "☆ Favorito"
        }
        favBtn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 16) }
        info.addView(favBtn)
        seriesFavBtn = favBtn
        header.addView(info)
        root.addView(header)

        if (s.serieId.isEmpty()) {
            // M3U series: direct URL, no episode list available.
            val playBtn = actionBtn("▶ Reproducir") { Playback.playSeriesUrl(ctx, s) }
            playBtn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 22) }
            root.addView(playBtn)
            setContentView(ScrollView(ctx).apply { addView(root) })
            playBtn.requestFocus()
            return
        }

        val status = Ui.text(ctx, "Cargando episodios…", 16f, R.color.text_dim)
        status.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 20) }
        root.addView(status)
        val epList = RecyclerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 520)).apply { topMargin = Ui.dp(ctx, 12) }
            layoutManager = LinearLayoutManager(ctx)
        }
        root.addView(epList)
        setContentView(ScrollView(ctx).apply { addView(root) })
        favBtn.requestFocus()

        scope.launch {
            val (info2, episodes) = withContext(Dispatchers.IO) { PlaylistLoader.getSeriesInfo(s.serieId) }
            plotTv.text = info2.plot
            val parts = ArrayList<String>()
            if (info2.genre.isNotEmpty()) parts.add(info2.genre)
            if (info2.rating.isNotEmpty()) parts.add("★ ${info2.rating}")
            if (info2.director.isNotEmpty()) parts.add("Director: ${info2.director}")
            if (info2.cast.isNotEmpty()) parts.add("Reparto: ${info2.cast}")
            if (parts.isNotEmpty()) metaTv.text = listOf(s.category, parts.joinToString(" · ")).filter { it.isNotEmpty() }.joinToString(" · ")
            status.text = if (episodes.isEmpty()) "Sin episodios disponibles." else "${episodes.size} episodios"
            epList.adapter = EpisodeAdapter(s, episodes)
        }
    }

    inner class EpisodeAdapter(private val series: SerieItem, private val items: List<Episode>) :
        RecyclerView.Adapter<EpisodeAdapter.Holder>() {
        inner class Holder(val tv: TextView) : RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val tv = TextView(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = Ui.dp(ctx, 8)
                }
                setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 12))
                textSize = 16f
                setTextColor(ctx.getColor(R.color.text))
                background = ctx.getDrawable(R.drawable.focus_bg)
                isFocusable = true; isClickable = true
            }
            return Holder(tv)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: Holder, pos: Int) {
            val ep = items[pos]
            val e = HistoryStore.entry(ep.key)
            val status = when {
                e != null && e.durationMs > 0 && e.fraction >= 0.95 -> "✓ Visto"
                e?.inProgress == true -> "⏵ ${Util.durationText(e.positionMs)} / ${Util.durationText(e.durationMs)}"
                else -> ""
            }
            val sNum = ep.season.toIntOrNull()?.let { "T$it" } ?: "T${ep.season}"
            val eNum = ep.num.toIntOrNull()?.let { "E$it" } ?: "E${ep.num}"
            h.tv.text = buildString {
                append("$sNum $eNum · ${ep.title.ifEmpty { "Episodio ${ep.num}" }}")
                if (ep.duration.isNotEmpty()) append("   ·   ${ep.duration}")
                if (status.isNotEmpty()) append("   ·   $status")
            }
            h.tv.setOnClickListener {
                Playback.playEpisode(this@VodDetailActivity, series, ep, resume = e?.inProgress == true)
            }
        }
    }

    // ---------------- Shared ----------------

    private fun detailRoot(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(this@VodDetailActivity, 40), Ui.dp(this@VodDetailActivity, 26),
            Ui.dp(this@VodDetailActivity, 40), Ui.dp(this@VodDetailActivity, 26))
        setBackgroundColor(getColor(R.color.bg))
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun poster(url: String, wDp: Int, hDp: Int): ImageView {
        val iv = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(this@VodDetailActivity, wDp), Ui.dp(this@VodDetailActivity, hDp))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = getDrawable(R.drawable.card_bg)
            clipToOutline = true
        }
        if (url.isNotBlank()) Glide.with(this).load(url).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).centerCrop().into(iv)
        return iv
    }

    private fun actionBtn(label: String, onClick: (View) -> Unit): TextView {
        val tv = TextView(this).apply {
            text = label
            textSize = 17f
            setTextColor(getColor(R.color.text))
            setPadding(Ui.dp(this@VodDetailActivity, 20), Ui.dp(this@VodDetailActivity, 11),
                Ui.dp(this@VodDetailActivity, 20), Ui.dp(this@VodDetailActivity, 11))
            background = getDrawable(R.drawable.btn_bg)
            isFocusable = true; isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = Ui.dp(this@VodDetailActivity, 12)
            }
            setOnClickListener { onClick(this) }
        }
        return tv
    }
}
