package com.ods.claudeusage.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.panels.VerticalLayout
import com.ods.claudeusage.model.AgentRun
import com.ods.claudeusage.model.SessionInfo
import com.ods.claudeusage.model.SubagentRun
import com.ods.claudeusage.model.TokenTotals
import com.ods.claudeusage.model.ToolStat
import com.ods.claudeusage.model.UsageBreakdown
import com.ods.claudeusage.ui.intui.BigNumberRow
import com.ods.claudeusage.ui.intui.Chip
import com.ods.claudeusage.ui.intui.ContextChart
import com.ods.claudeusage.ui.intui.ContextModel
import com.ods.claudeusage.ui.intui.IntUi
import com.ods.claudeusage.ui.intui.IntUi.px
import com.ods.claudeusage.ui.intui.InlineText
import com.ods.claudeusage.ui.intui.LegendGrid
import com.ods.claudeusage.ui.intui.MiniStats
import com.ods.claudeusage.ui.intui.ModelsSection
import com.ods.claudeusage.ui.intui.PillButton
import com.ods.claudeusage.ui.intui.RankedRows
import com.ods.claudeusage.ui.intui.Section
import com.ods.claudeusage.ui.intui.SegControl
import com.ods.claudeusage.ui.intui.SessionSection
import com.ods.claudeusage.ui.intui.SingleDetail
import com.ods.claudeusage.ui.intui.SpreadLine
import com.ods.claudeusage.ui.intui.StackedBar
import com.ods.claudeusage.ui.intui.TabStrip
import com.ods.claudeusage.ui.intui.TextLine
import com.ods.claudeusage.ui.intui.ToolsSection
import com.ods.claudeusage.ui.intui.FlowRow
import com.ods.claudeusage.ui.intui.stack
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import java.time.Duration
import java.time.Instant
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Paints the 1.1 sections headlessly into PNGs in build/render at 360px (and 300px to check reflow) in both
 * colour schemes, with the "turns" detail and a tool detail open.
 */
class RenderSmokeTest {

    @Test
    fun `renders the Int UI design in dark and light`() {
        System.setProperty("java.awt.headless", "true")
        for (dark in listOf(true, false)) for (width in listOf(360, 300)) {
            JBColor.setDark(dark)
            UIManager.put("Label.foreground", if (dark) Color(0xDFE1E5) else Color(0x000000))
            UIManager.put("Label.infoForeground", if (dark) Color(0x868A91) else Color(0x818594))
            UIManager.put("Label.disabledForeground", if (dark) Color(0x868A91) else Color(0x818594))
            UIManager.put("Panel.background", if (dark) Color(0x2B2D30) else Color(0xFFFFFF))
            UIManager.put("ToolWindow.background", if (dark) Color(0x1E1F22) else Color(0xF7F8FA))
            UIManager.put("Link.activeForeground", if (dark) Color(0x548AF7) else Color(0x3574F0))
            val out = File("build/render/intui-${if (dark) "dark" else "light"}-$width.png")
            out.parentFile.mkdirs()
            SwingUtilities.invokeAndWait {
                val panel = buildDemo()
                for (pass in 0 until 3) { panel.size = Dimension(width, if (pass == 0) 600 else panel.preferredSize.height); layoutTree(panel) }
                val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
                val g = image.createGraphics()
                panel.print(g)
                g.dispose()
                ImageIO.write(image, "png", out)
            }
            assertTrue(out.length() > 10_000, "rendered image should not be empty: ${out.length()} bytes")
            println("Rendered ${out.absolutePath}")
        }
    }

    private fun layoutTree(c: java.awt.Component) {
        c.doLayout()
        if (c is java.awt.Container) { c.validate(); for (child in c.components) layoutTree(child) }
    }

    private fun sampleSession(now: Instant): SessionInfo {
        val history = (0..12).map { i -> now.minus(Duration.ofMinutes((48 - i * 4).toLong())) to (12_000L + i * 50_000L) }
        val outputs = (0..40).map { i -> now.minus(Duration.ofMinutes((600 - i * 15).toLong())) to (300L + (i * 137) % 900) }
        return SessionInfo(
            sessionId = "6631c5cc-1234", projectDirName = "C--git-ODS-Database", filePath = "f", title = "Rider plugin", cwd = "C:\\git\\ODS Database",
            gitBranch = "features/warehouse-qr-07-label-dialog-layout", claudeVersion = "2.1.291", model = "claude-opus-5-5",
            contextTokens = 618_000, contextWindow = 1_000_000, lastTurnAt = now.minusSeconds(60), firstTurnAt = now.minus(Duration.ofHours(263)),
            turns = 1522, totals = TokenTotals(input = 3_100, output = 1_300_000, cacheCreation = 5_630_000, cacheRead = 704_700_000),
            subagentTotals = TokenTotals(cacheRead = 12_800_000), contextHistory = history,
            breakdown = UsageBreakdown(
                TokenTotals(3_100, 1_300_000, 5_630_000, 704_700_000), 354_000,
                mapOf("Tool call arguments" to 398_000L, "Shell results" to 241_000L, "Assistant text" to 90_000L, "MCP \u00B7 claude-in-chrome results" to 85_000L, "User & system prompts" to 75_000L, "Files & search results" to 62_000L, "Images & documents" to 17_000L),
                mapOf("MCP \u00B7 chrome" to 742, "Shell" to 737, "Files & search" to 458, "Planning & meta" to 42, "Subagents" to 19, "MCP \u00B7 rider" to 1), 3, 1,
                mapOf("MCP \u00B7 chrome" to mapOf("navigate" to ToolStat(211, 4_100, 1), "read_page" to ToolStat(198, 61_200, 0), "find" to ToolStat(174, 12_800, 2), "click" to ToolStat(159, 6_900, 0))),
            ),
            userTurns = 1498, outputHistory = outputs,
            agentRuns = listOf(AgentRun(now.minusSeconds(900), "verifier"), AgentRun(now.minusSeconds(800), "explore"), AgentRun(now.minusSeconds(700), "verifier")),
            subagentRuns = listOf(SubagentRun(now.minusSeconds(900), TokenTotals(cacheRead = 7_900_000), "a"), SubagentRun(now.minusSeconds(800), TokenTotals(cacheRead = 3_200_000), "b"), SubagentRun(now.minusSeconds(700), TokenTotals(cacheRead = 1_700_000), "c")),
        )
    }

    private fun buildDemo(): JPanel {
        val now = Instant.now()
        val session = sampleSession(now)
        val root = JPanel(BorderLayout()).apply { background = IntUi.bg; isOpaque = true }
        val content = JPanel(VerticalLayout(px(20))).apply { isOpaque = false; border = BorderFactory.createEmptyBorder(px(12), px(12), px(16), px(12)) }
        content.add(TextLine("m.peters@odsenergysolutions.com \u00B7 Team plan", IntUi.ui(12f), IntUi.info))

        val m = ContextModel(session, 75, now)
        val chart = ContextChart().apply { set(m) }
        val stats = MiniStats(3).apply { stats = listOf(MiniStats.Stat("Rate", "+${Fmt.tokens(m.rate.toLong())}/min"), MiniStats.Stat("75% in", ContextModel.durationLabel(m.min75)), MiniStats.Stat("Full in", ContextModel.durationLabel(m.min100))) }
        content.add(Section("ctx", "Context window", SegControl(listOf("Context", "5 h", "7 d")) {}, stack(8, JPanel(BorderLayout()).apply { isOpaque = false; add(Chip(m.status), BorderLayout.EAST) }, BigNumberRow().apply { set(m) }, stack(6, SpreadLine("Last ${m.minutesOfHistory.toInt()} min \u00B7 forecast at current rate", m.trend, leftFont = IntUi.ui(11f), rightFont = IntUi.ui(11f), rightColor = IntUi.info), chart), stats), card = true))

        // 5-hour window view of the same card: hero = time left, chart with reset marker
        val reset = now.plus(Duration.ofMinutes(134))
        val win = com.ods.claudeusage.model.UsageWindow("five_hour", "5-hour", 64.0, reset, Duration.ofHours(5))
        val samples = (0..40).map { i -> com.ods.claudeusage.model.UsageSample(now.minus(Duration.ofMinutes((80 - i * 2).toLong())), mapOf("five_hour" to 20.0 + i * 1.1), mapOf("five_hour" to reset.toEpochMilli()), null, null, null) }
        val fc = com.ods.claudeusage.model.WindowForecast("five_hour", "5-hour", 64.0, com.ods.claudeusage.model.ForecastVerdict.OK, 20.0, now.plus(Duration.ofMinutes(108)), 100.0, reset, 20_000_000, 31_000_000, 11_000_000)
        val wm = com.ods.claudeusage.ui.intui.WindowModel(win, fc, samples, now)
        content.add(Section("ctx5h", "Context window", SegControl(listOf("Context", "5 h", "7 d")) {}.apply { selectedIndex = 1 }, stack(8,
            JPanel(BorderLayout()).apply { isOpaque = false; add(Chip(wm.status), BorderLayout.EAST) },
            BigNumberRow().apply { set("2h 14m", "64%", "used", "≈ 11.0M tokens left · resets ${Fmt.clock(reset)}", false) },
            stack(6, SpreadLine("Last 1 h · forecast at current rate", "+20.0%/h", leftFont = IntUi.ui(11f), rightFont = IntUi.ui(11f), rightColor = IntUi.info), ContextChart().apply { set(wm) }),
            MiniStats(3).apply { this.stats = listOf(MiniStats.Stat("Used", "64%"), MiniStats.Stat("100% in", ContextModel.durationLabel(wm.min100)), MiniStats.Stat("Resets in", ContextModel.durationLabel(wm.minutesToReset))) },
        ), card = true))

        val detail = SingleDetail()
        val sess = SessionSection(detail).also { it.update(session, now) }
        content.add(Section("session", "Active session", sess.headerRight, sess.body, gapCss = 12))
        detail.toggle("turns")

        val b = session.breakdown
        val ordered = (b.bySource + ("Thinking" to b.thinkingTokens)).entries.sortedByDescending { it.value }
        val total = ordered.sumOf { it.value }
        content.add(Section("sources", "Where tokens go", SegControl(listOf("This session", "7 days")) {}, stack(10,
            SpreadLine("By source \u00B7 estimated from transcripts", Fmt.tokens(total), rightFont = IntUi.ui(13f, 500), right2 = " tokens"),
            StackedBar().apply { segments = ordered.mapIndexed { i, e -> StackedBar.Segment(e.value, IntUi.ramp[i % 11]) } },
            RankedRows(48, 42).apply { rows = ordered.mapIndexed { i, e -> RankedRows.Row(e.key, Fmt.tokens(e.value), String.format(java.util.Locale.US, "%.1f%%", e.value * 100.0 / total), IntUi.ramp[i % 11]) } },
        )))

        val t = b.byType
        content.add(Section("types", "By token type", InlineText("Exact \u00B7 API counts", IntUi.ui(12f), IntUi.info), stack(8,
            StackedBar().apply { segments = listOf(StackedBar.Segment(t.cacheRead, IntUi.c1), StackedBar.Segment(t.cacheCreation, IntUi.c2), StackedBar.Segment(t.output, IntUi.c4), StackedBar.Segment(t.input, IntUi.c3)) },
            LegendGrid(2, 6, 16).apply { items = listOf(LegendGrid.Item("Cache read", Fmt.tokens(t.cacheRead), IntUi.c1), LegendGrid.Item("Cache write", Fmt.tokens(t.cacheCreation), IntUi.c2), LegendGrid.Item("Output", Fmt.tokens(t.output), IntUi.c4), LegendGrid.Item("Input", Fmt.tokens(t.input), null, true)) },
        ), gapCss = 8))

        val tools = ToolsSection(detail).also { it.update(b.toolCalls, b.toolStats, b.thinkingTokens) }
        content.add(Section("tools", "Tool calls", tools.headerRight, tools.body, gapCss = 8))

        // Customizations (static stand-in: the real section needs a Project)
        val custHeader = JPanel(BorderLayout(px(6), 0)).apply { isOpaque = false; add(InlineText("9 user \u00B7 5 project", IntUi.mono(11.5f), IntUi.info), BorderLayout.CENTER); add(PillButton("New", PillButton.Style.PRIMARY, com.ods.claudeusage.ui.intui.IconKind.PLUS) {}, BorderLayout.EAST) }
        val top = FlowRow().apply { for ((c, n) in listOf("/build-test" to 131, "/pr" to 64, "/compact" to 52, "/review" to 29, "/clear" to 23)) add(PillButton(c, PillButton.Style.OUTLINED, null, 26) {}.apply { monoLabel = true; trailing = "$n\u00D7" }) }
        val tabs = TabStrip {}.apply { tabs = listOf(TabStrip.Tab("All", 14), TabStrip.Tab("Skills", 4), TabStrip.Tab("Agents", 3), TabStrip.Tab("Commands", 3), TabStrip.Tab("Hooks", 2), TabStrip.Tab("MCP", 2)) }
        content.add(Section("cust", "Customizations", custHeader, stack(10, top, tabs, TextLine("(item rows are rendered from the live scan in the IDE)", IntUi.ui(12f), IntUi.info))))

        val models = ModelsSection().also { it.update(listOf(
            com.ods.claudeusage.model.ModelUsage("claude-opus-5-5", TokenTotals(cacheRead = 1_850_000), TokenTotals(cacheRead = 132_900_000), 448, now),
            com.ods.claudeusage.model.ModelUsage("claude-fable-5-1", TokenTotals(cacheRead = 31_200_000), TokenTotals(cacheRead = 34_000_000), 122, now),
            com.ods.claudeusage.model.ModelUsage("claude-haiku-4-5", TokenTotals(), TokenTotals(cacheRead = 332_000), 8, now),
        )) }
        content.add(Section("models", "Models", models.seg, models.body, gapCss = 6))

        root.add(content, BorderLayout.CENTER)
        return root
    }
}
