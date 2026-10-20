package com.ods.claudeusage.ui.intui

import com.ods.claudeusage.model.SessionInfo
import com.ods.claudeusage.model.UsageSample
import com.ods.claudeusage.model.UsageWindow
import com.ods.claudeusage.model.WindowForecast
import com.ods.claudeusage.ui.Fmt
import com.ods.claudeusage.ui.components.AdaptiveComponent
import com.ods.claudeusage.ui.design.ellipsize
import com.ods.claudeusage.ui.design.text
import com.ods.claudeusage.ui.design.textCenter
import com.ods.claudeusage.ui.intui.IntUi.px
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.JComponent
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/** What the combined history + forecast chart needs, independent of whether it shows context tokens or a rate-limit window. */
interface ChartModel {
    /** Capacity in the chart's unit (tokens, or 100 for percent windows). */
    val limit: Long
    val used: Long
    val nowMs: Long
    /** (epoch ms, value), oldest first. */
    val history: List<Pair<Long, Long>>
    /** Units per minute. */
    val rate: Double
    val minutesOfHistory: Double
    /** Yellow marker threshold; 100 disables it. */
    val thresholdPercent: Int
    val min75: Double?
    val min100: Double?
    /** Window reset (epoch ms) drawn as a green marker, after which the value drops to zero. Null for context. */
    val resetMs: Long?
    /** Preferred span of the past part of the axis in minutes. */
    val pastMinutes: Double get() = max(30.0, minutesOfHistory).coerceAtMost(240.0)
    fun valueLabel(v: Long): String
}

/** Everything the context card derives from the session: numbers for the big row, the chart and the stats. */
class ContextModel(session: SessionInfo?, override val thresholdPercent: Int, now: Instant = Instant.now()) : ChartModel {
    override val limit: Long = session?.contextWindow ?: 0
    override val used: Long = session?.contextTokens ?: 0
    val model: String = Fmt.modelName(session?.model)
    val percent: Double = if (limit > 0) used * 100.0 / limit else 0.0
    override val nowMs: Long = now.toEpochMilli()
    override val resetMs: Long? = null

    /** History within the last 4 hours, (epoch ms, tokens), oldest first. */
    override val history: List<Pair<Long, Long>> = session?.contextHistory
        ?.filter { it.first.toEpochMilli() >= nowMs - 4 * 3_600_000L }
        ?.map { it.first.toEpochMilli() to it.second } ?: emptyList()

    /** Tokens per minute over the last four samples (the design's "rate"). */
    override val rate: Double = run {
        val recent = history.takeLast(4)
        if (recent.size < 2) return@run 0.0
        val dt = (recent.last().first - recent.first().first) / 60_000.0
        if (dt <= 0) 0.0 else (recent.last().second - recent.first().second) / dt
    }
    val avgRate: Double = run {
        val first = history.firstOrNull() ?: return@run 0.0
        val dt = (nowMs - first.first) / 60_000.0
        if (dt <= 0) 0.0 else (used - first.second) / dt
    }
    override val minutesOfHistory: Double = history.firstOrNull()?.let { (nowMs - it.first) / 60_000.0 } ?: 0.0
    override val min75: Double? = if (rate > 0) max(0.0, (limit * thresholdPercent / 100.0 - used) / rate) else null
    override val min100: Double? = if (rate > 0) max(0.0, (limit - used) / rate) else null

    val trend: String = when {
        rate <= 0 || avgRate <= 0 -> "Rate steady"
        rate > avgRate * 1.25 -> "Rate rising"
        rate < avgRate * 0.75 -> "Rate slowing"
        else -> "Rate steady"
    }

    val status: String = when {
        limit == 0L -> "No active session"
        percent >= thresholdPercent -> "Past $thresholdPercent% · new session"
        min75 == null -> "Rate steady"
        min75 < 15 -> "$thresholdPercent% in ${min75.roundToLong()} min"
        (min100 ?: Double.MAX_VALUE) < 60 -> "Full within the hour"
        else -> "Headroom ${durationLabel(min75)}"
    }

    override fun valueLabel(v: Long): String = Fmt.tokens(v)

    companion object {
        fun durationLabel(minutes: Double?): String {
            if (minutes == null) return "–"
            val m = minutes.roundToLong()
            return when {
                m >= 60 * 24 -> "${m / (60 * 24)} d ${(m % (60 * 24)) / 60} h"
                m >= 60 -> "${m / 60} h ${(m % 60).toString().padStart(2, '0')} min"
                else -> "$m min"
            }
        }
    }
}

/**
 * A rate-limit window (5-hour, 7-day) in the same shape: values are percent (limit 100), the history comes
 * from the chart samples, the rate from the forecast, and the window reset is a marker.
 */
class WindowModel(val window: UsageWindow, val forecast: WindowForecast?, samples: List<UsageSample>, now: Instant = Instant.now()) : ChartModel {
    override val limit: Long = 100
    override val used: Long = window.utilization.roundToLong()
    override val nowMs: Long = now.toEpochMilli()
    override val thresholdPercent: Int = 100
    override val resetMs: Long? = window.resetsAt?.toEpochMilli()
    val resetMillis = resetMs

    private val spanMs: Long = window.length?.toMillis() ?: 5 * 3_600_000L
    private val windowStartMs: Long = (resetMs ?: (nowMs + spanMs)) - spanMs

    override val history: List<Pair<Long, Long>> = samples
        .filter { it.at.toEpochMilli() in windowStartMs..nowMs && (resetMs == null || it.resets[window.key] == resetMs) }
        .mapNotNull { s -> s.windows[window.key]?.let { s.at.toEpochMilli() to it.roundToLong() } }

    /** Percent points per minute. */
    override val rate: Double = ((forecast?.ratePerHour ?: 0.0) / 60.0).coerceAtLeast(0.0)
    override val minutesOfHistory: Double = history.firstOrNull()?.let { (nowMs - it.first) / 60_000.0 } ?: 0.0
    override val pastMinutes: Double get() = max(30.0, minutesOfHistory).coerceAtMost(spanMs / 60_000.0)
    override val min75: Double? = null
    override val min100: Double? = if (rate > 0) max(0.0, (100 - window.utilization) / rate) else null

    /** Minutes until the window resets. */
    val minutesToReset: Double? = resetMs?.let { max(0.0, (it - nowMs) / 60_000.0) }
    val hitsBeforeReset: Boolean = min100 != null && minutesToReset != null && min100 < minutesToReset

    val status: String = when {
        window.utilization >= 99.5 -> "Limit reached · resets ${ContextModel.durationLabel(minutesToReset)}"
        hitsBeforeReset -> "100% in ${ContextModel.durationLabel(min100)}"
        minutesToReset != null -> "${Fmt.percent(window.utilization)} used · ${ContextModel.durationLabel(minutesToReset)} left"
        else -> "${Fmt.percent(window.utilization)} used"
    }

    override fun valueLabel(v: Long): String = "$v%"
}

/** 40px hero figure with two 12px lines beside it: `strong rest` and a second (mono) line. */
class BigNumberRow : JComponent() {
    private var hero = "–"
    private var strong = ""
    private var rest = ""
    private var second = ""
    private var secondMono = true
    init { isOpaque = false }

    fun set(hero: String, strong: String, rest: String, second: String, secondMono: Boolean = true) {
        this.hero = hero; this.strong = strong; this.rest = rest; this.second = second; this.secondMono = secondMono
        revalidate(); repaint()
    }

    fun set(m: ContextModel) = set("${m.percent.toInt()}%", Fmt.tokens(m.used), "of ${Fmt.tokens(m.limit)} tokens", m.model)

    private val numberFont get() = IntUi.ui(40f, 600).deriveFont(mapOf(java.awt.font.TextAttribute.TRACKING to -0.02f))

    /** 2px top inset + full ascent of the 40px glyphs, then room for the second line hanging under the baseline. */
    private fun rowHeight(): Int {
        val nfm = getFontMetrics(numberFont)
        val below = maxOf(nfm.descent, px(16) + getFontMetrics(IntUi.mono(11.5f)).descent)
        return px(2) + nfm.ascent + below
    }

    override fun getPreferredSize(): Dimension = Dimension(px(120), rowHeight())
    override fun getMinimumSize(): Dimension = Dimension(px(80), rowHeight())
    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try {
            val nf = numberFont
            val nfm = g2.getFontMetrics(nf)
            val baseline = (px(2) + nfm.ascent).toFloat()
            g2.text(hero, 0f, baseline, nf, IntUi.fg)
            val x = nfm.stringWidth(hero) + px(10)
            val sf = IntUi.ui(12f); val sfm = g2.getFontMetrics(sf)
            val mf = if (secondMono) IntUi.mono(11.5f) else sf
            g2.text(strong, x.toFloat(), baseline, sf, IntUi.fg)
            g2.text(" $rest", (x + sfm.stringWidth(strong)).toFloat(), baseline, sf, IntUi.info)
            g2.text(ellipsize(second, width - x, g2.getFontMetrics(mf)), x.toFloat(), baseline + px(16), mf, IntUi.info)
        } finally { g2.dispose() }
    }
}

/**
 * Combined history + forecast chart (312×88 viewBox stretched to the width) with legend chips and an
 * HTML-style time axis beneath it. Works for context tokens and for rate-limit windows.
 */
class ContextChart : AdaptiveComponent() {

    private var m: ChartModel? = null
    fun set(model: ChartModel) { m = model; repaint() }

    private val axisH get() = px(16) + px(4)
    private fun chartH(width: Int) = width * 88 / 312

    override fun preferredHeightFor(width: Int): Int = chartH(width) + axisH

    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try {
            val model = m ?: return
            val W = width.toFloat()
            val H = chartH(width).toFloat()
            val h0 = H * 4f / 88f; val h1 = H * 84f / 88f
            g2.color = IntUi.grid; g2.stroke = BasicStroke(1f)
            for (i in 0..4) { val y = h0 + (h1 - h0) * i / 4; g2.drawLine(0, y.toInt(), width, y.toInt()) }

            val past = model.pastMinutes
            val min100 = model.min100
            val resetMin = model.resetMs?.let { (it - model.nowMs) / 60_000.0 }
            var future = max(past * 2, (min100 ?: 0.0) * 1.15)
            if (resetMin != null) future = max(future, resetMin * 1.1).coerceAtMost(max(past * 2, resetMin * 1.1))
            val span = past + future
            fun xOf(minutes: Double): Float = ((past + minutes) / span * W).toFloat()
            fun yOf(v: Double): Float = if (model.limit <= 0) h1 else (h1 - (v / model.limit) * (h1 - h0)).toFloat().coerceIn(h0, h1)

            // ticks snapped to clock multiples
            val nowMin = model.nowMs / 60_000.0
            val step = listOf(15, 30, 60, 120, 180, 240, 360, 720, 1440, 2880).firstOrNull { span / it <= 5 } ?: 4320
            val zone = ZoneId.systemDefault()
            val multiDay = span > 36 * 60
            val fmt = if (multiDay) DAY_TIME else TIME
            val thresholdOn = model.thresholdPercent < 100
            val markers = listOfNotNull(xOf(0.0), if (thresholdOn) model.min75?.let { xOf(it) } else null, min100?.let { xOf(it) }, resetMin?.let { xOf(it) })
            val ticks = ArrayList<Pair<Float, String>>()
            val offsetMin = zone.rules.getOffset(Instant.ofEpochMilli(model.nowMs)).totalSeconds / 60.0
            var t = ceil((nowMin - past + offsetMin) / step) * step - offsetMin
            while (t <= nowMin + future) {
                val x = xOf(t - nowMin)
                if (markers.none { abs(it - x) < px(26) } && x >= px(14) && x <= W - px(14)) ticks += x to fmt.format(Instant.ofEpochMilli((t * 60_000).toLong()).atZone(zone))
                t += step
            }
            g2.color = IntUi.grid
            for ((x, _) in ticks) g2.drawLine(x.toInt(), h0.toInt(), x.toInt(), h1.toInt())

            val nowX = xOf(0.0)
            val nowY = yOf(model.used.toDouble())
            val hist = model.history.map { (ms, v) -> xOf((ms - model.nowMs) / 60_000.0) to yOf(v.toDouble()) }
            if (hist.isNotEmpty()) {
                val area = Path2D.Float(); area.moveTo(hist.first().first, h1)
                hist.forEach { (x, y) -> area.lineTo(x, y) }
                area.lineTo(nowX, nowY); area.lineTo(nowX, h1); area.closePath()
                g2.color = IntUi.alpha(IntUi.blue, 0.3f); g2.fill(area)
            }
            // forecast: rise at the rate to the threshold and 100 %, drop to zero at the reset
            val x75 = if (thresholdOn) model.min75?.let { xOf(it) } else null
            val x100 = min100?.let { xOf(it) }
            val resetX = resetMin?.let { xOf(it) }
            val fc = Path2D.Float(); fc.moveTo(nowX, nowY)
            val limitAt = { minutes: Double -> yOf(model.used + model.rate * minutes) }
            val endOfRise = listOfNotNull(min100, resetMin).minOrNull()
            if (model.rate > 0 && endOfRise != null) {
                if (x75 != null && model.min75!! < endOfRise) fc.lineTo(x75, yOf(model.limit * model.thresholdPercent / 100.0))
                fc.lineTo(xOf(endOfRise), limitAt(endOfRise))
                if (resetMin != null && endOfRise >= resetMin - 1e-6) { fc.lineTo(resetX!!, h1) ; fc.lineTo(W, h1) } else fc.lineTo(W, h0)
            } else if (resetMin != null) { fc.lineTo(resetX!!, nowY); fc.lineTo(resetX, h1); fc.lineTo(W, h1) } else fc.lineTo(W, nowY)
            val fcArea = Path2D.Float(fc); fcArea.lineTo(W, h1); fcArea.lineTo(nowX, h1); fcArea.closePath()
            g2.color = IntUi.alpha(IntUi.blue, 0.1f); g2.fill(fcArea)

            val yThr = yOf(model.limit * model.thresholdPercent / 100.0)
            if (thresholdOn) {
                g2.color = IntUi.yellow; g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(px(3f), px(3f)), 0f)
                g2.drawLine(0, yThr.toInt(), width, yThr.toInt())
            }
            if (hist.isNotEmpty()) {
                val line = Path2D.Float(); line.moveTo(hist.first().first, hist.first().second)
                hist.drop(1).forEach { (x, y) -> line.lineTo(x, y) }
                line.lineTo(nowX, nowY)
                g2.color = IntUi.blue; g2.stroke = BasicStroke(px(2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g2.draw(line)
            }
            g2.color = IntUi.blue; g2.stroke = BasicStroke(px(1.5f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(px(4f), px(3f)), 0f); g2.draw(fc)
            // now line + marker
            g2.color = IntUi.info; g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(px(2f), px(3f)), 0f)
            g2.drawLine(nowX.toInt(), h0.toInt(), nowX.toInt(), h1.toInt())
            // reset marker
            if (resetX != null && resetX <= W) {
                g2.color = IntUi.green; g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(px(2f), px(3f)), 0f)
                g2.drawLine(resetX.toInt(), h0.toInt(), resetX.toInt(), h1.toInt())
            }
            val r = px(3.5f)
            g2.color = IntUi.bg; g2.fill(Ellipse2D.Float(nowX - r, nowY - r, r * 2, r * 2))
            g2.color = IntUi.blue; g2.stroke = BasicStroke(px(2f)); g2.draw(Ellipse2D.Float(nowX - r, nowY - r, r * 2, r * 2))
            val hits100 = min100 != null && (resetMin == null || min100 < resetMin)
            if (x75 != null && hits100) { g2.color = IntUi.yellow; g2.fill(Ellipse2D.Float(x75 - r, yThr - r, r * 2, r * 2)) }
            if (x100 != null && hits100) { g2.color = IntUi.red; g2.fill(Ellipse2D.Float(x100 - r, h0 - r, r * 2, r * 2)) }
            if (resetX != null && resetX <= W) { g2.color = IntUi.green; g2.fill(Ellipse2D.Float(resetX - r, h1 - r, r * 2, r * 2)) }

            // legend chips
            val lf = IntUi.ui(11f); val lfm = g2.getFontMetrics(lf)
            fun chip(text: String, y: Float, color: java.awt.Color) {
                val w = lfm.stringWidth(text) + px(6)
                IntUi.fillRound(g2, px(2).toFloat(), y, w.toFloat(), lfm.height.toFloat(), 2, IntUi.panel)
                g2.text(text, (px(2) + px(3)).toFloat(), y + lfm.ascent, lf, color)
            }
            chip(if (model.limit == 100L) "100% · limit" else "100% · full", px(7) - lfm.height / 2f + 1, IntUi.info)
            if (thresholdOn) chip("${model.thresholdPercent}% · new session", px(27) - lfm.height / 2f + 1, IntUi.yellow)
            else if (resetX != null) chip("reset · ${fmt.format(Instant.ofEpochMilli(model.resetMs!!).atZone(zone))}", px(27) - lfm.height / 2f + 1, IntUi.green)

            // axis labels: anchored, pushed apart (6px gap), clamped
            val ay = H + px(4) + px(2)
            val af = IntUi.ui(11f); val afm = g2.getFontMetrics(af)
            val baseline = ay + afm.ascent
            class AxisLabel(var x: Float, val text: String, val color: java.awt.Color, val font: java.awt.Font) {
                val w = g2.getFontMetrics(font).stringWidth(text).toFloat()
            }
            val labels = ArrayList<AxisLabel>()
            for ((x, s) in ticks) labels += AxisLabel(x, s, IntUi.info, af)
            labels += AxisLabel(nowX, "now", IntUi.fg, IntUi.ui(11f, 500))
            if (x75 != null && hits100 && model.min75 != null) labels += AxisLabel(x75, fmt.format(Instant.ofEpochMilli(model.nowMs + (model.min75!! * 60_000).toLong()).atZone(zone)), IntUi.yellow, af)
            if (x100 != null && hits100 && min100 != null) labels += AxisLabel(x100, fmt.format(Instant.ofEpochMilli(model.nowMs + (min100 * 60_000).toLong()).atZone(zone)), IntUi.red, af)
            if (resetX != null && resetX <= W) labels += AxisLabel(resetX, fmt.format(Instant.ofEpochMilli(model.resetMs!!).atZone(zone)), IntUi.green, af)
            labels.sortBy { it.x }
            val gap = px(6).toFloat()
            for (l in labels) l.x = l.x.coerceIn(l.w / 2f, W - l.w / 2f)
            for (i in 1 until labels.size) {
                val prev = labels[i - 1]; val cur = labels[i]
                val minX = prev.x + prev.w / 2f + gap + cur.w / 2f
                if (cur.x < minX) cur.x = minX
            }
            for (i in labels.indices.reversed()) {
                val l = labels[i]
                val maxX = if (i == labels.lastIndex) W - l.w / 2f else labels[i + 1].x - labels[i + 1].w / 2f - gap - l.w / 2f
                if (l.x > maxX) l.x = maxX
            }
            for (l in labels) if (l.x - l.w / 2f >= -1f) g2.textCenter(l.text, l.x, baseline, l.font, l.color)
        } finally { g2.dispose() }
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val model = m ?: return null
        return "${model.valueLabel(model.used)} of ${model.valueLabel(model.limit)}; rate ${String.format(Locale.US, "%.1f", model.rate)} ${if (model.limit == 100L) "%/min" else "tokens/min"}"
    }

    companion object {
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)

        /** Minutes until [t], or null. */
        fun minutesUntil(t: Instant?, now: Instant): Double? = t?.let { Duration.between(now, it).toMillis() / 60_000.0 }
    }
}
