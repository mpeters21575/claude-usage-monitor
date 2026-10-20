package com.ods.claudeusage.ui.intui

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.ods.claudeusage.customizations.CustomizationItem
import com.ods.claudeusage.customizations.CustomizationType
import com.ods.claudeusage.customizations.CustomizationsService
import com.ods.claudeusage.customizations.Scope
import com.ods.claudeusage.model.UsageCounts
import com.ods.claudeusage.model.UsageStat
import com.ods.claudeusage.notify.ThresholdNotifier
import com.ods.claudeusage.ui.Fmt
import com.ods.claudeusage.ui.components.AdaptiveComponent
import com.ods.claudeusage.ui.design.ellipsize
import com.ods.claudeusage.ui.design.text
import com.ods.claudeusage.ui.design.wrapText
import com.ods.claudeusage.ui.intui.IntUi.px
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

/** Customizations in `~/.claude` and `./.claude`: top actions, filter tabs, manageable item rows. */
class CustomizationsSection(private val project: Project) {

    private val service = CustomizationsService.getInstance(project)

    val scopeLabel = InlineText("", IntUi.mono(11.5f), IntUi.info)
    private val newButton = PillButton("New", PillButton.Style.PRIMARY, IconKind.PLUS) { showNewMenu() }
    val headerRight: JComponent = JPanel(BorderLayout(px(6), 0)).apply { isOpaque = false; add(scopeLabel, BorderLayout.CENTER); add(newButton, BorderLayout.EAST) }

    private val topActions = FlowRow()
    private val tabs = TabStrip { render() }
    private val list = JPanel(VerticalLayout(px(2))).apply { isOpaque = false }
    val body: JPanel = stack(10, topActions, tabs, list)

    private var items: List<CustomizationItem> = emptyList()
    private var counts: UsageCounts = UsageCounts.EMPTY
    private var openId: String? = null
    private var renamingId: String? = null
    private var deletingId: String? = null

    private val typeOrder = listOf(null, CustomizationType.SKILL, CustomizationType.AGENT, CustomizationType.COMMAND, CustomizationType.HOOK, CustomizationType.MCP)

    init {
        project.messageBus.connect(project).subscribe(CustomizationsService.TOPIC, com.ods.claudeusage.customizations.CustomizationsListener { fresh ->
            ApplicationManager.getApplication().invokeLater({ items = fresh; render() }, project.disposed)
        })
        items = service.items
    }

    fun update(usage: UsageCounts) {
        counts = usage
        render()
    }

    private fun scopeOf(item: CustomizationItem) = item.scope

    private fun usageOf(item: CustomizationItem): UsageStat? = when (item.type) {
        CustomizationType.SKILL -> counts.skills.entries.firstOrNull { it.key == item.name || it.key.substringAfter(':') == item.name }?.value
        CustomizationType.AGENT -> counts.agents.entries.firstOrNull { it.key == item.name || it.key.substringAfter(':') == item.name }?.value
        CustomizationType.COMMAND -> counts.commands[item.name] ?: counts.commands.entries.firstOrNull { it.key.substringAfter(':') == item.name.removePrefix("/") }?.value
        CustomizationType.MCP -> counts.mcpServers[item.name]
        CustomizationType.HOOK -> null
    }

    private fun usedText(item: CustomizationItem): String {
        val u = usageOf(item) ?: return if (item.type == CustomizationType.HOOK) "–" else "never this week"
        return if (item.type == CustomizationType.AGENT) "${u.count} runs this week" else "${u.count}× this week"
    }

    private fun render() {
        val userCount = items.count { it.scope == Scope.USER }
        scopeLabel.text = "$userCount user · ${items.size - userCount} project"

        // Top 5 slash commands by use
        topActions.removeAll()
        counts.commands.entries.sortedByDescending { it.value.count }.take(5).forEach { (cmd, stat) ->
            topActions.add(PillButton(cmd, PillButton.Style.OUTLINED, null, 26) { runCommand(cmd) }.apply { monoLabel = true; trailing = "${stat.count}×"; toolTipText = "Used ${stat.count} times in the last 7 days. Click to copy to the clipboard." })
        }
        topActions.isVisible = topActions.componentCount > 0

        tabs.tabs = typeOrder.map { t -> TabStrip.Tab(t?.plural ?: "All", if (t == null) items.size else items.count { it.type == t }) }
        val selectedType = typeOrder.getOrNull(tabs.selectedIndex)
        list.removeAll()
        val visible = items.filter { selectedType == null || it.type == selectedType }
        if (visible.isEmpty()) list.add(TextLine("Nothing here yet. Use New to scaffold one.", IntUi.ui(12f), IntUi.info).apply { border = BorderFactory.createEmptyBorder(px(6), px(8), px(6), px(8)) })
        for (item in visible) list.add(ItemRow(item))
        body.revalidate(); body.repaint()
    }

    // ---- actions -----------------------------------------------------------------------------------------------

    private fun notify(title: String, text: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance().getNotificationGroup(ThresholdNotifier.GROUP_ID)?.createNotification(title, text, type)?.notify(project)
    }

    private fun error(message: String) = ApplicationManager.getApplication().invokeLater({ notify("Claude customizations", message, NotificationType.ERROR) }, project.disposed)

    private fun runCommand(cmd: String) {
        CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(cmd))
        notify("Copied $cmd", "Paste it into the Claude Code terminal to run it.")
    }

    private fun openInRider(path: Path) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        if (vf == null) { notify("Claude customizations", "File not found: $path", NotificationType.WARNING); return }
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    private fun reveal(path: Path) = RevealFileAction.openFile(path.toFile())

    private fun copyPath(item: CustomizationItem) {
        CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(item.file.toString()))
        notify("Path copied", item.file.toString())
    }

    private fun showNewMenu() {
        val menu = JBPopupMenu()
        for (type in CustomizationType.entries) {
            menu.add(JMenuItem("New ${type.label}").apply { addActionListener { scaffold(type) } })
        }
        menu.show(newButton, 0, newButton.height + px(4))
    }

    private fun scaffold(type: CustomizationType) {
        val name = Messages.showInputDialog(project, "Name of the new ${type.label.lowercase()}:", "New ${type.label}", null) ?: return
        if (name.isBlank()) return
        val scopeChoice = if (project.basePath != null) Messages.showDialog(project, "Where should it live?", "New ${type.label}", arrayOf("Project (./.claude)", "User (~/.claude)"), 0, null) else 1
        if (scopeChoice < 0) return
        val scope = if (scopeChoice == 0) Scope.PROJECT else Scope.USER
        service.run(::error) {
            val file = scaffold(type, scope, name)
            ApplicationManager.getApplication().invokeLater({ openInRider(file) }, project.disposed)
        }
    }

    private fun setEnabled(item: CustomizationItem, enabled: Boolean) = service.run(::error) { setEnabled(item, enabled) }
    private fun duplicate(item: CustomizationItem) = service.run(::error) { duplicate(item) }
    private fun moveScope(item: CustomizationItem) = service.run(::error) { moveScope(item) }
    private fun delete(item: CustomizationItem) { deletingId = null; openId = null; service.run(::error) { delete(item) } }
    private fun rename(item: CustomizationItem, newName: String) { renamingId = null; service.run(::error) { rename(item, newName) } }

    private fun showItemMenu(item: CustomizationItem, anchor: JComponent) {
        val menu = JBPopupMenu()
        menu.add(JMenuItem("Rename…").apply { isEnabled = item.type != CustomizationType.HOOK; addActionListener { renamingId = item.id; deletingId = null; openId = null; render() } })
        menu.add(JMenuItem("Duplicate").apply { addActionListener { duplicate(item) } })
        menu.add(JMenuItem(if (item.scope == Scope.PROJECT) "Move to user scope (~/.claude)" else "Move to project scope (./.claude)").apply { isEnabled = project.basePath != null; addActionListener { moveScope(item) } })
        menu.add(JMenuItem("Copy path").apply { addActionListener { copyPath(item) } })
        menu.add(JMenuItem("Show in Explorer").apply { addActionListener { reveal(item.file) } })
        menu.add(JPopupMenu.Separator())
        menu.add(JMenuItem("Delete…").apply { foreground = IntUi.red; addActionListener { deletingId = item.id; renamingId = null; render() } })
        menu.show(anchor, 0, anchor.height + px(4))
    }

    // ---- item row -----------------------------------------------------------------------------------------------

    private inner class ItemRow(private val item: CustomizationItem) : JPanel(VerticalLayout(0)) {
        private val open = openId == item.id
        init {
            isOpaque = false
            add(HeaderLine())
            if (deletingId == item.id) add(confirmStrip())
            if (renamingId == item.id) add(renameRow())
            if (open) add(detailBlock())
        }

        override fun paintComponent(g: Graphics) {
            if (!open) return
            val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
            try { IntUi.fillRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 6, IntUi.panel) } finally { g2.dispose() }
        }

        /** icon | name + chips | switch, open, menu */
        private inner class HeaderLine : JPanel(BorderLayout(px(8), 0)) {
            private var hover = false
            private val controls = JPanel(FlowLayout(FlowLayout.RIGHT, px(2), 0)).apply {
                isOpaque = false
                add(Switch { on -> setEnabled(item, on) }.apply { this.on = item.enabled; border = BorderFactory.createEmptyBorder(0, 0, 0, px(4)) })
                add(IconButton(IconKind.EXTERNAL, "Open in Rider") { openInRider(item.file) })
                val more = IconButton(IconKind.MORE, "More") {}
                more.addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { showItemMenu(item, more) } })
                add(more)
            }
            init {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(px(6), px(8), px(6), px(6))
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                add(NameArea(), BorderLayout.CENTER)
                add(controls, BorderLayout.EAST)
                val m = object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                    override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
                    override fun mouseClicked(e: MouseEvent) { openId = if (openId == item.id) null else item.id; render() }
                }
                addMouseListener(m)
            }
            override fun paintComponent(g: Graphics) {
                if (!hover || open) return
                val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
                try { IntUi.fillRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 6, IntUi.panel2) } finally { g2.dispose() }
            }
        }

        private inner class NameArea : JComponent() {
            init { isOpaque = false; toolTipText = item.description.ifBlank { item.displayPath } }
            override fun getPreferredSize(): Dimension = Dimension(px(80), px(20))
            override fun getMinimumSize(): Dimension = Dimension(px(40), px(20))
            override fun paintComponent(g: Graphics) {
                val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
                try {
                    val dim = !item.enabled
                    Icons.paint(g2, iconOf(item.type), 0f, (height - px(16)) / 2f, px(16).toFloat(), if (dim) IntUi.alpha(IntUi.info, 0.55f) else IntUi.info)
                    val nf = IntUi.mono(12f); val nfm = g2.getFontMetrics(nf)
                    val cf = IntUi.ui(10f); val cfm = g2.getFontMetrics(cf)
                    val baseline = (height + nfm.ascent - nfm.descent) / 2f
                    val scopeW = cfm.stringWidth(item.scope.label) + px(8)
                    val agentsW = if (item.agents.isNotEmpty()) cfm.stringWidth(item.agents.size.toString()) + px(18) else 0
                    var x = px(16) + px(8)
                    val nameMax = width - x - px(6) - scopeW - (if (agentsW > 0) px(6) + agentsW else 0)
                    val name = ellipsize(item.name, nameMax, nfm)
                    g2.text(name, x.toFloat(), baseline, nf, if (dim) IntUi.alpha(IntUi.fg, 0.55f) else IntUi.fg)
                    val nw = nfm.stringWidth(name)
                    if (dim) { g2.color = IntUi.alpha(IntUi.fg, 0.55f); g2.fillRect(x, (baseline - nfm.ascent * 0.35f).toInt(), nw, 1) }
                    x += nw + px(6)
                    IntUi.fillRound(g2, x.toFloat(), height / 2f - cfm.height / 2f, scopeW.toFloat(), cfm.height.toFloat(), 3, IntUi.panel2)
                    g2.text(item.scope.label, (x + px(4)).toFloat(), height / 2f + cfm.ascent / 2f - 1, cf, IntUi.info)
                    x += scopeW + px(6)
                    if (agentsW > 0) {
                        IntUi.fillRound(g2, x.toFloat(), height / 2f - cfm.height / 2f, agentsW.toFloat(), cfm.height.toFloat(), 3, IntUi.alpha(IntUi.purple, 0.13f))
                        Icons.paint(g2, IconKind.ARROW, (x + px(3)).toFloat(), height / 2f - px(5), px(10).toFloat(), IntUi.purpleText, 1.6f)
                        g2.text(item.agents.size.toString(), (x + px(15)).toFloat(), height / 2f + cfm.ascent / 2f - 1, cf, IntUi.purpleText)
                    }
                } finally { g2.dispose() }
            }
        }

        private fun confirmStrip(): JComponent {
            val text = TextLine("Delete ${item.name}? " + (if (item.isFileBased) "The file is moved to the Recycle Bin." else "The entry is removed from ${item.file.fileName}."), IntUi.ui(12f), IntUi.fg, wrap = true)
            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, px(6), 0)).apply {
                isOpaque = false
                add(PillButton("Cancel", PillButton.Style.OUTLINED, null, 22) { deletingId = null; render() })
                add(PillButton("Delete", PillButton.Style.DANGER, null, 22) { delete(item) })
            }
            return JPanel(BorderLayout()).apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(0, px(32), px(6), px(6))
                add(Strip({ IntUi.red }, text, buttons), BorderLayout.CENTER)
            }
        }

        private fun renameRow(): JComponent {
            val field = JBTextField(item.name).apply { font = IntUi.mono(12f) }
            val commit = { val v = field.text.trim(); if (v.isNotEmpty() && v != item.name) rename(item, v) else { renamingId = null; render() } }
            field.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ENTER) commit(); if (e.keyCode == KeyEvent.VK_ESCAPE) { renamingId = null; render() } }
            })
            val row = JPanel(BorderLayout(px(6), 0)).apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(0, px(32), px(6), px(6))
                add(field, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.LEFT, px(6), 0)).apply {
                    isOpaque = false
                    add(PillButton("Rename", PillButton.Style.PRIMARY, null, 24) { commit() })
                    add(PillButton("Cancel", PillButton.Style.OUTLINED, null, 24) { renamingId = null; render() })
                }, BorderLayout.EAST)
            }
            ApplicationManager.getApplication().invokeLater { field.requestFocusInWindow(); field.selectAll() }
            return row
        }

        private fun detailBlock(): JComponent {
            val parts = ArrayList<JComponent>()
            parts += TextLine(item.description.ifBlank { "No description." }, IntUi.ui(12f), IntUi.fg, wrap = true)
            if (item.agents.isNotEmpty()) parts += orchestrationBox()
            if (item.type == CustomizationType.AGENT) {
                val dispatchers = items.filter { it.type == CustomizationType.SKILL && item.name in it.agents }
                if (dispatchers.isNotEmpty()) {
                    val row = FlowRow(6, 6)
                    row.add(InlineText("Dispatched by", IntUi.ui(11f), IntUi.info))
                    for (s in dispatchers) row.add(Chip(s.name, IntUi.blueBg, IntUi.blue, 11f).apply {
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { openId = s.id; render() } })
                    })
                    parts += row
                }
            }
            parts += KeyGrid(listOf("Path" to item.displayPath, "Used" to usedText(item), "Modified" to (item.modified?.let { Fmt.relative(it) } ?: "–")))
            parts += FlowRow(6, 6).apply {
                add(PillButton("Open in Rider", PillButton.Style.PRIMARY, IconKind.EXTERNAL) { openInRider(item.file) })
                add(PillButton("Show in Explorer") { reveal(item.file) })
                add(PillButton(if (item.enabled) "Disable" else "Enable") { setEnabled(item, !item.enabled) })
                add(PillButton("Duplicate") { duplicate(item) })
            }
            return JPanel(VerticalLayout(px(8))).apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(px(2), px(10), px(10), px(32))
                parts.forEach(::add)
            }
        }

        private fun orchestrationBox(): JComponent {
            val chain = FlowRow(0, 6)
            chain.add(Chip(item.name, IntUi.blueBg, IntUi.blue, 11f).apply { font = IntUi.mono(11f) })
            for (a in item.agents) {
                chain.add(ArrowConnector())
                val runs = counts.agents.entries.firstOrNull { it.key == a || it.key.substringAfter(':') == a }?.value?.count ?: 0
                val target = items.firstOrNull { it.type == CustomizationType.AGENT && it.name == a }
                chain.add(AgentChip(a, "${runs}×") { target?.let { openId = it.id; render() } ?: openInRider(service.userHome.resolve("agents").resolve("$a.md")) })
            }
            return Strip({ IntUi.border },
                SpreadLine("Orchestration", item.mode ?: "", leftFont = IntUi.ui(11f), rightFont = IntUi.ui(11f), rightColor = IntUi.info),
                chain,
                TextLine(item.note ?: "", IntUi.ui(11f), IntUi.info, wrap = true),
            )
        }
    }

    private class ArrowConnector : JComponent() {
        init { isOpaque = false }
        override fun getPreferredSize(): Dimension = Dimension(px(22), px(22))
        override fun paintComponent(g: Graphics) {
            val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
            try {
                g2.color = IntUi.border2; g2.stroke = java.awt.BasicStroke(px(1.2f), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                val cy = height / 2f
                g2.draw(java.awt.geom.Line2D.Float(px(1).toFloat(), cy, px(18).toFloat(), cy))
                val p = java.awt.geom.Path2D.Float(); p.moveTo(px(14).toDouble(), (cy - px(3.5f)).toDouble()); p.lineTo(px(18).toDouble(), cy.toDouble()); p.lineTo(px(14).toDouble(), (cy + px(3.5f)).toDouble()); g2.draw(p)
            } finally { g2.dispose() }
        }
    }

    private class AgentChip(private val name: String, private val runs: String, private val onClick: () -> Unit) : HoverComponent() {
        init { cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR); toolTipText = "Open $name.md in Rider"; addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { onClick() } }) }
        override fun getPreferredSize(): Dimension = Dimension(px(10) + px(5) + getFontMetrics(IntUi.mono(11f)).stringWidth("$name $runs") + px(16), px(22))
        override fun getMinimumSize(): Dimension = preferredSize
        override fun paintComponent(g: Graphics) {
            val g2 = g2(g)
            try {
                IntUi.fillRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 4, IntUi.alpha(IntUi.purple, if (hover) 0.2f else 0.1f))
                IntUi.strokeRound(g2, 0f, 0f, width.toFloat(), height.toFloat(), 4, IntUi.alpha(IntUi.purple, 0.4f))
                Icons.paint(g2, IconKind.AGENT, px(8).toFloat(), (height - px(10)) / 2f, px(10).toFloat(), IntUi.purpleText, 1.6f)
                val f = IntUi.mono(11f); val fm = g2.getFontMetrics(f)
                val baseline = (height + fm.ascent - fm.descent) / 2f
                g2.text(name, (px(8) + px(10) + px(5)).toFloat(), baseline, f, IntUi.purpleText)
                g2.text(" $runs", (px(8) + px(10) + px(5) + fm.stringWidth(name)).toFloat(), baseline, f, IntUi.alpha(IntUi.purpleText, 0.7f))
            } finally { g2.dispose() }
        }
    }

    /** `label (56px, info) | value` grid; paths wrap in the monospace face. */
    private class KeyGrid(private val rows: List<Pair<String, String>>) : AdaptiveComponent() {
        private val labelW get() = px(56)
        private val gap get() = px(8)
        private fun valueFont(i: Int) = if (i == 0) IntUi.mono(11.5f) else IntUi.ui(12f)
        override fun preferredHeightFor(width: Int): Int = rows.withIndex().sumOf { (i, r) -> wrapText(r.second, width - labelW - gap, getFontMetrics(valueFont(i))).size * px(16) } + (rows.size - 1) * px(3)
        override fun paintComponent(g: Graphics) {
            val g2 = (g.create() as Graphics2D).also { IntUi.setup(it) }
            try {
                val lf = IntUi.ui(12f); val lfm = g2.getFontMetrics(lf)
                var y = 0
                for ((i, r) in rows.withIndex()) {
                    val vf = valueFont(i); val vfm = g2.getFontMetrics(vf)
                    g2.text(r.first, 0f, y + (px(16) + lfm.ascent - lfm.descent) / 2f, lf, IntUi.info)
                    for (line in wrapText(r.second, width - labelW - gap, vfm)) { g2.text(line, (labelW + gap).toFloat(), y + (px(16) + vfm.ascent - vfm.descent) / 2f, vf, IntUi.mono); y += px(16) }
                    y += px(3)
                }
            } finally { g2.dispose() }
        }
    }

    companion object {
        fun iconOf(type: CustomizationType): IconKind = when (type) {
            CustomizationType.SKILL -> IconKind.SKILL
            CustomizationType.AGENT -> IconKind.AGENT
            CustomizationType.COMMAND -> IconKind.COMMAND
            CustomizationType.HOOK -> IconKind.HOOK
            CustomizationType.MCP -> IconKind.MCP
        }
    }
}
