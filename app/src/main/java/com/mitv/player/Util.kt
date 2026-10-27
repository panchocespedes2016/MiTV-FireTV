package com.mitv.player

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Util {
    /** Accent/case-insensitive normalization (Windows search behaviour). */
    fun fold(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        return n.replace(Regex("\\p{Mn}+"), "")
    }

    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    fun time(ms: Long): String = if (ms <= 0) "" else hm.format(Date(ms))

    fun durationText(ms: Long): String {
        if (ms <= 0) return ""
        val totalSec = ms / 1000
        val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Windows rule: sports categories contain "sport" or "deporte". */
    fun isSportCategory(name: String): Boolean {
        val n = fold(name)
        return n.contains("sport") || n.contains("deporte")
    }

    fun minutesLeft(stopUtc: Long): Long = (stopUtc - System.currentTimeMillis()) / 60000

    private val utcHm = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }
}
