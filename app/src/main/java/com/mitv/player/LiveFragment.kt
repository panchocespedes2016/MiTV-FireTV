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

/** Lists are applied synchronously: when a category is focused, the
 *  whole filtered list is known at once — setItems swaps the data and
 *  notifies in the same call, so a position targeted right after a
 *  category switch is valid immediately. Nothing settles later in a
 *  background diff. The v1.0.9 hazard (a full refresh detaching the
 *  focused row) cannot fire on a category switch: focus is in the
 *  sidebar list, a different RecyclerView. The one path that refreshes
 *  the list under the focused row (unfavoriting inside Favoritos)
 *  re-focuses its row explicitly right after. */

/** TV en vivo (and Deportes with sportsOnly=true), per the approved mockup.
 *  Left: categories with counts (Historial, Favoritos, Todo, groups).
 *  Center: channel rows with EPG now + progress. Right: muted preview after
 *  a short focus dwell, now/next details. OK = fullscreen, Menu = favorite. */
@OptIn(UnstableApi::class)
class LiveFragment : Fragment(), FocusRestorable, BackToParentHandler {

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
    private lateinit var catAdapter: CatAdapter
    private lateinit var channelAdapter: ChannelAdapter
    // True once the first applyCategory() has run, so the early-return there
    // never skips the initial load (selectedCat starts empty).
    private var categoriesApplied = false
    // Empty until the first applyCategory() runs, so the early-return there
    // never skips the initial load.
    private var selectedCat = ""
    private var shownChannels: List<LiveChannel> = emptyList()
    private var focusedChannel: LiveChannel? = null
    // Last focused adapter positions, for position-aware focus restore
    // (FocusRestorable): -1 in focusedChanPos means focus was last in the
    // category list, not in the channels.
    private var focusedCatPos = 0
    private var focusedChanPos = -1
    // While a programmatic refocus into the category list is in flight
    // (restoreFocus / focusCatRow), a category row gaining focus must NOT
    // switch content: the gain is a fallback/restore, not navigation.
    // Without this, a fast-scroll detach that lands focus on the first
    // category row swaps the channel list to Historial under the user.
    private var suppressCategoryApply = false

    /** Focus a category row without treating the gain as a selection.
     *  Retries a few posted passes: a row that is not laid out yet must
     *  still get the focus instead of the search falling back to the
     *  tab bar. The suppress flag stays up across retries. */
    private fun requestCategoryFocus(pos: Int, attempts: Int = 3) {
        suppressCategoryApply = true
        catList.scrollToPosition(pos)
        catList.post {
            val holder = catList.findViewHolderForAdapterPosition(pos)
            if (holder != null) {
                holder.itemView.requestFocus()
                catList.post { suppressCategoryApply = false }
            } else if (attempts > 1) {
                catList.post { requestCategoryFocus(pos, attempts - 1) }
            } else {
                catList.post { suppressCategoryApply = false }
            }
        }
    }

    /** Focus the channel row at [pos], retrying a few posted passes so a
     *  row that is not laid out yet still gets the focus. */
    private fun focusChannelRow(pos: Int, attempts: Int = 3) {
        if (pos !in 0 until channelAdapter.itemCount) return
        chanList.scrollToPosition(pos)
        chanList.post {
            val holder = chanList.findViewHolderForAdapterPosition(pos)
            if (holder != null) holder.itemView.requestFocus()
            else if (attempts > 1) chanList.post { focusChannelRow(pos, attempts - 1) }
        }
    }

    /** Refocus the row at the last recorded position (channels preferred),
     *  scrolling it into view first. False when there is nothing to focus. */
    override fun restoreFocus(): Boolean {
        if (!::chanList.isInitialized || !::catList.isInitialized) return false
        val chanPos = focusedChanPos
        if (chanPos in 0 until channelAdapter.itemCount) {
            focusChannelRow(chanPos)
            return true
        }
        val catPos = focusedCatPos
        val catCount = catList.adapter?.itemCount ?: 0
        if (catPos in 0 until catCount) {
            requestCategoryFocus(catPos)
            return true
        }
        return false
    }

    /** Back climbs one level: channels -> categories, categories -> tab bar. */
    override fun handleBack(): Boolean {
        if (!::chanList.isInitialized || !::catList.isInitialized) return false
        val act = activity as? MainActivity ?: return false
        val focused = act.currentFocus ?: return false
        return when {
            isDescendantOf(focused, chanList) -> { focusCatRow(); true }
            isDescendantOf(focused, catList) -> {
                act.focusTab(if (sportsOnly) "sports" else "live"); true
            }
            else -> false
        }
    }

    private fun focusCatRow() {
        val count = catList.adapter?.itemCount ?: 0
        if (count == 0) return
        val pos = focusedCatPos.coerceIn(0, count - 1)
        requestCategoryFocus(pos)
    }

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

        catList = FocusTrapRecyclerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 215), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginEnd = Ui.dp(ctx, 14)
            }
            layoutManager = KeepFocusLinearLayoutManager(ctx)
            // The list container itself must never take focus: only its
            // rows are focusable, so a fallback can never park on it.
            isFocusable = false
            setBackgroundColor(ctx.getColor(R.color.panel))
            setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8))
        }
        root.addView(catList)

        chanList = FocusTrapRecyclerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            layoutManager = KeepFocusLinearLayoutManager(ctx)
            isFocusable = false
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
            // Same Fire TV idle-timer hazard as PlayerActivity: the muted
            // preview is video too. Applies only while this view is attached.
            keepScreenOn = true
            useController = false
            setBackgroundColor(0xFF000000.toInt())
        }
        panel.addView(playerView)
        previewBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        previewTitle = Ui.text(ctx, "", 20f, R.color.text, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
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
        catAdapter = CatAdapter()
        catList.adapter = catAdapter
        catAdapter.setItems(categories)
        channelAdapter = ChannelAdapter()
        chanList.adapter = channelAdapter
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
        val histCount = HistoryStore.recent(section).count { e -> base.any { it.key == e.key } }
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

    // Runs from a category row's focus listener. The list swap is
    // synchronous (setItems): the whole filtered list is in the
    // adapter the moment this returns, so a focus move right after
    // the switch targets a valid position. Only the previously/newly
    // selected category rows are refreshed, so the focused sidebar
    // row stays attached.
    private fun applyCategory(id: String, force: Boolean = false) {
        if (!force && id == selectedCat && categoriesApplied) return
        val oldId = selectedCat
        selectedCat = id
        categoriesApplied = true
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
        channelAdapter.setItems(shownChannels)
        val oldPos = categories.indexOfFirst { it.id == oldId }
        if (oldPos >= 0) catAdapter.notifyItemChanged(oldPos)
        val newPos = categories.indexOfFirst { it.id == id }
        if (newPos >= 0) catAdapter.notifyItemChanged(newPos)
    }

    // ---------- Categories adapter ----------

    inner class CatAdapter : RecyclerView.Adapter<CatAdapter.Holder>() {
        private var items: List<CatItem> = emptyList()
        fun setItems(newItems: List<CatItem>) { items = newItems; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        inner class Holder(val tv: TextView) : RecyclerView.ViewHolder(tv) {
            // Item bound to this holder: listeners read it instead of
            // resolving the adapter position, which can be NO_POSITION
            // while a layout is settling.
            var item: CatItem? = null
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val tv = TextView(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 11), Ui.dp(ctx, 12), Ui.dp(ctx, 11))
                textSize = 16f
                isFocusable = true; isClickable = true
                background = ctx.getDrawable(R.drawable.card_bg)
            }
            val h = Holder(tv)
            tv.setOnClickListener {
                h.item?.let { applyCategory(it.id) }
            }
            tv.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    // A restore-driven gain is consumed, never a selection.
                    if (suppressCategoryApply) { suppressCategoryApply = false; return@setOnFocusChangeListener }
                    h.item?.let { item ->
                        val p = h.bindingAdapterPosition
                        if (p != RecyclerView.NO_POSITION) focusedCatPos = p
                        focusedChanPos = -1
                        applyCategory(item.id)
                    }
                }
            }
            tv.setOnKeyListener { _, keyCode, ev ->
                // Deterministic Up exit from the first row: focus search
                // geometry can't be trusted to leave the list for the tab
                // bar outside it, so the top row goes to its tab by name.
                if (ev.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                    h.bindingAdapterPosition == 0) {
                    (activity as? MainActivity)?.focusTab(if (sportsOnly) "sports" else "live")
                    true
                } else false
            }
            return h
        }
        override fun onBindViewHolder(h: Holder, pos: Int) {
            val item = items[pos]
            h.item = item
            val ctx = h.tv.context
            h.tv.text = "${item.label}    ${item.count}"
            h.tv.setTextColor(ctx.getColor(
                if (item.id == selectedCat) R.color.text else R.color.text_dim))
            h.tv.setTypeface(h.tv.typeface, if (item.id == selectedCat) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    // ---------- Channels adapter ----------

    inner class ChannelAdapter : RecyclerView.Adapter<ChannelAdapter.Holder>() {
        private var items: List<LiveChannel> = emptyList()
        fun setItems(newItems: List<LiveChannel>) { items = newItems; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        inner class Holder(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
            val num: TextView = root.getChildAt(0) as TextView
            val star: TextView = root.getChildAt(1) as TextView
            val logoFrame: ViewGroup = root.getChildAt(2) as ViewGroup
            // Holder-owned logo views: the logo ImageView is created once and
            // Glide loads into the SAME target on every bind, so a previous
            // load is cancelled instead of landing late on a reused row.
            val logoIv: ImageView = logoFrame.getChildAt(0) as ImageView
            val logoFb: TextView = logoFrame.getChildAt(1) as TextView
            val info: LinearLayout = root.getChildAt(3) as LinearLayout
            val nameTv: TextView = (info.getChildAt(0) as LinearLayout).getChildAt(0) as TextView
            val progTv: TextView = info.getChildAt(1) as TextView
            // Progress bar as two weight-shared children created once; bind
            // only moves the weight, with no new views and no posted layout.
            val barFg: View = (info.getChildAt(2) as LinearLayout).getChildAt(0)
            val barRest: View = (info.getChildAt(2) as LinearLayout).getChildAt(1)
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
                // Room for 5 digits: a digit at 15sp is ~8.3dp, so five
                // need ~42dp; 56dp leaves margin. The list tops at 4
                // digits today (1543), but the column must not clip when
                // a list grows past 9999.
                layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 56), ViewGroup.LayoutParams.WRAP_CONTENT)
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
            logoFrame.addView(ImageView(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4))
            })
            logoFrame.addView(Ui.text(ctx, "", 11f, android.R.color.black, true).apply {
                gravity = android.view.Gravity.CENTER
                setTextColor(0xFF222222.toInt())
                visibility = View.GONE
                layoutParams = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            })
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
            val barWrap = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 4)).apply { topMargin = Ui.dp(ctx, 6) }
            }
            barWrap.addView(View(ctx).apply {
                setBackgroundColor(ctx.getColor(R.color.progress_blue))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f)
            })
            barWrap.addView(View(ctx).apply {
                setBackgroundColor(ctx.getColor(R.color.border))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            })
            info.addView(barWrap)
            val timesTv = Ui.text(ctx, "", 12f, R.color.text_faint).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 3) }
            }
            info.addView(timesTv)
            root.addView(info)
            val h = Holder(root)
            root.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) {
                    Playback.playChannel(ctx, items[p], if (sportsOnly) "sports" else "tv")
                }
            }
            root.setOnKeyListener { _, keyCode, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val p = h.bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (p == 0) {
                        (activity as? MainActivity)?.focusTab(if (sportsOnly) "sports" else "live")
                        true
                    } else false
                    KeyEvent.KEYCODE_MENU -> {
                        val ch = items[p]
                        val nowFav = Favorites.toggle(ch.key)
                        updateStar(h.star, ch)
                        buildCategories(); catAdapter.setItems(categories)
                        // Unfavoriting inside Favoritos must drop the row
                        // now, not on the next category switch. The list
                        // was rebuilt under the focused row, so put the
                        // focus back on the row now at this position
                        // (the next channel) instead of leaving it lost.
                        if (selectedCat == "__favs__") {
                            applyCategory(selectedCat, force = true)
                            focusedChanPos = p.coerceAtMost((channelAdapter.itemCount - 1).coerceAtLeast(0))
                            focusChannelRow(focusedChanPos)
                        }
                        android.widget.Toast.makeText(ctx,
                            if (nowFav) "★ Agregado a Favoritos" else "Quitado de Favoritos",
                            android.widget.Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }
            root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val p = h.bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) {
                        focusedChanPos = p
                        val ch = items[p]
                        focusedChannel = ch
                        schedulePreview(ch)
                        Marquee.start(h.nameTv, h.progTv)
                    }
                } else {
                    Marquee.stop(h.nameTv, h.progTv, previewTitle)
                }
            }
            return h
        }

        override fun onBindViewHolder(h: Holder, pos: Int) {
            val ch = items[pos]
            // A recycled row keeps the old marquee state: stop it before
            // the new text lands, and restart it if this row has focus.
            Marquee.stop(h.nameTv, h.progTv)
            h.num.text = (pos + 1).toString()
            updateStar(h.star, ch)
            // Logo: toggle the holder-owned views, never create new ones.
            if (ch.logo.isBlank()) {
                Glide.with(h.logoIv.context).clear(h.logoIv)
                h.logoIv.setImageDrawable(null)
                h.logoIv.visibility = View.GONE
                h.logoFb.visibility = View.VISIBLE
                h.logoFb.text = ch.name.take(5)
            } else {
                h.logoFb.visibility = View.GONE
                h.logoIv.visibility = View.VISIBLE
                Glide.with(h.logoIv.context).load(ch.logo)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).fitCenter().into(h.logoIv)
            }
            h.nameTv.text = ch.name + if (ch.hasCatchup) "  ↺" else ""
            val (now, _) = EpgStore.nowNext(ch)
            if (now != null) {
                h.progTv.text = now.title
                val total = (now.stopUtc - now.startUtc).coerceAtLeast(1)
                val done = (System.currentTimeMillis() - now.startUtc).coerceIn(0, total)
                setBar(h, done.toDouble() / total)
                h.timesTv.text = "${Util.time(now.startUtc)} – ${Util.time(now.stopUtc)}"
            } else {
                h.progTv.text = "Sin información de guía"
                setBar(h, 0.0)
                h.timesTv.text = ""
            }
            if (h.root.hasFocus()) h.root.post { Marquee.start(h.nameTv, h.progTv) }
        }

        private fun updateStar(star: TextView, ch: LiveChannel) {
            val fav = Favorites.isFav(ch.key)
            star.text = if (fav) "★" else "☆"
            star.setTextColor(star.context.getColor(if (fav) R.color.star else R.color.text_faint))
        }

        private fun setBar(h: Holder, fraction: Double) {
            val f = fraction.coerceIn(0.0, 1.0).toFloat()
            (h.barFg.layoutParams as LinearLayout.LayoutParams).weight = f
            (h.barRest.layoutParams as LinearLayout.LayoutParams).weight = 1f - f
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
        // The preview shows the focused channel: its title scrolls while
        // the row has focus, like the row's own texts.
        Marquee.stop(previewTitle)
        Marquee.start(previewTitle)
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
