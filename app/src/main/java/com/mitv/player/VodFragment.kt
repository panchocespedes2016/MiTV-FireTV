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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

/** Diff callbacks: list updates are diffed off the main thread and dispatched
 *  per item (ListAdapter), instead of swapping the whole adapter. */
private val movieDiff = object : DiffUtil.ItemCallback<VodMovie>() {
    override fun areItemsTheSame(a: VodMovie, b: VodMovie) = a.key == b.key
    override fun areContentsTheSame(a: VodMovie, b: VodMovie) = a == b
}

private val serieDiff = object : DiffUtil.ItemCallback<SerieItem>() {
    override fun areItemsTheSame(a: SerieItem, b: SerieItem) = a.key == b.key
    override fun areContentsTheSame(a: SerieItem, b: SerieItem) = a == b
}

private val vodCatItemDiff = object : DiffUtil.ItemCallback<LiveFragment.CatItem>() {
    override fun areItemsTheSame(a: LiveFragment.CatItem, b: LiveFragment.CatItem) = a.id == b.id
    override fun areContentsTheSame(a: LiveFragment.CatItem, b: LiveFragment.CatItem) = a == b
}

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

    /** Refocus the card/row at the last recorded position (grid preferred),
     *  scrolling it into view first. False when there is nothing to focus. */
    override fun restoreFocus(): Boolean {
        if (!::grid.isInitialized || !::catList.isInitialized) return false
        val gridPos = focusedGridPos
        val gridCount = grid.adapter?.itemCount ?: 0
        if (gridPos in 0 until gridCount) {
            grid.scrollToPosition(gridPos)
            grid.post {
                grid.findViewHolderForAdapterPosition(gridPos)?.itemView?.requestFocus()
            }
            return true
        }
        val catPos = focusedCatPos
        val catCount = catList.adapter?.itemCount ?: 0
        if (catPos in 0 until catCount) {
            catList.scrollToPosition(catPos)
            catList.post {
                catList.findViewHolderForAdapterPosition(catPos)?.itemView?.requestFocus()
            }
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
        catList.scrollToPosition(pos)
        catList.post {
            catList.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
        }
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
            setBackgroundColor(ctx.getColor(R.color.panel))
            setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8))
        }
        root.addView(catList)
        grid = FocusTrapRecyclerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            layoutManager = KeepFocusGridLayoutManager(ctx, 5)
            clipToPadding = false
            setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4))
        }
        root.addView(grid)

        buildCategories()
        catAdapter = CatAdapter()
        catList.adapter = catAdapter
        catAdapter.submitList(categories)
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
        val section = if (isSeries) "series" else "movies"
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
    private fun applyCategory(id: String) {
        if (id == selectedCat && categoriesApplied) return
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
            seriesAdapter?.submitList(items)
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
            movieAdapter?.submitList(items)
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

    inner class CatAdapter : ListAdapter<LiveFragment.CatItem, CatAdapter.Holder>(vodCatItemDiff) {
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
            val h = Holder(tv)
            tv.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) applyCategory(getItem(p).id)
            }
            tv.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val p = h.bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) {
                        focusedCatPos = p
                        focusedGridPos = -1
                        applyCategory(getItem(p).id)
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
            val item = getItem(pos)
            h.tv.text = "${item.label}    ${item.count}"
            h.tv.setTextColor(h.tv.context.getColor(if (item.id == selectedCat) R.color.text else R.color.text_dim))
        }
    }

    inner class MovieAdapter : ListAdapter<VodMovie, MovieAdapter.Holder>(movieDiff) {
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
                if (p != RecyclerView.NO_POSITION) VodDetailActivity.startMovie(ctx, getItem(p))
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
                        val m = getItem(p)
                        val entry = HistoryStore.entry(m.key)
                        val resume = entry?.inProgress == true
                        Ui.showMenuDialog(ctx, m.name, listOf(
                            (if (resume) "⏵ Continuar (${Util.durationText(entry.positionMs)})" else "▶ Reproducir") to
                                { Playback.playMovie(ctx, m, resume = resume) },
                            (if (Favorites.isFav(m.key)) "☆ Quitar de Favoritos" else "★ Favorito") to {
                                Favorites.toggle(m.key)
                                buildCategories(); catAdapter.submitList(categories)
                                notifyItemChanged(p)
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
            val m = getItem(pos)
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

    inner class SeriesAdapter : ListAdapter<SerieItem, SeriesAdapter.Holder>(serieDiff) {
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
                if (p != RecyclerView.NO_POSITION) VodDetailActivity.startSeries(ctx, getItem(p))
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
                        val s = getItem(p)
                        Ui.showMenuDialog(ctx, s.name, listOf(
                            "▶ Ver episodios" to { VodDetailActivity.startSeries(ctx, s) },
                            (if (Favorites.isFav(s.key)) "☆ Quitar de Favoritos" else "★ Favorito") to {
                                Favorites.toggle(s.key)
                                buildCategories(); catAdapter.submitList(categories)
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
            val s = getItem(pos)
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
