package com.ods.claudeusage.auth

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.ods.claudeusage.model.CredentialState
import com.ods.claudeusage.model.Credentials
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Finds the OAuth credentials Claude Code is signed in with.
 *
 * Lookup order: `CLAUDE_CODE_OAUTH_TOKEN` environment variable, `<claude home>/.credentials.json`,
 * and on macOS the "Claude Code-credentials" keychain item.
 */
class CredentialStore {

    data class Lookup(val state: CredentialState, val file: Path?)

    fun load(claudeHome: Path): Lookup {
        System.getenv("CLAUDE_CODE_OAUTH_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
            val creds = Credentials(token, null, null, null, null, null, "CLAUDE_CODE_OAUTH_TOKEN")
            return Lookup(CredentialState.Available(creds), null)
        }

        val file = claudeHome.resolve(".credentials.json")
        if (Files.isRegularFile(file)) {
            try {
                val json = Files.readString(file, StandardCharsets.UTF_8)
                val creds = parse(json, file.toString())
                if (creds != null) return Lookup(stateFor(creds), file)
                return Lookup(CredentialState.Missing("No claudeAiOauth entry in $file. Sign in with Claude Code first."), file)
            } catch (e: Exception) {
                LOG.warn("Cannot read $file", e)
                return Lookup(CredentialState.Missing("Cannot read $file: ${e.message}"), file)
            }
        }

        if (SystemInfo.isMac) {
            try {
                val cmd = GeneralCommandLine("security", "find-generic-password", "-s", "Claude Code-credentials", "-w")
                val out = ExecUtil.execAndGetOutput(cmd, 5_000)
                if (out.exitCode == 0 && out.stdout.isNotBlank()) {
                    val creds = parse(out.stdout.trim(), "macOS keychain")
                    if (creds != null) return Lookup(stateFor(creds), null)
                }
            } catch (e: Exception) {
                LOG.warn("Keychain lookup failed", e)
            }
        }

        return Lookup(
            CredentialState.Missing("No Claude Code credentials found in $claudeHome. Run `claude` once and sign in."),
            null,
        )
    }

    private fun stateFor(c: Credentials): CredentialState =
        if (c.isExpired) CredentialState.Expired(c) else CredentialState.Available(c)

    companion object {
        private val LOG = logger<CredentialStore>()

        fun parse(json: String, source: String): Credentials? {
            val root = JsonParser.parseString(json).asJsonObject
            val oauth: JsonObject = root.getAsJsonObject("claudeAiOauth") ?: return null
            val access = oauth.get("accessToken")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            return Credentials(
                accessToken = access,
                refreshToken = oauth.get("refreshToken")?.takeIf { it.isJsonPrimitive }?.asString,
                expiresAt = oauth.get("expiresAt")?.takeIf { it.isJsonPrimitive }?.asLong?.let(Instant::ofEpochMilli),
                refreshTokenExpiresAt = oauth.get("refreshTokenExpiresAt")?.takeIf { it.isJsonPrimitive }?.asLong
                    ?.let(Instant::ofEpochMilli),
                subscriptionType = oauth.get("subscriptionType")?.takeIf { it.isJsonPrimitive }?.asString,
                rateLimitTier = oauth.get("rateLimitTier")?.takeIf { it.isJsonPrimitive }?.asString,
                source = source,
            )
        }
    }
}
