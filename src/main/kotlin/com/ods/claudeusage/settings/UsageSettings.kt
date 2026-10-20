package com.ods.claudeusage.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.nio.file.Path
import java.nio.file.Paths

@Service(Service.Level.APP)
@State(name = "ClaudeUsageMonitorSettings", storages = [Storage("claudeUsageMonitor.xml")])
class UsageSettings : PersistentStateComponent<UsageSettings.State> {

    class State {
        /** Empty means: `$CLAUDE_CONFIG_DIR` if set, otherwise `~/.claude`. */
        var claudeHome: String = ""
        /** Anthropic rate-limits the usage endpoint hard; a few calls per quarter hour is what it tolerates. */
        var apiPollIntervalSeconds: Int = 300
        var transcriptPollIntervalSeconds: Int = 3
        var chartWindowMinutes: Int = 60
        var contextWindowTokens: Int = 200_000
        var largeContextWindowTokens: Int = 1_000_000
        var newSessionThresholdPercent: Int = 75
        var warnUtilizationPercent: Int = 80
        var criticalUtilizationPercent: Int = 95
        var notificationsEnabled: Boolean = true
        var autoRefreshToken: Boolean = true
        var showStatusBarWidget: Boolean = true
        var retentionDays: Int = 8
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    val claudeHomePath: Path
        get() {
            val configured = state.claudeHome.trim()
            if (configured.isNotEmpty()) return Paths.get(expandHome(configured))
            val env = System.getenv("CLAUDE_CONFIG_DIR")?.trim()
            if (!env.isNullOrEmpty()) return Paths.get(expandHome(env))
            return Paths.get(System.getProperty("user.home"), ".claude")
        }

    private fun expandHome(p: String): String =
        if (p.startsWith("~")) System.getProperty("user.home") + p.substring(1) else p

    companion object {
        fun getInstance(): UsageSettings = ApplicationManager.getApplication().getService(UsageSettings::class.java)
    }
}
