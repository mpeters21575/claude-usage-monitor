package com.ods.claudeusage.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class TranscriptIndexTest {

    @Test
    fun `project paths are encoded like Claude Code does`() {
        assertEquals("C--git-ODS-Database--codex", TranscriptIndex.encodeProjectPath("C:\\git\\ODS Database\\.codex"))
        assertEquals("C--git-ODS-Database", TranscriptIndex.encodeProjectPath("C:/git/ODS Database"))
        assertEquals("-home-dev-my-app", TranscriptIndex.encodeProjectPath("/home/dev/my.app"))
    }

    @Test
    fun `tool names are grouped`() {
        assertEquals("MCP \u00B7 rider", TranscriptIndex.toolGroup("mcp__rider__get_file_problems"))
        assertEquals("Files & search", TranscriptIndex.toolGroup("Read"))
        assertEquals("Shell", TranscriptIndex.toolGroup("Bash"))
        assertEquals("Subagents", TranscriptIndex.toolGroup("Agent"))
        assertEquals("Other built-in tools", TranscriptIndex.toolGroup("Whatever"))
    }

    @Test
    fun `synthetic transcript is indexed, de-duplicated and attributed`(@TempDir home: Path) {
        val project = home.resolve("projects").resolve("C--work-app")
        Files.createDirectories(project)
        val file = project.resolve("11111111-2222-3333-4444-555555555555.jsonl")
        val t0 = Instant.parse("2026-10-06T08:00:00Z")
        val lines = listOf(
            user(t0, """{"role":"user","content":"Please read the file"}"""),
            // Same message id over two lines (two content blocks) must count usage once.
            assistant(t0.plusSeconds(5), "msg_1", "req_1", """[{"type":"text","text":"Sure, reading it now."}]""", input = 10, cacheCreate = 1000, output = 20),
            assistant(t0.plusSeconds(5), "msg_1", "req_1", """[{"type":"tool_use","id":"toolu_1","name":"Read","input":{"file_path":"a.txt"}}]""", input = 10, cacheCreate = 1000, output = 20),
            user(t0.plusSeconds(6), """{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"${"x".repeat(4000)}"}]}"""),
            assistant(t0.plusSeconds(10), "msg_2", "req_2", """[{"type":"tool_use","id":"toolu_2","name":"mcp__rider__get_file_problems","input":{}}]""", input = 5, cacheRead = 1000, cacheCreate = 1200, output = 30, thinking = 7),
            user(t0.plusSeconds(11), """{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_2","content":[{"type":"text","text":"${"y".repeat(800)}"}]}]}"""),
            """{"type":"custom-title","customTitle":"My session","sessionId":"11111111-2222-3333-4444-555555555555"}""",
        )
        Files.write(file, lines)
        Files.writeString(home.resolve("settings.json"), """{"model":"opus[1m]"}""")

        val index = TranscriptIndex()
        val parsed = index.scan(home, Duration.ofDays(8))
        assertEquals(2, parsed, "two distinct assistant messages")
        assertEquals("opus[1m]", index.configuredModel)

        val session = index.activeSession("C:\\work\\app") { 200_000 }
        assertNotNull(session)
        session!!
        assertEquals("My session", session.title)
        assertEquals("claude-test-model", session.model)
        assertEquals(2, session.turns)
        assertEquals(5 + 1000 + 1200, session.contextTokens)
        assertEquals(10 + 1000 + 20 + 5 + 1000 + 1200 + 30, session.totals.total)

        val b = session.breakdown
        assertEquals(7, b.thinkingTokens)
        assertEquals(1000L, b.bySource["Files & search results"], "4000 chars -> 1000 tokens")
        assertEquals(200L, b.bySource["MCP \u00B7 rider results"])
        assertTrue((b.bySource[TranscriptIndex.SOURCE_USER] ?: 0) > 0)
        assertTrue((b.bySource[TranscriptIndex.SOURCE_ASSISTANT_TEXT] ?: 0) > 0)
        assertEquals(1, b.toolCalls["Files & search"])
        assertEquals(1, b.toolCalls["MCP \u00B7 rider"])
        assertEquals(1, session.userTurns, "tool_result-only user messages are not user turns")
        assertEquals(1, b.toolStats["Files & search"]!!["Read"]!!.calls)
        assertEquals(1000L, b.toolStats["Files & search"]!!["Read"]!!.resultTokens)
        assertEquals(1, b.toolStats["MCP \u00B7 rider"]!!["get_file_problems"]!!.calls)
        val counts = index.usageCounts(t0.minusSeconds(1))
        assertEquals(1, counts.mcpServers["rider"]!!.count)

        // Appending a turn is picked up incrementally.
        Files.write(file, listOf(assistant(t0.plusSeconds(20), "msg_3", "req_3", """[{"type":"text","text":"Done."}]""", input = 1, cacheRead = 2300, output = 5)), java.nio.file.StandardOpenOption.APPEND)
        assertEquals(1, index.scan(home, Duration.ofDays(8)))
        assertEquals(3, index.activeSession(null) { 200_000 }!!.turns)

        val models = index.modelUsage(t0.plusSeconds(60))
        assertEquals(1, models.size)
        assertEquals(3, models[0].turns7d)
    }

    @Test
    fun `real local transcripts can be indexed`() {
        val home = Path.of(System.getProperty("user.home"), ".claude")
        assumeTrue(Files.isDirectory(home.resolve("projects")), "no local Claude Code transcripts")
        val index = TranscriptIndex()
        val started = System.nanoTime()
        val parsed = index.scan(home, Duration.ofDays(8))
        val ms = (System.nanoTime() - started) / 1_000_000
        println("Indexed ${index.fileCount} transcript files, $parsed assistant turns in $ms ms")
        val session = index.activeSession(null) { 200_000 }
        assertNotNull(session, "expected at least one session")
        println("Active session model=${session!!.model} context=${session.contextTokens} turns=${session.turns} sources=${session.breakdown.bySource.keys}")
        assertTrue(session.contextTokens > 0)
        assertTrue(index.modelUsage(Instant.now()).isNotEmpty())
        // Second scan must be cheap: only bytes appended since the first scan are read (a live session may add a few turns).
        val again = System.nanoTime()
        val appended = index.scan(home, Duration.ofDays(8))
        assertTrue(appended < 50, "second scan parsed $appended turns; expected only live appends")
        assertTrue((System.nanoTime() - again) / 1_000_000 < 2_000)
    }

    private fun user(at: Instant, message: String) =
        """{"type":"user","uuid":"${java.util.UUID.randomUUID()}","timestamp":"$at","sessionId":"11111111-2222-3333-4444-555555555555","message":$message}"""

    private fun assistant(at: Instant, id: String, requestId: String, content: String, input: Long = 0, output: Long = 0, cacheCreate: Long = 0, cacheRead: Long = 0, thinking: Long? = null): String {
        val details = if (thinking != null) ""","output_tokens_details":{"thinking_tokens":$thinking}""" else ""
        return """{"type":"assistant","uuid":"${java.util.UUID.randomUUID()}","timestamp":"$at","sessionId":"11111111-2222-3333-4444-555555555555","requestId":"$requestId","isSidechain":false,"cwd":"C:\\work\\app","gitBranch":"main","version":"2.1.0",""" +
            """"message":{"id":"$id","model":"claude-test-model","role":"assistant","content":$content,"usage":{"input_tokens":$input,"output_tokens":$output,"cache_creation_input_tokens":$cacheCreate,"cache_read_input_tokens":$cacheRead$details}}}"""
    }
}
