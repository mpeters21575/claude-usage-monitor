package com.ods.claudeusage.ui.intui

import com.intellij.ui.components.panels.VerticalLayout
import com.ods.claudeusage.ui.components.AdaptiveComponent
import com.ods.claudeusage.ui.design.ellipsize
import com.ods.claudeusage.ui.design.text
import com.ods.claudeusage.ui.design.textCenter
import com.ods.claudeusage.ui.design.textRight
import com.ods.claudeusage.ui.design.wrapText
import com.ods.claudeusage.ui.intui.IntUi.px
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel

/** Base for small painted widgets with hover tracking. */
abstract class HoverComponent : JComponent() {
    protected var hover = false
    init {
        isOpaque = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
        })
    }
    protected fun g2(g: Graphics): Graphics2D = (g.create() as Graphics2D).also { IntUi.setup(it) }
}

/** A line of text that wraps (or ellipsises) to its width. */
class TextLine(text: String = "", var textFont: Font = IntUi.ui(12f), var color: Color? = null, private val wrap: Boolean = false, private val lineHeight: Float = 1.4f) : AdaptiveComponent() {
    var text: String = text
        set(v) { if (field != v) { field = v; revalidate(); repaint() } }
    var tooltip: String? = null
    override val minimumWidth: Int get() = px(40)
    override fun preferredHeightFor(width: Int): Int {
        val fm = getFontMetrics(textFont)
        val lines = if (wrap) wrapText(text, width, fm).size else 1
        return lines * (textFont.size2D * lineHeight).toInt()
    }
    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try {
            val fm = g2.getFontMetrics(textFont)
            val lh = (textFont.size2D * lineHeight).toInt()
            val c = color ?: IntUi.fg
            if (wrap) {
                var y = 0
                for (line in wrapText(text, width, fm)) { g2.text(line, 0f, y + (lh + fm.ascent - fm.descent) / 2f, textFont, c); y += lh }
            } else g2.text(ellipsize(text, width, fm), 0f, (height + fm.ascent - fm.descent) / 2f, textFont, c)
        } finally { g2.dispose() }
    }
    override fun getToolTipText(event: MouseEvent): String? = tooltip ?: text.takeIf { !wrap && getFontMetrics(textFont).stringWidth(it) > width }
}

/** Left and right text on one 12px line (sub-rows, detail headers, the Thinking row). */
class SpreadLine(
    left: String = "", right: String = "",
    var leftFont: Font = IntUi.ui(12f), var rightFont: Font = IntUi.ui(12f),
    var leftColor: Color? = null, var rightColor: Color? = null,
    /** Optional second right-hand fragment drawn after [right] in [right2Font]/[right2Color] (e.g. "1.34M" + " tokens"). */
    var right2: String = "", var right2Font: Font = IntUi.ui(12f), var right2Color: Color? = null,
) : JComponent() {
    var left: String = left
        set(v) { field = v; repaint() }
    var right: String = right
        set(v) { field = v; repaint() }
    init { isOpaque = false }
    override fun getPreferredSize(): Dimension = Dimension(px(80), maxOf(getFontMetrics(leftFont).height, getFontMetrics(rightFont).height) + px(2))
    override fun getMinimumSize(): Dimension = Dimension(px(40), preferredSize.height)
    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try {
            val rfm = g2.getFontMetrics(rightFont)
            val baseline = (height + rfm.ascent - rfm.descent) / 2f
            var rightEdge = width.toFloat()
            if (right2.isNotEmpty()) {
                g2.textRight(right2, rightEdge, baseline, right2Font, right2Color ?: IntUi.info)
                rightEdge -= g2.getFontMetrics(right2Font).stringWidth(right2)
            }
            g2.textRight(right, rightEdge, baseline, rightFont, rightColor ?: IntUi.fg)
            rightEdge -= rfm.stringWidth(right)
            val lfm = g2.getFontMetrics(leftFont)
            g2.text(ellipsize(left, (rightEdge - px(8)).toInt(), lfm), 0f, baseline, leftFont, leftColor ?: IntUi.info)
        } finally { g2.dispose() }
    }
}

/** Status chip: 12px, padding 1px 6px, radius 4. */
class Chip(text: String = "", var bg: Color? = null, var fgColor: Color? = null, private val sizeCss: Float = 12f) : JComponent() {
    var text: String = text
        set(v) { if (field != v) { field = v; revalidate(); repaint() } }
    init { isOpaque = false }
    private val textFont get() = IntUi.ui(sizeCss)
    override fun getPreferredSize(): Dimension { val fm = getFontMetrics(textFont); return Dimension(fm.stringWidth(text) + px(12), fm.height + px(2)) }
    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize
    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try {
            IntUi.fillRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 4, bg ?: IntUi.blueBg)
            val fm = g2.getFontMetrics(textFont)
            g2.text(text, px(6).toFloat(), (height + fm.ascent - fm.descent) / 2f, textFont, fgColor ?: IntUi.blue)
        } finally { g2.dispose() }
    }
}

/** Segmented control: panel2 track, radius 6, padding 2; selected option panel bg + fg, others info. */
class SegControl(private val options: List<String>, private val onChange: (Int) -> Unit) : JComponent() {
    var selectedIndex = 0
        set(v) { if (v != field) { field = v; repaint(); onChange(v) } }
    private val textFont get() = IntUi.ui(12f)
    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { indexAt(e.x).takeIf { it >= 0 }?.let { selectedIndex = it } } })
    }
    private fun widths() = getFontMetrics(textFont).let { fm -> options.map { fm.stringWidth(it) + px(16) } }
    private fun indexAt(x: Int): Int { var acc = px(2); for ((i, w) in widths().withIndex()) { if (x in acc until acc + w) return i; acc += w }; return -1 }
    override fun getPreferredSize(): Dimension = Dimension(widths().sum() + px(4), getFontMetrics(textFont).height + px(6) + px(4))
    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize
    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try {
            IntUi.fillRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 6, IntUi.panel2)
            val fm = g2.getFontMetrics(textFont)
            var x = px(2)
            for ((i, w) in widths().withIndex()) {
                if (i == selectedIndex) IntUi.fillRound(g2, x.toFloat(), px(2).toFloat(), w.toFloat(), (height - px(4)).toFloat(), 4, IntUi.panel)
                g2.text(options[i], (x + px(8)).toFloat(), (height + fm.ascent - fm.descent) / 2f, textFont, if (i == selectedIndex) IntUi.fg else IntUi.info)
                x += w
            }
        } finally { g2.dispose() }
    }
}

/** Clickable stat tile: label 12 info (+ optional chevron), value 18/600; active border blue, hover border2. */
class StatTile(label: String, value: String, private val chevron: Boolean, private val onClick: () -> Unit) : HoverComponent() {
    var label = label
        set(v) { field = v; repaint() }
    var value = value
        set(v) { field = v; repaint() }
    var active = false
        set(v) { field = v; repaint() }
    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { onClick() } })
    }
    override fun getPreferredSize(): Dimension = Dimension(px(80), px(8) + px(17) + px(22) + px(8) + 2)
    override fun getMinimumSize(): Dimension = Dimension(px(60), preferredSize.height)
    override fun paintComponent(g: Graphics) {
        val g2 = g2(g)
        try {
            IntUi.fillRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 6, IntUi.panel)
            IntUi.strokeRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 6, if (active) IntUi.blue else if (hover) IntUi.border2 else IntUi.border)
            val lf = IntUi.ui(12f); val vf = IntUi.ui(18f, 600)
            val lfm = g2.getFontMetrics(lf); val vfm = g2.getFontMetrics(vf)
            val padX = px(10)
            val chevW = if (chevron) px(14) else 0
            g2.text(ellipsize(label, width - padX * 2 - chevW, lfm), padX.toFloat(), (px(8) + 1 + lfm.ascent).toFloat(), lf, IntUi.info)
            if (chevron) Icons.chevron(g2, (width - padX - px(5)).toFloat(), (px(8) + 1 + lfm.height / 2f), 10f, IntUi.info, if (active) 270.0 else 90.0)
            g2.text(ellipsize(value, width - padX * 2, vfm), padX.toFloat(), (px(8) + 1 + px(17) + vfm.ascent).toFloat(), vf, IntUi.fg)
        } finally { g2.dispose() }
    }
}

/** Panel-coloured container with border, radius 6, padding 10 12 and 8px gaps (detail panels). */
class DetailPanel(vararg parts: JComponent) : JPanel(VerticalLayout(px(8))) {
    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(px(10), px(12), px(10), px(12))
        parts.forEach(::add)
    }
    fun setParts(vararg parts: JComponent) { removeAll(); parts.forEach(::add); revalidate(); repaint() }
    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try {
            IntUi.fillRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 6, IntUi.panel)
            IntUi.strokeRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 6, IntUi.border)
        } finally { g2.dispose() }
    }
}

/** Three (or n) columns of label 12 info over value 13/500. */
class MiniStats(private val cols: Int = 3) : AdaptiveComponent() {
    class Stat(val label: String, val value: String, val tooltip: String? = null)
    var stats: List<Stat> = emptyList()
        set(v) { field = v; revalidate(); repaint() }
    var valueFont: Font = IntUi.ui(13f, 500)
    private val rowH get() = px(17) + px(18)
    override fun preferredHeightFor(width: Int): Int = if (stats.isEmpty()) 0 else ((stats.size + cols - 1) / cols) * rowH + (((stats.size + cols - 1) / cols) - 1) * px(8)
    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try {
            val gap = px(8)
            val colW = (width - gap * (cols - 1)) / cols
            val lf = IntUi.ui(12f)
            val lfm = g2.getFontMetrics(lf); val vfm = g2.getFontMetrics(valueFont)
            for ((i, s) in stats.withIndex()) {
                val x = (i % cols) * (colW + gap)
                val y = (i / cols) * (rowH + px(8))
                g2.text(ellipsize(s.label, colW, lfm), x.toFloat(), (y + lfm.ascent + 1).toFloat(), lf, IntUi.info)
                g2.text(ellipsize(s.value, colW, vfm), x.toFloat(), (y + px(17) + vfm.ascent).toFloat(), valueFont, IntUi.fg)
            }
        } finally { g2.dispose() }
    }
    override fun getToolTipText(event: MouseEvent): String? {
        if (stats.isEmpty()) return null
        val colW = (width - px(8) * (cols - 1)) / cols
        val i = (event.x / (colW + px(8))).coerceIn(0, cols - 1) + (event.y / (rowH + px(8))) * cols
        return stats.getOrNull(i)?.let { it.tooltip ?: "${it.label}: ${it.value}" }
    }
}

/** Stacked bar: 8px tall, radius 4, 2px gaps; segments proportional to value. */
class StackedBar(private val heightCss: Int = 8) : JComponent() {
    class Segment(val value: Long, val color: Color?, val title: String = "")
    var segments: List<Segment> = emptyList()
        set(v) { field = v.filter { it.value > 0 }; repaint() }
    init { isOpaque = false; toolTipText = "" }
    override fun getPreferredSize(): Dimension = Dimension(px(80), px(heightCss))
    override fun getMinimumSize(): Dimension = Dimension(px(40), px(heightCss))
    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try {
            val total = segments.sumOf { it.value }.toDouble()
            g2.clip = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), px(4).toFloat() * 2, px(4).toFloat() * 2)
            if (total <= 0) { g2.color = IntUi.panel2; g2.fillRect(0, 0, width, height); return }
            val gap = px(2).toFloat()
            val avail = width - gap * (segments.size - 1)
            var x = 0f
            for (s in segments) {
                val w = maxOf(px(2).toFloat(), (avail * s.value / total).toFloat())
                g2.color = s.color ?: IntUi.panel2
                g2.fill(Rectangle2D.Float(x, 0f, w, height.toFloat()))
                x += w + gap
            }
        } finally { g2.dispose() }
    }
    override fun getToolTipText(event: MouseEvent): String? {
        val total = segments.sumOf { it.value }.toDouble()
        if (total <= 0) return null
        val gap = px(2).toFloat(); val avail = width - gap * (segments.size - 1)
        var x = 0f
        for (s in segments) { val w = maxOf(px(2).toFloat(), (avail * s.value / total).toFloat()); if (event.x >= x && event.x <= x + w) return s.title.ifEmpty { null }; x += w + gap }
        return null
    }
}

/** 2×n legend: 8px swatch (radius 2, or outlined), label, right-aligned value. */
class LegendGrid(private val cols: Int = 2, private val rowGapCss: Int = 4, private val colGapCss: Int = 16) : AdaptiveComponent() {
    class Item(val label: String, val value: String, val color: Color?, val outline: Boolean = false, val valueInfo: String = "")
    var items: List<Item> = emptyList()
        set(v) { field = v; revalidate(); repaint() }
    private val rowH get() = px(17)
    override fun preferredHeightFor(width: Int): Int = if (items.isEmpty()) 0 else ((items.size + cols - 1) / cols).let { it * rowH + (it - 1) * px(rowGapCss) }
    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try {
            val f = IntUi.ui(12f); val fm = g2.getFontMetrics(f)
            val colW = (width - px(colGapCss) * (cols - 1)) / cols
            for ((i, it) in items.withIndex()) {
                val x = (i % cols) * (colW + px(colGapCss))
                val y = (i / cols) * (rowH + px(rowGapCss))
                val baseline = y + (rowH + fm.ascent - fm.descent) / 2f
                val sw = px(8).toFloat(); val sy = y + rowH / 2f - sw / 2
                if (it.outline) { g2.color = IntUi.border2; g2.draw(RoundRectangle2D.Float(x + 0.5f, sy + 0.5f, sw - 1, sw - 1, px(2).toFloat() * 2, px(2).toFloat() * 2)) }
                else IntUi.fillRound(g2, x.toFloat(), sy, sw, sw, 2, it.color ?: IntUi.c4)
                var right = (x + colW).toFloat()
                if (it.valueInfo.isNotEmpty()) { g2.textRight(it.valueInfo, right, baseline, f, IntUi.info); right -= fm.stringWidth(it.valueInfo) + px(4) }
                g2.textRight(it.value, right, baseline, f, IntUi.fg)
                right -= fm.stringWidth(it.value)
                g2.text(ellipsize(it.label, (right - x - sw - px(14)).toInt(), fm), x + sw + px(8), baseline, f, IntUi.fg)
            }
        } finally { g2.dispose() }
    }
}

/** Ranked rows: `[swatch] name | col1 | col2` with an optional share bar and second line under each row. */
class RankedRows(private val col1Css: Int, private val col2Css: Int, private val gapCss: Int = 6) : AdaptiveComponent() {
    class Row(
        val name: String, val col1: String, val col2: String,
        val color: Color? = null, val mono: Boolean = false, val col1Info: Boolean = false,
        /** 0..1 share drawn as a 3px bar beneath the row, in [barColor]. */
        val share: Double? = null, val barColor: Color? = null,
        /** Text drawn right of the share bar (models: "448 turns"). */
        val barLabel: String? = null,
        val tooltip: String? = null,
        val onClick: (() -> Unit)? = null,
    )
    var rows: List<Row> = emptyList()
        set(v) { field = v; revalidate(); repaint() }
    var emptyText = ""
    private val rowH get() = px(17)
    private fun rowHeight(r: Row) = rowH + (if (r.share != null) px(3) + px(5) else 0)
    override fun preferredHeightFor(width: Int): Int = if (rows.isEmpty()) (if (emptyText.isEmpty()) 0 else rowH) else rows.sumOf { rowHeight(it) } + (rows.size - 1) * px(gapCss)
    init {
        addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { rowAt(e.y)?.onClick?.invoke() } })
    }
    private fun rowAt(y: Int): Row? { var acc = 0; for (r in rows) { val h = rowHeight(r); if (y in acc until acc + h) return r; acc += h + px(gapCss) }; return null }
    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try {
            val f = IntUi.ui(12f); val fm = g2.getFontMetrics(f)
            if (rows.isEmpty()) { if (emptyText.isNotEmpty()) g2.text(emptyText, 0f, fm.ascent.toFloat() + px(2), f, IntUi.info); return }
            val c1 = px(col1Css); val c2 = px(col2Css); val gap = px(8)
            var y = 0
            for (r in rows) {
                val baseline = y + (rowH + fm.ascent - fm.descent) / 2f
                var x = 0f
                if (r.color != null) { val sw = px(8).toFloat(); IntUi.fillRound(g2, 0f, y + rowH / 2f - sw / 2, sw, sw, 2, r.color); x = sw + px(8) }
                val nameW = width - c1 - c2 - gap * 2 - x.toInt()
                val nf = if (r.mono) IntUi.mono(12f) else f
                g2.text(ellipsize(r.name, nameW, g2.getFontMetrics(nf)), x, baseline, nf, if (r.mono) IntUi.mono else IntUi.fg)
                g2.textRight(r.col1, (width - c2 - gap).toFloat(), baseline, f, if (r.col1Info) IntUi.info else IntUi.fg)
                g2.textRight(r.col2, width.toFloat(), baseline, f, if (r.col1Info) IntUi.fg else IntUi.info)
                y += rowH
                if (r.share != null) {
                    val by = y + px(2)
                    val barLeft = if (r.barLabel != null) px(16) else 0
                    val labelW = r.barLabel?.let { g2.getFontMetrics(IntUi.ui(11f)).stringWidth(it) + px(8) } ?: 0
                    val bw = width - barLeft - labelW
                    IntUi.fillRound(g2, barLeft.toFloat(), by.toFloat(), bw.toFloat(), px(3).toFloat(), 2, IntUi.panel2)
                    IntUi.fillRound(g2, barLeft.toFloat(), by.toFloat(), (bw * r.share.coerceIn(0.0, 1.0)).toFloat(), px(3).toFloat(), 2, r.barColor ?: IntUi.c1)
                    r.barLabel?.let { g2.textRight(it, width.toFloat(), by + px(3) / 2f + g2.getFontMetrics(IntUi.ui(11f)).ascent / 2f - 1, IntUi.ui(11f), IntUi.info) }
                    y += px(3) + px(5)
                }
                y += px(gapCss)
            }
        } finally { g2.dispose() }
    }
    override fun getToolTipText(event: MouseEvent): String? = rowAt(event.y)?.let { it.tooltip ?: "${it.name}: ${it.col1} ${it.col2}" }
}

/** Rows of `label (96px, info) | 4px bar | value (60px, right)`. */
class BarRows : AdaptiveComponent() {
    class Row(val label: String, val share: Double, val value: String, val color: Color?)
    var rows: List<Row> = emptyList()
        set(v) { field = v; revalidate(); repaint() }
    private val rowH get() = px(17)
    override fun preferredHeightFor(width: Int): Int = rows.size * rowH + (rows.size - 1).coerceAtLeast(0) * px(4)
    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try {
            val f = IntUi.ui(12f); val fm = g2.getFontMetrics(f)
            val labelW = px(96); val valueW = px(60); val gap = px(10)
            var y = 0
            for (r in rows) {
                val baseline = y + (rowH + fm.ascent - fm.descent) / 2f
                g2.text(ellipsize(r.label, labelW, fm), 0f, baseline, f, IntUi.info)
                val bx = labelW + gap; val bw = width - bx - gap - valueW
                val by = y + rowH / 2f - px(2)
                IntUi.fillRound(g2, bx.toFloat(), by, bw.toFloat(), px(4).toFloat(), 2, IntUi.panel2)
                IntUi.fillRound(g2, bx.toFloat(), by, maxOf(px(2).toFloat(), (bw * r.share.coerceIn(0.0, 1.0)).toFloat()), px(4).toFloat(), 2, r.color ?: IntUi.c1)
                g2.textRight(r.value, width.toFloat(), baseline, f, IntUi.fg)
                y += rowH + px(4)
            }
        } finally { g2.dispose() }
    }
}

/** 12-bar sparkline (32px) with three axis labels beneath. */
class SparkBars : AdaptiveComponent() {
    var values: List<Int> = emptyList()
        set(v) { field = v; repaint() }
    var axis: List<String> = emptyList()
        set(v) { field = v; repaint() }
    var titles: List<String> = emptyList()
    override fun preferredHeightFor(width: Int): Int = px(32) + px(6) + px(15)
    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try {
            val n = values.size.coerceAtLeast(1)
            val gap = px(3).toFloat()
            val bw = (width - gap * (n - 1)) / n
            val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
            val h = px(32).toFloat()
            for ((i, v) in values.withIndex()) {
                val bh = maxOf(h * 0.04f, h * v / max)
                g2.color = if (v == 0) IntUi.alpha(IntUi.c1, 0.25f) else IntUi.c1
                val x = i * (bw + gap)
                g2.fill(RoundRectangle2D.Float(x, h - bh, bw, bh + px(2), px(2).toFloat() * 2, px(2).toFloat() * 2))
                g2.fill(Rectangle2D.Float(x, h - minOf(bh, px(2).toFloat()), bw, minOf(bh, px(2).toFloat())))
            }
            val f = IntUi.ui(11f); val fm = g2.getFontMetrics(f)
            val baseline = h + px(6) + fm.ascent
            axis.getOrNull(0)?.let { g2.text(it, 0f, baseline, f, IntUi.info) }
            axis.getOrNull(1)?.let { g2.textCenter(it, width / 2f, baseline, f, IntUi.info) }
            axis.getOrNull(2)?.let { g2.textRight(it, width.toFloat(), baseline, f, IntUi.info) }
        } finally { g2.dispose() }
    }
    override fun getToolTipText(event: MouseEvent): String? {
        val n = values.size.coerceAtLeast(1); val gap = px(3).toFloat(); val bw = (width - gap * (n - 1)) / n
        val i = (event.x / (bw + gap)).toInt()
        return titles.getOrNull(i) ?: values.getOrNull(i)?.let { "$it turns" }
    }
}

/** "Started … ─●───●─ Active …" activity span row. */
class ActivitySpan : JComponent() {
    var start = ""
        set(v) { field = v; repaint() }
    var end = ""
        set(v) { field = v; repaint() }
    init { isOpaque = false }
    override fun getPreferredSize(): Dimension = Dimension(px(120), px(17))
    override fun getMinimumSize(): Dimension = Dimension(px(60), px(17))
    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try {
            val f = IntUi.ui(12f); val fm = g2.getFontMetrics(f)
            val baseline = (height + fm.ascent - fm.descent) / 2f
            val sw = fm.stringWidth(start); val ew = fm.stringWidth(end)
            g2.text(start, 0f, baseline, f, IntUi.info)
            g2.textRight(end, width.toFloat(), baseline, f, IntUi.fg)
            val x0 = sw + px(8); val x1 = width - ew - px(8)
            if (x1 - x0 > px(20)) {
                val cy = height / 2f
                IntUi.fillRound(g2, x0.toFloat(), cy - 1, (x1 - x0).toFloat(), px(2).toFloat(), 1, IntUi.panel2)
                g2.color = IntUi.border2; g2.fill(Ellipse2D.Float(x0.toFloat(), cy - px(3), px(6).toFloat(), px(6).toFloat()))
                g2.color = IntUi.green; g2.fill(Ellipse2D.Float((x1 - px(6)).toFloat(), cy - px(3), px(6).toFloat(), px(6).toFloat()))
            }
        } finally { g2.dispose() }
    }
}

/** Icon + monospace text row for the environment block; optional info-coloured suffix. */
class IconRow(private val icon: IconKind, text: String = "", var suffix: String = "") : JComponent() {
    var text: String = text
        set(v) { field = v; repaint() }
    var tooltip: String? = null
    init { isOpaque = false; toolTipText = "" }
    override fun getPreferredSize(): Dimension = Dimension(px(120), px(17))
    override fun getMinimumSize(): Dimension = Dimension(px(60), px(17))
    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try {
            Icons.paint(g2, icon, 0f, (height - px(14)) / 2f, px(14).toFloat(), IntUi.info)
            val f = IntUi.mono(12f); val fm = g2.getFontMetrics(f)
            val baseline = (height + fm.ascent - fm.descent) / 2f
            val x = px(14) + px(8)
            val sf = IntUi.ui(12f)
            val sw = if (suffix.isNotEmpty()) g2.getFontMetrics(sf).stringWidth(" · $suffix") else 0
            val t = ellipsize(text, width - x - sw, fm)
            g2.text(t, x.toFloat(), baseline, f, IntUi.mono)
            if (suffix.isNotEmpty()) g2.text(" · $suffix", x + fm.stringWidth(t).toFloat(), baseline, sf, IntUi.info)
        } finally { g2.dispose() }
    }
    override fun getToolTipText(event: MouseEvent): String? = tooltip ?: text
}

/** Buttons: primary (blue bg, white), outlined (border2), or ghost. Optional leading icon. */
class PillButton(text: String, private val style: Style = Style.OUTLINED, private val icon: IconKind? = null, private val heightCss: Int = 24, private val onClick: () -> Unit) : HoverComponent() {
    enum class Style { PRIMARY, OUTLINED, DANGER }
    var text = text
        set(v) { field = v; revalidate(); repaint() }
    /** Secondary info-coloured text after the label (use counts). */
    var trailing = ""
        set(v) { field = v; revalidate(); repaint() }
    var monoLabel = false
    var enabled2 = true
    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { if (enabled2) onClick() } })
    }
    private val textFont get() = if (monoLabel) IntUi.mono(12f) else IntUi.ui(12f)
    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(textFont)
        val tw = if (trailing.isNotEmpty()) getFontMetrics(IntUi.ui(12f)).stringWidth(trailing) + px(6) else 0
        return Dimension(fm.stringWidth(text) + tw + px(20) + (if (icon != null) px(16) else 0), px(heightCss))
    }
    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize
    override fun paintComponent(g: Graphics) {
        val g2 = g2(g)
        try {
            val fgc: Color
            when (style) {
                Style.PRIMARY -> { IntUi.fillRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 4, if (hover) IntUi.alpha(IntUi.blue, 0.85f) else IntUi.blue); fgc = IntUi.white }
                Style.DANGER -> { IntUi.fillRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 4, IntUi.red); fgc = IntUi.white }
                Style.OUTLINED -> { IntUi.fillRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 4, IntUi.panel); IntUi.strokeRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 4, if (hover) IntUi.blue else IntUi.border2); fgc = if (hover) IntUi.blue else IntUi.fg }
            }
            val fm = g2.getFontMetrics(textFont)
            var x = px(10).toFloat()
            if (icon != null) { Icons.paint(g2, icon, x - px(2), (height - px(12)) / 2f, px(12).toFloat(), fgc, 1.4f); x += px(16) }
            val baseline = (height + fm.ascent - fm.descent) / 2f
            g2.text(text, x, baseline, textFont, fgc)
            if (trailing.isNotEmpty()) g2.text(trailing, x + fm.stringWidth(text) + px(6), baseline, IntUi.ui(12f), IntUi.info)
        } finally { g2.dispose() }
    }
}

/** 22px square icon button with hover background. */
class IconButton(private val icon: IconKind, private val tip: String, private val onClick: () -> Unit) : HoverComponent() {
    var pressedLook = false
        set(v) { field = v; repaint() }
    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = tip
        addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { onClick() } })
    }
    override fun getPreferredSize(): Dimension = Dimension(px(22), px(22))
    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize
    override fun paintComponent(g: Graphics) {
        val g2 = g2(g)
        try {
            if (hover || pressedLook) IntUi.fillRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 4, IntUi.border2)
            Icons.paint(g2, icon, (width - px(14)) / 2f, (height - px(14)) / 2f, px(14).toFloat(), if (hover) IntUi.fg else IntUi.info)
        } finally { g2.dispose() }
    }
}

/** 26×14 switch: green when on, border2 when off, white 10px knob. */
class Switch(private val onToggle: (Boolean) -> Unit) : HoverComponent() {
    var on = true
        set(v) { field = v; repaint() }
    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = ""
        addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { onToggle(!on) } })
    }
    override fun getPreferredSize(): Dimension = Dimension(px(26), px(14))
    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize
    override fun paintComponent(g: Graphics) {
        val g2 = g2(g)
        try {
            IntUi.fillRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 7, if (on) IntUi.green else IntUi.border2)
            g2.color = IntUi.white
            val k = px(10).toFloat()
            g2.fill(Ellipse2D.Float(if (on) width - k - px(2) else px(2).toFloat(), (height - k) / 2f, k, k))
        } finally { g2.dispose() }
    }
    override fun getToolTipText(event: MouseEvent): String = if (on) "Enabled · click to disable" else "Disabled · click to enable"
}

/** Horizontal filter tabs with counts; selected tab has a 2px blue underline. */
class TabStrip(private val onPick: (Int) -> Unit) : AdaptiveComponent() {
    class Tab(val label: String, val count: Int)
    var tabs: List<Tab> = emptyList()
        set(v) { field = v; revalidate(); repaint() }
    var selectedIndex = 0
        set(v) { if (v != field) { field = v; repaint(); onPick(v) } }
    private var hoverIndex = -1
    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val m = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { indexAt(e.x).takeIf { it >= 0 }?.let { selectedIndex = it } }
            override fun mouseMoved(e: MouseEvent) { val i = indexAt(e.x); if (i != hoverIndex) { hoverIndex = i; repaint() } }
            override fun mouseExited(e: MouseEvent) { hoverIndex = -1; repaint() }
        }
        addMouseListener(m); addMouseMotionListener(m)
    }
    private fun widths(): List<Int> { val f = getFontMetrics(IntUi.ui(12f)); val c = getFontMetrics(IntUi.ui(11f)); return tabs.map { f.stringWidth(it.label) + px(4) + c.stringWidth(it.count.toString()) + px(12) } }
    private fun indexAt(x: Int): Int { var acc = 0; for ((i, w) in widths().withIndex()) { if (x in acc until acc + w) return i; acc += w + px(2) }; return -1 }
    override fun preferredHeightFor(width: Int): Int = px(5) + px(17) + px(7) + 1
    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try {
            g2.color = IntUi.border; g2.fillRect(0, height - 1, width, 1)
            val f = IntUi.ui(12f); val c = IntUi.ui(11f)
            val fm = g2.getFontMetrics(f)
            var x = 0
            for ((i, t) in tabs.withIndex()) {
                val w = widths()[i]
                val selected = i == selectedIndex
                val baseline = px(5) + (px(17) + fm.ascent - fm.descent) / 2f
                g2.text(t.label, (x + px(6)).toFloat(), baseline, f, if (selected || i == hoverIndex) IntUi.fg else IntUi.info)
                g2.text(t.count.toString(), (x + px(6) + fm.stringWidth(t.label) + px(4)).toFloat(), baseline, c, IntUi.info)
                if (selected) { g2.color = IntUi.blue; g2.fillRect(x, height - px(2), w, px(2)) }
                x += w + px(2)
            }
        } finally { g2.dispose() }
    }
}

/** 1px divider line. */
class Divider : JComponent() {
    init { isOpaque = false }
    override fun getPreferredSize(): Dimension = Dimension(px(10), 1)
    override fun getMinimumSize(): Dimension = preferredSize
    override fun paintComponent(g: Graphics) { g.color = IntUi.border; g.fillRect(0, 0, width, 1) }
}

/** Row layout helper: left stretches, right keeps its preferred size. */
fun spread(left: JComponent, right: JComponent?, gapCss: Int = 8): JPanel = JPanel(BorderLayout(px(gapCss), 0)).apply {
    isOpaque = false
    add(left, BorderLayout.CENTER)
    right?.let { add(it, BorderLayout.EAST) }
}

/** Vertical stack with a gap. */
fun stack(gapCss: Int, vararg parts: JComponent): JPanel = JPanel(VerticalLayout(px(gapCss))).apply {
    isOpaque = false
    parts.forEach(::add)
}

/** Wrapping row of components with gaps (top-5 action buttons, orchestration chain). */
class FlowRow(private val hGapCss: Int = 6, private val vGapCss: Int = 6) : JPanel() {
    init { isOpaque = false; layout = Layout() }
    private inner class Layout : java.awt.LayoutManager {
        override fun addLayoutComponent(name: String?, comp: java.awt.Component?) {}
        override fun removeLayoutComponent(comp: java.awt.Component?) {}
        override fun preferredLayoutSize(parent: java.awt.Container): Dimension {
            val w = if (parent.width > 0) parent.width else (parent.parent?.width ?: px(336))
            return Dimension(w, layoutRows(parent, w, false))
        }
        override fun minimumLayoutSize(parent: java.awt.Container): Dimension = preferredLayoutSize(parent)
        override fun layoutContainer(parent: java.awt.Container) { layoutRows(parent, parent.width, true) }
        private fun layoutRows(parent: java.awt.Container, width: Int, apply: Boolean): Int {
            var x = 0; var y = 0; var rowH = 0
            for (c in parent.components) {
                if (!c.isVisible) continue
                val d = c.preferredSize
                if (x > 0 && x + d.width > width) { x = 0; y += rowH + px(vGapCss); rowH = 0 }
                if (apply) c.setBounds(x, y, minOf(d.width, width), d.height)
                x += d.width + px(hGapCss)
                rowH = maxOf(rowH, d.height)
            }
            return y + rowH
        }
    }
}

/** Marker so the dashboard can re-layout flow rows after a width change. */
fun JComponent.relayout() { revalidate(); repaint() }

/** Draws a 1px stroke rounded rect border used by inline confirm strips. */
class Strip(private val borderColor: () -> Color, vararg parts: JComponent) : JPanel(VerticalLayout(px(6))) {
    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(px(8), px(10), px(8), px(10))
        parts.forEach(::add)
    }
    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try {
            IntUi.fillRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 6, IntUi.bg)
            g2.stroke = BasicStroke(1f)
            IntUi.strokeRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 6, borderColor())
        } finally { g2.dispose() }
    }
}

/** Non-wrapping text that sizes to its content (header controls, inline labels). */
class InlineText(text: String = "", var textFont: Font = IntUi.ui(12f), var color: Color? = null) : JComponent() {
    var text: String = text
        set(v) { if (field != v) { field = v; revalidate(); repaint() } }
    var tooltip: String? = null
    init { isOpaque = false; toolTipText = "" }
    override fun getPreferredSize(): Dimension = Dimension(getFontMetrics(textFont).stringWidth(text) + px(2), maxOf(px(20), getFontMetrics(textFont).height))
    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize
    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try { val fm = g2.getFontMetrics(textFont); g2.text(text, 0f, (height + fm.ascent - fm.descent) / 2f, textFont, color ?: IntUi.fg) } finally { g2.dispose() }
    }
    override fun getToolTipText(event: MouseEvent): String? = tooltip
}
