package com.ods.claudeusage.predict

import com.ods.claudeusage.model.ForecastVerdict
import com.ods.claudeusage.model.SessionInfo
import com.ods.claudeusage.model.TokenTotals
import com.ods.claudeusage.model.TurnEvent
import com.ods.claudeusage.model.UsageBreakdown
import com.ods.claudeusage.model.UsageSample
import com.ods.claudeusage.model.UsageWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ForecasterTest {

    private val now: Instant = Instant.parse("2026-10-06T08:00:00Z")

    private fun samples(key: String, resetsAt: Instant, vararg minutesAgoToPercent: Pair<Int, Double>): List<UsageSample> =
        minutesAgoToPercent.map { (m, p) ->
            UsageSample(now.minus(Duration.ofMinutes(m.toLong())), mapOf(key to p), mapOf(key to resetsAt.toEpochMilli()), null, null, null)
        }

    @Test
    fun `steady growth that crosses 100 before reset is flagged`() {
        val reset = now.plus(Duration.ofHours(3))
        val window = UsageWindow("five_hour", "5-hour", 70.0, reset, Duration.ofHours(5))
        // 20 percent points per hour over the last half hour
        val s = samples("five_hour", reset, 30 to 60.0, 20 to 63.3, 10 to 66.7)
        val f = Forecaster.windowForecast(window, s, emptyList(), now)
        assertEquals(ForecastVerdict.WILL_HIT_LIMIT, f.verdict)
        assertTrue(f.ratePerHour in 18.0..22.0, "rate was ${f.ratePerHour}")
        assertNotNull(f.etaToLimit)
        assertTrue(f.etaToLimit!!.isBefore(reset))
        assertEquals(100.0, f.projectedAtReset)
    }

    @Test
    fun `slow growth is safe`() {
        val reset = now.plus(Duration.ofHours(1))
        val window = UsageWindow("five_hour", "5-hour", 50.0, reset, Duration.ofHours(5))
        val s = samples("five_hour", reset, 30 to 49.0, 15 to 49.5)
        val f = Forecaster.windowForecast(window, s, emptyList(), now)
        assertEquals(ForecastVerdict.OK, f.verdict)
        assertTrue(f.projectedAtReset!! < 60.0)
    }

    @Test
    fun `samples from a previous window are ignored`() {
        val reset = now.plus(Duration.ofHours(1))
        val window = UsageWindow("five_hour", "5-hour", 5.0, reset, Duration.ofHours(5))
        val old = samples("five_hour", reset.minus(Duration.ofHours(5)), 30 to 90.0, 20 to 95.0, 10 to 99.0)
        val f = Forecaster.windowForecast(window, old, emptyList(), now)
        assertEquals(ForecastVerdict.INSUFFICIENT_DATA, f.verdict)
    }

    @Test
    fun `token capacity is extrapolated from local usage`() {
        val reset = now.plus(Duration.ofHours(2))
        val window = UsageWindow("five_hour", "5-hour", 25.0, reset, Duration.ofHours(5))
        val events = listOf(
            TurnEvent(now.minus(Duration.ofHours(1)), "s", "claude-opus", TokenTotals(input = 1_000, output = 500, cacheRead = 98_500), 100_000, false),
            TurnEvent(now.minus(Duration.ofHours(6)), "s", "claude-opus", TokenTotals(input = 999_999), 0, false), // before window start
        )
        val f = Forecaster.windowForecast(window, emptyList(), events, now)
        assertEquals(100_000L, f.usedTokensInWindow)
        assertEquals(400_000L, f.estimatedCapacityTokens)
        assertEquals(300_000L, f.estimatedTokensLeft)
    }

    @Test
    fun `per model windows only count that model`() {
        val reset = now.plus(Duration.ofDays(2))
        val window = UsageWindow("seven_day_opus", "7-day Opus", 50.0, reset, Duration.ofDays(7))
        val events = listOf(
            TurnEvent(now.minus(Duration.ofHours(1)), "s", "claude-opus-4", TokenTotals(input = 1_000), 0, false),
            TurnEvent(now.minus(Duration.ofHours(1)), "s", "claude-sonnet-4", TokenTotals(input = 5_000), 0, false),
        )
        val f = Forecaster.windowForecast(window, emptyList(), events, now)
        assertEquals(1_000L, f.usedTokensInWindow)
    }

    @Test
    fun `context forecast reaches threshold`() {
        val history = (0..5).map { i -> now.minus(Duration.ofMinutes((10 - i * 2).toLong())) to (100_000L + i * 10_000L) }
        val session = SessionInfo(
            sessionId = "s", projectDirName = "p", filePath = "f", title = null, cwd = null, gitBranch = null, claudeVersion = null,
            model = "claude-opus", contextTokens = 150_000, contextWindow = 200_000, lastTurnAt = now, firstTurnAt = now, turns = 6,
            totals = TokenTotals.ZERO, subagentTotals = TokenTotals.ZERO, contextHistory = history, breakdown = UsageBreakdown.EMPTY,
        )
        val f = Forecaster.contextForecast(session, 90, now)
        assertEquals(ForecastVerdict.OK, f.verdict)
        assertTrue(f.tokensPerMinute in 4_500.0..5_500.0, "rate ${f.tokensPerMinute}")
        // 30k tokens to the 90% threshold at ~5k/min => about 6 minutes
        val minutes = Duration.between(now, f.etaToThreshold).toMinutes()
        assertTrue(minutes in 5..7, "eta minutes $minutes")

        val over = Forecaster.contextForecast(session.copy(contextTokens = 190_000), 90, now)
        assertEquals(ForecastVerdict.WILL_HIT_LIMIT, over.verdict)
    }
}
