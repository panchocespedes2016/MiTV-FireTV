package com.mitv.player

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Ajustes: only real settings (playlists, sync, image cache, EPG, about).
 *  No decorative controls, per the user's MiTV rule. */
class SettingsFragment : Fragment() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var rootBox: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val scroll = ScrollView(requireContext())
        rootBox = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rootBox)
        render()
        return scroll
    }

    override fun onResume() {
        super.onResume()
        if (::rootBox.isInitialized) render()
    }

    private fun render() {
        val ctx = requireContext()
        // Preserve focus across the rebuild. render() runs on every onResume;
        // removing the focused row without restoring focus makes Fire OS fall
        // back to the window's first focusable view (the Inicio tab), whose
        // focus listener then yanks the user back home. And never STEAL focus
        // when it is outside the list (e.g. on the Ajustes tab itself).
        var focusIdx = -1
        for (i in 0 until rootBox.childCount) {
            if (rootBox.getChildAt(i).isFocused) { focusIdx = i; break }
        }
        rootBox.removeAllViews()

        // ---- Listas IPTV ----
        rootBox.addView(Ui.sectionTitle(ctx, "Listas IPTV", "— la activa se usa en toda la app"))
        if (PlaylistStore.items.isEmpty()) {
            rootBox.addView(Ui.text(ctx, "No hay ninguna lista todavía.", 16f, R.color.text_dim))
        }
        for (pl in PlaylistStore.items) {
            val detail = if (pl.kind == "xtream") pl.server else pl.url
            val row = settingRow(ctx,
                "${if (pl.isActive) "● ACTIVA  " else ""}${pl.name}",
                "${if (pl.kind == "xtream") "XTREAM" else "M3U"} · $detail") {
                playlistActions(pl)
            }
            rootBox.addView(row)
        }
        rootBox.addView(settingRow(ctx, "＋ Agregar lista", "Xtream Codes o M3U") {
            startActivity(Intent(ctx, PlaylistEditActivity::class.java))
        })

        // ---- Sincronización ----
        rootBox.addView(Ui.sectionTitle(ctx, "Contenido", "").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(ctx, 18)
        })
        val upd = if (ContentStore.updatedUtc > 0)
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ContentStore.updatedUtc)) else "nunca"
        rootBox.addView(settingRow(ctx, "Sincronizar ahora",
            "${ContentStore.channels.size} canales · ${ContentStore.movies.size} películas · ${ContentStore.series.size} series · Última: $upd") {
            (activity as? MainActivity)?.syncContent(showToast = true) { render() }
        })

        // ---- EPG ----
        rootBox.addView(Ui.sectionTitle(ctx, "Guía (EPG)", "").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(ctx, 18)
        })
        val epgUpd = if (EpgStore.updatedUtc > 0)
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(EpgStore.updatedUtc)) else "nunca"
        rootBox.addView(settingRow(ctx, "Actualizar guía ahora", "Última actualización: $epgUpd") {
            val pl = PlaylistStore.active
            if (pl == null) {
                Toast.makeText(ctx, "No hay lista activa", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "Descargando guía…", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try { EpgStore.refresh(pl); true } catch (e: Exception) { false }
                    }
                    Toast.makeText(ctx, if (ok) "Guía actualizada" else "No se pudo actualizar la guía", Toast.LENGTH_LONG).show()
                    render()
                }
            }
        })

        // ---- Caché de imágenes ----
        rootBox.addView(Ui.sectionTitle(ctx, "Caché de imágenes", "— límite 500 MB").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(ctx, 18)
        })
        val cacheDir = File(ctx.cacheDir, "glide_images")
        val usedMb = dirSize(cacheDir) / (1024 * 1024)
        rootBox.addView(settingRow(ctx, "Borrar caché de imágenes", "En uso: $usedMb MB — las imágenes se vuelven a descargar al verlas") {
            scope.launch {
                withContext(Dispatchers.IO) { Glide.get(ctx).clearDiskCache() }
                Glide.get(ctx).clearMemory()
                Toast.makeText(ctx, "Caché borrada", Toast.LENGTH_SHORT).show()
                render()
            }
        })

        // ---- Acerca de ----
        rootBox.addView(Ui.sectionTitle(ctx, "Acerca de", "").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(ctx, 18)
        })
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        rootBox.addView(Ui.text(ctx, "MiTV para Fire TV · Versión ${pi.versionName} (${pi.versionCode})\nPaquete: ${ctx.packageName}", 15f, R.color.text_dim))

        if (focusIdx >= 0 && rootBox.childCount > 0) {
            rootBox.getChildAt(focusIdx.coerceAtMost(rootBox.childCount - 1))?.requestFocus()
        }
    }

    private fun playlistActions(pl: Playlist) {
        val ctx = requireContext()
        val options = arrayListOf<String>()
        if (!pl.isActive) options.add("Marcar como activa")
        options.add("Editar")
        options.add("Eliminar")
        AlertDialog.Builder(ctx)
            .setTitle(pl.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Marcar como activa" -> {
                        PlaylistStore.setActive(pl)
                        ContentStore.clear()
                        render()
                        (activity as? MainActivity)?.syncContent(showToast = true) { render() }
                    }
                    "Editar" -> startActivity(Intent(ctx, PlaylistEditActivity::class.java).apply {
                        putExtra("index", PlaylistStore.items.indexOf(pl))
                    })
                    "Eliminar" -> {
                        AlertDialog.Builder(ctx)
                            .setTitle("Eliminar lista")
                            .setMessage("¿Eliminar «${pl.name}»? Esta acción no se puede deshacer.")
                            .setPositiveButton("Eliminar") { _, _ ->
                                val wasActive = pl.isActive
                                PlaylistStore.remove(pl)
                                if (wasActive) ContentStore.clear()
                                render()
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun settingRow(ctx: android.content.Context, title: String, sub: String, onClick: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(ctx, 9)
            }
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 12), Ui.dp(ctx, 16), Ui.dp(ctx, 12))
            background = ctx.getDrawable(R.drawable.focus_bg)
            isFocusable = true; isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(Ui.text(ctx, title, 17f, R.color.text, true))
        if (sub.isNotEmpty()) {
            val s = Ui.text(ctx, sub, 13f, R.color.text_dim)
            s.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 3) }
            row.addView(s)
        }
        return row
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }
}
