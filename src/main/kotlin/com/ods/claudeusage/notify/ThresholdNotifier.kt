package com.ods.claudeusage.notify

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import com.ods.claudeusage.model.ContextForecast
import com.ods.claudeusage.model.ForecastVerdict
import com.ods.claudeusage.model.MonitorState
import com.ods.claudeusage.model.SessionInfo
import com.ods.claudeusage.settings.UsageSettings
import com.ods.claudeusage.ui.Fmt
import java.util.Collections

/** Fires each alert once per window reset (or once per session for context alerts). */
class ThresholdNotifier {

    private val fired: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    /** Forget what was already reported, e.g. after an account switch. */
    fun reset() = fired.clear()

    fun evaluate(state: MonitorState, session: SessionInfo?, contextForecast: ContextForecast?, settings: UsageSettings.State) {
        if (!settings.notificationsEnabled) return
        val usage = state.usage ?: return

        for (w in usage.windows) {
            val resetKey = w.resetsAt?.toEpochMilli() ?: 0L
            if (w.utilization >= settings.criticalUtilizationPercent) {
                once("critical:${w.key}:$resetKey") {
                    notify(
                        "Claude ${w.label} limit almost reached",
                        "${Fmt.percent(w.utilization)} used. Resets ${Fmt.relative(w.resetsAt)}.",
                        NotificationType.ERROR,
                    )
                }
            } else if (w.utilization >= settings.warnUtilizationPercent) {
                once("warn:${w.key}:$resetKey") {
                    notify(
                        "Claude ${w.label} window at ${Fmt.percent(w.utilization)}",
                        "Resets ${Fmt.relative(w.resetsAt)}.",
                        NotificationType.WARNING,
                    )
                }
            }
        }

        for (f in state.forecasts) {
            if (f.verdict == ForecastVerdict.WILL_HIT_LIMIT && f.etaToLimit != null && f.utilization < settings.criticalUtilizationPercent) {
                once("forecast:${f.key}:${f.resetsAt?.toEpochMilli() ?: 0L}") {
                    notify(
                        "Claude ${f.label} limit will be hit before it resets",
                        "At the current pace (${Fmt.rate(f.ratePerHour)}) you reach 100% ${Fmt.relative(f.etaToLimit)}, " +
                            "before the reset ${Fmt.relative(f.resetsAt)}.",
                        NotificationType.WARNING,
                    )
                }
            }
        }

        if (session != null && contextForecast != null && contextForecast.verdict == ForecastVerdict.WILL_HIT_LIMIT) {
            once("context:${session.sessionId}") {
                notify(
                    "Claude Code session is getting large",
                    "The active session uses ${Fmt.tokens(session.contextTokens)} of its ${Fmt.tokens(session.contextWindow)} context " +
                        "(${Fmt.percent(session.contextPercent)}). Consider starting a new session.",
                    NotificationType.WARNING,
                )
            }
        }
    }

    private inline fun once(key: String, block: () -> Unit) {
        if (fired.add(key)) {
            if (fired.size > 500) fired.iterator().let { it.next(); it.remove() }
            block()
        }
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID) ?: return
        val notification = group.createNotification(title, content, type)
        notification.addAction(
            NotificationAction.createSimpleExpiring("Open Claude Usage") {
                val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return@createSimpleExpiring
                ToolWindowManager.getInstance(project).getToolWindow("Claude Usage")?.show()
            },
        )
        notification.notify(ProjectManager.getInstance().openProjects.firstOrNull())
    }

    companion object {
        const val GROUP_ID = "Claude Usage Monitor"
    }
}
