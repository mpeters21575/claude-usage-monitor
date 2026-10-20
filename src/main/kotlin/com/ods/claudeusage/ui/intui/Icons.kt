package com.ods.claudeusage.ui.intui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D

/** Lucide-style 16-unit line icons from the design, painted at any size. */
enum class IconKind { CHEVRON, SKILL, AGENT, COMMAND, HOOK, MCP, BRANCH, FOLDER, TERMINAL, EXTERNAL, MORE, PLUS, ARROW, FILE }

object Icons {

    /** Paints [kind] in a box of [size] px at (x, y). Stroke width in CSS px. */
    fun paint(g2: Graphics2D, kind: IconKind, x: Float, y: Float, size: Float, color: Color, strokeCss: Float = 1.2f) {
        val s = size / 16f
        val g = g2.create() as Graphics2D
        try {
            IntUi.setup(g)
            g.translate(x.toDouble(), y.toDouble())
            g.scale(s.toDouble(), s.toDouble())
            g.color = color
            g.stroke = BasicStroke(strokeCss / s * IntUi.px(1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            when (kind) {
                IconKind.CHEVRON -> g.draw(path(3.5f, 2f, 6.5f, 5f, 3.5f, 8f).also { }, scale = 1.6f)
                IconKind.SKILL -> {
                    val p = Path2D.Float(); p.moveTo(8.0, 1.5); p.lineTo(9.8, 5.5); p.lineTo(14.0, 6.0); p.lineTo(10.9, 8.9); p.lineTo(11.7, 13.2); p.lineTo(8.0, 11.1); p.lineTo(4.3, 13.2); p.lineTo(5.1, 8.9); p.lineTo(2.0, 6.0); p.lineTo(6.2, 5.5); p.closePath()
                    g.fill(p)
                }
                IconKind.AGENT -> { g.draw(java.awt.geom.Rectangle2D.Float(4f, 6.5f, 8f, 6f)); g.draw(line(8f, 2f, 8f, 4.5f)); g.draw(line(6.5f, 9.5f, 6.5f, 9.6f)); g.draw(line(9.5f, 9.5f, 9.5f, 9.6f)); g.draw(line(2f, 8.5f, 2f, 10.5f)); g.draw(line(14f, 8.5f, 14f, 10.5f)) }
                IconKind.COMMAND -> { g.draw(path(2f, 3.5f, 6f, 7.5f, 2f, 11.5f)); g.draw(line(8f, 12.5f, 14f, 12.5f)) }
                IconKind.HOOK -> { val p = Path2D.Float(); p.moveTo(5.0, 2.5); p.lineTo(5.0, 8.5); p.curveTo(5.0, 10.2, 6.3, 11.5, 8.0, 11.5); p.curveTo(9.7, 11.5, 11.0, 10.2, 11.0, 8.5); p.lineTo(11.0, 4.0); g.draw(p); g.draw(Ellipse2D.Float(7.5f, 10.5f, 3f, 3f)) }
                IconKind.MCP -> { g.draw(java.awt.geom.Rectangle2D.Float(2.5f, 5.5f, 11f, 5f)); g.draw(line(5f, 8f, 5.01f, 8f)); g.draw(line(7f, 8f, 7.01f, 8f)); g.draw(line(13.5f, 8f, 14f, 8f)); g.draw(line(2f, 8f, 2.5f, 8f)) }
                IconKind.BRANCH -> { g.draw(Ellipse2D.Float(2.7f, 1.7f, 3.6f, 3.6f)); g.draw(Ellipse2D.Float(2.7f, 10.7f, 3.6f, 3.6f)); g.draw(Ellipse2D.Float(9.7f, 3.7f, 3.6f, 3.6f)); g.draw(line(4.5f, 5.3f, 4.5f, 10.7f)); val p = Path2D.Float(); p.moveTo(11.5, 7.3); p.curveTo(11.5, 9.8, 9.0, 10.3, 4.5, 10.5); g.draw(p) }
                IconKind.FOLDER -> { val p = Path2D.Float(); p.moveTo(1.5, 4.5); p.lineTo(5.5, 4.5); p.lineTo(7.0, 6.0); p.lineTo(14.5, 6.0); p.lineTo(14.5, 13.0); p.lineTo(1.5, 13.0); p.closePath(); g.draw(p) }
                IconKind.TERMINAL -> { g.draw(java.awt.geom.Rectangle2D.Float(3f, 2.5f, 10f, 11f)); g.draw(path(5.5f, 6f, 7.5f, 8f, 5.5f, 10f)); g.draw(line(8.5f, 10f, 11f, 10f)) }
                IconKind.EXTERNAL -> { g.draw(path(9f, 2.5f, 13.5f, 2.5f, 13.5f, 7f)); g.draw(line(13.5f, 2.5f, 7.5f, 8.5f)); val p = Path2D.Float(); p.moveTo(12.0, 9.5); p.lineTo(12.0, 13.0); p.quadTo(12.0, 13.5, 11.5, 13.5); p.lineTo(3.0, 13.5); p.quadTo(2.5, 13.5, 2.5, 13.0); p.lineTo(2.5, 4.5); p.quadTo(2.5, 4.0, 3.0, 4.0); p.lineTo(6.5, 4.0); g.draw(p) }
                IconKind.MORE -> { for (cx in listOf(3.5f, 8f, 12.5f)) g.fill(Ellipse2D.Float(cx - 1.3f, 6.7f, 2.6f, 2.6f)) }
                IconKind.PLUS -> { g.stroke = BasicStroke(1.6f / s * IntUi.px(1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(line(8f, 3f, 8f, 13f)); g.draw(line(3f, 8f, 13f, 8f)) }
                IconKind.ARROW -> { g.draw(line(3f, 8f, 7f, 8f)); g.draw(path(9f, 4f, 13f, 8f, 9f, 12f)) }
                IconKind.FILE -> { val p = Path2D.Float(); p.moveTo(4.0, 1.5); p.lineTo(9.5, 1.5); p.lineTo(13.0, 5.0); p.lineTo(13.0, 14.5); p.lineTo(4.0, 14.5); p.closePath(); g.draw(p); g.draw(path(9.5f, 1.5f, 9.5f, 5f, 13f, 5f)) }
            }
        } finally {
            g.dispose()
        }
    }

    /** Chevron for section headers and tiles: 10px, rotated by [degrees]. */
    fun chevron(g2: Graphics2D, cx: Float, cy: Float, sizeCss: Float, color: Color, degrees: Double) {
        val g = g2.create() as Graphics2D
        try {
            IntUi.setup(g)
            val size = IntUi.px(sizeCss)
            g.translate(cx.toDouble(), cy.toDouble())
            g.rotate(Math.toRadians(degrees))
            g.color = color
            g.stroke = BasicStroke(IntUi.px(1.4f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val k = (size / 10f).toDouble()
            val p = Path2D.Float(); p.moveTo(-1.5 * k, -3 * k); p.lineTo(1.5 * k, 0.0); p.lineTo(-1.5 * k, 3 * k)
            g.draw(p)
        } finally {
            g.dispose()
        }
    }

    private fun Graphics2D.draw(p: Path2D.Float, scale: Float) { val g = this; g.draw(p) }

    private fun path(vararg xy: Float): Path2D.Float {
        val p = Path2D.Float()
        p.moveTo(xy[0].toDouble(), xy[1].toDouble())
        var i = 2
        while (i < xy.size) { p.lineTo(xy[i].toDouble(), xy[i + 1].toDouble()); i += 2 }
        return p
    }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float) = java.awt.geom.Line2D.Float(x1, y1, x2, y2)
}
