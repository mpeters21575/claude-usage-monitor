package com.ods.claudeusage.ui.intui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.ui.components.panels.VerticalLayout
import com.ods.claudeusage.ui.design.text
import com.ods.claudeusage.ui.intui.IntUi.px
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Collapsible section: header row with a rotating chevron and bold title (hover blue) on the left and a
 * contextual control on the right; the body hides when collapsed. The collapsed state is persisted per project.
 */
class Section(
    private val id: String,
    title: String,
    right: JComponent?,
    private val body: JComponent,
    private val card: Boolean = false,
    private val gapCss: Int = 10,
    private val project: Project? = null,
) : JPanel(VerticalLayout(px(gapCss))) {

    private val header = HeaderButton(title) { toggle() }

    var collapsed: Boolean = project?.let { PropertiesComponent.getInstance(it).getBoolean(key(id), false) } ?: false
        private set

    init {
        isOpaque = false
        if (card) border = BorderFactory.createEmptyBorder(px(12), px(12), px(12), px(12))
        add(spread(header, right))
        add(body)
        apply()
    }

    private fun toggle() {
        collapsed = !collapsed
        project?.let { PropertiesComponent.getInstance(it).setValue(key(id), collapsed, false) }
        apply()
    }

    private fun apply() {
        body.isVisible = !collapsed
        header.expanded = !collapsed
        revalidate(); repaint()
    }

    override fun paintComponent(g: Graphics) {
        if (!card) return
        val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
        try {
            IntUi.fillRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 8, IntUi.panel)
            IntUi.strokeRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 8, IntUi.border)
        } finally { g2.dispose() }
    }

    /** Chevron + 13px/600 title; hover turns the title blue. */
    private class HeaderButton(private val title: String, private val onClick: () -> Unit) : HoverComponent() {
        var expanded = true
            set(v) { field = v; repaint() }
        init {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { onClick() } })
        }
        private val titleFont get() = IntUi.ui(13f, 600)
        override fun getPreferredSize(): Dimension = Dimension(px(10) + px(6) + getFontMetrics(titleFont).stringWidth(title) + px(4), px(20))
        override fun getMinimumSize(): Dimension = Dimension(px(40), px(20))
        override fun paintComponent(g: Graphics) {
            val g2 = g2(g)
            try {
                Icons.chevron(g2, px(5).toFloat(), height / 2f, 10f, IntUi.info, if (expanded) 90.0 else 0.0)
                val fm = g2.getFontMetrics(titleFont)
                g2.text(title, (px(10) + px(6)).toFloat(), (height + fm.ascent - fm.descent) / 2f, titleFont, if (hover) IntUi.blue else IntUi.fg)
            } finally { g2.dispose() }
        }
    }

    companion object {
        private fun key(id: String) = "claudeUsage.collapsed.$id"
    }
}

/** Tool-window-style header row (36px): semibold title, connection pill, options button. */
class HeaderBar(private val onOptions: () -> Unit) : JPanel(BorderLayout(px(8), 0)) {
    private val title = TextLine("Claude Usage", IntUi.ui(13f, 600))
    private val pill = object : JComponent() {
        var text = "Connecting…"
        var dot: java.awt.Color = IntUi.info
        init { isOpaque = false; toolTipText = "" }
        override fun getPreferredSize(): Dimension = Dimension(px(6) + px(6) + getFontMetrics(IntUi.ui(12f)).stringWidth(text) + px(4), px(20))
        override fun getMinimumSize(): Dimension = preferredSize
        override fun paintComponent(g: Graphics) {
            val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
            try {
                g2.color = dot
                g2.fill(java.awt.geom.Ellipse2D.Float(0f, height / 2f - px(3), px(6).toFloat(), px(6).toFloat()))
                val f = IntUi.ui(12f); val fm = g2.getFontMetrics(f)
                g2.text(text, (px(6) + px(6)).toFloat(), (height + fm.ascent - fm.descent) / 2f, f, IntUi.info)
            } finally { g2.dispose() }
        }
        override fun getToolTipText(event: MouseEvent): String? = tooltip
        var tooltip: String? = null
    }
    private val options = IconButton(IconKind.MORE, "Options") { onOptions() }

    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(0, px(12), 0, px(8))
        preferredSize = Dimension(px(200), px(36))
        add(title, BorderLayout.CENTER)
        add(JPanel(BorderLayout(px(8), 0)).apply { isOpaque = false; add(pill, BorderLayout.CENTER); add(options, BorderLayout.EAST) }, BorderLayout.EAST)
    }

    fun update(status: String, dot: java.awt.Color, tooltip: String?) {
        pill.text = status; pill.dot = dot; pill.tooltip = tooltip
        pill.revalidate(); pill.repaint()
    }

    override fun paintComponent(g: Graphics) {
        g.color = IntUi.border
        g.fillRect(0, height - 1, width, 1)
    }
}
