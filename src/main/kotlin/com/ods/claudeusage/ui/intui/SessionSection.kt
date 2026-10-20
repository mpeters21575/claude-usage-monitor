package com.ods.claudeusage.ui.intui

import com.intellij.ui.components.panels.VerticalLayout
import com.ods.claudeusage.model.SessionInfo
import com.ods.claudeusage.ui.Fmt
import com.ods.claudeusage.ui.intui.IntUi.px
import java.awt.GridLayout
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.JPanel

/** One detail panel open at a time, shared by the session tiles and the tool tiles. */
class SingleDetail {
    var key: String? = null
        private set
    private val listeners = ArrayList<() -> Unit>()
    fun toggle(k: String) { key = if (key == k) null else k; listeners.forEach { it() } }
    fun onChange(l: () -> Unit) { listeners += l }
}

/** Active session: stat tiles with detail panels, token mix, activity span, environment. */
class SessionSection(private val detail: SingleDetail) {

    val headerRight = InlineText("", IntUi.mono(12f), IntUi.info)

    private val turnsTile = StatTile("Turns", "–", true) { detail.toggle("turns") }
    private val tokensTile = StatTile("Tokens", "–", true) { detail.toggle("tokens") }
    private val subTile = StatTile("Subagents", "–", true) { detail.toggle("sub") }
    private val tiles = JPanel(GridLayout(1, 3, px(6), 0)).apply { isOpaque = false; add(turnsTile); add(tokensTile); add(subTile) }

    private val detailPanel = DetailPanel().apply { isVisible = false }
    private val mixBar = StackedBar()
    private val mixLegend = LegendGrid(2, 4, 16)
    private val span = ActivitySpan()
    private val branch = IconRow(IconKind.BRANCH)
    private val dir = IconRow(IconKind.FOLDER)
    private val env = IconRow(IconKind.TERMINAL)

    val body: JPanel = stack(
        12,
        tiles,
        detailPanel,
        stack(6, mixBar, mixLegend),
        span,
        stack(6, branch, dir, env),
    )

    private var session: SessionInfo? = null
    private var now: Instant = Instant.now()

    init {
        detail.onChange { renderDetail() }
    }

    fun update(s: SessionInfo?, now: Instant) {
        session = s; this.now = now
        headerRight.text = s?.let { Fmt.shortId(it.sessionId) } ?: ""
        headerRight.tooltip = s?.title
        if (s == null) {
            turnsTile.value = "–"; tokensTile.value = "–"; subTile.value = "–"
            mixBar.segments = emptyList(); mixLegend.items = emptyList()
            span.start = "No Claude Code transcript found yet"; span.end = ""
            branch.text = ""; dir.text = ""; env.text = ""; env.suffix = ""
            renderDetail(); return
        }
        turnsTile.value = String.format(Locale.US, "%,d", s.turns)
        tokensTile.value = Fmt.tokens(s.totals.total)
        subTile.value = Fmt.tokens(s.subagentTotals.total)
        val t = s.totals
        mixBar.segments = listOf(
            StackedBar.Segment(t.cacheRead, IntUi.c1, "Cache read ${Fmt.tokens(t.cacheRead)}"),
            StackedBar.Segment(t.cacheCreation, IntUi.c2, "Cache write ${Fmt.tokens(t.cacheCreation)}"),
            StackedBar.Segment(t.output, IntUi.c4, "Output ${Fmt.tokens(t.output)}"),
            StackedBar.Segment(t.input, IntUi.c3, "Input ${Fmt.tokens(t.input)}"),
        )
        mixLegend.items = listOf(
            LegendGrid.Item("Cache read", Fmt.tokens(t.cacheRead), IntUi.c1),
            LegendGrid.Item("Cache write", Fmt.tokens(t.cacheCreation), IntUi.c2),
            LegendGrid.Item("Output", Fmt.tokens(t.output), IntUi.c4),
            LegendGrid.Item("Input", Fmt.tokens(t.input), null, outline = true),
        )
        span.start = "Started ${spanDuration(s.firstTurnAt)} ago"
        span.end = "Active ${spanDuration(s.lastTurnAt)} ago"
        branch.text = s.gitBranch ?: "–"; branch.tooltip = s.gitBranch
        dir.text = s.cwd ?: "–"; dir.tooltip = s.cwd
        env.text = Fmt.modelName(s.model); env.suffix = s.claudeVersion?.let { "Claude Code v$it" } ?: ""
        renderDetail()
    }

    private fun spanDuration(t: Instant?): String {
        if (t == null) return "–"
        val d = Duration.between(t, now).abs()
        val m = d.toMinutes()
        return when {
            m < 1 -> "${d.seconds} s"
            m < 60 -> "$m min"
            m < 60 * 24 -> "${m / 60} h ${m % 60} min"
            else -> "${m / (60 * 24)} d ${(m % (60 * 24)) / 60} h"
        }
    }

    private fun renderDetail() {
        val s = session
        val key = detail.key
        turnsTile.active = key == "turns"; tokensTile.active = key == "tokens"; subTile.active = key == "sub"
        if (s == null || key !in setOf("turns", "tokens", "sub")) { detailPanel.isVisible = false; body.revalidate(); return }
        when (key) {
            "turns" -> {
                val avgTurn = if (s.turns > 0) s.totals.total / s.turns else 0
                val avgOut = if (s.turns > 0) s.totals.output / s.turns else 0
                val longest = s.outputHistory.maxOfOrNull { it.second } ?: 0
                val buckets = IntArray(12)
                val hourStart = now.atZone(ZoneId.systemDefault()).withMinute(0).withSecond(0).withNano(0)
                val windowStart = hourStart.minusHours(11)
                for ((at, _) in s.outputHistory) {
                    val h = Duration.between(windowStart.toInstant(), at).toHours()
                    if (h in 0..11) buckets[h.toInt()]++
                }
                val fmt = DateTimeFormatter.ofPattern("HH:mm")
                val axis = listOf(0, 6, 11).map { fmt.format(windowStart.plusHours(it.toLong())) }
                detailPanel.setParts(
                    SpreadLine("Turns", "${String.format(Locale.US, "%,d", s.turns)} assistant · ${String.format(Locale.US, "%,d", s.userTurns)} user", leftFont = IntUi.ui(12f, 600), leftColor = IntUi.fg),
                    MiniStats(3).apply { stats = listOf(MiniStats.Stat("Avg / turn", "${Fmt.tokens(avgTurn)} tokens"), MiniStats.Stat("Avg output", "${Fmt.tokens(avgOut)} tokens"), MiniStats.Stat("Longest", "${Fmt.tokens(longest)} out")) },
                    TextLine("Turns per hour, last 12 h", IntUi.ui(12f), IntUi.info),
                    SparkBars().apply { values = buckets.toList(); this.axis = axis; titles = buckets.mapIndexed { i, n -> "$n turns, ${fmt.format(windowStart.plusHours(i.toLong()))}" } },
                )
            }
            "tokens" -> {
                val t = s.totals
                val max = listOf(t.cacheRead, t.cacheCreation, t.output, t.input).maxOrNull()?.coerceAtLeast(1) ?: 1
                val cached = t.cacheRead + t.cacheCreation + t.input
                val hitRate = if (cached > 0) t.cacheRead * 100.0 / cached else 0.0
                val billable = t.input + t.output + (t.cacheCreation * 1.25).toLong() + (t.cacheRead * 0.1).toLong()
                detailPanel.setParts(
                    SpreadLine("Tokens", "${Fmt.tokens(t.total)} total · exact API counts", leftFont = IntUi.ui(12f, 600), leftColor = IntUi.fg),
                    BarRows().apply {
                        rows = listOf(
                            BarRows.Row("Cache read", t.cacheRead.toDouble() / max, Fmt.tokens(t.cacheRead), IntUi.c1),
                            BarRows.Row("Cache write", t.cacheCreation.toDouble() / max, Fmt.tokens(t.cacheCreation), IntUi.c2),
                            BarRows.Row("Output", t.output.toDouble() / max, Fmt.tokens(t.output), IntUi.c4),
                            BarRows.Row("Input", t.input.toDouble() / max, Fmt.tokens(t.input), IntUi.c3),
                        )
                    },
                    Divider(),
                    MiniStats(2).apply { stats = listOf(MiniStats.Stat("Cache hit rate", Fmt.percent(hitRate)), MiniStats.Stat("Billable equivalent", "≈ ${Fmt.tokens(billable)} uncached", "input + output + 1.25 × cache write + 0.1 × cache read")) },
                )
            }
            "sub" -> {
                // Attribute subagent transcripts to Agent calls by order: both are chronological.
                val runs = s.agentRuns
                val files = s.subagentRuns
                val perType = LinkedHashMap<String, Pair<Int, Long>>()
                for ((i, run) in runs.withIndex()) {
                    val tokens = files.getOrNull(i)?.totals?.total ?: 0L
                    val cur = perType[run.type] ?: (0 to 0L)
                    perType[run.type] = (cur.first + 1) to (cur.second + tokens)
                }
                if (files.size > runs.size) {
                    val extra = files.drop(runs.size).sumOf { it.totals.total }
                    val cur = perType["other"] ?: (0 to 0L)
                    perType["other"] = (cur.first + files.size - runs.size) to (cur.second + extra)
                }
                val total = s.subagentTotals.total.coerceAtLeast(1)
                detailPanel.setParts(
                    SpreadLine("Subagents", "${runs.size.coerceAtLeast(files.size)} runs · ${Fmt.tokens(s.subagentTotals.total)} tokens", leftFont = IntUi.ui(12f, 600), leftColor = IntUi.fg),
                    RankedRows(36, 52).apply {
                        emptyText = "No subagent runs in this session"
                        rows = perType.entries.sortedByDescending { it.value.second }.map { (name, v) ->
                            RankedRows.Row(name, "${v.first}×", Fmt.tokens(v.second), mono = true, col1Info = true, share = v.second.toDouble() / total, barColor = IntUi.purple)
                        }
                    },
                    Divider(),
                    TextLine("Subagent tokens are counted separately from the main context window.", IntUi.ui(12f), IntUi.info, wrap = true),
                )
            }
        }
        detailPanel.isVisible = true
        body.revalidate(); body.repaint()
    }
}

/** Tool calls: tiles per group and a detail panel for the selected group. */
class ToolsSection(private val detail: SingleDetail) {
    val headerRight = InlineText("", IntUi.ui(12f), IntUi.info)
    private val grid = JPanel(GridLayout(0, 3, px(6), px(6))).apply { isOpaque = false }
    private val detailPanel = DetailPanel().apply { isVisible = false }
    val body: JPanel = stack(8, grid, detailPanel)
    private var groups: List<Pair<String, Int>> = emptyList()
    private var stats: Map<String, Map<String, com.ods.claudeusage.model.ToolStat>> = emptyMap()
    private val tiles = ArrayList<StatTile>()

    init { detail.onChange { renderDetail() } }

    fun update(toolCalls: Map<String, Int>, toolStats: Map<String, Map<String, com.ods.claudeusage.model.ToolStat>>, thinkingTokens: Long) {
        headerRight.text = "Thinking ${Fmt.tokens(thinkingTokens)} tokens"
        val sorted = toolCalls.entries.sortedByDescending { it.value }.map { it.key to it.value }
        stats = toolStats
        if (sorted.map { it.first } != groups.map { it.first }) {
            groups = sorted
            grid.removeAll(); tiles.clear()
            for ((i, g) in sorted.withIndex()) {
                val tile = StatTile(g.first, String.format(Locale.US, "%,d", g.second), false) { detail.toggle("tool$i") }
                tiles += tile; grid.add(tile)
            }
            grid.revalidate()
        } else {
            groups = sorted
            for ((i, g) in sorted.withIndex()) tiles[i].value = String.format(Locale.US, "%,d", g.second)
        }
        renderDetail()
    }

    private fun renderDetail() {
        val key = detail.key
        val idx = key?.takeIf { it.startsWith("tool") }?.removePrefix("tool")?.toIntOrNull()
        tiles.forEachIndexed { i, t -> t.active = i == idx }
        val group = idx?.let { groups.getOrNull(it) }
        if (group == null) { detailPanel.isVisible = false; body.revalidate(); return }
        val tools = stats[group.first] ?: emptyMap()
        val calls = tools.values.sumOf { it.calls }.coerceAtLeast(group.second)
        val tokens = tools.values.sumOf { it.resultTokens }
        val errors = tools.values.sumOf { it.errors }
        detailPanel.setParts(
            SpreadLine(group.first, "${String.format(Locale.US, "%,d", calls)} calls this session", leftFont = IntUi.ui(12f, 600), leftColor = IntUi.fg),
            MiniStats(3).apply {
                stats = listOf(
                    MiniStats.Stat("Result tokens", Fmt.tokens(tokens)),
                    MiniStats.Stat("Avg / call", if (calls > 0) Fmt.tokens(tokens / calls) else "0"),
                    MiniStats.Stat("Errors", if (errors > 0 && calls > 0) "$errors (${String.format(Locale.US, "%.1f", errors * 100.0 / calls)}%)" else "0"),
                )
            },
            TextLine("Most used", IntUi.ui(12f), IntUi.info),
            RankedRows(40, 52).apply {
                emptyText = "No calls recorded"
                rows = tools.entries.sortedByDescending { it.value.calls }.take(6).map { (name, st) ->
                    RankedRows.Row(name, "${st.calls}×", Fmt.tokens(st.resultTokens), mono = true, col1Info = true, share = if (calls > 0) st.calls.toDouble() / calls else 0.0, barColor = IntUi.c1,
                        tooltip = "$name: ${st.calls} calls, ${Fmt.tokensExact(st.resultTokens)} result tokens, ${st.errors} errors")
                }
            },
        )
        detailPanel.isVisible = true
        body.revalidate(); body.repaint()
    }
}

/** Models: tokens by model for the 5-hour or 7-day window. */
class ModelsSection {
    private val subRow = SpreadLine("Tokens by model · last 7 days", "", rightFont = IntUi.ui(13f, 500), right2 = " tokens")
    private val bar = StackedBar()
    private val rows = RankedRows(56, 40, 8).apply { emptyText = "No usage in the last 7 days" }
    val body: JPanel = stack(6, subRow, bar, JPanel(VerticalLayout(0)).apply { isOpaque = false; add(rows) })
    private var models: List<com.ods.claudeusage.model.ModelUsage> = emptyList()
    val seg = SegControl(listOf("5 h", "7 d")) { render() }

    init { seg.selectedIndex = 1 }

    fun update(m: List<com.ods.claudeusage.model.ModelUsage>) { models = m; render() }

    private fun color(model: String, i: Int) = when {
        model.contains("opus") -> IntUi.c1
        model.contains("fable") || model.contains("mythos") -> IntUi.purple
        model.contains("haiku") -> IntUi.green
        else -> listOf(IntUi.c2, IntUi.c3, IntUi.c4)[i % 3]
    }

    private fun render() {
        val fiveHour = seg.selectedIndex == 0
        subRow.left = "Tokens by model · " + if (fiveHour) "last 5 hours" else "last 7 days"
        val data = models.map { Triple(it, if (fiveHour) it.fiveHour.total else it.sevenDay.total, it.turns7d) }.filter { it.second > 0 }.sortedByDescending { it.second }
        val total = data.sumOf { it.second }.coerceAtLeast(1)
        val totalTurns = data.sumOf { it.third }.coerceAtLeast(1)
        subRow.right = Fmt.tokens(total)
        bar.segments = data.mapIndexed { i, (m, tok, _) -> StackedBar.Segment(tok, color(m.model, i), "${Fmt.modelName(m.model)} ${Fmt.tokens(tok)}") }
        rows.rows = data.mapIndexed { i, (m, tok, turns) ->
            RankedRows.Row(Fmt.modelName(m.model), Fmt.tokens(tok), Fmt.percent(tok * 100.0 / total), color(m.model, i), mono = true, share = turns.toDouble() / totalTurns, barColor = IntUi.c4, barLabel = "$turns turns", tooltip = m.model)
        }
        body.revalidate(); body.repaint()
    }
}
