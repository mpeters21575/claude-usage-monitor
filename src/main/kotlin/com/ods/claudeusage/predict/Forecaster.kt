package com.ods.claudeusage.predict

import com.ods.claudeusage.model.ContextForecast
import com.ods.claudeusage.model.ForecastVerdict
import com.ods.claudeusage.model.SessionInfo
import com.ods.claudeusage.model.TurnEvent
import com.ods.claudeusage.model.UsageSample
import com.ods.claudeusage.model.UsageWindow
import com.ods.claudeusage.model.WindowForecast
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong

/** Straight-line extrapolation of recent usage; deliberately simple and explainable. */
object Forecaster {

    private val LOOKBACK = Duration.ofMinutes(60)
    private val MIN_SPAN = Duration.ofMinutes(3)
    private const val FLAT_RATE_PER_HOUR = 0.05

    fun windowForecast(window: UsageWindow, samples: List<UsageSample>, events: List<TurnEvent>, now: Instant): WindowForecast {
        val resetMillis = window.resetsAt?.toEpochMilli()
        val since = now.minus(LOOKBACK)
        val points = samples.asSequence()
            .filter { !it.at.isBefore(since) }
            .filter { resetMillis == null || it.resets[window.key] == resetMillis }
            .mapNotNull { s -> s.windows[window.key]?.let { s.at.toEpochMilli() to it } }
            .toList() + (now.toEpochMilli() to window.utilization)

        val span = if (points.size >= 2) points.last().first - points.first().first else 0L
        val slopePerMs = if (points.size >= 3 && span >= MIN_SPAN.toMillis()) leastSquaresSlope(points) else null
        val ratePerHour = (slopePerMs ?: 0.0) * 3_600_000.0

        var verdict = when {
            slopePerMs == null -> ForecastVerdict.INSUFFICIENT_DATA
            ratePerHour < FLAT_RATE_PER_HOUR -> ForecastVerdict.FLAT
            else -> ForecastVerdict.OK
        }
        var eta: Instant? = null
        var projectedAtReset: Double? = null
        if (verdict == ForecastVerdict.OK) {
            val msToLimit = (100.0 - window.utilization) / slopePerMs!!
            eta = now.plusMillis(msToLimit.coerceAtMost(1e12).roundToLong())
            if (window.resetsAt != null) {
                val msToReset = Duration.between(now, window.resetsAt).toMillis().coerceAtLeast(0)
                projectedAtReset = (window.utilization + slopePerMs * msToReset).coerceIn(0.0, 100.0)
                if (eta.isBefore(window.resetsAt)) verdict = ForecastVerdict.WILL_HIT_LIMIT
            } else {
                verdict = ForecastVerdict.WILL_HIT_LIMIT
            }
        }
        if (window.utilization >= 99.5) verdict = ForecastVerdict.WILL_HIT_LIMIT

        val start = window.windowStart
        val filter = window.modelFilter
        val used = if (start == null) 0L else events.asSequence()
            .filter { !it.at.isBefore(start) && !it.at.isAfter(now) }
            .filter { filter == null || it.model.contains(filter, ignoreCase = true) }
            .sumOf { it.totals.total }
        val capacity = if (used > 0 && window.utilization >= 0.5) (used / (window.utilization / 100.0)).roundToLong() else null
        val left = capacity?.let { (it - used).coerceAtLeast(0) }

        return WindowForecast(
            key = window.key,
            label = window.label,
            utilization = window.utilization,
            verdict = verdict,
            ratePerHour = ratePerHour,
            etaToLimit = eta,
            projectedAtReset = projectedAtReset,
            resetsAt = window.resetsAt,
            usedTokensInWindow = used,
            estimatedCapacityTokens = capacity,
            estimatedTokensLeft = left,
        )
    }

    fun contextForecast(session: SessionInfo, thresholdPercent: Int, now: Instant): ContextForecast {
        val since = now.minus(Duration.ofMinutes(20))
        val recent = session.contextHistory.filter { !it.first.isBefore(since) }.ifEmpty { session.contextHistory.takeLast(8) }
        val points = recent.map { it.first.toEpochMilli() to it.second.toDouble() }
        val slope = if (points.size >= 2 && points.last().first > points.first().first) leastSquaresSlope(points) else null
        val perMinute = (slope ?: 0.0) * 60_000.0
        val thresholdTokens = session.contextWindow * thresholdPercent / 100.0

        if (session.contextPercent >= thresholdPercent) {
            return ContextForecast(ForecastVerdict.WILL_HIT_LIMIT, perMinute, now, null, thresholdPercent)
        }
        if (slope == null) return ContextForecast(ForecastVerdict.INSUFFICIENT_DATA, perMinute, null, null, thresholdPercent)
        if (perMinute < 50.0) return ContextForecast(ForecastVerdict.FLAT, perMinute, null, null, thresholdPercent)

        val etaThreshold = now.plusMillis(((thresholdTokens - session.contextTokens) / slope).coerceIn(0.0, 1e12).roundToLong())
        val etaFull = now.plusMillis(((session.contextWindow - session.contextTokens) / slope).coerceIn(0.0, 1e12).roundToLong())
        return ContextForecast(ForecastVerdict.OK, perMinute, etaThreshold, etaFull, thresholdPercent)
    }

    /** Ordinary least squares slope of y over x (x in millis). */
    fun leastSquaresSlope(points: List<Pair<Long, Double>>): Double? {
        if (points.size < 2) return null
        val x0 = points.first().first
        val n = points.size.toDouble()
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for ((xRaw, y) in points) {
            val x = (xRaw - x0).toDouble()
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val denom = n * sxx - sx * sx
        if (denom == 0.0) return null
        return (n * sxy - sx * sy) / denom
    }
}
