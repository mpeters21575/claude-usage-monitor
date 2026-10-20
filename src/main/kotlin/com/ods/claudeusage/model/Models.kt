package com.ods.claudeusage.model

import java.time.Duration
import java.time.Instant

/** OAuth credentials of the Claude account that Claude Code is signed in with. */
data class Credentials(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant?,
    val refreshTokenExpiresAt: Instant?,
    val subscriptionType: String?,
    val rateLimitTier: String?,
    val source: String,
) {
    val isExpired: Boolean get() = expiresAt != null && Instant.now().isAfter(expiresAt)

    val canRefresh: Boolean
        get() = !refreshToken.isNullOrBlank() &&
            (refreshTokenExpiresAt == null || Instant.now().isBefore(refreshTokenExpiresAt))

    /** Human friendly plan name, e.g. "Team / Max 5x". */
    fun planLabel(): String {
        val plan = subscriptionType?.replaceFirstChar { it.uppercase() }
        val tier = rateLimitTier
            ?.removePrefix("default_")
            ?.removePrefix("claude_")
            ?.replace('_', ' ')
            ?.replaceFirstChar { it.uppercase() }
        return listOfNotNull(plan, tier).distinct().joinToString("  ·  ").ifBlank { "Unknown plan" }
    }
}

sealed interface CredentialState {
    data class Available(val credentials: Credentials) : CredentialState
    data class Expired(val credentials: Credentials) : CredentialState
    data class Missing(val reason: String) : CredentialState

    val credentialsOrNull: Credentials?
        get() = when (this) {
            is Available -> credentials
            is Expired -> credentials
            is Missing -> null
        }
}

/** One rate-limit window as reported by Anthropic (utilization is 0..100). */
data class UsageWindow(
    val key: String,
    val label: String,
    val utilization: Double,
    val resetsAt: Instant?,
    val length: Duration?,
) {
    val remainingPercent: Double get() = (100.0 - utilization).coerceIn(0.0, 100.0)
    val windowStart: Instant? get() = if (resetsAt != null && length != null) resetsAt.minus(length) else null

    /** Model family this window is restricted to (lower-case substring of the model id), or null for all models. */
    val modelFilter: String?
        get() = listOf("opus", "sonnet", "haiku", "fable", "mythos").firstOrNull { key.contains(it, ignoreCase = true) }
}

data class ExtraUsage(
    val isEnabled: Boolean,
    val monthlyLimit: Double?,
    val usedCredits: Double?,
    val utilization: Double?,
)

data class SubscriptionUsage(
    val windows: List<UsageWindow>,
    val extraUsage: ExtraUsage?,
    val fetchedAt: Instant,
) {
    fun window(key: String): UsageWindow? = windows.firstOrNull { it.key == key }
}

/** Token counters of one assistant turn, or an aggregate of many. */
data class TokenTotals(
    val input: Long = 0,
    val output: Long = 0,
    val cacheCreation: Long = 0,
    val cacheRead: Long = 0,
) {
    val total: Long get() = input + output + cacheCreation + cacheRead
    val uncached: Long get() = input + output

    operator fun plus(o: TokenTotals) = TokenTotals(
        input + o.input, output + o.output, cacheCreation + o.cacheCreation, cacheRead + o.cacheRead,
    )

    companion object {
        val ZERO = TokenTotals()
    }
}

/** One assistant message found in a transcript. */
data class TurnEvent(
    val at: Instant,
    val sessionId: String,
    val model: String,
    val totals: TokenTotals,
    /** Prompt size of this turn = everything the model had in context. */
    val contextTokens: Long,
    val isSidechain: Boolean,
)

data class SessionInfo(
    val sessionId: String,
    val projectDirName: String,
    val filePath: String,
    val title: String?,
    val cwd: String?,
    val gitBranch: String?,
    val claudeVersion: String?,
    val model: String?,
    val contextTokens: Long,
    val contextWindow: Long,
    val lastTurnAt: Instant?,
    val firstTurnAt: Instant?,
    val turns: Int,
    val totals: TokenTotals,
    val subagentTotals: TokenTotals,
    /** Context growth history (time, prompt tokens) of this session, oldest first. */
    val contextHistory: List<Pair<Instant, Long>>,
    val breakdown: UsageBreakdown,
    val userTurns: Int = 0,
    /** (time, output tokens) per assistant turn, oldest first. */
    val outputHistory: List<Pair<Instant, Long>> = emptyList(),
    val agentRuns: List<AgentRun> = emptyList(),
    val subagentRuns: List<SubagentRun> = emptyList(),
) {
    val contextPercent: Double get() = if (contextWindow <= 0) 0.0 else contextTokens * 100.0 / contextWindow
}

/** Calls, estimated result tokens and errors of one tool (by its exact name). */
data class ToolStat(val calls: Int = 0, val resultTokens: Long = 0, val errors: Int = 0) {
    operator fun plus(o: ToolStat) = ToolStat(calls + o.calls, resultTokens + o.resultTokens, errors + o.errors)
}

/** One `Agent` tool call in a main transcript. */
data class AgentRun(val at: Instant, val type: String)

/** One subagent transcript of a session. */
data class SubagentRun(val firstAt: Instant?, val totals: TokenTotals, val agentId: String)

/** How often and when a customization (skill, agent, command, MCP server) was used. */
data class UsageStat(val count: Int, val lastUsed: Instant?) {
    operator fun plus(o: UsageStat) = UsageStat(count + o.count, listOfNotNull(lastUsed, o.lastUsed).maxOrNull())
}

data class UsageCounts(
    val skills: Map<String, UsageStat>,
    val agents: Map<String, UsageStat>,
    val commands: Map<String, UsageStat>,
    val mcpServers: Map<String, UsageStat>,
) {
    companion object {
        val EMPTY = UsageCounts(emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }
}

/**
 * Where the tokens of a session (or a period) went. [byType] is exact (from the API usage counters);
 * [bySource] is an estimate from the transcript content (about four characters per token), grouped by what
 * produced the content: user prompts, assistant text, thinking, tool calls, MCP tool results per server,
 * built-in tool results per family, and so on.
 */
data class UsageBreakdown(
    val byType: TokenTotals,
    val thinkingTokens: Long,
    val bySource: Map<String, Long>,
    val toolCalls: Map<String, Int>,
    val webSearches: Int,
    val webFetches: Int,
    /** tool group -> exact tool name -> stats */
    val toolStats: Map<String, Map<String, ToolStat>> = emptyMap(),
) {
    val estimatedTotal: Long get() = bySource.values.sum()

    companion object {
        val EMPTY = UsageBreakdown(TokenTotals.ZERO, 0, emptyMap(), emptyMap(), 0, 0)
    }
}

/** Identity of the signed-in Claude account, resolved from the OAuth profile endpoint when possible. */
data class AccountInfo(
    /** Stable key used to detect account switches. */
    val key: String,
    val email: String?,
    val displayName: String?,
    val organization: String?,
    val plan: String,
) {
    val label: String get() = email ?: displayName ?: organization ?: plan
}

data class ModelUsage(
    val model: String,
    val fiveHour: TokenTotals,
    val sevenDay: TokenTotals,
    val turns7d: Int,
    val lastUsed: Instant?,
)

enum class ForecastVerdict { INSUFFICIENT_DATA, FLAT, OK, WILL_HIT_LIMIT }

data class WindowForecast(
    val key: String,
    val label: String,
    val utilization: Double,
    val verdict: ForecastVerdict,
    /** Percent points per hour. */
    val ratePerHour: Double,
    val etaToLimit: Instant?,
    val projectedAtReset: Double?,
    val resetsAt: Instant?,
    val usedTokensInWindow: Long,
    /** Estimated capacity of the window in tokens, extrapolated from utilization and local token counts. */
    val estimatedCapacityTokens: Long?,
    val estimatedTokensLeft: Long?,
)

data class ContextForecast(
    val verdict: ForecastVerdict,
    val tokensPerMinute: Double,
    val etaToThreshold: Instant?,
    val etaToFull: Instant?,
    val thresholdPercent: Int,
)

/** One point of the chart history. */
data class UsageSample(
    val at: Instant,
    /** window key -> utilization percent */
    val windows: Map<String, Double>,
    /** window key -> reset epoch millis, used to detect a new window */
    val resets: Map<String, Long>,
    val contextPercent: Double?,
    val contextTokens: Long?,
    val sessionId: String?,
    val accountKey: String? = null,
)

data class MonitorState(
    val credentialState: CredentialState,
    val account: AccountInfo?,
    val usage: SubscriptionUsage?,
    val usageError: String?,
    val models: List<ModelUsage>,
    val forecasts: List<WindowForecast>,
    val weekBreakdown: UsageBreakdown,
    val lastUpdated: Instant?,
    val lastApiSuccessAt: Instant?,
    val nextApiPollAt: Instant?,
    /** Set while Anthropic's usage endpoint answers 429; polling resumes afterwards. */
    val rateLimitedUntil: Instant?,
    val transcriptFilesIndexed: Int,
    val isRefreshing: Boolean,
) {
    val isRateLimited: Boolean get() = rateLimitedUntil != null && Instant.now().isBefore(rateLimitedUntil)

    /** Usage data is present and the last error, if any, is only a temporary rate limit. */
    val isConnected: Boolean get() = usage != null && (usageError == null || isRateLimited) && credentialState is CredentialState.Available

    companion object {
        val EMPTY = MonitorState(
            credentialState = CredentialState.Missing("Not loaded yet"),
            account = null,
            usage = null,
            usageError = null,
            models = emptyList(),
            forecasts = emptyList(),
            weekBreakdown = UsageBreakdown.EMPTY,
            lastUpdated = null,
            lastApiSuccessAt = null,
            nextApiPollAt = null,
            rateLimitedUntil = null,
            transcriptFilesIndexed = 0,
            isRefreshing = false,
        )
    }
}
