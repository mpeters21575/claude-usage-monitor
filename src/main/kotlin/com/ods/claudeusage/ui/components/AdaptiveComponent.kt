package com.ods.claudeusage.ui.components

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * A component whose preferred height depends on the width it is given (wrapping text, legends that move
 * below a chart when narrow). It re-validates once after a resize so vertical layouts converge on the
 * right height instead of clipping.
 */
abstract class AdaptiveComponent : JComponent() {

    private var lastLayoutWidth = -1

    init {
        isOpaque = false
        toolTipText = "" // registers with the tooltip manager; the text comes from getToolTipText(MouseEvent)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (width != lastLayoutWidth) {
                    lastLayoutWidth = width
                    SwingUtilities.invokeLater { revalidate(); repaint() }
                }
            }
        })
    }

    /** Minimum width the component asks for; the layout usually stretches it. */
    protected open val minimumWidth: Int get() = JBUI.scale(200)

    /** Height needed when laid out at [width] pixels. */
    protected abstract fun preferredHeightFor(width: Int): Int

    /** Width to lay out against: own width when known, otherwise the nearest sized ancestor. */
    protected fun effectiveWidth(): Int {
        if (width > 0) return width
        var p = parent
        while (p != null) {
            if (p.width > 0) {
                val insets = p.insets
                return (p.width - insets.left - insets.right).coerceAtLeast(minimumWidth)
            }
            p = p.parent
        }
        return JBUI.scale(360)
    }

    override fun getPreferredSize(): Dimension = Dimension(minimumWidth, preferredHeightFor(effectiveWidth()))

    override fun getMinimumSize(): Dimension = Dimension(minimumWidth, preferredHeightFor(minimumWidth))

    /** Shortens [text] with an ellipsis so it fits in [maxWidth] pixels. */
    protected fun fit(text: String, maxWidth: Int, fm: java.awt.FontMetrics): String {
        if (fm.stringWidth(text) <= maxWidth) return text
        var t = text
        while (t.length > 1 && fm.stringWidth("$t…") > maxWidth) t = t.dropLast(1)
        return t.trimEnd() + "…"
    }

    /** Greedy word wrap of [text] into lines no wider than [maxWidth]. */
    protected fun wrap(text: String, maxWidth: Int, fm: java.awt.FontMetrics): List<String> {
        if (maxWidth <= 0) return listOf(text)
        val lines = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            var line = StringBuilder()
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (fm.stringWidth(candidate) <= maxWidth || line.isEmpty()) {
                    if (line.isEmpty() && fm.stringWidth(candidate) > maxWidth) {
                        // A single word wider than the column: hard-break it.
                        var rest = word
                        while (fm.stringWidth(rest) > maxWidth && rest.length > 1) {
                            var cut = rest.length
                            while (cut > 1 && fm.stringWidth(rest.substring(0, cut)) > maxWidth) cut--
                            lines += rest.substring(0, cut)
                            rest = rest.substring(cut)
                        }
                        line = StringBuilder(rest)
                    } else {
                        line = StringBuilder(candidate)
                    }
                } else {
                    lines += line.toString()
                    line = StringBuilder(word)
                }
            }
            lines += line.toString()
        }
        return lines
    }
}
