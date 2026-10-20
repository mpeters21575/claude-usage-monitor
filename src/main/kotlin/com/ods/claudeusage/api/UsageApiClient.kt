package com.ods.claudeusage.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.util.io.HttpRequests
import com.ods.claudeusage.model.AccountInfo
import com.ods.claudeusage.model.ExtraUsage
import com.ods.claudeusage.model.SubscriptionUsage
import com.ods.claudeusage.model.UsageWindow
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Reads the account rate-limit usage that Claude Code shows with `/usage`.
 *
 * The response is a flat object whose entries are windows (`five_hour`, `seven_day`, `seven_day_opus`, ...)
 * with `utilization` (percent) and `resets_at`, an optional `extra_usage` object, and a `limits` array with
 * per-scope entries (for example a weekly limit for one model). The parser keeps every window it finds rather
 * than hard-coding the list, so new windows show up automatically.
 *
 * The endpoint is rate limited aggressively (a burst of requests gets HTTP 429 for minutes), so callers must
 * poll sparingly and honour [UsageApiException.retryAfter].
 */
class UsageApiClient {

    class UsageApiException(
        val statusCode: Int,
        message: String,
        cause: Throwable? = null,
        /** Server-suggested wait from the `Retry-After` header, when present and positive. */
        val retryAfter: Duration? = null,
    ) : RuntimeException(message, cause)

    fun fetch(accessToken: String): SubscriptionUsage = parse(get(USAGE_URL, accessToken), Instant.now())

    /**
     * Resolves who is signed in. Returns null when the endpoint is unavailable; callers fall back to the
     * plan information stored next to the token.
     */
    fun fetchProfile(accessToken: String, plan: String): AccountInfo? {
        val body = try {
            get(PROFILE_URL, accessToken)
        } catch (e: UsageApiException) {
            if (e.statusCode == 401 || e.statusCode == 429) throw e
            return null
        }
        return try {
            parseProfile(JsonParser.parseString(body).asJsonObject, plan)
        } catch (_: Exception) {
            null
        }
    }

    /** Raw outcome of one HTTP call; exceptions are only raised outside the HTTP helper's callback. */
    private class Response(val code: Int, val body: String?, val retryAfter: Duration?, val detail: String?)

    private fun get(url: String, accessToken: String): String {
        val response = try {
            HttpRequests.request(url)
                .productNameAsUserAgent()
                .accept("application/json")
                .connectTimeout(15_000)
                .readTimeout(20_000)
                .throwStatusCodeException(false)
                .tuner { c ->
                    c.setRequestProperty("Authorization", "Bearer $accessToken")
                    c.setRequestProperty("anthropic-beta", OAUTH_BETA)
                }
                .connect { request ->
                    val connection = request.connection as HttpURLConnection
                    val code = connection.responseCode
                    if (code in 200..299) {
                        Response(code, request.readString(), null, null)
                    } else {
                        val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let(Duration::ofSeconds)
                        val detail = try {
                            connection.errorStream?.readBytes()?.toString(StandardCharsets.UTF_8)?.trim()?.take(200)
                        } catch (_: Exception) { null }
                        Response(code, null, retryAfter, detail)
                    }
                }
        } catch (e: Exception) {
            throw UsageApiException(0, "Network error: " + (e.message ?: e.javaClass.simpleName), e)
        }
        if (response.body != null) return response.body
        val code = response.code
        val hint = when (code) {
            401 -> "access token expired or revoked"
            403 -> "account has no access to usage data"
            429 -> "rate limited by Anthropic"
            in 500..599 -> "Anthropic service error"
            else -> "unexpected response"
        }
        throw UsageApiException(code, "HTTP $code: $hint" + (response.detail?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""), null, response.retryAfter)
    }

    companion object {
        private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
        private const val PROFILE_URL = "https://api.anthropic.com/api/oauth/profile"
        private const val OAUTH_BETA = "oauth-2025-04-20"

        private val LABELS = mapOf(
            "five_hour" to "5-hour",
            "seven_day" to "7-day",
            "seven_day_opus" to "7-day Opus",
            "seven_day_sonnet" to "7-day Sonnet",
            "seven_day_haiku" to "7-day Haiku",
            "seven_day_oauth_apps" to "7-day Apps",
        )

        /** Windows in display order; unknown keys sort after these alphabetically. */
        private val ORDER = listOf("five_hour", "seven_day", "seven_day_opus", "seven_day_sonnet", "seven_day_haiku")

        fun parse(body: String, fetchedAt: Instant): SubscriptionUsage {
            val root = JsonParser.parseString(body).asJsonObject
            val windows = ArrayList<UsageWindow>()
            var extra: ExtraUsage? = null
            for ((key, value) in root.entrySet()) {
                if (!value.isJsonObject) continue
                val obj = value.asJsonObject
                if (key == "extra_usage") {
                    extra = ExtraUsage(
                        isEnabled = obj.get("is_enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                        monthlyLimit = obj.number("monthly_limit"),
                        usedCredits = obj.number("used_credits"),
                        utilization = obj.number("utilization"),
                    )
                    continue
                }
                val utilization = obj.number("utilization") ?: continue
                windows += UsageWindow(
                    key = key,
                    label = LABELS[key] ?: humanize(key),
                    utilization = utilization.coerceIn(0.0, 100.0),
                    resetsAt = obj.string("resets_at")?.let(::parseInstant),
                    length = lengthFor(key),
                )
            }
            // Scoped limits (e.g. a weekly limit for one model) that are not already covered by a top-level window.
            root.array("limits")?.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val limit = element.asJsonObject
                val percent = limit.number("percent") ?: return@forEach
                val resetsAt = limit.string("resets_at")?.let(::parseInstant)
                val scopeName = limit.obj("scope")?.let { scope ->
                    scope.obj("model")?.let { it.string("display_name") ?: it.string("id") } ?: scope.string("surface")
                }
                val kind = limit.string("kind") ?: return@forEach
                val group = limit.string("group") ?: kind
                val duplicate = windows.any { w -> w.resetsAt == resetsAt && w.utilization == percent && scopeName == null }
                if (duplicate) return@forEach
                val key = "limit:$kind" + (scopeName?.let { ":${it.lowercase()}" } ?: "")
                if (windows.any { it.key == key }) return@forEach
                val base = when (group) { "session" -> "5-hour"; "weekly" -> "7-day"; else -> humanize(group) }
                windows += UsageWindow(
                    key = key,
                    label = if (scopeName != null) "$base $scopeName" else "$base ${humanize(kind)}",
                    utilization = percent.coerceIn(0.0, 100.0),
                    resetsAt = resetsAt,
                    length = when (group) { "session" -> Duration.ofHours(5); "weekly" -> Duration.ofDays(7); else -> null },
                )
            }
            windows.sortWith(compareBy({ ORDER.indexOf(it.key).let { i -> if (i < 0) ORDER.size else i } }, { it.key }))
            return SubscriptionUsage(windows, extra, fetchedAt)
        }

        fun parseProfile(root: JsonObject, plan: String): AccountInfo? {
            val account = root.obj("account") ?: root
            val email = findString(account, "email", "email_address") ?: findString(root, "email", "email_address")
            val name = findString(account, "display_name", "full_name", "name")
            val org = root.obj("organization")?.let { findString(it, "name", "display_name") }
                ?: root.array("organizations")?.firstOrNull { it.isJsonObject }?.asJsonObject?.let { findString(it, "name") }
            val id = findString(account, "uuid", "id") ?: email ?: org
            if (id == null && name == null) return null
            return AccountInfo(key = id ?: name!!, email = email, displayName = name, organization = org, plan = plan)
        }

        private fun findString(o: JsonObject, vararg names: String): String? {
            for (n in names) {
                val v = o.get(n)
                if (v != null && v.isJsonPrimitive && v.asJsonPrimitive.isString && v.asString.isNotBlank()) return v.asString
            }
            return null
        }

        private fun JsonObject.number(name: String): Double? =
            get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

        private fun JsonObject.string(name: String): String? =
            get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

        /** Null-safe object member: Gson's getAsJsonObject throws on JSON null. */
        private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

        private fun JsonObject.array(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray

        private fun lengthFor(key: String): Duration? = when {
            key.startsWith("five_hour") -> Duration.ofHours(5)
            key.startsWith("seven_day") -> Duration.ofDays(7)
            key.startsWith("one_hour") -> Duration.ofHours(1)
            key.startsWith("one_day") -> Duration.ofDays(1)
            key.startsWith("monthly") -> Duration.ofDays(30)
            else -> null
        }

        private fun humanize(key: String): String =
            key.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        fun parseInstant(s: String): Instant? = try {
            Instant.parse(s)
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(s).toInstant()
            } catch (_: Exception) {
                null
            }
        }
    }
}
