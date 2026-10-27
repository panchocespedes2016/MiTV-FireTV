package com.mitv.player

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

object Ui {
    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun text(c: Context, s: String, sizeSp: Float, colorRes: Int = R.color.text,
             bold: Boolean = false): TextView = TextView(c).apply {
        text = s; textSize = sizeSp
        setTextColor(c.getColor(colorRes))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        includeFontPadding = false
    }

    fun sectionTitle(c: Context, s: String, sub: String = ""): TextView {
        val t = text(c, s, 20f, R.color.text, true)
        if (sub.isNotEmpty()) {
            val full = android.text.SpannableString("$s  $sub")
            full.setSpan(android.text.style.ForegroundColorSpan(c.getColor(R.color.text_faint)),
                s.length, full.length, 0)
            full.setSpan(android.text.style.RelativeSizeSpan(0.75f), s.length, full.length, 0)
            t.text = full
        }
        t.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(c, 10) }
        return t
    }

    fun loadImage(iv: ImageView, url: String, emojiFallback: String = "🎬") {
        if (url.isBlank()) {
            iv.setImageDrawable(null)
            iv.tag = emojiFallback
            return
        }
        Glide.with(iv.context)
            .load(url)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .centerCrop()
            .into(iv)
    }

    /** White rounded tile with the channel logo, like the approved mockup. */
    fun logoTile(c: Context, url: String, fallbackText: String, wDp: Int, hDp: Int): View {
        val frame = android.widget.FrameLayout(c).apply {
            layoutParams = ViewGroup.LayoutParams(dp(c, wDp), dp(c, hDp))
            background = c.getDrawable(R.drawable.logo_tile)
            clipToOutline = true
        }
        val iv = ImageView(c).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(c, 5), dp(c, 5), dp(c, 5), dp(c, 5))
        }
        frame.addView(iv)
        if (url.isBlank()) {
            val tv = text(c, fallbackText.take(6), 13f, android.R.color.black, true)
            tv.gravity = Gravity.CENTER
            tv.layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            tv.setTextColor(Color.parseColor("#222222"))
            frame.addView(tv)
        } else {
            Glide.with(c).load(url)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .fitCenter().into(iv)
        }
        return frame
    }

    fun progressBar(c: Context, fraction: Double, colorRes: Int = R.color.progress_blue,
                    heightDp: Int = 4): View {
        val outer = View(c).apply {
            setBackgroundColor(c.getColor(R.color.border))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp))
        }
        val wrap = android.widget.FrameLayout(c).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp))
        }
        wrap.addView(outer)
        val inner = View(c).apply { setBackgroundColor(c.getColor(colorRes)) }
        val lp = android.widget.FrameLayout.LayoutParams(0, dp(c, heightDp))
        inner.layoutParams = lp
        wrap.addView(inner)
        wrap.post {
            val w = (wrap.width * fraction.coerceIn(0.0, 1.0)).toInt()
            inner.layoutParams = android.widget.FrameLayout.LayoutParams(w, dp(c, heightDp))
        }
        return wrap
    }

    fun hintButton(c: Context, key: String, label: String): TextView {
        val t = text(c, "$key  $label", 13f, R.color.text_faint)
        return t
    }

    /** Small DPAD-friendly options menu opened with the remote's Menu (☰) key.
     *  The app's own dark panel background is used: the base theme is light. */
    fun showMenuDialog(ctx: Context, title: String, options: List<Pair<String, () -> Unit>>) {
        val dialog = android.app.Dialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.getDrawable(R.drawable.dialog_bg)
            setPadding(dp(ctx, 10), dp(ctx, 16), dp(ctx, 10), dp(ctx, 16))
        }
        val titleTv = text(ctx, title, 19f, R.color.text, true).apply {
            setPadding(dp(ctx, 16), dp(ctx, 4), dp(ctx, 16), dp(ctx, 4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 10) }
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(titleTv)
        var first: View? = null
        options.forEach { (label, action) ->
            val tv = text(ctx, label, 17f, R.color.text).apply {
                setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
                background = ctx.getDrawable(R.drawable.focus_bg)
                isFocusable = true; isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 6) }
                setOnClickListener { dialog.dismiss(); action() }
            }
            if (first == null) first = tv
            root.addView(tv)
        }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout(dp(ctx, 460), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
        first?.requestFocus()
    }

    fun emptyText(c: Context, s: String): TextView {
        val t = text(c, s, 17f, R.color.text_dim)
        t.gravity = Gravity.CENTER
        t.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return t
    }

    fun spinner(c: Context): ProgressBar = ProgressBar(c).apply {
        layoutParams = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER)
    }
}
