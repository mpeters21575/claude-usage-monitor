package com.ods.claudeusage.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.ods.claudeusage.service.UsageMonitorService

class UsageConfigurable : BoundConfigurable("Claude Usage Monitor") {

    override fun createPanel(): DialogPanel {
        val s = UsageSettings.getInstance().state
        return panel {
            group("Data Sources") {
                row("Claude home directory:") {
                    textField()
                        .columns(40)
                        .bindText(s::claudeHome)
                        .comment("Leave empty to use \$CLAUDE_CONFIG_DIR or ~/.claude. Credentials and transcripts are read from here.")
                }
                row("Usage API poll interval (seconds):") {
                    intTextField(120..3600).bindIntText(s::apiPollIntervalSeconds)
                        .comment(
                            "How often the account usage endpoint is queried. Anthropic rate-limits it hard (HTTP 429 for minutes after a burst); " +
                                "300 seconds is safe. Context and token figures come from local transcripts and update every few seconds regardless.",
                        )
                }
                row("Transcript scan interval (seconds):") {
                    intTextField(1..60).bindIntText(s::transcriptPollIntervalSeconds)
                }
                row("Transcript retention (days):") {
                    intTextField(1..30).bindIntText(s::retentionDays)
                        .comment("Transcripts older than this are not indexed. 8 days covers the 7-day window.")
                }
            }
            group("Context Window") {
                row("Default context window (tokens):") {
                    intTextField(1_000..10_000_000).bindIntText(s::contextWindowTokens)
                }
                row("Large context window (tokens):") {
                    intTextField(1_000..10_000_000).bindIntText(s::largeContextWindowTokens)
                        .comment("Used when the model id or Claude Code's configured model contains \"[1m]\".")
                }
                row("Recommend a new session at (%):") {
                    intTextField(10..100).bindIntText(s::newSessionThresholdPercent)
                        .comment("When the active session's context exceeds this share of the window, the dashboard asks you to start a new session.")
                }
            }
            group("Chart") {
                row("Chart time window (minutes):") {
                    intTextField(5..1440).bindIntText(s::chartWindowMinutes)
                }
            }
            group("Alerts") {
                row {
                    checkBox("Show balloon notifications").bindSelected(s::notificationsEnabled)
                }
                row("Warn at utilization (%):") {
                    intTextField(1..100).bindIntText(s::warnUtilizationPercent)
                }
                row("Critical at utilization (%):") {
                    intTextField(1..100).bindIntText(s::criticalUtilizationPercent)
                }
                row {
                    checkBox("Show status bar widget").bindSelected(s::showStatusBarWidget)
                }
            }
            group("Authentication") {
                row {
                    checkBox("Automatically refresh an expired access token")
                        .bindSelected(s::autoRefreshToken)
                        .comment(
                            "Uses the refresh token Claude Code stored and writes the new tokens back to .credentials.json " +
                                "so Claude Code keeps working with them. The plugin waits two minutes after the token expires " +
                                "and never refreshes while Claude Code is writing the file itself.",
                        )
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        UsageMonitorService.getInstance().settingsChanged()
    }
}
