package com.mitv.player

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** XMLTV guide, following Windows EpgStore.cs.
 *  The raw XML is parsed once and stored PROCESSED as a compact TSV file
 *  (channelId \t start \t stop \t title \t desc) so the ~37 MB raw XML is
 *  not kept on the Stick's 8 GB storage. Now/next lookup by tvg-id /
 *  epg_channel_id, falling back to normalized channel name. */
object EpgStore {
    private const val FILE = "epg-processed.tsv"
    private const val META = "epg-meta.txt"

    @Volatile private var byChannel: Map<String, List<EpgProgramme>> = emptyMap()
    @Volatile private var nameToId: Map<String, String> = emptyMap()
    @Volatile private var ambiguousIds: Set<String> = emptySet()
    @Volatile var updatedUtc: Long = 0

    val hasData: Boolean get() = byChannel.isNotEmpty()

    fun epgUrlFor(pl: Playlist): String {
        return if (pl.kind == "xtream") {
            val srv = pl.server.trim().trimEnd('/')
            if (srv.isEmpty()) "" else "$srv/xmltv.php?username=${Net.esc(pl.username)}&password=${Net.esc(pl.password)}"
        } else pl.epgUrl.trim()
    }

    fun refresh(pl: Playlist) {
        val url = epgUrlFor(pl)
        if (url.isEmpty()) return
        val raw = Net.maybeGunzip(Net.getBytes(url))
        if (raw.size < 100) return
        parse(String(raw, Charsets.UTF_8))
        persist()
        updatedUtc = System.currentTimeMillis()
        saveMeta()
    }

    fun loadCache() {
        try {
            val f = File(JsonFiles.dir, FILE)
            if (!f.exists()) return
            val byCh = HashMap<String, MutableList<EpgProgramme>>()
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val parts = line.split('\t')
                    if (parts.size < 5) continue
                    val p = EpgProgramme(parts[0], parts[1].toLongOrNull() ?: 0,
                        parts[2].toLongOrNull() ?: 0, parts[3], parts[4])
                    byCh.getOrPut(p.channelId) { ArrayList() }.add(p)
                }
            }
            byCh.values.forEach { it.sortBy { p -> p.startUtc } }
            byChannel = byCh
            // Rebuild name mapping lazily is not possible from TSV (no display
            // names stored); name fallback uses channel display names saved
            // in the meta sidecar when present.
            loadMetaNames()
            val meta = JsonFiles.read(META)
            if (meta != null) updatedUtc = meta.lines().firstOrNull()?.toLongOrNull() ?: 0
        } catch (e: Exception) { }
    }

    private fun persist() {
        try {
            val f = File(JsonFiles.dir, FILE)
            f.bufferedWriter().use { w ->
                for ((_, list) in byChannel) for (p in list) {
                    w.write(esc(p.channelId)); w.write("\t")
                    w.write(p.startUtc.toString()); w.write("\t")
                    w.write(p.stopUtc.toString()); w.write("\t")
                    w.write(esc(p.title)); w.write("\t")
                    w.write(esc(p.desc)); w.newLine()
                }
            }
        } catch (e: Exception) { }
    }

    private fun saveMeta() {
        // line1: updatedUtc; following lines: normalizedName \t channelId
        val sb = StringBuilder()
        sb.append(updatedUtc).append('\n')
        for ((k, v) in nameToId) sb.append(k).append('\t').append(v).append('\n')
        JsonFiles.write(META, sb.toString())
    }

    private fun loadMetaNames() {
        try {
            val meta = JsonFiles.read(META) ?: return
            val map = HashMap<String, String>()
            val lines = meta.lines()
            for (i in 1 until lines.size) {
                val parts = lines[i].split('\t')
                if (parts.size == 2) map[parts[0]] = parts[1]
            }
            nameToId = map
        } catch (e: Exception) { }
    }

    private fun esc(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun parse(xml: String) {
        val byCh = HashMap<String, MutableList<EpgProgramme>>()
        val n2id = HashMap<String, String>()
        val idNames = HashMap<String, MutableSet<String>>()
        val cutoff = System.currentTimeMillis() - 6 * 3600_000L
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        var curChannelId: String? = null
        var inChannel = false
        var progChannel = ""; var progStart = 0L; var progStop = 0L
        var inProg = false
        var curTitle: String? = null; var curDesc: String? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> { inChannel = true; curChannelId = parser.getAttributeValue(null, "id") }
                    "display-name" -> if (inChannel && curChannelId != null) {
                        val n = norm(parser.nextText())
                        if (n.isNotEmpty()) {
                            idNames.getOrPut(curChannelId) { HashSet() }.add(n)
                            if (!n2id.containsKey(n)) n2id[n] = curChannelId
                        }
                    }
                    "programme" -> {
                        inProg = true
                        progChannel = parser.getAttributeValue(null, "channel") ?: ""
                        progStart = parseTime(parser.getAttributeValue(null, "start"))
                        progStop = parseTime(parser.getAttributeValue(null, "stop"))
                        curTitle = null; curDesc = null
                    }
                    "title" -> if (inProg && curTitle == null) curTitle = parser.nextText().trim()
                    "desc" -> if (inProg && curDesc == null) curDesc = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> inChannel = false
                    "programme" -> {
                        inProg = false
                        if (progChannel.isNotEmpty() && progStop >= cutoff && progStart > 0) {
                            byCh.getOrPut(progChannel) { ArrayList() }.add(
                                EpgProgramme(progChannel, progStart, progStop, curTitle ?: "", curDesc ?: ""))
                        }
                    }
                }
            }
            event = parser.next()
        }
        byCh.values.forEach { it.sortBy { p -> p.startUtc } }
        ambiguousIds = idNames.filterValues { it.size > 1 }.keys
        byChannel = byCh
        nameToId = n2id
    }

    private val timeFmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC"); isLenient = true
    }

    @Synchronized private fun parseTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        return try {
            val t = s.trim()
            val norm = if (t.length > 14) t.substring(0, 14) + " " + t.substring(14).trim().ifEmpty { "+0000" }
                       else t + " +0000"
            timeFmt.parse(norm)?.time ?: 0
        } catch (e: Exception) { 0 }
    }

    fun norm(s: String?): String {
        val sb = StringBuilder()
        for (c in (s ?: "").lowercase()) if (c.isLetterOrDigit()) sb.append(c)
        return sb.toString()
    }

    fun programsFor(ch: LiveChannel): List<EpgProgramme>? {
        if (ch.tvgId.isNotEmpty() && !ambiguousIds.contains(ch.tvgId)) {
            byChannel[ch.tvgId]?.let { return it }
        }
        val k = norm(ch.name)
        if (k.isNotEmpty()) {
            val id = nameToId[k]
            if (id != null && !ambiguousIds.contains(id)) return byChannel[id]
        }
        return null
    }

    fun nowNext(ch: LiveChannel): Pair<EpgProgramme?, EpgProgramme?> {
        val list = programsFor(ch) ?: return null to null
        val now = System.currentTimeMillis()
        // binary search for the programme containing now
        var lo = 0; var hi = list.size - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val p = list[mid]
            if (p.startUtc <= now && now < p.stopUtc) { found = mid; break }
            else if (p.startUtc > now) hi = mid - 1 else lo = mid + 1
        }
        if (found < 0) return null to null
        return list[found] to if (found + 1 < list.size) list[found + 1] else null
    }
}
