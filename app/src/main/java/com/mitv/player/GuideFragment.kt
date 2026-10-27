package com.mitv.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Guía v1: per-channel now/next list (a full time-grid is deferred — see
 *  BUILD-REPORT). Rows play the channel on OK. */
class GuideFragment : Fragment() {

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
        val withEpg = ContentStore.channels.mapNotNull { ch ->
            val (now, next) = EpgStore.nowNext(ch)
            if (now == null) null else Triple(ch, now, next)
        }
        if (withEpg.isEmpty()) {
            root.addView(Ui.emptyText(ctx, "La guía no coincide con ningún canal de la lista activa."))
            return root
        }
        val list = RecyclerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            layoutManager = LinearLayoutManager(ctx)
        }
        list.adapter = GuideAdapter(withEpg)
        root.addView(list)
        return root
    }

    inner class GuideAdapter(private val items: List<Triple<LiveChannel, EpgProgramme, EpgProgramme?>>) :
        RecyclerView.Adapter<GuideAdapter.Holder>() {
        inner class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
            val logoFrame: ViewGroup = row.getChildAt(0) as ViewGroup
            val info: LinearLayout = row.getChildAt(1) as LinearLayout
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
            val logoFrame = android.widget.FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 54), Ui.dp(ctx, 54)).apply { marginEnd = Ui.dp(ctx, 14) }
                background = ctx.getDrawable(R.drawable.logo_tile)
            }
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
            return Holder(row)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: Holder, pos: Int) {
            val (ch, now, next) = items[pos]
            val ctx = h.row.context
            h.logoFrame.removeAllViews()
            val iv = android.widget.ImageView(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4))
            }
            h.logoFrame.addView(iv)
            if (ch.logo.isNotBlank()) com.bumptech.glide.Glide.with(ctx).load(ch.logo)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.AUTOMATIC).fitCenter().into(iv)
            h.nameTv.text = ch.name
            val total = (now.stopUtc - now.startUtc).coerceAtLeast(1)
            val done = ((System.currentTimeMillis() - now.startUtc).coerceIn(0, total) * 100 / total).toInt()
            h.nowTv.text = "AHORA · ${Util.time(now.startUtc)}–${Util.time(now.stopUtc)} · ${now.title}  ($done%)"
            h.nextTv.text = if (next != null) "DESPUÉS · ${Util.time(next.startUtc)}–${Util.time(next.stopUtc)} · ${next.title}" else ""
            h.row.setOnClickListener { Playback.playChannel(ctx, ch) }
        }
    }
}
