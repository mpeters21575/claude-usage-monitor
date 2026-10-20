package com.ods.claudeusage.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.ods.claudeusage.model.CredentialState
import com.ods.claudeusage.model.MonitorState
import com.ods.claudeusage.model.SessionInfo
import com.ods.claudeusage.model.UsageBreakdown
import com.ods.claudeusage.model.UsageWindow
import com.ods.claudeusage.ui.intui.WindowModel
import com.ods.claudeusage.notify.ThresholdNotifier
import com.ods.claudeusage.service.UsageListener
import com.ods.claudeusage.service.UsageMonitorService
import com.ods.claudeusage.settings.UsageConfigurable
import com.ods.claudeusage.settings.UsageSettings
import com.ods.claudeusage.transcript.TranscriptIndex
import com.ods.claudeusage.ui.intui.BigNumberRow
import com.ods.claudeusage.ui.intui.Chip
import com.ods.claudeusage.ui.intui.ContextChart
import com.ods.claudeusage.ui.intui.ContextModel
import com.ods.claudeusage.ui.intui.CustomizationsSection
import com.ods.claudeusage.ui.intui.HeaderBar
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
import com.ods.claudeusage.ui.intui.Strip
import com.ods.claudeusage.ui.intui.TextLine
import com.ods.claudeusage.ui.intui.ToolsSection
import com.ods.claudeusage.ui.intui.stack
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.time.Duration
import java.time.Instant
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Tool window content, version 1.1: Int UI look, collapsible sections, context card with the combined
 * history/forecast chart, clickable stat tiles, customizations manager and models breakdown.
 */
class UsageDashboardPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val service = UsageMonitorService.getInstance()
    private val settings get() = UsageSettings.getInstance().state

    private val header = HeaderBar { openSettings() }
    private val account = TextLine("", IntUi.ui(12f), IntUi.info)

    // notice (no equivalent in the design; shown only when something needs attention)
    private val noticeText = TextLine("", IntUi.ui(12f), IntUi.fg, wrap = true)
    private val noticeActions = JPanel(FlowLayout(FlowLayout.LEFT, px(6), 0)).apply { isOpaque = false }
    private var noticeColor = IntUi.border
    private val notice = Strip({ noticeColor }, noticeText, noticeActions).apply { isVisible = false }

    // context card: three views on one card
    private val statusChip = Chip("Connecting…")
    private val bigNumber = BigNumberRow()
    private val caption = SpreadLine("", "", leftFont = IntUi.ui(11f), rightFont = IntUi.ui(11f), rightColor = IntUi.info)
    private val chart = ContextChart()
    private val contextStats = MiniStats(3)
    private val viewSeg = SegControl(listOf("Context", "5 h", "7 d")) { i ->
        com.intellij.ide.util.PropertiesComponent.getInstance(project).setValue(VIEW_KEY, i, 0)
        lastState?.let(::render)
    }

    private val detail = SingleDetail()
    private val sessionSection = SessionSection(detail)
    private val toolsSection = ToolsSection(detail)
    private val customizations = CustomizationsSection(project)
    private val modelsSection = ModelsSection()

    // where tokens go
    private val sourcesSeg = SegControl(listOf("This session", "7 days")) { lastState?.let(::render) }
    private val sourcesSub = SpreadLine("By source · estimated from transcripts", "", rightFont = IntUi.ui(13f, 500), right2 = " tokens")
    private val sourcesBar = StackedBar()
    private val sourcesRows = RankedRows(48, 42).apply { emptyText = "No transcript content indexed yet" }

    // by token type
    private val typesBar = StackedBar()
    private val typesLegend = LegendGrid(2, 6, 16)

    private val footer = TextLine("", IntUi.ui(11f), IntUi.info, wrap = true)

    private var lastState: MonitorState? = null
    private val clock = Timer(1000) { lastState?.let(::render) }

    init {
        isOpaque = true
        background = IntUi.bg

        val content = JPanel(VerticalLayout(px(20))).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(px(12), px(12), px(16), px(12))
            add(account)
            add(notice)
            add(Section("ctx", "Context window", viewSeg, stack(8, JPanel(BorderLayout()).apply { isOpaque = false; add(statusChip, BorderLayout.EAST) }, bigNumber, stack(6, caption, chart), contextStats), card = true, gapCss = 8, project = project))
            add(Section("session", "Active session", sessionSection.headerRight, sessionSection.body, gapCss = 12, project = project))
            add(Section("sources", "Where tokens go", sourcesSeg, stack(10, sourcesSub, sourcesBar, sourcesRows), project = project))
            add(Section("types", "By token type", InlineText("Exact · API counts", IntUi.ui(12f), IntUi.info), stack(8, typesBar, typesLegend), gapCss = 8, project = project))
            add(Section("tools", "Tool calls", toolsSection.headerRight, toolsSection.body, gapCss = 8, project = project))
            add(Section("cust", "Customizations", customizations.headerRight, customizations.body, project = project))
            add(Section("models", "Models", modelsSection.seg, modelsSection.body, gapCss = 6, project = project))
        }
        val footerBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(px(6), px(12), px(6), px(12))
            add(footer, BorderLayout.CENTER)
        }
        val column = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(content, BorderLayout.CENTER)
            add(object : JPanel(BorderLayout()) {
                init { isOpaque = false; add(footerBar, BorderLayout.CENTER) }
                override fun paintComponent(g: java.awt.Graphics) { g.color = IntUi.border; g.fillRect(0, 0, width, 1) }
            }, BorderLayout.SOUTH)
        }
        val scroll = JBScrollPane(column).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.isOpaque = false
            isOpaque = false
            verticalScrollBar.unitIncrement = px(16)
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)

        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(UsageMonitorService.TOPIC, UsageListener { state -> ApplicationManager.getApplication().invokeLater({ onState(state) }, project.disposed) })
        connection.subscribe(UsageMonitorService.SETTINGS_TOPIC, UsageMonitorService.SettingsListener { ApplicationManager.getApplication().invokeLater({ lastState?.let(::render) }, project.disposed) })

        viewSeg.selectedIndex = com.intellij.ide.util.PropertiesComponent.getInstance(project).getInt(VIEW_KEY, 0).coerceIn(0, 2)
        onState(service.state)
        clock.start()
    }

    // ---- rendering -----------------------------------------------------------------------------------------

    private fun onState(state: MonitorState) {
        lastState = state
        render(state)
    }

    private var lastCountsAt: Instant? = null

    private fun render(state: MonitorState) {
        background = IntUi.bg
        val now = Instant.now()
        val session = service.sessionFor(project.basePath)

        renderHeader(state, now)
        renderNotice(state)
        when (viewSeg.selectedIndex) {
            1 -> renderWindow(state, "five_hour", "5-hour", now)
            2 -> renderWindow(state, "seven_day", "7-day", now)
            else -> renderContext(session, now)
        }
        sessionSection.update(session, now)
        renderSources(state, session)
        val b = session?.breakdown ?: UsageBreakdown.EMPTY
        toolsSection.update(b.toolCalls, b.toolStats, b.thinkingTokens)
        if (lastCountsAt == null || Duration.between(lastCountsAt, now) > Duration.ofSeconds(5)) {
            lastCountsAt = now
            customizations.update(service.usageCounts(7))
        }
        modelsSection.update(state.models)
        footer.text = "Reading ${UsageSettings.getInstance().claudeHomePath} · ${state.transcriptFilesIndexed} transcripts indexed · " +
            "usage API every ${settings.apiPollIntervalSeconds} s, transcripts every ${settings.transcriptPollIntervalSeconds} s"
        repaint()
    }

    private fun renderHeader(state: MonitorState, now: Instant) {
        val creds = state.credentialState.credentialsOrNull
        account.text = when {
            state.account != null -> "${state.account.label} · ${state.account.plan} plan"
            creds != null -> creds.planLabel()
            else -> "Not connected"
        }
        val sinceSuccess = state.lastApiSuccessAt?.let { Duration.between(it, now) }
        val stale = sinceSuccess != null && sinceSuccess > Duration.ofMinutes(20)
        val (status, dot) = when {
            state.isRefreshing -> "Refreshing…" to IntUi.blue
            state.isConnected && !stale -> (if (state.isRateLimited) "Connected · rate limited" else "Connected") to IntUi.green
            state.isConnected -> "Stale" to IntUi.yellow
            state.credentialState is CredentialState.Expired -> "Token expired" to IntUi.yellow
            state.credentialState is CredentialState.Available && state.usageError != null -> (if (state.usage == null) "Connecting…" else "Reconnecting") to IntUi.yellow
            else -> "Disconnected" to IntUi.red
        }
        val tip = listOfNotNull(
            sinceSuccess?.let { "Last successful fetch ${Fmt.duration(it)} ago" },
            state.nextApiPollAt?.let { Duration.between(now, it) }?.takeIf { !it.isNegative }?.let { "Next poll in ${Fmt.duration(it)}" },
            state.usageError,
        ).joinToString("<br>").let { if (it.isEmpty()) null else "<html>$it</html>" }
        header.update(status, dot, tip)
    }

    private fun showNotice(color: java.awt.Color, text: String, vararg buttons: JComponent) {
        noticeColor = color
        noticeText.text = text
        noticeActions.removeAll(); buttons.forEach(noticeActions::add); noticeActions.isVisible = buttons.isNotEmpty()
        notice.isVisible = true
        notice.revalidate(); notice.repaint()
    }

    private fun renderNotice(state: MonitorState) {
        val cs = state.credentialState
        when {
            cs is CredentialState.Expired -> showNotice(
                IntUi.yellow,
                "The stored Claude access token has expired." + (if (settings.autoRefreshToken) " It is refreshed automatically two minutes after expiry unless Claude Code refreshes it first." else ""),
                *(if (cs.credentials.canRefresh) arrayOf<JComponent>(PillButton("Refresh now", PillButton.Style.PRIMARY) { refreshToken() }) else emptyArray()),
            )
            cs is CredentialState.Missing -> showNotice(IntUi.border, "Not signed in. Sign in through Claude Code (the Rider plugin or claude in a terminal); the panel connects to that account automatically.", PillButton("Settings") { openSettings() })
            state.isRateLimited -> showNotice(
                IntUi.border,
                "Anthropic's usage endpoint is rate limited (HTTP 429). " + (state.lastApiSuccessAt?.let { "Showing rate-limit data from ${Fmt.clock(it)}; " } ?: "") +
                    "next attempt ${Fmt.relative(state.rateLimitedUntil)}. Context and token figures keep updating from the local transcripts.",
            )
            state.usageError != null -> showNotice(IntUi.yellow, (if (state.usage != null) "Showing the last successful data. " else "") + "Usage API: ${state.usageError}", PillButton("Retry") { service.refreshNow() })
            else -> if (notice.isVisible) { notice.isVisible = false; notice.revalidate() }
        }
    }

    private fun renderContext(session: SessionInfo?, now: Instant) {
        val m = ContextModel(session, settings.newSessionThresholdPercent, now)
        statusChip.text = m.status
        bigNumber.set(m)
        caption.left = "Last ${m.minutesOfHistory.coerceAtLeast(30.0).coerceAtMost(240.0).toInt()} min · forecast at current rate"
        caption.right = m.trend
        chart.set(m)
        contextStats.stats = listOf(
            MiniStats.Stat("Rate", if (m.rate > 0) "+${Fmt.tokens(m.rate.toLong())}/min" else "–", "Context growth over the last four turns"),
            MiniStats.Stat("${m.thresholdPercent}% in", if (m.percent >= m.thresholdPercent) "now" else ContextModel.durationLabel(m.min75)),
            MiniStats.Stat("Full in", ContextModel.durationLabel(m.min100)),
        )
    }

    /** 5-hour / 7-day view: hero = time left until the reset, chart = utilisation with forecast and reset marker. */
    private fun renderWindow(state: MonitorState, key: String, label: String, now: Instant) {
        val w = state.usage?.window(key)
        if (w == null) {
            statusChip.text = if (state.usage == null) "Waiting for usage data" else "No $label window"
            bigNumber.set("–", "", "", if (state.usage == null) "usage API not reached yet" else "Anthropic did not report this window", secondMono = false)
            caption.left = "$label window"; caption.right = ""
            chart.set(WindowModel(UsageWindow(key, label, 0.0, null, null), null, emptyList(), now))
            contextStats.stats = listOf(MiniStats.Stat("Used", "–"), MiniStats.Stat("100% in", "–"), MiniStats.Stat("Resets in", "–"))
            return
        }
        val f = state.forecasts.firstOrNull { it.key == key }
        val accountKey = state.account?.key
        val samples = service.history.snapshot().filter { it.accountKey == null || accountKey == null || it.accountKey == accountKey }
        val m = WindowModel(w, f, samples, now)
        statusChip.text = m.status
        val tokensLeft = f?.estimatedTokensLeft?.let { "≈ ${Fmt.tokens(it)} left" } ?: "${Fmt.percent(w.remainingPercent)} left"
        bigNumber.set(
            ContextModel.durationLabel(m.minutesToReset).replace(" min", "m").replace(" h ", "h "),
            Fmt.percent(w.utilization), "used",
            tokensLeft + (if (w.resetsAt != null) " · resets ${Fmt.clock(w.resetsAt)}" else ""), secondMono = false,
        )
        caption.left = "Last ${m.pastMinutes.toInt().let { if (it >= 60) "${it / 60} h" else "$it min" }} · forecast at current rate"
        caption.right = f?.takeIf { it.ratePerHour > 0 }?.let { "+${Fmt.rate(it.ratePerHour)}" } ?: "Rate steady"
        val stats = ArrayList<MiniStats.Stat>()
        stats += MiniStats.Stat("Used", Fmt.percent(w.utilization), f?.usedTokensInWindow?.let { "${Fmt.tokens(it)} tokens used in this window" })
        stats += MiniStats.Stat("100% in", if (m.hitsBeforeReset) ContextModel.durationLabel(m.min100) else "not before reset")
        stats += MiniStats.Stat("Resets in", ContextModel.durationLabel(m.minutesToReset), w.resetsAt?.let { "Resets at ${Fmt.clock(it)}" })
        if (key == "seven_day") {
            for (other in state.usage!!.windows.filter { it.key != key && it.length == java.time.Duration.ofDays(7) }) {
                stats += MiniStats.Stat(other.label.removePrefix("7-day ").ifBlank { other.label }, Fmt.percent(other.utilization), "${other.label}: ${Fmt.percent(other.utilization)} used, resets ${Fmt.relative(other.resetsAt, now)}")
            }
        }
        contextStats.stats = stats
    }

    private fun renderSources(state: MonitorState, session: SessionInfo?) {
        val b: UsageBreakdown = if (sourcesSeg.selectedIndex == 1) state.weekBreakdown else session?.breakdown ?: UsageBreakdown.EMPTY
        val sources = LinkedHashMap(b.bySource)
        if (b.thinkingTokens > 0) sources[TranscriptIndex.SOURCE_THINKING] = b.thinkingTokens
        val ordered = sources.entries.sortedByDescending { it.value }
        val total = ordered.sumOf { it.value }.coerceAtLeast(1)
        sourcesSub.right = Fmt.tokens(total)
        val ramp = IntUi.ramp
        sourcesBar.segments = ordered.mapIndexed { i, e -> StackedBar.Segment(e.value, ramp[i % ramp.size], "${e.key} ${Fmt.tokens(e.value)}") }
        sourcesRows.rows = ordered.mapIndexed { i, e ->
            RankedRows.Row(e.key, Fmt.tokens(e.value), String.format(java.util.Locale.US, "%.1f%%", e.value * 100.0 / total), ramp[i % ramp.size],
                tooltip = "${e.key}: ${Fmt.tokensExact(e.value)} tokens" + (b.toolCalls[e.key.removeSuffix(" results")]?.let { " · $it calls" } ?: ""))
        }
        val t = (session?.breakdown ?: UsageBreakdown.EMPTY).byType
        typesBar.segments = listOf(
            StackedBar.Segment(t.cacheRead, IntUi.c1, "Cache read ${Fmt.tokens(t.cacheRead)}"), StackedBar.Segment(t.cacheCreation, IntUi.c2, "Cache write ${Fmt.tokens(t.cacheCreation)}"),
            StackedBar.Segment(t.output, IntUi.c4, "Output ${Fmt.tokens(t.output)}"), StackedBar.Segment(t.input, IntUi.c3, "Input ${Fmt.tokens(t.input)}"),
        )
        typesLegend.items = listOf(
            LegendGrid.Item("Cache read", Fmt.tokens(t.cacheRead), IntUi.c1), LegendGrid.Item("Cache write", Fmt.tokens(t.cacheCreation), IntUi.c2),
            LegendGrid.Item("Output", Fmt.tokens(t.output), IntUi.c4), LegendGrid.Item("Input", Fmt.tokens(t.input), null, outline = true),
        )
    }

    // ---- actions -------------------------------------------------------------------------------------------

    private fun refreshToken() {
        service.refreshTokenNow { result ->
            val group = NotificationGroupManager.getInstance().getNotificationGroup(ThresholdNotifier.GROUP_ID) ?: return@refreshTokenNow
            result.fold(
                onSuccess = { group.createNotification("Claude access token refreshed", "Valid until ${Fmt.clock(it.expiresAt)}.", NotificationType.INFORMATION).notify(project) },
                onFailure = { group.createNotification("Claude token refresh failed", it.message ?: it.javaClass.simpleName, NotificationType.ERROR).notify(project) },
            )
        }
    }

    private fun openSettings() = ShowSettingsUtil.getInstance().showSettingsDialog(project, UsageConfigurable::class.java)

    override fun dispose() {
        clock.stop()
    }

    companion object {
        private const val VIEW_KEY = "claudeUsage.contextView"
    }
}
