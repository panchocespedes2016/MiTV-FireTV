package com.mitv.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

/** TV en vivo (and Deportes with sportsOnly=true), per the approved mockup.
 *  Left: categories with counts (Historial, Favoritos, Todo, groups).
 *  Center: channel rows with EPG now + progress. Right: muted preview after
 *  a short focus dwell, now/next details. OK = fullscreen, Menu = favorite. */
@OptIn(UnstableApi::class)
class LiveFragment : Fragment() {

    private var sportsOnly = false

    private lateinit var catList: RecyclerView
    private lateinit var chanList: RecyclerView
    private lateinit var previewBox: LinearLayout
    private lateinit var previewTitle: TextView
    private lateinit var previewMeta: TextView
    private lateinit var previewDesc: TextView
    private lateinit var previewNext: TextView
    private lateinit var playerView: PlayerView

    private var previewPlayer: ExoPlayer? = null
    private var previewUrl: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingPreview: Runnable? = null

    private var categories: List<CatItem> = emptyList()
    // Empty until the first applyCategory() runs, so the early-return there
    // never skips the initial load.
    private var selectedCat = ""
    private var shownChannels: List<LiveChannel> = emptyList()
    private var focusedChannel: LiveChannel? = null

    data class CatItem(val id: String, val label: String, val count: Int)

    companion object {
        fun newInstance(sportsOnly: Boolean) = LiveFragment().apply {
            arguments = Bundle().apply { putBoolean("sports", sportsOnly) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sportsOnly = arguments?.getBoolean("sports") ?: false
    }

    private fun baseChannels(): List<LiveChannel> =
        if (sportsOnly) ContentStore.channels.filter { Util.isSportCategory(it.group) }
        else ContentStore.channels

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        catList = RecyclerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 215), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginEnd = Ui.dp(ctx, 14)
            }
            layoutManager = LinearLayoutManager(ctx)
            setBackgroundColor(ctx.getColor(R.color.panel))
            setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8))
        }
        root.addView(catList)

        chanList = RecyclerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            layoutManager = LinearLayoutManager(ctx)
        }
        root.addView(chanList)

        // ---- Preview panel ----
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 390), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = Ui.dp(ctx, 14)
            }
            setBackgroundColor(ctx.getColor(R.color.panel))
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 16), Ui.dp(ctx, 16), Ui.dp(ctx, 16))
        }
        playerView = PlayerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 201))
            useController = false
            setBackgroundColor(0xFF000000.toInt())
        }
        panel.addView(playerView)
        previewBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        previewTitle = Ui.text(ctx, "", 20f, R.color.text, true)
        previewMeta = Ui.text(ctx, "", 14f, R.color.text_dim)
        previewDesc = Ui.text(ctx, "", 14f, R.color.text_dim)
        previewDesc.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8) }
        previewNext = Ui.text(ctx, "", 14f, R.color.text)
        previewNext.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 12) }
        previewBox.addView(previewTitle)
        previewBox.addView(previewMeta)
        previewBox.addView(previewDesc)
        previewBox.addView(previewNext)
        panel.addView(previewBox)
        root.addView(panel)

        buildCategories()
        catList.adapter = CatAdapter()
        applyCategory(selectedCat)

        if (!ContentStore.hasData) {
            previewTitle.text = "Sin contenido"
            previewDesc.text = if (PlaylistStore.active == null)
                "Agrega una lista en Ajustes → Listas IPTV." else "Sincroniza la lista desde Ajustes."
        }
        return root
    }

    private fun buildCategories() {
        val base = baseChannels()
        val section = if (sportsOnly) "sports" else "tv"
        val histCount = HistoryStore.recent(section).count { e -> base.any { it.key == e.key } } +
            if (sportsOnly) 0 else HistoryStore.recent("sports").size.let { 0 }
        val favCount = base.count { Favorites.isFav(it.key) }
        val list = ArrayList<CatItem>()
        list.add(CatItem("__history__", "🕘 Historial", histCount))
        list.add(CatItem("__favs__", "★ Favoritos", favCount))
        list.add(CatItem("__all__", "Todo", base.size))
        base.groupBy { it.group }.toSortedMap().forEach { (g, items) ->
            list.add(CatItem("g:$g", g.ifEmpty { "Sin categoría" }, items.size))
        }
        categories = list
    }

    // Runs from a category row's focus listener: a full notifyDataSetChanged()
    // here detaches the focused row, the focus is lost, and Fire OS falls
    // back to the first tab (Inicio). Refresh only the previously/newly
    // selected rows so the focused view stays attached.
    private fun applyCategory(id: String) {
        if (id == selectedCat && chanList.adapter != null) return
        val oldId = selectedCat
        selectedCat = id
        val base = baseChannels()
        shownChannels = when {
            id == "__all__" -> base
            id == "__favs__" -> base.filter { Favorites.isFav(it.key) }
            id == "__history__" -> {
                val section = if (sportsOnly) "sports" else "tv"
                val recent = HistoryStore.recent(section).mapNotNull { e -> base.firstOrNull { it.key == e.key } }
                recent
            }
            id.startsWith("g:") -> base.filter { it.group == id.substring(2) }
            else -> base
        }
        chanList.adapter = ChannelAdapter(shownChannels)
        val catAdapter = catList.adapter ?: return
        val oldPos = categories.indexOfFirst { it.id == oldId }
        if (oldPos >= 0) catAdapter.notifyItemChanged(oldPos)
        val newPos = categories.indexOfFirst { it.id == id }
        if (newPos >= 0) catAdapter.notifyItemChanged(newPos)
    }

    // ---------- Categories adapter ----------

    inner class CatAdapter : RecyclerView.Adapter<CatAdapter.Holder>() {
        inner class Holder(val tv: TextView) : RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val tv = TextView(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 11), Ui.dp(ctx, 12), Ui.dp(ctx, 11))
                textSize = 16f
                isFocusable = true; isClickable = true
                background = ctx.getDrawable(R.drawable.card_bg)
            }
            return Holder(tv)
        }
        override fun getItemCount() = categories.size
        override fun onBindViewHolder(h: Holder, pos: Int) {
            val item = categories[pos]
            val ctx = h.tv.context
            h.tv.text = "${item.label}    ${item.count}"
            h.tv.setTextColor(ctx.getColor(
                if (item.id == selectedCat) R.color.text else R.color.text_dim))
            h.tv.setTypeface(h.tv.typeface, if (item.id == selectedCat) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            h.tv.setOnClickListener { applyCategory(item.id) }
            h.tv.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) applyCategory(item.id) }
        }
    }

    // ---------- Channels adapter ----------

    inner class ChannelAdapter(private val items: List<LiveChannel>) : RecyclerView.Adapter<ChannelAdapter.Holder>() {
        inner class Holder(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
            val num: TextView = root.getChildAt(0) as TextView
            val star: TextView = root.getChildAt(1) as TextView
            val logoFrame: ViewGroup = root.getChildAt(2) as ViewGroup
            val info: LinearLayout = root.getChildAt(3) as LinearLayout
            val nameTv: TextView = (info.getChildAt(0) as LinearLayout).getChildAt(0) as TextView
            val progTv: TextView = info.getChildAt(1) as TextView
            val barWrap: ViewGroup = info.getChildAt(2) as ViewGroup
            val timesTv: TextView = info.getChildAt(3) as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = Ui.dp(ctx, 9)
                }
                setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 10), Ui.dp(ctx, 14), Ui.dp(ctx, 10))
                background = ctx.getDrawable(R.drawable.focus_bg)
                isFocusable = true; isClickable = true
            }
            val num = Ui.text(ctx, "", 15f, R.color.text_faint).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 26), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            root.addView(num)
            val star = Ui.text(ctx, "☆", 20f, R.color.text_faint).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 30), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            root.addView(star)
            val logoFrame = android.widget.FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 48), Ui.dp(ctx, 48)).apply { marginEnd = Ui.dp(ctx, 10) }
                background = ctx.getDrawable(R.drawable.logo_tile)
            }
            root.addView(logoFrame)
            val info = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            // The name takes the full width of the info column on ONE line.
            // (Without width/weight here it was squeezed into a ~18dp strip
            // and Android broke it letter by letter, vertically.)
            val nameRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val nameTv = Ui.text(ctx, "", 17f, R.color.text, true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            nameRow.addView(nameTv)
            info.addView(nameRow)
            val progTv = Ui.text(ctx, "", 14f, R.color.text_dim).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 3) }
            }
            info.addView(progTv)
            val barWrap = android.widget.FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 4)).apply { topMargin = Ui.dp(ctx, 6) }
            }
            info.addView(barWrap)
            val timesTv = Ui.text(ctx, "", 12f, R.color.text_faint).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 3) }
            }
            info.addView(timesTv)
            root.addView(info)
            return Holder(root)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: Holder, pos: Int) {
            val ch = items[pos]
            val ctx = h.root.context
            h.num.text = (pos + 1).toString()
            updateStar(h.star, ch)
            // logo
            h.logoFrame.removeAllViews()
            val iv = ImageView(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4))
            }
            h.logoFrame.addView(iv)
            if (ch.logo.isNotBlank()) {
                Glide.with(ctx).load(ch.logo).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).fitCenter().into(iv)
            } else {
                val fb = Ui.text(ctx, ch.name.take(5), 11f, android.R.color.black, true)
                fb.gravity = android.view.Gravity.CENTER
                fb.setTextColor(0xFF222222.toInt())
                fb.layoutParams = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                h.logoFrame.addView(fb)
            }
            h.nameTv.text = ch.name + if (ch.hasCatchup) "  ↺" else ""
            val (now, _) = EpgStore.nowNext(ch)
            if (now != null) {
                h.progTv.text = now.title
                val total = (now.stopUtc - now.startUtc).coerceAtLeast(1)
                val done = (System.currentTimeMillis() - now.startUtc).coerceIn(0, total)
                fillBar(h.barWrap, done.toDouble() / total)
                h.timesTv.text = "${Util.time(now.startUtc)} – ${Util.time(now.stopUtc)}"
            } else {
                h.progTv.text = "Sin información de guía"
                fillBar(h.barWrap, 0.0)
                h.timesTv.text = ""
            }
            h.root.setOnClickListener {
                Playback.playChannel(ctx, ch, if (sportsOnly) "sports" else "tv")
            }
            h.root.setOnKeyListener { _, keyCode, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                    val nowFav = Favorites.toggle(ch.key)
                    updateStar(h.star, ch)
                    buildCategories(); catList.adapter?.notifyDataSetChanged()
                    android.widget.Toast.makeText(ctx,
                        if (nowFav) "★ Agregado a Favoritos" else "Quitado de Favoritos",
                        android.widget.Toast.LENGTH_SHORT).show()
                    true
                } else false
            }
            h.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    focusedChannel = ch
                    schedulePreview(ch)
                }
            }
        }

        private fun updateStar(star: TextView, ch: LiveChannel) {
            val fav = Favorites.isFav(ch.key)
            star.text = if (fav) "★" else "☆"
            star.setTextColor(star.context.getColor(if (fav) R.color.star else R.color.text_faint))
        }

        private fun fillBar(wrap: ViewGroup, fraction: Double) {
            wrap.removeAllViews()
            val ctx = wrap.context
            val bg = View(ctx).apply {
                setBackgroundColor(ctx.getColor(R.color.border))
                layoutParams = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 4))
            }
            wrap.addView(bg)
            val fg = View(ctx).apply { setBackgroundColor(ctx.getColor(R.color.progress_blue)) }
            wrap.addView(fg)
            wrap.post {
                val w = (wrap.width * fraction.coerceIn(0.0, 1.0)).toInt()
                fg.layoutParams = android.widget.FrameLayout.LayoutParams(w, Ui.dp(ctx, 4))
            }
        }
    }

    // ---------- Preview ----------

    private fun schedulePreview(ch: LiveChannel) {
        updatePreviewText(ch)
        pendingPreview?.let { handler.removeCallbacks(it) }
        val r = Runnable { startPreviewPlayer(ch) }
        pendingPreview = r
        handler.postDelayed(r, 600)
    }

    private fun updatePreviewText(ch: LiveChannel) {
        val pos = shownChannels.indexOf(ch)
        previewTitle.text = "${if (pos >= 0) (pos + 1).toString() + " · " else ""}${ch.name}"
        val (now, next) = EpgStore.nowNext(ch)
        if (now != null) {
            previewMeta.text = "${Util.time(now.startUtc)} – ${Util.time(now.stopUtc)} · Termina en ${Util.minutesLeft(now.stopUtc)} min"
            previewDesc.text = listOf(now.title, now.desc).filter { it.isNotEmpty() }.joinToString("\n")
            previewNext.text = if (next != null) "A CONTINUACIÓN\n${next.title} · ${Util.time(next.startUtc)} – ${Util.time(next.stopUtc)}" else ""
        } else {
            previewMeta.text = ch.group
            previewDesc.text = "Sin información de guía para este canal."
            previewNext.text = ""
        }
    }

    private fun startPreviewPlayer(ch: LiveChannel) {
        if (!isAdded) return
        val ctx = context ?: return
        if (previewPlayer == null) {
            previewPlayer = ExoPlayer.Builder(ctx).build().apply {
                volume = 0f
                repeatMode = ExoPlayer.REPEAT_MODE_ONE
            }
            playerView.player = previewPlayer
        }
        if (previewUrl != ch.streamUrl) {
            previewUrl = ch.streamUrl
            previewPlayer?.setMediaItem(MediaItem.fromUri(ch.streamUrl))
            previewPlayer?.prepare()
        }
        previewPlayer?.playWhenReady = true
    }

    private fun releasePreview() {
        pendingPreview?.let { handler.removeCallbacks(it) }
        previewPlayer?.release()
        previewPlayer = null
        previewUrl = null
        playerView.player = null
    }

    override fun onPause() {
        super.onPause()
        releasePreview()
    }

    override fun onResume() {
        super.onResume()
        focusedChannel?.let { schedulePreview(it) }
    }

    override fun onDestroyView() {
        releasePreview()
        super.onDestroyView()
    }
}
