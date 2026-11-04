package com.mitv.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.mitv.player.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A fragment that can restore DPAD focus to the exact list position it was
 *  last at, after the focus was lost entirely (fast scrolling). */
interface FocusRestorable {
    fun restoreFocus(): Boolean
}

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val tabViews = HashMap<String, TextView>()
    private var currentTab = "home"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var syncing = false
    /** Last view focused inside the fragment content, for focus restoration
     *  when fast scrolling loses the focus entirely (see onKeyDown). */
    private var lastContentFocus: View? = null

    companion object {
        val TABS = listOf(
            "home" to "⌂ Inicio",
            "live" to "📺 TV en vivo",
            "movies" to "🎬 Películas",
            "series" to "📚 Series",
            "sports" to "⚽ Deportes",
            "guide" to "📅 Guía",
            "settings" to "⚙️ Ajustes",
            "search" to "🔍 Buscar"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus != null && !tabViews.values.contains(newFocus) && isInContent(newFocus)) {
                lastContentFocus = newFocus
            }
        }

        for ((key, label) in TABS) {
            val tv = TextView(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 16f
                setSingleLine(true)
                setTextColor(getColor(R.color.text_dim))
                setPadding(Ui.dp(this@MainActivity, 11), Ui.dp(this@MainActivity, 8),
                    Ui.dp(this@MainActivity, 11), Ui.dp(this@MainActivity, 8))
                isFocusable = true
                isClickable = true
                background = getDrawable(R.drawable.tab_bg)
                setOnClickListener { selectTab(key, requestFocus = false) }
                setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        selectTab(key, requestFocus = false)
                        b.tabScroll.post {
                            b.tabScroll.smoothScrollTo(
                                (view.left - Ui.dp(this@MainActivity, 24)).coerceAtLeast(0), 0)
                        }
                    }
                }
            }
            tabViews[key] = tv
            b.tabBar.addView(tv)
        }

        selectTab("home", requestFocus = false)
        tabViews["home"]?.requestFocus()

        if (PlaylistStore.active == null) {
            Toast.makeText(this, "Agrega tu primera lista en Ajustes", Toast.LENGTH_LONG).show()
        }
    }

    /** True when [v] lives inside the fragment container (not the tab bar). */
    private fun isInContent(v: View): Boolean {
        var p = v.parent
        while (p != null) {
            if ((p as? View)?.id == R.id.content) return true
            p = (p as? View)?.parent
        }
        return false
    }

    /** View id of a tab, for nextFocusUp wiring from fragment content. */
    fun tabId(key: String): Int = tabViews[key]?.id ?: View.NO_ID

    /** Move remote focus to a tab (selects it via the focus listener). */
    fun focusTab(key: String) {
        tabViews[key]?.requestFocus()
    }

    fun selectTab(key: String, requestFocus: Boolean) {
        if (key == currentTab && supportFragmentManager.findFragmentById(R.id.content) != null) {
            updateTabStyles()
            return
        }
        currentTab = key
        lastContentFocus = null
        updateTabStyles()
        val fragment: Fragment = when (key) {
            "home" -> HomeFragment()
            "live" -> LiveFragment.newInstance(sportsOnly = false)
            "sports" -> LiveFragment.newInstance(sportsOnly = true)
            "movies" -> VodFragment.newInstance(isSeries = false)
            "series" -> VodFragment.newInstance(isSeries = true)
            "guide" -> GuideFragment()
            "search" -> SearchFragment()
            "settings" -> SettingsFragment()
            else -> HomeFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.content, fragment)
            .commit()
        if (requestFocus) tabViews[key]?.requestFocus()
    }

    private fun updateTabStyles() {
        for ((key, tv) in tabViews) {
            tv.isSelected = key == currentTab
            tv.setTextColor(getColor(if (key == currentTab) R.color.text else R.color.text_dim))
        }
        b.hintBar.text = when (currentTab) {
            "live", "sports" -> "↑↓ Cambiar de canal   ·   OK Ver en pantalla completa   ·   ☰ Menú ★ Favorito   ·   La vista previa arranca sola, en silencio"
            "movies", "series" -> "←→↑↓ Navegar   ·   OK Ver ficha   ·   ☰ Menú Opciones"
            "settings" -> "OK Seleccionar   ·   ↩ Atrás Volver"
            else -> "←→↑↓ Navegar   ·   OK Ver / Abrir   ·   ☰ Menú ★ Favorito y opciones   ·   ↩ Atrás Volver"
        }
    }

    /** Refresh content from the active playlist (background), then reload UI. */
    fun syncContent(showToast: Boolean = true, onDone: (() -> Unit)? = null) {
        if (syncing) return
        if (PlaylistStore.active == null) {
            Toast.makeText(this, "No hay lista activa", Toast.LENGTH_SHORT).show()
            onDone?.invoke(); return
        }
        syncing = true
        if (showToast) Toast.makeText(this, "Sincronizando ${PlaylistStore.active?.name}…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = withContext(Dispatchers.IO) { PlaylistLoader.refresh(includeEpg = true) }
            syncing = false
            if (showToast) Toast.makeText(this@MainActivity,
                if (result.ok) "Sincronizado: ${result.message}" else result.message, Toast.LENGTH_LONG).show()
            // Rebuild current tab to show fresh data
            val cur = currentTab
            currentTab = ""
            selectTab(cur, requestFocus = false)
            onDone?.invoke()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (currentTab != "home") {
                selectTab("home", requestFocus = true)
                return true
            }
        }
        // Deterministic tab-row navigation: when focus is on a tab, Left/Right
        // moves explicitly to the neighbouring tab instead of relying on
        // focus-search geometry (which proved unreliable on Fire OS: after a
        // fragment swap, later Right presses stopped moving the focus).
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            val focused = currentFocus
            // Focus got lost entirely (observed on Fire OS after some fragment
            // swaps, and when fast scrolling recycles the focused row before
            // its replacement is laid out): without this, the system's
            // fallback focus search jumps back to the first tab (Inicio),
            // whose focus listener switches the whole screen. Restore the
            // last content view instead; never run while a dialog owns the
            // focus. Only when that view is gone too, fall back to the tab.
            if (focused == null && !Ui.dialogShowing) {
                val frag = supportFragmentManager.findFragmentById(R.id.content)
                if (frag is FocusRestorable && frag.restoreFocus()) return true
                val restore = lastContentFocus?.takeIf { it.isAttachedToWindow }
                if (restore != null) restore.requestFocus()
                else tabViews[currentTab]?.requestFocus()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                val idx = TABS.indexOfFirst { tabViews[it.first] === focused }
                if (idx >= 0) {
                    val next = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) idx + 1 else idx - 1
                    if (next in TABS.indices) tabViews[TABS[next].first]?.requestFocus()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
