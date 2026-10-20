package com.ods.claudeusage.auth

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.HttpRequests
import com.ods.claudeusage.model.Credentials
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Exchanges the refresh token stored by Claude Code for a fresh access token.
 *
 * Refresh tokens rotate, so when [refresh] is asked to write back it replaces both tokens in
 * `.credentials.json` atomically; otherwise Claude Code would be left with a revoked refresh token.
 */
class TokenRefresher {

    class RefreshException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    fun refresh(current: Credentials, credentialsFile: Path?, writeBack: Boolean): Credentials {
        val refreshToken = current.refreshToken?.takeIf { it.isNotBlank() }
            ?: throw RefreshException("No refresh token available (source: ${current.source}).")

        val payload = JsonObject().apply {
            addProperty("grant_type", "refresh_token")
            addProperty("refresh_token", refreshToken)
            addProperty("client_id", CLAUDE_CODE_CLIENT_ID)
        }

        val body = try {
            HttpRequests.post(TOKEN_URL, HttpRequests.JSON_CONTENT_TYPE)
                .productNameAsUserAgent()
                .accept("application/json")
                .connectTimeout(15_000)
                .readTimeout(20_000)
                .connect { request ->
                    request.write(payload.toString())
                    request.readString()
                }
        } catch (e: HttpRequests.HttpStatusException) {
            throw RefreshException("Token endpoint returned HTTP ${e.statusCode}. Sign in again with `claude`.", e)
        } catch (e: Exception) {
            throw RefreshException("Token refresh failed: ${e.message}", e)
        }

        val json = JsonParser.parseString(body).asJsonObject
        val access = json.get("access_token")?.asString ?: throw RefreshException("Token response has no access_token.")
        val newRefresh = json.get("refresh_token")?.takeIf { it.isJsonPrimitive }?.asString ?: refreshToken
        val expiresIn = json.get("expires_in")?.takeIf { it.isJsonPrimitive }?.asLong ?: 3600L
        val expiresAt = Instant.now().plusSeconds(expiresIn)

        val refreshed = current.copy(
            accessToken = access,
            refreshToken = newRefresh,
            expiresAt = expiresAt,
            source = current.source,
        )

        if (writeBack && credentialsFile != null && Files.isRegularFile(credentialsFile)) {
            writeBack(credentialsFile, refreshed)
        }
        return refreshed
    }

    private fun writeBack(file: Path, creds: Credentials) {
        try {
            val root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).asJsonObject
            val oauth = root.getAsJsonObject("claudeAiOauth") ?: JsonObject().also { root.add("claudeAiOauth", it) }
            oauth.addProperty("accessToken", creds.accessToken)
            oauth.addProperty("refreshToken", creds.refreshToken)
            oauth.addProperty("expiresAt", creds.expiresAt?.toEpochMilli())
            val tmp = file.resolveSibling(".credentials.json.${System.nanoTime()}.tmp")
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8)
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
            LOG.info("Wrote refreshed Claude credentials to $file")
        } catch (e: Exception) {
            throw RefreshException("Token refreshed but writing $file failed: ${e.message}", e)
        }
    }

    companion object {
        private val LOG = logger<TokenRefresher>()
        private const val TOKEN_URL = "https://console.anthropic.com/v1/oauth/token"

        /** Public OAuth client id of the Claude Code CLI (PKCE public client, no secret). */
        private const val CLAUDE_CODE_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    }
}
