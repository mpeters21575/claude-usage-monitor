package com.ods.claudeusage.ui.design

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.geom.Line2D

/** Small drawing helpers shared by the design components. All coordinates are device pixels. */

internal fun Graphics2D.text(s: String, x: Float, baseline: Float, font: Font, color: Color) {
    this.font = font
    this.color = color
    drawString(s, x, baseline)
}

internal fun Graphics2D.textRight(s: String, right: Float, baseline: Float, font: Font, color: Color) {
    this.font = font
    val w = fontMetrics.stringWidth(s)
    this.color = color
    drawString(s, right - w, baseline)
}

internal fun Graphics2D.textCenter(s: String, cx: Float, baseline: Float, font: Font, color: Color) {
    this.font = font
    val w = fontMetrics.stringWidth(s)
    this.color = color
    drawString(s, cx - w / 2f, baseline)
}

internal fun Graphics2D.hairline(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, dash: FloatArray? = null, width: Float = 1f) {
    this.color = color
    stroke = if (dash == null) BasicStroke(width) else BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f)
    draw(Line2D.Float(x1, y1, x2, y2))
}

internal fun ellipsize(s: String, maxWidth: Int, fm: FontMetrics): String {
    if (maxWidth <= 0) return ""
    if (fm.stringWidth(s) <= maxWidth) return s
    var t = s
    while (t.isNotEmpty() && fm.stringWidth("$t…") > maxWidth) t = t.dropLast(1)
    return t.trimEnd() + "…"
}

/** Greedy word wrap. */
internal fun wrapText(s: String, maxWidth: Int, fm: FontMetrics): List<String> {
    if (maxWidth <= 0) return listOf(s)
    val out = ArrayList<String>()
    var line = ""
    for (word in s.split(' ')) {
        val candidate = if (line.isEmpty()) word else "$line $word"
        if (fm.stringWidth(candidate) <= maxWidth || line.isEmpty()) {
            line = candidate
            // hard-break overlong single words
            while (fm.stringWidth(line) > maxWidth && line.length > 1) {
                var cut = line.length
                while (cut > 1 && fm.stringWidth(line.substring(0, cut)) > maxWidth) cut--
                out += line.substring(0, cut)
                line = line.substring(cut)
            }
        } else {
            out += line
            line = word
        }
    }
    out += line
    return out
}

/** Approximate CSS line-height: distance between baselines for a font at the given multiplier. */
internal fun lineHeight(fm: FontMetrics, multiplier: Float): Int = (fm.font.size2D * multiplier).toInt()

/** Public aliases for callers outside this package. */
fun ellipsizeFor(s: String, maxWidth: Int, fm: FontMetrics): String = ellipsize(s, maxWidth, fm)
fun wrapFor(s: String, maxWidth: Int, fm: FontMetrics): List<String> = wrapText(s, maxWidth, fm)
