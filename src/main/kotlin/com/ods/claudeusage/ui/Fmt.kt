package com.ods.claudeusage.ui

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object Fmt {
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)

    fun tokens(n: Long?): String {
        if (n == null) return "–"
        val a = abs(n)
        return when {
            a >= 1_000_000_000 -> String.format(Locale.ENGLISH, "%.2fB", n / 1e9)
            a >= 10_000_000 -> String.format(Locale.ENGLISH, "%.1fM", n / 1e6)
            a >= 1_000_000 -> String.format(Locale.ENGLISH, "%.2fM", n / 1e6)
            a >= 10_000 -> String.format(Locale.ENGLISH, "%.0fk", n / 1e3)
            a >= 1_000 -> String.format(Locale.ENGLISH, "%.1fk", n / 1e3)
            else -> n.toString()
        }
    }

    fun tokensExact(n: Long): String = String.format(Locale.ENGLISH, "%,d", n)

    fun percent(p: Double?): String = if (p == null) "–" else if (p >= 10) "${p.roundToInt()}%" else String.format(Locale.ENGLISH, "%.1f%%", p)

    fun rate(perHour: Double): String = String.format(Locale.ENGLISH, "%.1f%%/h", perHour)

    fun clock(t: Instant?): String {
        if (t == null) return "–"
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val sameDay = t.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
        return if (sameDay) CLOCK.format(t.atZone(zone)) else DAY_CLOCK.format(t.atZone(zone))
    }

    /** "in 2h 14m" / "3m ago". */
    fun relative(t: Instant?, now: Instant = Instant.now()): String {
        if (t == null) return "–"
        val d = Duration.between(now, t)
        val future = !d.isNegative
        val text = duration(d.abs())
        return if (future) "in $text" else "$text ago"
    }

    fun duration(d: Duration): String {
        val totalMinutes = d.toMinutes()
        return when {
            d.seconds < 60 -> "${d.seconds}s"
            totalMinutes < 60 -> "${totalMinutes}m"
            totalMinutes < 60 * 24 -> "${totalMinutes / 60}h ${totalMinutes % 60}m"
            else -> "${totalMinutes / (60 * 24)}d ${(totalMinutes % (60 * 24)) / 60}h"
        }
    }

    fun shortId(id: String): String = if (id.length > 8) id.substring(0, 8) else id

    fun modelName(model: String?): String {
        if (model == null) return "unknown"
        return model.removePrefix("claude-").replace(Regex("-\\d{8}$"), "")
    }
}
