package com.ods.claudeusage.ui.intui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.TextAttribute
import java.awt.geom.RoundRectangle2D

/**
 * Int UI tokens for the 1.1 design. Colours come from the IDE theme where a named key exists; the fallbacks
 * are the Int UI light / dark reference values from the handoff, so Darcula and custom themes still look right.
 */
object IntUi {

    // ---- colours ---------------------------------------------------------------------------------------------

    val bg: Color get() = JBColor.namedColor("ToolWindow.background", JBColor(0xF7F8FA, 0x1E1F22))
    val panel: Color get() = JBColor.namedColor("Panel.background", JBColor(0xFFFFFF, 0x2B2D30)).let { if (it.rgb == bg.rgb) JBColor(0xFFFFFF, 0x2B2D30) else it }
    val panel2: Color get() = JBColor(0xEBECF0, 0x393B40)
    val border: Color get() = JBColor.namedColor("Borders.color", JBColor(0xEBECF0, 0x393B40))
    val border2: Color get() = JBColor.namedColor("Component.borderColor", JBColor(0xD3D5DB, 0x43454A))
    val fg: Color get() = UIUtil.getLabelForeground()
    val info: Color get() = UIUtil.getContextHelpForeground()
    val mono: Color get() = JBColor(0x333333, 0xCED0D6)
    val blue: Color get() = JBUI.CurrentTheme.Link.Foreground.ENABLED
    val blueBg: Color get() = JBColor(0xEDF3FF, 0x25324D)
    val green: Color get() = JBColor.namedColor("Label.successForeground", JBColor(0x369650, 0x5FAD65))
    val yellow: Color get() = JBColor.namedColor("Label.warningForeground", JBColor(0xC27D04, 0xD6AE58))
    val red: Color get() = JBColor.namedColor("Label.errorForeground", JBColor(0xE55765, 0xF2666A))
    val grid: Color get() = border
    val c1: Color get() = blue
    val c2: Color get() = JBColor(0x7FA6F4, 0x8AAEF9)
    val c3: Color get() = JBColor(0xBDD0F9, 0xBCD0FB)
    val c4: Color get() = JBColor(0xA8ADBD, 0x6C707E)
    val purple: Color get() = Color(0x8E5BD9)
    val purpleText: Color get() = JBColor(0x6E3FB8, 0xB98EF0)
    val white: Color get() = Color.WHITE

    /** Source ramp for "Where tokens go" (11 colours). */
    val ramp: List<Color>
        get() = listOf(c1, c2, c3, c4, Color(0x8E5BD9), Color(0xB98EF0), Color(0xD5C0F6), Color(0x5FAD65), Color(0x9BD29F), Color(0xD6AE58), Color(0xEAD39B))

    fun alpha(c: Color, a: Float): Color = Color(c.red, c.green, c.blue, (a.coerceIn(0f, 1f) * 255).toInt())

    // ---- sizes -----------------------------------------------------------------------------------------------

    fun px(v: Int): Int = JBUI.scale(v)
    fun px(v: Float): Float = com.intellij.ui.scale.JBUIScale.scale(v)

    // ---- type ------------------------------------------------------------------------------------------------

    /** IDE UI font at a CSS pixel size; weight 600+ is the bold face, 500 asks for a medium weight when available. */
    fun ui(sizeCss: Float, weight: Int = 400): Font {
        val base = JBFont.label().deriveFont(px(sizeCss))
        return when {
            weight >= 600 -> base.deriveFont(Font.BOLD)
            weight >= 500 -> base.deriveFont(mapOf(TextAttribute.WEIGHT to TextAttribute.WEIGHT_MEDIUM))
            else -> base
        }
    }

    /** IDE editor font (JetBrains Mono by default). */
    fun mono(sizeCss: Float): Font {
        val app = ApplicationManager.getApplication()
        val base = if (app != null && !app.isDisposed) {
            try { EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN) } catch (_: Throwable) { null }
        } else null
        val f = base ?: Font("JetBrains Mono", Font.PLAIN, 12).takeIf { it.family.contains("JetBrains", true) } ?: Font(Font.MONOSPACED, Font.PLAIN, 12)
        return f.deriveFont(px(sizeCss))
    }

    fun setup(g2: Graphics2D) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    fun fillRound(g2: Graphics2D, x: Float, y: Float, w: Float, h: Float, radiusCss: Int, color: Color) {
        val r = px(radiusCss).toFloat() * 2
        g2.color = color
        g2.fill(RoundRectangle2D.Float(x, y, w, h, r, r))
    }

    fun strokeRound(g2: Graphics2D, x: Float, y: Float, w: Float, h: Float, radiusCss: Int, color: Color) {
        val r = px(radiusCss).toFloat() * 2
        g2.color = color
        g2.stroke = java.awt.BasicStroke(1f)
        g2.draw(RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1f, h - 1f, r, r))
    }
}
