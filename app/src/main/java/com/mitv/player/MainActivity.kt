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

/** A fragment that handles the remote's Back key itself by moving focus up
 *  one level (column -> parent category/menu) instead of leaving the tab.
 *  True when the key was consumed. */
interface BackToParentHandler {
    fun handleBack(): Boolean
}

/** True when [view] is [ancestor] or lives inside it. */
internal fun isDescendantOf(view: View?, ancestor: View): Boolean {
    var v = view
    while (v != null) {
        if (v === ancestor) return true
        v = v.parent as? View
    }
    return false
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
    /** Set immediately before every EXPLICIT tab focus request (focusTab,
     *  selectTab with requestFocus, Left/Right tab moves, tab clicks).
     *  A tab that gains focus WITHOUT it got there by fallback — the
     *  framework returning focus to the first focusable view after the
     *  focused row was detached between keys (proven 2026-11-04 by live
     *  focus logging: focus went null -> Inicio tab 34 ms after the last
     *  Down, with no key pressed). Such a gain must never switch screens;
     *  focus goes back to the content instead. */
    private var tabFocusExpected = false

    private fun clearTabFocusFlagSoon() {
        b.tabBar.post { tabFocusExpected = false }
    }

    /** Send focus back to the content view that lost it (last focused
     *  content view, else the fragment's position restore); only when
     *  there is no content view left does the current tab keep focus. */
    private fun restoreContentFocus() {
        val restore = lastContentFocus?.takeIf { it.isAttachedToWindow }
        if (restore != null) { restore.requestFocus(); return }
        val frag = supportFragmentManager.findFragmentById(R.id.content)
        if (frag is FocusRestorable && frag.restoreFocus()) return
        tabFocusExpected = true
        tabViews[currentTab]?.requestFocus()
        clearTabFocusFlagSoon()
    }

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
                setOnClickListener {
                    tabFocusExpected = true
                    selectTab(key, requestFocus = false)
                    clearTabFocusFlagSoon()
                }
                setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        val expected = tabFocusExpected
                        tabFocusExpected = false
                        if (expected) {
                            selectTab(key, requestFocus = false)
                            b.tabScroll.post {
                                b.tabScroll.smoothScrollTo(
                                    (view.left - Ui.dp(this@MainActivity, 24)).coerceAtLeast(0), 0)
                            }
                        } else if (!Ui.dialogShowing) {
                            // Fallback gain (focused row detached between
                            // keys): never switch screens on it. Send focus
                            // back to the content that lost it.
                            restoreContentFocus()
                        }
                    }
                }
            }
            tabViews[key] = tv
            b.tabBar.addView(tv)
        }

        selectTab("home", requestFocus = false)
        tabFocusExpected = true
        tabViews["home"]?.requestFocus()
        clearTabFocusFlagSoon()

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
        tabFocusExpected = true
        tabViews[key]?.requestFocus()
        clearTabFocusFlagSoon()
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
        if (requestFocus) {
            tabFocusExpected = true
            tabViews[key]?.requestFocus()
            clearTabFocusFlagSoon()
        }
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
            // Back climbs one level inside the current screen first
            // (column -> category -> tab bar); only when the fragment has
            // no parent step does Back leave for Inicio. Never while a
            // dialog owns the focus (it dismisses itself).
            if (!Ui.dialogShowing) {
                val frag = supportFragmentManager.findFragmentById(R.id.content)
                if (frag is BackToParentHandler && frag.handleBack()) return true
            }
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
                else {
                    tabFocusExpected = true
                    tabViews[currentTab]?.requestFocus()
                    clearTabFocusFlagSoon()
                }
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                val idx = TABS.indexOfFirst { tabViews[it.first] === focused }
                if (idx >= 0) {
                    val next = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) idx + 1 else idx - 1
                    if (next in TABS.indices) {
                        tabFocusExpected = true
                        tabViews[TABS[next].first]?.requestFocus()
                        clearTabFocusFlagSoon()
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
