package com.mitv.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

/** One guide row: the channel plus its current and next programmes. */
private typealias GuideRow = Triple<LiveChannel, EpgProgramme, EpgProgramme?>

/** Guía v1: per-channel now/next list (a full time-grid is deferred — see
 *  BUILD-REPORT). Same two-column layout as TV en vivo: the category
 *  column has exactly TV's dimensions (215dp, same margins, padding
 *  and row styling), and each row carries the channel number (56dp,
 *  room for 5 digits, as in TV) and the favorite star. Rows play the
 *  channel on OK; Menu toggles the favorite, as in TV. */
class GuideFragment : Fragment(), FocusRestorable, BackToParentHandler {

    private lateinit var catList: RecyclerView
    private lateinit var guideList: RecyclerView
    private lateinit var catAdapter: CatAdapter
    private lateinit var guideAdapter: GuideAdapter

    private var allWithEpg: List<GuideRow> = emptyList()
    private var shownItems: List<GuideRow> = emptyList()
    private var categories: List<LiveFragment.CatItem> = emptyList()
    private var categoriesApplied = false
    private var selectedCat = ""
    // Last focused adapter positions, for position-aware focus restore
    // (FocusRestorable): -1 in focusedGuidePos means focus was last in
    // the category list, not in the guide rows.
    private var focusedCatPos = 0
    private var focusedGuidePos = -1
    // While a programmatic refocus into the category list is in flight
    // (restoreFocus / focusCatRow), a category row gaining focus must NOT
    // switch content: the gain is a fallback/restore, not navigation.
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

    /** Focus the guide row at [pos], retrying a few posted passes so a
     *  row that is not laid out yet still gets the focus. */
    private fun focusGuideRow(pos: Int, attempts: Int = 3) {
        if (pos !in 0 until guideAdapter.itemCount) return
        guideList.scrollToPosition(pos)
        guideList.post {
            val holder = guideList.findViewHolderForAdapterPosition(pos)
            if (holder != null) holder.itemView.requestFocus()
            else if (attempts > 1) guideList.post { focusGuideRow(pos, attempts - 1) }
        }
    }

    /** Refocus the row at the last recorded position (guide rows
     *  preferred), scrolling it into view first. False when there is
     *  nothing to focus. */
    override fun restoreFocus(): Boolean {
        if (!::guideList.isInitialized || !::catList.isInitialized) return false
        val guidePos = focusedGuidePos
        if (guidePos in 0 until guideAdapter.itemCount) {
            focusGuideRow(guidePos)
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

    /** Back climbs one level: guide rows -> categories, categories -> tab bar. */
    override fun handleBack(): Boolean {
        if (!::guideList.isInitialized || !::catList.isInitialized) return false
        val act = activity as? MainActivity ?: return false
        val focused = act.currentFocus ?: return false
        return when {
            isDescendantOf(focused, guideList) -> { focusCatRow(); true }
            isDescendantOf(focused, catList) -> {
                act.focusTab("guide"); true
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        if (!EpgStore.hasData) {
            root.addView(Ui.emptyText(ctx, "Sin guía todavía.\nSincroniza la lista (Ajustes) para descargar la EPG."))
            return root
        }
        allWithEpg = ContentStore.channels.mapNotNull { ch ->
            val (now, next) = EpgStore.nowNext(ch)
            if (now == null) null else Triple(ch, now, next)
        }
        if (allWithEpg.isEmpty()) {
            root.addView(Ui.emptyText(ctx, "La guía no coincide con ningún canal de la lista activa."))
            return root
        }
        root.orientation = LinearLayout.HORIZONTAL

        // Category column: exactly TV's dimensions and styling.
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

        guideList = FocusTrapRecyclerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            layoutManager = KeepFocusLinearLayoutManager(ctx)
            isFocusable = false
        }
        root.addView(guideList)

        buildCategories()
        catAdapter = CatAdapter()
        catList.adapter = catAdapter
        catAdapter.setItems(categories)
        guideAdapter = GuideAdapter()
        guideList.adapter = guideAdapter
        applyCategory(selectedCat)
        return root
    }

    private fun buildCategories() {
        val base = allWithEpg.map { it.first }
        val histCount = HistoryStore.recent("tv").count { e -> base.any { it.key == e.key } }
        val favCount = base.count { Favorites.isFav(it.key) }
        val list = ArrayList<LiveFragment.CatItem>()
        list.add(LiveFragment.CatItem("__history__", "🕘 Historial", histCount))
        list.add(LiveFragment.CatItem("__favs__", "★ Favoritos", favCount))
        list.add(LiveFragment.CatItem("__all__", "Todo", base.size))
        base.groupBy { it.group }.toSortedMap().forEach { (g, items) ->
            list.add(LiveFragment.CatItem("g:$g", g.ifEmpty { "Sin categoría" }, items.size))
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
        val base = allWithEpg
        shownItems = when {
            id == "__all__" -> base
            id == "__favs__" -> base.filter { Favorites.isFav(it.first.key) }
            id == "__history__" -> {
                HistoryStore.recent("tv").mapNotNull { e -> base.firstOrNull { it.first.key == e.key } }
            }
            id.startsWith("g:") -> base.filter { it.first.group == id.substring(2) }
            else -> base
        }
        guideAdapter.setItems(shownItems)
        val oldPos = categories.indexOfFirst { it.id == oldId }
        if (oldPos >= 0) catAdapter.notifyItemChanged(oldPos)
        val newPos = categories.indexOfFirst { it.id == id }
        if (newPos >= 0) catAdapter.notifyItemChanged(newPos)
    }

    // ---------- Categories adapter (same rows as TV) ----------

    inner class CatAdapter : RecyclerView.Adapter<CatAdapter.Holder>() {
        private var items: List<LiveFragment.CatItem> = emptyList()
        fun setItems(newItems: List<LiveFragment.CatItem>) { items = newItems; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        inner class Holder(val tv: TextView) : RecyclerView.ViewHolder(tv) {
            // Item bound to this holder: listeners read it instead of
            // resolving the adapter position, which can be NO_POSITION
            // while a layout is settling.
            var item: LiveFragment.CatItem? = null
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
                        focusedGuidePos = -1
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
                    (activity as? MainActivity)?.focusTab("guide")
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
            h.tv.setTextColor(ctx.getColor(if (item.id == selectedCat) R.color.text else R.color.text_dim))
        }
    }

    // ---------- Guide rows: number + star + logo + now/next ----------

    inner class GuideAdapter : RecyclerView.Adapter<GuideAdapter.Holder>() {
        private var items: List<GuideRow> = emptyList()
        fun setItems(newItems: List<GuideRow>) { items = newItems; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        inner class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
            val num: TextView = row.getChildAt(0) as TextView
            val star: TextView = row.getChildAt(1) as TextView
            val logoFrame: ViewGroup = row.getChildAt(2) as ViewGroup
            // Holder-owned logo view: Glide loads into the SAME target on
            // every bind, so a previous load is cancelled instead of landing
            // late on a reused row.
            val logoIv: ImageView = logoFrame.getChildAt(0) as ImageView
            val info: LinearLayout = row.getChildAt(3) as LinearLayout
            val nameTv: TextView = info.getChildAt(0) as TextView
            val nowTv: TextView = info.getChildAt(1) as TextView
            val nextTv: TextView = info.getChildAt(2) as TextView
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = Ui.dp(ctx, 8)
                }
                setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 10), Ui.dp(ctx, 14), Ui.dp(ctx, 10))
                background = ctx.getDrawable(R.drawable.focus_bg)
                isFocusable = true; isClickable = true
            }
            // Channel number: same 56dp column as TV (room for 5 digits).
            row.addView(Ui.text(ctx, "", 15f, R.color.text_faint).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 56), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            // Favorite star: same styling as TV.
            row.addView(Ui.text(ctx, "☆", 20f, R.color.text_faint).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 30), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            val logoFrame = android.widget.FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 54), Ui.dp(ctx, 54)).apply { marginEnd = Ui.dp(ctx, 14) }
                background = ctx.getDrawable(R.drawable.logo_tile)
            }
            logoFrame.addView(ImageView(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4))
            })
            row.addView(logoFrame)
            val info = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(Ui.text(ctx, "", 17f, R.color.text, true))
            val nowTv = Ui.text(ctx, "", 14f, R.color.text_dim).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            info.addView(nowTv)
            val nextTv = Ui.text(ctx, "", 13f, R.color.text_faint).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            info.addView(nextTv)
            row.addView(info)
            val h = Holder(row)
            row.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val p = h.bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) focusedGuidePos = p
                }
            }
            row.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) Playback.playChannel(ctx, items[p].first)
            }
            row.setOnKeyListener { _, keyCode, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val p = h.bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (p == 0) {
                        (activity as? MainActivity)?.focusTab("guide")
                        true
                    } else false
                    // Same favorite toggle as TV: the star flips at once,
                    // and unfavoriting inside Favoritos drops the row now.
                    KeyEvent.KEYCODE_MENU -> {
                        val ch = items[p].first
                        val nowFav = Favorites.toggle(ch.key)
                        updateStar(h.star, ch)
                        buildCategories(); catAdapter.setItems(categories)
                        if (selectedCat == "__favs__") {
                            applyCategory(selectedCat, force = true)
                            focusedGuidePos = p.coerceAtMost((guideAdapter.itemCount - 1).coerceAtLeast(0))
                            focusGuideRow(focusedGuidePos)
                        }
                        Toast.makeText(ctx,
                            if (nowFav) "★ Agregado a Favoritos" else "Quitado de Favoritos",
                            Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }
            return h
        }
        override fun onBindViewHolder(h: Holder, pos: Int) {
            val (ch, now, next) = items[pos]
            h.num.text = (pos + 1).toString()
            updateStar(h.star, ch)
            if (ch.logo.isNotBlank()) Glide.with(h.logoIv.context).load(ch.logo)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).fitCenter().into(h.logoIv)
            else {
                Glide.with(h.logoIv.context).clear(h.logoIv)
                h.logoIv.setImageDrawable(null)
            }
            h.nameTv.text = ch.name
            val total = (now.stopUtc - now.startUtc).coerceAtLeast(1)
            val done = ((System.currentTimeMillis() - now.startUtc).coerceIn(0, total) * 100 / total).toInt()
            h.nowTv.text = "AHORA · ${Util.time(now.startUtc)}–${Util.time(now.stopUtc)} · ${now.title}  ($done%)"
            h.nextTv.text = if (next != null) "DESPUÉS · ${Util.time(next.startUtc)}–${Util.time(next.stopUtc)} · ${next.title}" else ""
        }

        private fun updateStar(star: TextView, ch: LiveChannel) {
            val fav = Favorites.isFav(ch.key)
            star.text = if (fav) "★" else "☆"
            star.setTextColor(star.context.getColor(if (fav) R.color.star else R.color.text_faint))
        }
    }
}
