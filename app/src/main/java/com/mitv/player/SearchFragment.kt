package com.mitv.player

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

/** Search over loaded content, accent/case-insensitive (Windows rule),
 *  results grouped by type: channels, movies, series. */
class SearchFragment : Fragment() {

    private lateinit var resultsBox: LinearLayout
    private lateinit var input: EditText

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        input = EditText(ctx).apply {
            hint = "Escribe para buscar en canales, películas y series…"
            textSize = 19f
            setTextColor(ctx.getColor(R.color.text))
            setHintTextColor(ctx.getColor(R.color.text_faint))
            setBackgroundColor(ctx.getColor(R.color.panel2))
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 12), Ui.dp(ctx, 16), Ui.dp(ctx, 12))
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { render(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        // The input spans the full width, so the system's focus search out of
        // it is useless (Up landed on Inicio, Left/Right were swallowed by the
        // text cursor). Since v1.0.5 Buscar is the LAST tab, so wire the edges
        // explicitly: Up goes to the Buscar tab; Left at the start of the text
        // jumps back to Ajustes (the previous tab). Right is the text cursor.
        input.setOnKeyListener { _, keyCode, ev ->
            if (ev.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val act = activity as? MainActivity ?: return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                    if (input.selectionStart <= 0) { act.focusTab("settings"); true } else false
                else -> false
            }
        }
        root.addView(input)

        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = Ui.dp(ctx, 14) }
        }
        resultsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(resultsBox)
        root.addView(scroll)

        render("")
        // requestFocus during onCreateView runs before the view is attached
        // and the focus is lost again; defer it until the view is in the window.
        root.post {
            (activity as? MainActivity)?.let { input.nextFocusUpId = it.tabId("search") }
            input.requestFocus()
        }
        return root
    }

    private fun render(queryRaw: String) {
        val ctx = requireContext()
        resultsBox.removeAllViews()
        val q = Util.fold(queryRaw.trim())
        if (q.length < 2) {
            resultsBox.addView(Ui.text(ctx, "Escribe al menos 2 letras. La búsqueda ignora acentos y mayúsculas.", 15f, R.color.text_faint))
            return
        }
        var any = false

        val chans = ContentStore.channels.filter { Util.fold(it.name).contains(q) }.take(30)
        if (chans.isNotEmpty()) {
            any = true
            resultsBox.addHeader("Canales (${chans.size})")
            for (ch in chans) {
                resultsBox.addView(resultRow(ctx, ch.logo, ch.name, ch.group, useLogoTile = true) {
                    Playback.playChannel(ctx, ch)
                })
            }
        }
        val movies = ContentStore.movies.filter { Util.fold(it.name).contains(q) }.take(40)
        if (movies.isNotEmpty()) {
            any = true
            resultsBox.addHeader("Películas (${movies.size})")
            for (m in movies) {
                resultsBox.addView(resultRow(ctx, m.logo, m.name, listOf(m.year, m.category).filter { it.isNotEmpty() }.joinToString(" · "), useLogoTile = false) {
                    VodDetailActivity.startMovie(ctx, m)
                })
            }
        }
        val series = ContentStore.series.filter { Util.fold(it.name).contains(q) }.take(40)
        if (series.isNotEmpty()) {
            any = true
            resultsBox.addHeader("Series (${series.size})")
            for (s in series) {
                resultsBox.addView(resultRow(ctx, s.cover, s.name, s.category, useLogoTile = false) {
                    VodDetailActivity.startSeries(ctx, s)
                })
            }
        }
        if (!any) resultsBox.addView(Ui.text(ctx, "Sin resultados para «$queryRaw».", 16f, R.color.text_dim))
    }

    private fun LinearLayout.addHeader(s: String) {
        val t = Ui.sectionTitle(requireContext(), s)
        (t.layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(requireContext(), 14)
        addView(t)
    }

    private fun resultRow(ctx: Context, image: String, title: String, sub: String,
                          useLogoTile: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(ctx, 8)
            }
            setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 9), Ui.dp(ctx, 12), Ui.dp(ctx, 9))
            background = ctx.getDrawable(R.drawable.focus_bg)
            isFocusable = true; isClickable = true
            setOnClickListener { onClick() }
        }
        if (useLogoTile) {
            row.addView(Ui.logoTile(ctx, image, title, 54, 54))
        } else {
            val iv = android.widget.ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 44), Ui.dp(ctx, 62)).apply { marginEnd = Ui.dp(ctx, 12) }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
            }
            if (image.isNotBlank()) Glide.with(ctx).load(image).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).centerCrop().into(iv)
            row.addView(iv)
        }
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (useLogoTile) marginStart = Ui.dp(ctx, 12)
            }
        }
        info.addView(Ui.text(ctx, title, 17f, R.color.text, true))
        if (sub.isNotEmpty()) info.addView(Ui.text(ctx, sub, 13f, R.color.text_dim))
        row.addView(info)
        return row
    }
}
