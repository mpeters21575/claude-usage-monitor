package com.ods.claudeusage.ui.statusbar

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import com.ods.claudeusage.model.CredentialState
import com.ods.claudeusage.model.MonitorState
import com.ods.claudeusage.service.UsageListener
import com.ods.claudeusage.service.UsageMonitorService
import com.ods.claudeusage.settings.UsageSettings
import com.ods.claudeusage.ui.Fmt
import java.awt.Component
import java.awt.event.MouseEvent

class UsageStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID
    override fun getDisplayName(): String = "Claude Usage"
    override fun isAvailable(project: Project): Boolean = UsageSettings.getInstance().state.showStatusBarWidget
    override fun createWidget(project: Project): StatusBarWidget = UsageStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val ID = "ClaudeUsageStatusBar"
    }
}

class UsageStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private var text = "Claude …"
    private var tooltip: String? = "Claude usage: loading"

    override fun ID(): String = UsageStatusBarWidgetFactory.ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(UsageMonitorService.TOPIC, UsageListener { state -> ApplicationManager.getApplication().invokeLater({ apply(state) }, project.disposed) })
        apply(UsageMonitorService.getInstance().state)
    }

    private fun apply(state: MonitorState) {
        val service = UsageMonitorService.getInstance()
        val session = service.sessionFor(project.basePath)
        val parts = ArrayList<String>()
        val usage = state.usage
        if (usage != null) {
            usage.window("five_hour")?.let { parts += "5h ${Fmt.percent(it.utilization)}" }
            usage.window("seven_day")?.let { parts += "7d ${Fmt.percent(it.utilization)}" }
        } else {
            parts += when (state.credentialState) {
                is CredentialState.Expired -> "token expired"
                is CredentialState.Missing -> "not signed in"
                is CredentialState.Available -> if (state.usageError != null) "offline" else "…"
            }
        }
        session?.let { parts += "ctx ${Fmt.percent(it.contextPercent)}" }
        text = "Claude " + parts.joinToString(" · ")

        val tip = StringBuilder("<html><b>Claude Usage</b>")
        state.account?.let { tip.append("<br>").append(it.label).append(" (").append(it.plan).append(")") }
        usage?.windows?.forEach { w ->
            tip.append("<br>").append(w.label).append(": ").append(Fmt.percent(w.utilization)).append(" used, resets ").append(Fmt.relative(w.resetsAt))
        }
        session?.let {
            tip.append("<br>Session ").append(Fmt.modelName(it.model)).append(": ").append(Fmt.tokens(it.contextTokens))
                .append(" / ").append(Fmt.tokens(it.contextWindow)).append(" context")
        }
        state.usageError?.let { tip.append("<br><i>").append(it).append("</i>") }
        tip.append("<br><br>Click to open the dashboard</html>")
        tooltip = tip.toString()
        statusBar?.updateWidget(ID())
    }

    override fun getText(): String = text
    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT
    override fun getTooltipText(): String? = tooltip

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ToolWindowManager.getInstance(project).getToolWindow("Claude Usage")?.show()
    }

    override fun dispose() {
        statusBar = null
    }
}
