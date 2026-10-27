package com.mitv.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

/** Home per the approved mockup: Continuar viendo, Canales destacados,
 *  Películas populares, Series populares. */
class HomeFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)

        if (!ContentStore.hasData) {
            root.addView(Ui.emptyText(ctx,
                if (PlaylistStore.active == null)
                    "Todavía no hay ninguna lista.\nVe a Ajustes → Listas IPTV para agregar la primera."
                else "Sincronizando contenido de «${PlaylistStore.active?.name}»…\nSi tarda, revisa la lista en Ajustes."))
            return scroll
        }

        var firstFocusable: View? = null

        // ---- Continuar viendo ----
        val cont = HistoryStore.continueWatching()
        if (cont.isNotEmpty()) {
            root.addSection(Ui.sectionTitle(ctx, "Continuar viendo", "— lo retomas donde lo dejaste"))
            val row = hRow(ctx)
            for (e in cont) {
                val card = mediaCard(ctx, e.logo, e.title, e.sub, e.fraction) {
                    resumeEntry(e)
                }
                if (firstFocusable == null) firstFocusable = card
                row.addView(card)
            }
            root.addView(hScroll(ctx, row))
        }

        // ---- Canales destacados ----
        val top = HistoryStore.mostPlayedChannels()
        val topChannels = if (top.isNotEmpty()) top.mapNotNull { e ->
            ContentStore.channels.firstOrNull { it.key == e.key }
        } else emptyList()
        if (topChannels.isNotEmpty()) {
            root.addSection(Ui.sectionTitle(ctx, "Canales destacados", "— los que más ves"))
            val row = hRow(ctx)
            for (ch in topChannels.take(10)) {
                val card = channelCard(ctx, ch)
                if (firstFocusable == null) firstFocusable = card
                row.addView(card)
            }
            root.addView(hScroll(ctx, row))
        }

        // ---- Películas populares (most recent by added) ----
        val movies = ContentStore.movies.sortedByDescending { it.added }.take(12)
        if (movies.isNotEmpty()) {
            root.addSection(Ui.sectionTitle(ctx, "Películas populares", "— lo más reciente de tu lista"))
            val row = hRow(ctx)
            for (m in movies) {
                val card = posterCard(ctx, m.logo, m.name, listOf(m.year, m.category).filter { it.isNotEmpty() }.joinToString(" · ")) {
                    (activity as? MainActivity)?.let { VodDetailActivity.startMovie(it, m) }
                }
                if (firstFocusable == null) firstFocusable = card
                row.addView(card)
            }
            root.addView(hScroll(ctx, row))
        }

        // ---- Series (most recent) ----
        val series = ContentStore.series.sortedByDescending { it.added }.take(12)
        if (series.isNotEmpty()) {
            root.addSection(Ui.sectionTitle(ctx, "Series", "— lo más reciente de tu lista"))
            val row = hRow(ctx)
            for (s in series) {
                val card = posterCard(ctx, s.cover, s.name, s.category) {
                    (activity as? MainActivity)?.let { VodDetailActivity.startSeries(it, s) }
                }
                if (firstFocusable == null) firstFocusable = card
                row.addView(card)
            }
            root.addView(hScroll(ctx, row))
        }

        if (firstFocusable == null) {
            root.addView(Ui.emptyText(ctx, "Sin contenido todavía. Sincroniza la lista desde Ajustes."))
        }
        return scroll
    }

    private fun LinearLayout.addSection(v: View) {
        (v.layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(requireContext(), 16)
        addView(v)
    }

    private fun hScroll(ctx: android.content.Context, row: LinearLayout): android.widget.HorizontalScrollView =
        android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(row)
        }

    private fun hRow(ctx: android.content.Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun resumeEntry(e: WatchEntry) {
        when (e.kind) {
            "movie" -> ContentStore.movies.firstOrNull { it.key == e.key }?.let { Playback.playMovie(requireContext(), it, resume = true) }
            "episode" -> {
                // Find the episode by its stream URL among Xtream series is not
                // cached; use the stored URL directly.
                startActivity(android.content.Intent(requireContext(), PlayerActivity::class.java).apply {
                    putExtra("url", e.streamUrl); putExtra("title", e.title); putExtra("sub", e.sub)
                    putExtra("key", e.key); putExtra("live", false); putExtra("startMs", e.positionMs)
                })
            }
            "live" -> ContentStore.channels.firstOrNull { it.key == e.key }?.let { Playback.playChannel(requireContext(), it) }
        }
    }

    private fun mediaCard(ctx: android.content.Context, logo: String, title: String, sub: String,
                          fraction: Double, onClick: () -> Unit): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 250), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = Ui.dp(ctx, 18)
            }
        }
        val iv = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 140))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ctx.getDrawable(R.drawable.card_bg)
            clipToOutline = true
            isFocusable = true; isClickable = true
            foreground = ctx.getDrawable(R.drawable.card_fg)
            setOnClickListener { onClick() }
        }
        if (logo.isNotBlank()) Glide.with(ctx).load(logo).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).centerCrop().into(iv)
        col.addView(iv)
        val t = Ui.text(ctx, title, 15f, R.color.text, true)
        t.maxLines = 1; t.ellipsize = android.text.TextUtils.TruncateAt.END
        t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 7) }
        col.addView(t)
        val s = Ui.text(ctx, sub, 13f, R.color.text_faint)
        s.maxLines = 1; s.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(s)
        if (fraction > 0) {
            val bar = Ui.progressBar(ctx, fraction, R.color.accent2, 4)
            (bar.layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(ctx, 6)
            col.addView(bar)
        }
        col.tag = iv
        return col
    }

    private fun posterCard(ctx: android.content.Context, logo: String, title: String, sub: String,
                           onClick: () -> Unit): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 150), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = Ui.dp(ctx, 16)
            }
        }
        val iv = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 210))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ctx.getDrawable(R.drawable.card_bg)
            clipToOutline = true
            isFocusable = true; isClickable = true
            foreground = ctx.getDrawable(R.drawable.card_fg)
            setOnClickListener { onClick() }
        }
        if (logo.isNotBlank()) Glide.with(ctx).load(logo).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).centerCrop().into(iv)
        col.addView(iv)
        val t = Ui.text(ctx, title, 14f, R.color.text, true)
        t.maxLines = 1; t.ellipsize = android.text.TextUtils.TruncateAt.END
        t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 7) }
        col.addView(t)
        val s = Ui.text(ctx, sub, 12f, R.color.text_faint)
        s.maxLines = 1; s.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(s)
        return col
    }

    private fun channelCard(ctx: android.content.Context, ch: LiveChannel): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 170), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = Ui.dp(ctx, 16)
            }
        }
        val tile = Ui.logoTile(ctx, ch.logo, ch.name, 170, 96).apply {
            isFocusable = true; isClickable = true
            foreground = ctx.getDrawable(R.drawable.card_fg)
            setOnClickListener { Playback.playChannel(ctx, ch) }
        }
        col.addView(tile)
        val t = Ui.text(ctx, ch.name, 14f, R.color.text, true)
        t.gravity = android.view.Gravity.CENTER
        t.maxLines = 1; t.ellipsize = android.text.TextUtils.TruncateAt.END
        t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 7) }
        col.addView(t)
        return col
    }
}
