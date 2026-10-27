package com.mitv.player

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Add / edit a playlist (Xtream or M3U), mirroring Windows PlaylistEditView.
 *  Fields are standard Android text inputs — on the Fire TV they open the
 *  on-screen keyboard when focused and confirmed with OK. */
class PlaylistEditActivity : AppCompatActivity() {

    private var editIndex = -1
    private var kind = "xtream"

    private lateinit var kindXtreamBtn: TextView
    private lateinit var kindM3uBtn: TextView
    private lateinit var nameInput: EditText
    private lateinit var serverInput: EditText
    private lateinit var userInput: EditText
    private lateinit var passInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var epgInput: EditText
    private lateinit var xtreamBox: LinearLayout
    private lateinit var m3uBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editIndex = intent.getIntExtra("index", -1)
        val existing = if (editIndex in PlaylistStore.items.indices) PlaylistStore.items[editIndex] else null
        if (existing != null) kind = existing.kind

        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 44), Ui.dp(ctx, 28), Ui.dp(ctx, 44), Ui.dp(ctx, 28))
            setBackgroundColor(getColor(R.color.bg))
        }

        root.addView(Ui.text(ctx, if (existing == null) "Agregar lista" else "Editar lista", 26f, R.color.text, true))

        // Kind selector
        val kindRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 18); bottomMargin = Ui.dp(ctx, 6)
            }
        }
        kindXtreamBtn = kindButton("Xtream Codes") { setKind("xtream") }
        kindM3uBtn = kindButton("M3U (URL)") { setKind("m3u") }
        kindRow.addView(kindXtreamBtn)
        kindRow.addView(kindM3uBtn)
        root.addView(kindRow)

        nameInput = field("Nombre de la lista (ej: Mi proveedor)")
        root.addView(label("Nombre"))
        root.addView(nameInput)

        xtreamBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        serverInput = field("Servidor (ej: http://servidor.com:8080)")
        userInput = field("Usuario")
        passInput = field("Contraseña", password = true)
        xtreamBox.addView(label("Servidor"))
        xtreamBox.addView(serverInput)
        xtreamBox.addView(label("Usuario"))
        xtreamBox.addView(userInput)
        xtreamBox.addView(label("Contraseña"))
        xtreamBox.addView(passInput)
        root.addView(xtreamBox)

        m3uBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        urlInput = field("URL de la lista M3U")
        epgInput = field("URL de la guía EPG (opcional)")
        m3uBox.addView(label("URL de la lista"))
        m3uBox.addView(urlInput)
        m3uBox.addView(label("URL de EPG (opcional)"))
        m3uBox.addView(epgInput)
        root.addView(m3uBox)

        val saveBtn = TextView(ctx).apply {
            text = "💾 Guardar lista"
            textSize = 18f
            setTextColor(getColor(R.color.text))
            setPadding(Ui.dp(ctx, 22), Ui.dp(ctx, 12), Ui.dp(ctx, 22), Ui.dp(ctx, 12))
            background = getDrawable(R.drawable.btn_bg)
            isFocusable = true; isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 22)
            }
            setOnClickListener { save(existing) }
        }
        root.addView(saveBtn)

        if (existing != null) {
            nameInput.setText(existing.name)
            serverInput.setText(existing.server)
            userInput.setText(existing.username)
            passInput.setText(existing.password)
            urlInput.setText(existing.url)
            epgInput.setText(existing.epgUrl)
        }
        setKind(kind)
        setContentView(ScrollView(ctx).apply { addView(root) })
        nameInput.requestFocus()
    }

    private fun label(s: String): TextView = Ui.text(this, s, 14f, R.color.text_dim).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@PlaylistEditActivity, 12); bottomMargin = Ui.dp(this@PlaylistEditActivity, 4)
        }
    }

    private fun field(hint: String, password: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        textSize = 17f
        setTextColor(getColor(R.color.text))
        setHintTextColor(getColor(R.color.text_faint))
        setBackgroundColor(getColor(R.color.panel2))
        setPadding(Ui.dp(this@PlaylistEditActivity, 14), Ui.dp(this@PlaylistEditActivity, 11),
            Ui.dp(this@PlaylistEditActivity, 14), Ui.dp(this@PlaylistEditActivity, 11))
        isSingleLine = true
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun kindButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 17f
        setTextColor(getColor(R.color.text))
        setPadding(Ui.dp(this@PlaylistEditActivity, 20), Ui.dp(this@PlaylistEditActivity, 11),
            Ui.dp(this@PlaylistEditActivity, 20), Ui.dp(this@PlaylistEditActivity, 11))
        background = getDrawable(R.drawable.btn_bg)
        isFocusable = true; isClickable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = Ui.dp(this@PlaylistEditActivity, 12)
        }
        setOnClickListener { onClick() }
    }

    private fun setKind(k: String) {
        kind = k
        xtreamBox.visibility = if (k == "xtream") android.view.View.VISIBLE else android.view.View.GONE
        m3uBox.visibility = if (k == "m3u") android.view.View.VISIBLE else android.view.View.GONE
        kindXtreamBtn.alpha = if (k == "xtream") 1f else 0.55f
        kindM3uBtn.alpha = if (k == "m3u") 1f else 0.55f
    }

    private fun save(existing: Playlist?) {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) { toast("Falta el nombre de la lista"); return }
        val pl = existing ?: Playlist()
        pl.kind = kind
        pl.name = name
        if (kind == "xtream") {
            pl.server = serverInput.text.toString().trim()
            pl.username = userInput.text.toString().trim()
            pl.password = passInput.text.toString()
            if (pl.server.isEmpty() || pl.username.isEmpty()) { toast("Falta servidor o usuario"); return }
            if (!pl.server.startsWith("http")) pl.server = "http://" + pl.server
        } else {
            pl.url = urlInput.text.toString().trim()
            pl.epgUrl = epgInput.text.toString().trim()
            if (pl.url.isEmpty()) { toast("Falta la URL de la lista"); return }
        }
        var isNewActive = false
        if (existing == null) {
            PlaylistStore.add(pl)
            isNewActive = pl.isActive
        } else {
            PlaylistStore.save()
            isNewActive = pl.isActive
        }
        toast(if (isNewActive) "Lista guardada y activa. Sincroniza desde Ajustes." else "Lista guardada")
        finish()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
