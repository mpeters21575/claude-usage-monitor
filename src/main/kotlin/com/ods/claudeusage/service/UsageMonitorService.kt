package com.ods.claudeusage.service

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import com.ods.claudeusage.api.UsageApiClient
import com.ods.claudeusage.auth.CredentialStore
import com.ods.claudeusage.auth.TokenRefresher
import com.ods.claudeusage.history.HistoryStore
import com.ods.claudeusage.model.AccountInfo
import com.ods.claudeusage.model.ContextForecast
import com.ods.claudeusage.model.CredentialState
import com.ods.claudeusage.model.Credentials
import com.ods.claudeusage.model.MonitorState
import com.ods.claudeusage.model.SessionInfo
import com.ods.claudeusage.model.SubscriptionUsage
import com.ods.claudeusage.model.UsageSample
import com.ods.claudeusage.notify.ThresholdNotifier
import com.ods.claudeusage.predict.Forecaster
import com.ods.claudeusage.settings.UsageSettings
import com.ods.claudeusage.transcript.TranscriptIndex
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Listener for state changes; always called on a background thread. */
fun interface UsageListener {
    fun stateChanged(state: MonitorState)
}

/** Creates the monitor as soon as the first project opens so the status bar and notifications work without the tool window. */
class UsageStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        UsageMonitorService.getInstance()
    }
}

@Service(Service.Level.APP)
class UsageMonitorService : Disposable {

    private val executor = AppExecutorUtil.createBoundedScheduledExecutorService("Claude Usage Monitor", 1)
    private val credentialStore = CredentialStore()
    private val apiClient = UsageApiClient()
    private val refresher = TokenRefresher()
    private val transcripts = TranscriptIndex()
    private val notifier = ThresholdNotifier()

    val history = HistoryStore(Path.of(PathManager.getSystemPath(), "claude-usage-monitor", "history.jsonl"))

    @Volatile
    var state: MonitorState = MonitorState.EMPTY
        private set

    private val settings get() = UsageSettings.getInstance()

    private var apiFuture: ScheduledFuture<*>? = null
    private var transcriptFuture: ScheduledFuture<*>? = null

    // Everything below is only touched from the single-threaded executor.
    private var credentialsFile: Path? = null
    private var credentialsStamp: String? = null
    private var credentialState: CredentialState = CredentialState.Missing("Not loaded yet")
    private var tokenFingerprint: String? = null
    private var account: AccountInfo? = null
    private var usage: SubscriptionUsage? = null
    private var usageError: String? = null
    private var lastApiSuccessAt: Instant? = null
    private var nextApiPollAt: Instant? = null
    private var backoffUntil: Instant? = null
    private var rateLimitedUntil: Instant? = null
    private var consecutiveFailures = 0
    private var lastSampleAt: Instant? = null

    init {
        executor.execute { history.load() }
        schedule()
    }

    @Synchronized
    private fun schedule() {
        apiFuture?.cancel(false)
        transcriptFuture?.cancel(false)
        val s = settings.state
        apiFuture = executor.scheduleWithFixedDelay(::pollApiSafely, 0, s.apiPollIntervalSeconds.toLong().coerceAtLeast(15), TimeUnit.SECONDS)
        transcriptFuture = executor.scheduleWithFixedDelay(::pollTranscriptsSafely, 1, s.transcriptPollIntervalSeconds.toLong().coerceAtLeast(1), TimeUnit.SECONDS)
        nextApiPollAt = Instant.now()
    }

    fun settingsChanged() {
        schedule()
        ApplicationManager.getApplication().messageBus.syncPublisher(SETTINGS_TOPIC).settingsChanged()
    }

    /** Forces an immediate API fetch and transcript scan. */
    fun refreshNow() {
        executor.execute {
            backoffUntil = null
            rateLimitedUntil = null
            publish(isRefreshing = true)
            pollApiSafely()
            pollTranscriptsSafely()
        }
    }

    /** Exchanges the refresh token for a new access token, always writing the result back for Claude Code. */
    fun refreshTokenNow(onDone: (Result<Credentials>) -> Unit) {
        executor.execute {
            val result = runCatching {
                val lookup = credentialStore.load(settings.claudeHomePath)
                credentialsFile = lookup.file
                val current = lookup.state.credentialsOrNull ?: credentialState.credentialsOrNull
                    ?: throw TokenRefresher.RefreshException("No stored credentials to refresh.")
                val refreshed = refresher.refresh(current, credentialsFile, writeBack = true)
                credentialState = CredentialState.Available(refreshed)
                backoffUntil = null
                refreshed
            }
            onDone(result)
            pollApiSafely()
        }
    }

    /** The active Claude Code session for a project (or globally when [projectBasePath] is null). */
    fun sessionFor(projectBasePath: String?): SessionInfo? =
        transcripts.activeSession(projectBasePath, ::contextWindowFor)

    fun contextForecastFor(session: SessionInfo): ContextForecast =
        Forecaster.contextForecast(session, settings.state.newSessionThresholdPercent, Instant.now())

    /** Skill, agent, slash-command and MCP usage over the last [days] days. */
    fun usageCounts(days: Long = 7): com.ods.claudeusage.model.UsageCounts =
        transcripts.usageCounts(Instant.now().minus(Duration.ofDays(days)))

    /** Token usage per model (5-hour and 7-day windows). */
    fun modelUsage(): List<com.ods.claudeusage.model.ModelUsage> = transcripts.modelUsage(Instant.now())

    fun contextWindowFor(model: String?): Long {
        val s = settings.state
        val configured = transcripts.configuredModel
        val large = (model?.contains("[1m]", ignoreCase = true) == true) ||
            (configured?.contains("[1m]", ignoreCase = true) == true) ||
            (model?.contains("-1m", ignoreCase = true) == true)
        return if (large) s.largeContextWindowTokens.toLong() else s.contextWindowTokens.toLong()
    }

    // ---- polling -------------------------------------------------------------------------------------------

    private fun pollApiSafely() {
        try {
            pollApi()
        } catch (t: Throwable) {
            LOG.warn("Usage poll failed", t)
            usageError = t.message ?: t.javaClass.simpleName
        } finally {
            val regular = Instant.now().plusSeconds(settings.state.apiPollIntervalSeconds.toLong())
            nextApiPollAt = backoffUntil?.takeIf { it.isAfter(regular) } ?: regular
            publish(isRefreshing = false)
        }
    }

    private fun pollApi() {
        val now = Instant.now()
        if (backoffUntil?.isAfter(now) == true) return

        val lookup = credentialStore.load(settings.claudeHomePath)
        credentialsFile = lookup.file
        credentialsStamp = stampOf(lookup.file)
        var cs = lookup.state

        // Keep a token we refreshed ourselves if the file still holds the old, expired one.
        val mine = credentialState.credentialsOrNull
        if (cs is CredentialState.Expired && mine != null && !mine.isExpired && mine.accessToken != cs.credentials.accessToken) {
            cs = CredentialState.Available(mine)
        }

        if (cs is CredentialState.Expired && settings.state.autoRefreshToken && cs.credentials.canRefresh && refreshGraceElapsed(cs.credentials)) {
            try {
                cs = CredentialState.Available(refresher.refresh(cs.credentials, credentialsFile, writeBack = true))
                credentialsStamp = stampOf(credentialsFile)
            } catch (e: TokenRefresher.RefreshException) {
                LOG.warn(e.message)
                usageError = e.message
            }
        }
        credentialState = cs

        val creds = (cs as? CredentialState.Available)?.credentials
        if (creds == null) {
            usageError = when (cs) {
                is CredentialState.Expired -> "Access token expired. Waiting for Claude Code to refresh it, or refresh it from here."
                is CredentialState.Missing -> cs.reason
                else -> null
            }
            return
        }

        // Account identity: re-resolve whenever the token material changes (login, switch, refresh).
        val fingerprint = fingerprintOf(creds)
        if (fingerprint != tokenFingerprint || account == null) {
            val resolved = try {
                apiClient.fetchProfile(creds.accessToken, creds.planLabel())
            } catch (e: UsageApiClient.UsageApiException) {
                handleApiFailure(e, creds); return
            } ?: AccountInfo(
                key = "plan:" + (creds.subscriptionType ?: "?") + ":" + (creds.rateLimitTier ?: "?"),
                email = null, displayName = null, organization = null, plan = creds.planLabel(),
            )
            tokenFingerprint = fingerprint
            onAccountResolved(resolved)
        }

        try {
            usage = apiClient.fetch(creds.accessToken)
            usageError = null
            lastApiSuccessAt = Instant.now()
            backoffUntil = null
            rateLimitedUntil = null
            consecutiveFailures = 0
        } catch (e: UsageApiClient.UsageApiException) {
            handleApiFailure(e, creds)
        }
    }

    private fun handleApiFailure(e: UsageApiClient.UsageApiException, creds: Credentials) {
        usageError = e.message
        consecutiveFailures++
        val now = Instant.now()
        LOG.warn("Claude usage API call failed (status ${e.statusCode}, attempt $consecutiveFailures): ${e.message}", e.cause)
        when (e.statusCode) {
            401 -> {
                // Rejected although not expired by its own clock: treat as expired so a refresh is attempted next round.
                credentialState = CredentialState.Expired(creds.copy(expiresAt = creds.expiresAt ?: now.minusSeconds(1)))
                tokenFingerprint = null
            }
            429 -> {
                // The endpoint tolerates only a few calls per quarter hour. Back off exponentially (2, 4, 8, 15 minutes)
                // or for as long as the server asks, whichever is longer, and keep showing the last good data.
                val exponential = Duration.ofMinutes(minOf(15L, 2L shl (consecutiveFailures - 1).coerceIn(0, 3)))
                val wait = maxOf(e.retryAfter ?: Duration.ZERO, exponential)
                backoffUntil = now.plus(wait)
                rateLimitedUntil = backoffUntil
            }
            in 500..599 -> backoffUntil = now.plus(Duration.ofMinutes(2))
            0 -> backoffUntil = now.plus(Duration.ofSeconds(minOf(300L, 30L shl (consecutiveFailures - 1).coerceIn(0, 4)))) // network trouble
        }
    }

    private fun onAccountResolved(resolved: AccountInfo) {
        val previous = account
        account = resolved
        if (previous != null && previous.key != resolved.key) {
            LOG.info("Claude account switched from ${previous.label} to ${resolved.label}")
            usage = null
            usageError = null
            backoffUntil = null
            notifier.reset()
            notifyAccountSwitch(resolved)
        }
    }

    private fun notifyAccountSwitch(a: AccountInfo) {
        if (!settings.state.notificationsEnabled) return
        val group = NotificationGroupManager.getInstance().getNotificationGroup(ThresholdNotifier.GROUP_ID) ?: return
        group.createNotification("Claude account switched", "Now showing usage for ${a.label} (${a.plan}).", NotificationType.INFORMATION)
            .notify(ProjectManager.getInstance().openProjects.firstOrNull())
    }

    /** Wait a little after expiry, and never while Claude Code is actively rewriting the file, to avoid racing its own refresh. */
    private fun refreshGraceElapsed(c: Credentials): Boolean {
        val now = Instant.now()
        val expiredLongEnough = c.expiresAt == null || Duration.between(c.expiresAt, now) >= REFRESH_GRACE
        val file = credentialsFile
        val fileQuiet = file == null || try {
            Duration.between(Files.getLastModifiedTime(file).toInstant(), now) >= REFRESH_GRACE
        } catch (_: Exception) { true }
        return expiredLongEnough && fileQuiet
    }

    private fun pollTranscriptsSafely() {
        try {
            if (credentialsChangedOnDisk()) {
                backoffUntil = null
                pollApiSafely()
            }
            transcripts.scan(settings.claudeHomePath, Duration.ofDays(settings.state.retentionDays.toLong()))
            recordSample()
        } catch (t: Throwable) {
            LOG.warn("Transcript scan failed", t)
        } finally {
            publish(isRefreshing = false)
        }
    }

    /**
     * Cheap change detection on the credentials file: a login or account switch in Claude Code shows up here within
     * seconds. Claude Code also rewrites the file for unrelated reasons (MCP server tokens), so the API is only
     * hit again when the access token itself changed; the usage endpoint's quota is too small for anything else.
     */
    private fun credentialsChangedOnDisk(): Boolean {
        val file = credentialsFile ?: settings.claudeHomePath.resolve(".credentials.json")
        val stamp = stampOf(file)
        if (stamp == credentialsStamp) return false
        credentialsStamp = stamp
        val lookup = credentialStore.load(settings.claudeHomePath)
        credentialsFile = lookup.file
        val creds = lookup.state.credentialsOrNull
            ?: return credentialState !is CredentialState.Missing // signed out: re-evaluate once
        return fingerprintOf(creds) != tokenFingerprint
    }

    private fun stampOf(file: Path?): String? {
        if (file == null) return null
        return try {
            if (!Files.exists(file)) "missing" else Files.getLastModifiedTime(file).toMillis().toString() + ":" + Files.size(file)
        } catch (_: Exception) {
            null
        }
    }

    private fun fingerprintOf(c: Credentials): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(c.accessToken.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun recordSample() {
        val now = Instant.now()
        val last = lastSampleAt
        if (last != null && Duration.between(last, now) < SAMPLE_INTERVAL) return
        lastSampleAt = now
        val session = transcripts.activeSession(null, ::contextWindowFor)
        val u = usage
        history.add(
            UsageSample(
                at = now,
                windows = u?.windows?.associate { it.key to it.utilization } ?: emptyMap(),
                resets = u?.windows?.mapNotNull { w -> w.resetsAt?.let { w.key to it.toEpochMilli() } }?.toMap() ?: emptyMap(),
                contextPercent = session?.contextPercent,
                contextTokens = session?.contextTokens,
                sessionId = session?.sessionId,
                accountKey = account?.key,
            ),
        )
    }

    private fun publish(isRefreshing: Boolean) {
        val now = Instant.now()
        val u = usage
        val events = transcripts.eventsSince(now.minus(Duration.ofDays(7)))
        val accountKey = account?.key
        val samples = history.snapshot().filter { it.accountKey == null || accountKey == null || it.accountKey == accountKey }
        val forecasts = u?.windows?.map { Forecaster.windowForecast(it, samples, events, now) } ?: emptyList()
        val newState = MonitorState(
            credentialState = credentialState,
            account = account,
            usage = u,
            usageError = usageError,
            models = transcripts.modelUsage(now),
            forecasts = forecasts,
            weekBreakdown = transcripts.breakdownSince(now.minus(Duration.ofDays(7))),
            lastUpdated = now,
            lastApiSuccessAt = lastApiSuccessAt,
            nextApiPollAt = nextApiPollAt,
            rateLimitedUntil = rateLimitedUntil,
            transcriptFilesIndexed = transcripts.fileCount,
            isRefreshing = isRefreshing,
        )
        state = newState
        val session = transcripts.activeSession(null, ::contextWindowFor)
        notifier.evaluate(newState, session, session?.let(::contextForecastFor), settings.state)
        val app = ApplicationManager.getApplication()
        if (app != null && !app.isDisposed) app.messageBus.syncPublisher(TOPIC).stateChanged(newState)
    }

    override fun dispose() {
        apiFuture?.cancel(false)
        transcriptFuture?.cancel(false)
        executor.shutdownNow()
        history.compact()
    }

    fun interface SettingsListener {
        fun settingsChanged()
    }

    companion object {
        private val LOG = logger<UsageMonitorService>()
        private val SAMPLE_INTERVAL: Duration = Duration.ofSeconds(10)
        private val REFRESH_GRACE: Duration = Duration.ofMinutes(2)

        @JvmField
        val TOPIC: Topic<UsageListener> = Topic.create("Claude Usage Monitor", UsageListener::class.java)

        @JvmField
        val SETTINGS_TOPIC: Topic<SettingsListener> = Topic.create("Claude Usage Monitor Settings", SettingsListener::class.java)

        fun getInstance(): UsageMonitorService = ApplicationManager.getApplication().getService(UsageMonitorService::class.java)
    }
}
