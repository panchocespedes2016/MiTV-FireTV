package com.mitv.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

/** Lists are applied synchronously, same rule as LiveFragment: when a
 *  category is focused, the whole filtered list is known at once
 *  (setItems swaps the data and notifies in the same call). Nothing
 *  settles later in a background diff. */

/** Películas / Series: category sidebar + poster grid (approved mockup style). */
class VodFragment : Fragment(), FocusRestorable, BackToParentHandler {

    private var isSeries = false
    private lateinit var catList: RecyclerView
    private lateinit var grid: RecyclerView
    private lateinit var catAdapter: CatAdapter
    private var movieAdapter: MovieAdapter? = null
    private var seriesAdapter: SeriesAdapter? = null
    private var categories: List<LiveFragment.CatItem> = emptyList()
    // True once the first applyCategory() has run, so the early-return there
    // never skips the initial load (selectedCat starts empty).
    private var categoriesApplied = false
    // Empty until the first applyCategory() runs, so the early-return there
    // never skips the initial load.
    private var selectedCat = ""
    // Last focused adapter positions, for position-aware focus restore
    // (FocusRestorable): -1 in focusedGridPos means focus was last in the
    // category list, not in the grid.
    private var focusedCatPos = 0
    private var focusedGridPos = -1
    // Same guard as LiveFragment: while a programmatic refocus into the
    // category list is in flight, a category row gaining focus must NOT
    // switch content (fallback/restore, not navigation).
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

    /** Focus the grid card at [pos], retrying a few posted passes so a
     *  card that is not laid out yet still gets the focus. */
    private fun focusGridRow(pos: Int, attempts: Int = 3) {
        val count = grid.adapter?.itemCount ?: 0
        if (pos !in 0 until count) return
        grid.scrollToPosition(pos)
        grid.post {
            val holder = grid.findViewHolderForAdapterPosition(pos)
            if (holder != null) holder.itemView.requestFocus()
            else if (attempts > 1) grid.post { focusGridRow(pos, attempts - 1) }
        }
    }

    /** Refocus the card/row at the last recorded position (grid preferred),
     *  scrolling it into view first. False when there is nothing to focus. */
    override fun restoreFocus(): Boolean {
        if (!::grid.isInitialized || !::catList.isInitialized) return false
        val gridPos = focusedGridPos
        val gridCount = grid.adapter?.itemCount ?: 0
        if (gridPos in 0 until gridCount) {
            focusGridRow(gridPos)
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

    /** Back climbs one level: grid -> categories, categories -> tab bar. */
    override fun handleBack(): Boolean {
        if (!::grid.isInitialized || !::catList.isInitialized) return false
        val act = activity as? MainActivity ?: return false
        val focused = act.currentFocus ?: return false
        return when {
            isDescendantOf(focused, grid) -> { focusCatRow(); true }
            isDescendantOf(focused, catList) -> {
                act.focusTab(if (isSeries) "series" else "movies"); true
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

    companion object {
        fun newInstance(isSeries: Boolean) = VodFragment().apply {
            arguments = Bundle().apply { putBoolean("series", isSeries) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isSeries = arguments?.getBoolean("series") ?: false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        catList = FocusTrapRecyclerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 250), ViewGroup.LayoutParams.MATCH_PARENT).apply {
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
        grid = FocusTrapRecyclerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            layoutManager = KeepFocusGridLayoutManager(ctx, 5)
            isFocusable = false
            clipToPadding = false
            setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4))
        }
        root.addView(grid)

        buildCategories()
        catAdapter = CatAdapter()
        catList.adapter = catAdapter
        catAdapter.setItems(categories)
        if (isSeries) {
            seriesAdapter = SeriesAdapter()
            grid.adapter = seriesAdapter
        } else {
            movieAdapter = MovieAdapter()
            grid.adapter = movieAdapter
        }
        applyCategory(selectedCat)
        return root
    }

    private fun buildCategories() {
        val list = ArrayList<LiveFragment.CatItem>()
        if (isSeries) {
            val hist = HistoryStore.recent("series").size
            val fav = ContentStore.series.count { Favorites.isFav(it.key) }
            list.add(LiveFragment.CatItem("__history__", "🕘 Historial", hist))
            list.add(LiveFragment.CatItem("__favs__", "★ Favoritos", fav))
            list.add(LiveFragment.CatItem("__all__", "Todo", ContentStore.series.size))
            ContentStore.series.groupBy { it.category }.toSortedMap().forEach { (g, items) ->
                list.add(LiveFragment.CatItem("g:$g", g.ifEmpty { "Sin categoría" }, items.size))
            }
        } else {
            val hist = HistoryStore.recent("movies").size
            val fav = ContentStore.movies.count { Favorites.isFav(it.key) }
            list.add(LiveFragment.CatItem("__history__", "🕘 Historial", hist))
            list.add(LiveFragment.CatItem("__favs__", "★ Favoritos", fav))
            list.add(LiveFragment.CatItem("__all__", "Todo", ContentStore.movies.size))
            ContentStore.movies.groupBy { it.category }.toSortedMap().forEach { (g, items) ->
                list.add(LiveFragment.CatItem("g:$g", g.ifEmpty { "Sin categoría" }, items.size))
            }
        }
        categories = list
    }

    // Same focus-loss hazard as LiveFragment: this runs from a category
    // row's focus listener, so only the previously/newly selected rows are
    // refreshed instead of the whole list (see LiveFragment.applyCategory).
    private fun applyCategory(id: String, force: Boolean = false) {
        if (!force && id == selectedCat && categoriesApplied) return
        val oldId = selectedCat
        selectedCat = id
        categoriesApplied = true
        if (isSeries) {
            val items = when {
                id == "__all__" -> ContentStore.series
                id == "__favs__" -> ContentStore.series.filter { Favorites.isFav(it.key) }
                id == "__history__" -> HistoryStore.recent("series").mapNotNull { e ->
                    ContentStore.series.firstOrNull { it.key == e.key }
                }
                id.startsWith("g:") -> ContentStore.series.filter { it.category == id.substring(2) }
                else -> ContentStore.series
            }
            seriesAdapter?.setItems(items)
        } else {
            val items = when {
                id == "__all__" -> ContentStore.movies
                id == "__favs__" -> ContentStore.movies.filter { Favorites.isFav(it.key) }
                id == "__history__" -> HistoryStore.recent("movies").mapNotNull { e ->
                    ContentStore.movies.firstOrNull { it.key == e.key }
                }
                id.startsWith("g:") -> ContentStore.movies.filter { it.category == id.substring(2) }
                else -> ContentStore.movies
            }
            movieAdapter?.setItems(items)
        }
        val oldPos = categories.indexOfFirst { it.id == oldId }
        if (oldPos >= 0) catAdapter.notifyItemChanged(oldPos)
        val newPos = categories.indexOfFirst { it.id == id }
        if (newPos >= 0) catAdapter.notifyItemChanged(newPos)
    }

    /** Same watched-toggle as the detail screen, so the ☰ menu can offer it from the grid. */
    private fun toggleWatchedMovie(m: VodMovie) {
        val key = m.key
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
                        focusedGridPos = -1
                        applyCategory(item.id)
                    }
                }
            }
            tv.setOnKeyListener { _, keyCode, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                    h.bindingAdapterPosition == 0) {
                    (activity as? MainActivity)?.focusTab(if (isSeries) "series" else "movies")
                    true
                } else false
            }
            return h
        }
        override fun onBindViewHolder(h: Holder, pos: Int) {
            val item = items[pos]
            h.item = item
            h.tv.text = "${item.label}    ${item.count}"
            h.tv.setTextColor(h.tv.context.getColor(if (item.id == selectedCat) R.color.text else R.color.text_dim))
        }
    }

    inner class MovieAdapter : RecyclerView.Adapter<MovieAdapter.Holder>() {
        private var items: List<VodMovie> = emptyList()
        fun setItems(newItems: List<VodMovie>) { items = newItems; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        inner class Holder(val col: LinearLayout) : RecyclerView.ViewHolder(col) {
            val iv: ImageView = col.getChildAt(0) as ImageView
            val title: TextView = col.getChildAt(1) as TextView
            val sub: TextView = col.getChildAt(2) as TextView
            val watched: TextView = col.getChildAt(3) as TextView
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = Ui.dp(ctx, 12); bottomMargin = Ui.dp(ctx, 16)
                }
            }
            val iv = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 190))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ctx.getDrawable(R.drawable.card_bg)
                clipToOutline = true
                isFocusable = true; isClickable = true
                foreground = ctx.getDrawable(R.drawable.card_fg)
            }
            col.addView(iv)
            val title = Ui.text(ctx, "", 14f, R.color.text, true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 6) }
            }
            col.addView(title)
            val sub = Ui.text(ctx, "", 12f, R.color.text_faint).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            col.addView(sub)
            val watched = Ui.text(ctx, "", 12f, R.color.star)
            col.addView(watched)
            val h = Holder(col)
            iv.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val p = h.bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) focusedGridPos = p
                }
            }
            iv.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) VodDetailActivity.startMovie(ctx, items[p])
            }
            iv.setOnKeyListener { _, kc, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val p = h.bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return@setOnKeyListener false
                when (kc) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (p == 0) {
                        (activity as? MainActivity)?.focusTab("movies")
                        true
                    } else false
                    KeyEvent.KEYCODE_MENU -> {
                        val m = items[p]
                        val entry = HistoryStore.entry(m.key)
                        val resume = entry?.inProgress == true
                        Ui.showMenuDialog(ctx, m.name, listOf(
                            (if (resume) "⏵ Continuar (${Util.durationText(entry.positionMs)})" else "▶ Reproducir") to
                                { Playback.playMovie(ctx, m, resume = resume) },
                            (if (Favorites.isFav(m.key)) "☆ Quitar de Favoritos" else "★ Favorito") to {
                                Favorites.toggle(m.key)
                                buildCategories(); catAdapter.setItems(categories)
                                if (selectedCat == "__favs__") {
                                    applyCategory(selectedCat, force = true)
                                    // List rebuilt under the focused card:
                                    // focus the card now at this position.
                                    focusedGridPos = p.coerceAtMost(((grid.adapter?.itemCount ?: 1) - 1).coerceAtLeast(0))
                                    focusGridRow(focusedGridPos)
                                }
                                if (p < itemCount) notifyItemChanged(p)
                            },
                            (if (HistoryStore.isWatched(m.key)) "✓ Visto (quitar)" else "✓ Marcar como visto") to {
                                toggleWatchedMovie(m)
                                notifyItemChanged(p)
                            }
                        ))
                        true
                    }
                    else -> false
                }
            }
            return h
        }
        override fun onBindViewHolder(h: Holder, pos: Int) {
            val m = items[pos]
            val ctx = h.col.context
            if (m.logo.isNotBlank()) Glide.with(ctx).load(m.logo)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).centerCrop().into(h.iv)
            else {
                Glide.with(ctx).clear(h.iv)
                h.iv.setImageDrawable(null)
            }
            h.title.text = m.name
            h.sub.text = listOf(m.year, m.category).filter { it.isNotEmpty() }.joinToString(" · ")
            h.watched.text = when {
                HistoryStore.isWatched(m.key) -> "✓ Visto"
                HistoryStore.entry(m.key)?.inProgress == true -> "⏵ Continuar"
                else -> ""
            }
        }
    }

    inner class SeriesAdapter : RecyclerView.Adapter<SeriesAdapter.Holder>() {
        private var items: List<SerieItem> = emptyList()
        fun setItems(newItems: List<SerieItem>) { items = newItems; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        inner class Holder(val col: LinearLayout) : RecyclerView.ViewHolder(col) {
            val iv: ImageView = col.getChildAt(0) as ImageView
            val title: TextView = col.getChildAt(1) as TextView
            val sub: TextView = col.getChildAt(2) as TextView
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = Ui.dp(ctx, 12); bottomMargin = Ui.dp(ctx, 16)
                }
            }
            val iv = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 190))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ctx.getDrawable(R.drawable.card_bg)
                clipToOutline = true
                isFocusable = true; isClickable = true
                foreground = ctx.getDrawable(R.drawable.card_fg)
            }
            col.addView(iv)
            val title = Ui.text(ctx, "", 14f, R.color.text, true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 6) }
            }
            col.addView(title)
            val sub = Ui.text(ctx, "", 12f, R.color.text_faint).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            col.addView(sub)
            val h = Holder(col)
            iv.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val p = h.bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) focusedGridPos = p
                }
            }
            iv.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) VodDetailActivity.startSeries(ctx, items[p])
            }
            iv.setOnKeyListener { _, kc, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val p = h.bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return@setOnKeyListener false
                when (kc) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (p == 0) {
                        (activity as? MainActivity)?.focusTab("series")
                        true
                    } else false
                    KeyEvent.KEYCODE_MENU -> {
                        val s = items[p]
                        Ui.showMenuDialog(ctx, s.name, listOf(
                            "▶ Ver episodios" to { VodDetailActivity.startSeries(ctx, s) },
                            (if (Favorites.isFav(s.key)) "☆ Quitar de Favoritos" else "★ Favorito") to {
                                Favorites.toggle(s.key)
                                buildCategories(); catAdapter.setItems(categories)
                                if (selectedCat == "__favs__") {
                                    applyCategory(selectedCat, force = true)
                                    // List rebuilt under the focused card:
                                    // focus the card now at this position.
                                    focusedGridPos = p.coerceAtMost(((grid.adapter?.itemCount ?: 1) - 1).coerceAtLeast(0))
                                    focusGridRow(focusedGridPos)
                                }
                            }
                        ))
                        true
                    }
                    else -> false
                }
            }
            return h
        }
        override fun onBindViewHolder(h: Holder, pos: Int) {
            val s = items[pos]
            val ctx = h.col.context
            if (s.cover.isNotBlank()) Glide.with(ctx).load(s.cover)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).centerCrop().into(h.iv)
            else {
                Glide.with(ctx).clear(h.iv)
                h.iv.setImageDrawable(null)
            }
            h.title.text = s.name
            h.sub.text = s.category
        }
    }
}
