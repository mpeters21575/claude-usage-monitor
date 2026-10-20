package com.ods.claudeusage.api

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class UsageApiClientTest {

    private val sample = """
        {
          "five_hour": {"utilization": 42.5, "resets_at": "2026-10-06T10:00:00Z"},
          "seven_day": {"utilization": 18, "resets_at": "2026-10-09T07:00:00+00:00"},
          "seven_day_opus": {"utilization": 3.25, "resets_at": "2026-10-09T07:00:00Z"},
          "some_future_window": {"utilization": 7, "resets_at": null},
          "extra_usage": {"is_enabled": true, "monthly_limit": 100, "used_credits": 12.5, "utilization": 12.5},
          "unrelated": "ignored"
        }
    """.trimIndent()

    @Test
    fun `parses every window and keeps display order`() {
        val usage = UsageApiClient.parse(sample, Instant.EPOCH)
        assertEquals(listOf("five_hour", "seven_day", "seven_day_opus", "some_future_window"), usage.windows.map { it.key })
        val fiveHour = usage.window("five_hour")!!
        assertEquals(42.5, fiveHour.utilization)
        assertEquals(Instant.parse("2026-10-06T10:00:00Z"), fiveHour.resetsAt)
        assertEquals(Duration.ofHours(5), fiveHour.length)
        assertEquals(Instant.parse("2026-10-06T05:00:00Z"), fiveHour.windowStart)
        assertEquals("5-hour", fiveHour.label)
    }

    @Test
    fun `offset date times and unknown windows are handled`() {
        val usage = UsageApiClient.parse(sample, Instant.EPOCH)
        assertEquals(Instant.parse("2026-10-09T07:00:00Z"), usage.window("seven_day")!!.resetsAt)
        val unknown = usage.window("some_future_window")!!
        assertNull(unknown.resetsAt)
        assertNull(unknown.length)
        assertEquals("Some Future Window", unknown.label)
        assertEquals("opus", usage.window("seven_day_opus")!!.modelFilter)
        assertNull(usage.window("seven_day")!!.modelFilter)
    }

    @Test
    fun `extra usage is parsed`() {
        val extra = UsageApiClient.parse(sample, Instant.EPOCH).extraUsage
        assertNotNull(extra)
        assertTrue(extra!!.isEnabled)
        assertEquals(100.0, extra.monthlyLimit)
        assertEquals(12.5, extra.usedCredits)
    }

    /** Shape observed from the live endpoint on 2026-10-06: many null windows plus a `limits` array with scoped entries. */
    private val live = """
        {"five_hour":{"utilization":28.0,"resets_at":"2026-10-06T10:29:59.886900+00:00","limit_dollars":null,"used_dollars":null,"remaining_dollars":null,"locked_reason":null},
         "seven_day":{"utilization":5.0,"resets_at":"2026-10-12T23:59:59.886925+00:00","limit_dollars":null,"used_dollars":null,"remaining_dollars":null,"locked_reason":null},
         "seven_day_oauth_apps":null,"seven_day_opus":null,"seven_day_sonnet":null,"tangelo":null,
         "extra_usage":{"is_enabled":false,"monthly_limit":null,"used_credits":null,"utilization":null,"currency":null,"user_disabled":true,"spend_limit_reached":false},
         "limits":[
           {"kind":"session","group":"session","percent":28,"severity":"normal","resets_at":"2026-10-06T10:29:59.886900+00:00","scope":null,"is_active":true},
           {"kind":"weekly_all","group":"weekly","percent":5,"severity":"normal","resets_at":"2026-10-12T23:59:59.886925+00:00","scope":null,"is_active":false},
           {"kind":"weekly_scoped","group":"weekly","percent":9,"severity":"normal","resets_at":"2026-10-12T23:59:59.887158+00:00","scope":{"model":{"id":null,"display_name":"Fable"},"surface":null},"is_active":true}
         ]}
    """.trimIndent()

    @Test
    fun `live response shape with null windows and scoped limits`() {
        val usage = UsageApiClient.parse(live, Instant.EPOCH)
        assertEquals(listOf("five_hour", "seven_day", "limit:weekly_scoped:fable"), usage.windows.map { it.key })
        assertEquals(28.0, usage.window("five_hour")!!.utilization)
        assertEquals(Instant.parse("2026-10-06T10:29:59.886900Z"), usage.window("five_hour")!!.resetsAt)
        val fable = usage.window("limit:weekly_scoped:fable")!!
        assertEquals("7-day Fable", fable.label)
        assertEquals(9.0, fable.utilization)
        assertEquals(Duration.ofDays(7), fable.length)
        assertEquals("fable", fable.modelFilter)
        val extra = usage.extraUsage!!
        assertEquals(false, extra.isEnabled)
        assertNull(extra.monthlyLimit)
    }

    @Test
    fun `profile parsing tolerates different shapes`() {
        val nested = JsonParser.parseString("""{"account":{"uuid":"abc","email_address":"dev@example.com","full_name":"Dev"},"organization":{"name":"ODS"}}""").asJsonObject
        val a = UsageApiClient.parseProfile(nested, "Team")!!
        assertEquals("abc", a.key)
        assertEquals("dev@example.com", a.email)
        assertEquals("ODS", a.organization)
        assertEquals("dev@example.com", a.label)

        val flat = JsonParser.parseString("""{"email":"x@y.z"}""").asJsonObject
        assertEquals("x@y.z", UsageApiClient.parseProfile(flat, "Pro")!!.key)

        assertNull(UsageApiClient.parseProfile(JsonParser.parseString("{}").asJsonObject, "Pro"))
    }
}
