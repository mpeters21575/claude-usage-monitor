package com.ods.claudeusage.transcript

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.ods.claudeusage.api.UsageApiClient
import com.ods.claudeusage.model.AgentRun
import com.ods.claudeusage.model.ModelUsage
import com.ods.claudeusage.model.SessionInfo
import com.ods.claudeusage.model.SubagentRun
import com.ods.claudeusage.model.TokenTotals
import com.ods.claudeusage.model.ToolStat
import com.ods.claudeusage.model.TurnEvent
import com.ods.claudeusage.model.UsageBreakdown
import com.ods.claudeusage.model.UsageCounts
import com.ods.claudeusage.model.UsageStat
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name
import kotlin.math.min

/**
 * Incremental index over Claude Code's JSONL transcripts in `<claude home>/projects`.
 *
 * Every file is read only once; later scans read the bytes appended since. Assistant usage counters are
 * de-duplicated by message id + request id because Claude Code writes one line per content block with the
 * same `usage` object. Content blocks of every line are attributed to a source category (user prompt,
 * assistant text, thinking, tool call, MCP tool result per server, built-in tool result per family) using an
 * estimate of four characters per token. Per-tool statistics, slash-command, skill, agent and MCP usage are
 * collected for the Customizations section.
 */
class TranscriptIndex {

    private class Breakdown {
        val sources = HashMap<String, Long>()
        val toolCalls = HashMap<String, Int>()
        val toolStats = HashMap<String, HashMap<String, ToolStat>>()
        var thinking = 0L
        var webSearches = 0
        var webFetches = 0
        var userTurns = 0

        fun add(source: String, chars: Int) {
            if (chars <= 0) return
            sources.merge(source, estimateTokens(chars), Long::plus)
        }

        fun tool(group: String, name: String, delta: ToolStat) {
            val perGroup = toolStats.getOrPut(group) { HashMap() }
            perGroup[name] = (perGroup[name] ?: ToolStat()) + delta
        }

        fun fold(into: Breakdown) {
            sources.forEach { (k, v) -> into.sources.merge(k, v, Long::plus) }
            toolCalls.forEach { (k, v) -> into.toolCalls.merge(k, v, Int::plus) }
            toolStats.forEach { (g, tools) -> tools.forEach { (n, s) -> into.tool(g, n, s) } }
            into.thinking += thinking
            into.webSearches += webSearches
            into.webFetches += webFetches
            into.userTurns += userTurns
        }

        fun toModel(byType: TokenTotals) = UsageBreakdown(
            byType = byType,
            thinkingTokens = thinking,
            bySource = sources.toMap(),
            toolCalls = toolCalls.toMap(),
            webSearches = webSearches,
            webFetches = webFetches,
            toolStats = toolStats.mapValues { it.value.toMap() },
        )
    }

    private class FileState(val path: Path, val projectDirName: String, val isSubagent: Boolean, val sessionIdFromPath: String) {
        var offset = 0L
        var lastModified = 0L
        var lastSize = 0L
        val events = ArrayList<TurnEvent>()
        val seen = HashSet<String>()
        val toolNames = HashMap<String, String>()
        val breakdown = Breakdown()
        /** "skill:<name>" / "agent:<type>" / "command:/<name>" / "mcp:<server>" -> timestamps */
        val uses = HashMap<String, ArrayList<Instant>>()
        val agentRuns = ArrayList<AgentRun>()
        var title: String? = null
        var cwd: String? = null
        var gitBranch: String? = null
        var version: String? = null
        var sessionId: String = sessionIdFromPath
        var lastEventAt: Instant? = null
        var firstEventAt: Instant? = null

        fun totals(): TokenTotals = events.fold(TokenTotals.ZERO) { a, e -> a + e.totals }

        fun reset() {
            offset = 0; events.clear(); seen.clear(); toolNames.clear(); lastEventAt = null; firstEventAt = null
            uses.clear(); agentRuns.clear()
            breakdown.sources.clear(); breakdown.toolCalls.clear(); breakdown.toolStats.clear(); breakdown.thinking = 0
            breakdown.webSearches = 0; breakdown.webFetches = 0; breakdown.userTurns = 0
        }
    }

    private val files = ConcurrentHashMap<Path, FileState>()

    /** `model` from `<claude home>/settings.json`, e.g. "opus[1m]". */
    @Volatile
    var configuredModel: String? = null
        private set

    val fileCount: Int get() = files.size

    /** Scans for new and changed transcript files. Returns the number of assistant turns parsed in this pass. */
    @Synchronized
    fun scan(claudeHome: Path, retention: Duration): Int {
        readConfiguredModel(claudeHome)
        val projects = claudeHome.resolve("projects")
        if (!Files.isDirectory(projects)) {
            files.clear()
            return 0
        }
        val cutoff = Instant.now().minus(retention).toEpochMilli()
        val present = HashSet<Path>()
        var parsed = 0
        try {
            Files.walk(projects, 4).use { stream ->
                stream.filter { it.name.endsWith(".jsonl") && Files.isRegularFile(it) }.forEach { path ->
                    val modified = try { Files.getLastModifiedTime(path).toMillis() } catch (_: IOException) { return@forEach }
                    if (modified < cutoff) return@forEach
                    present.add(path)
                    val state = files.getOrPut(path) { newState(projects, path) }
                    val size = try { Files.size(path) } catch (_: IOException) { return@forEach }
                    if (size == state.lastSize && modified == state.lastModified) return@forEach
                    synchronized(state) {
                        if (size < state.offset) state.reset()
                        parsed += readAppended(state, size)
                        state.lastSize = size
                        state.lastModified = modified
                    }
                }
            }
        } catch (e: IOException) {
            LOG.warn("Transcript scan failed", e)
        }
        files.keys.retainAll(present)
        return parsed
    }

    private fun newState(projects: Path, path: Path): FileState {
        val rel = projects.relativize(path)
        val projectDirName = rel.getName(0).toString()
        val isSubagent = rel.nameCount >= 4 && rel.getName(rel.nameCount - 2).toString() == "subagents"
        val sessionId = if (isSubagent) rel.getName(rel.nameCount - 3).toString() else path.name.removeSuffix(".jsonl")
        return FileState(path, projectDirName, isSubagent, sessionId)
    }

    private fun readConfiguredModel(claudeHome: Path) {
        val settings = claudeHome.resolve("settings.json")
        configuredModel = try {
            if (Files.isRegularFile(settings)) {
                JsonParser.parseString(Files.readString(settings)).asJsonObject
                    .get("model")?.takeIf { it.isJsonPrimitive }?.asString
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun readAppended(state: FileState, size: Long): Int {
        var parsed = 0
        try {
            FileChannel.open(state.path, StandardOpenOption.READ).use { channel ->
                while (state.offset < size) {
                    val toRead = min(size - state.offset, CHUNK).toInt()
                    val buf = ByteBuffer.allocate(toRead)
                    channel.position(state.offset)
                    while (buf.hasRemaining() && channel.read(buf) > 0) { /* fill */ }
                    buf.flip()
                    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                    if (bytes.isEmpty()) break
                    var end = bytes.lastIndexOf('\n'.code.toByte())
                    if (end < 0) {
                        if (state.offset + bytes.size >= size) end = bytes.size - 1 else break
                    }
                    val text = String(bytes, 0, end + 1, StandardCharsets.UTF_8)
                    for (line in text.lineSequence()) {
                        if (line.isBlank()) continue
                        if (parseLine(state, line)) parsed++
                    }
                    state.offset += end + 1
                }
            }
        } catch (e: IOException) {
            LOG.debug("Cannot read ${state.path}", e)
        }
        return parsed
    }

    private fun parseLine(state: FileState, line: String): Boolean {
        return try {
            when {
                line.contains("\"type\":\"assistant\"") -> parseAssistant(state, line)
                line.contains("\"type\":\"user\"") -> { parseUser(state, line); false }
                line.contains("\"type\":\"custom-title\"") -> { parseTitle(state, line); false }
                line.contains("\"type\":\"agent-name\"") -> { parseAgentName(state, line); false }
                line.contains("\"type\":\"summary\"") && state.title == null -> { parseSummary(state, line); false }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun parseAssistant(state: FileState, line: String): Boolean {
        val root = JsonParser.parseString(line).asJsonObject
        if (root.str("type") != "assistant") return false
        val message = root.getAsJsonObject("message") ?: return false
        val model = message.str("model") ?: return false
        if (model.startsWith("<")) return false // synthetic messages
        val at = root.str("timestamp")?.let(UsageApiClient::parseInstant) ?: Instant.now()

        val usage = message.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
        val thinkingFromUsage = usage?.get("output_tokens_details")?.takeIf { it.isJsonObject }?.asJsonObject?.long("thinking_tokens")
        attributeAssistantContent(state, message.get("content"), thinkingFromUsage == null, at)

        usage ?: return false
        val id = message.str("id") ?: root.str("uuid") ?: return false
        val key = id + ":" + (root.str("requestId") ?: "")
        if (!state.seen.add(key)) return false

        if (thinkingFromUsage != null) state.breakdown.thinking += thinkingFromUsage
        usage.get("server_tool_use")?.takeIf { it.isJsonObject }?.asJsonObject?.let {
            state.breakdown.webSearches += it.long("web_search_requests").toInt()
            state.breakdown.webFetches += it.long("web_fetch_requests").toInt()
        }

        val totals = TokenTotals(
            input = usage.long("input_tokens"),
            output = usage.long("output_tokens"),
            cacheCreation = usage.long("cache_creation_input_tokens"),
            cacheRead = usage.long("cache_read_input_tokens"),
        )
        root.str("sessionId")?.let { state.sessionId = it }
        root.str("cwd")?.let { state.cwd = it }
        root.str("gitBranch")?.let { state.gitBranch = it }
        root.str("version")?.let { state.version = it }
        val event = TurnEvent(
            at = at,
            sessionId = state.sessionId,
            model = model,
            totals = totals,
            contextTokens = totals.input + totals.cacheCreation + totals.cacheRead,
            isSidechain = root.get("isSidechain")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
        )
        state.events.add(event)
        val previous = state.lastEventAt
        if (previous == null || at.isAfter(previous)) state.lastEventAt = at
        val first = state.firstEventAt
        if (first == null || at.isBefore(first)) state.firstEventAt = at
        return true
    }

    private fun attributeAssistantContent(state: FileState, content: JsonElement?, estimateThinking: Boolean, at: Instant) {
        if (content == null) return
        if (content.isJsonPrimitive) {
            state.breakdown.add(SOURCE_ASSISTANT_TEXT, content.asString.length); return
        }
        if (!content.isJsonArray) return
        for (block in content.asJsonArray) {
            if (!block.isJsonObject) continue
            val b = block.asJsonObject
            when (b.str("type")) {
                "text" -> state.breakdown.add(SOURCE_ASSISTANT_TEXT, b.str("text")?.length ?: 0)
                "thinking" -> if (estimateThinking) state.breakdown.thinking += estimateTokens(b.str("thinking")?.length ?: 0)
                "tool_use" -> {
                    val name = b.str("name") ?: "unknown"
                    b.str("id")?.let { state.toolNames[it] = name }
                    val group = toolGroup(name)
                    state.breakdown.toolCalls.merge(group, 1, Int::plus)
                    state.breakdown.tool(group, displayToolName(name), ToolStat(calls = 1))
                    val input = b.get("input")?.takeIf { it.isJsonObject }?.asJsonObject
                    state.breakdown.add(SOURCE_TOOL_CALLS, input?.toString()?.length ?: 0)
                    when {
                        name == "Agent" || name == "Task" -> {
                            val type = input?.str("subagent_type") ?: "general-purpose"
                            state.agentRuns.add(AgentRun(at, type))
                            state.uses.getOrPut("agent:$type") { ArrayList() }.add(at)
                        }
                        name == "Skill" -> input?.str("skill")?.let { state.uses.getOrPut("skill:$it") { ArrayList() }.add(at) }
                        name.startsWith("mcp__") -> state.uses.getOrPut("mcp:" + name.removePrefix("mcp__").substringBefore("__")) { ArrayList() }.add(at)
                    }
                }
            }
        }
    }

    private fun parseUser(state: FileState, line: String) {
        val root = JsonParser.parseString(line).asJsonObject
        if (root.str("type") != "user") return
        val message = root.getAsJsonObject("message") ?: return
        val content = message.get("content") ?: return
        val at = root.str("timestamp")?.let(UsageApiClient::parseInstant) ?: Instant.now()
        if (content.isJsonPrimitive) {
            val text = content.asString
            state.breakdown.add(SOURCE_USER, text.length)
            state.breakdown.userTurns++
            noteCommands(state, text, at)
            return
        }
        if (!content.isJsonArray) return
        var counted = false
        for (block in content.asJsonArray) {
            if (!block.isJsonObject) continue
            val b = block.asJsonObject
            when (b.str("type")) {
                "text" -> {
                    val text = b.str("text") ?: ""
                    state.breakdown.add(SOURCE_USER, text.length)
                    if (!counted && !text.startsWith("<system-reminder>")) { state.breakdown.userTurns++; counted = true }
                    noteCommands(state, text, at)
                }
                "tool_result" -> {
                    val toolName = b.str("tool_use_id")?.let { state.toolNames[it] } ?: "unknown"
                    val group = toolGroup(toolName)
                    val chars = contentLength(b.get("content"))
                    state.breakdown.add("$group results", chars)
                    val error = b.get("is_error")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
                    state.breakdown.tool(group, displayToolName(toolName), ToolStat(resultTokens = estimateTokens(chars), errors = if (error) 1 else 0))
                }
                "image", "document" -> state.breakdown.add(SOURCE_ATTACHMENTS, 6_000) // ~1.5k tokens per image
            }
        }
    }

    private fun noteCommands(state: FileState, text: String, at: Instant) {
        if (!text.contains("<command-name>")) return
        COMMAND_RE.findAll(text).forEach { m -> state.uses.getOrPut("command:" + m.groupValues[1]) { ArrayList() }.add(at) }
    }

    private fun contentLength(e: JsonElement?): Int = when {
        e == null -> 0
        e.isJsonPrimitive -> e.asString.length
        e.isJsonArray -> (e as JsonArray).sumOf { part ->
            if (part.isJsonObject) {
                val p = part.asJsonObject
                when (p.str("type")) {
                    "text" -> p.str("text")?.length ?: 0
                    "image" -> 6_000
                    else -> p.toString().length
                }
            } else part.toString().length
        }
        else -> e.toString().length
    }

    private fun parseTitle(state: FileState, line: String) {
        val root = JsonParser.parseString(line).asJsonObject
        val title = root.entrySet()
            .filter { (k, v) -> k !in setOf("type", "sessionId", "timestamp", "uuid", "parentUuid") && v.isJsonPrimitive && v.asJsonPrimitive.isString }
            .map { it.value.asString }
            .firstOrNull { it.isNotBlank() }
        if (title != null) state.title = title
    }

    private fun parseAgentName(state: FileState, line: String) {
        val root = JsonParser.parseString(line).asJsonObject
        root.str("agentName")?.takeIf { it.isNotBlank() }?.let { state.title = it }
    }

    private fun parseSummary(state: FileState, line: String) {
        val root = JsonParser.parseString(line).asJsonObject
        root.str("summary")?.takeIf { it.isNotBlank() }?.let { state.title = it }
    }

    /** All assistant turns (main sessions and subagents) since [since], unordered. */
    fun eventsSince(since: Instant): List<TurnEvent> {
        val out = ArrayList<TurnEvent>()
        for (f in files.values) {
            synchronized(f) { for (e in f.events) if (!e.at.isBefore(since)) out.add(e) }
        }
        return out
    }

    /** Token usage per model within the 5-hour and 7-day windows ending now. */
    fun modelUsage(now: Instant): List<ModelUsage> {
        val fiveHourStart = now.minus(Duration.ofHours(5))
        val sevenDayStart = now.minus(Duration.ofDays(7))
        class Acc(var five: TokenTotals = TokenTotals.ZERO, var seven: TokenTotals = TokenTotals.ZERO, var turns: Int = 0, var last: Instant? = null)
        val acc = HashMap<String, Acc>()
        for (e in eventsSince(sevenDayStart)) {
            val a = acc.getOrPut(e.model) { Acc() }
            a.seven += e.totals
            a.turns++
            if (!e.at.isBefore(fiveHourStart)) a.five += e.totals
            val last = a.last
            if (last == null || e.at.isAfter(last)) a.last = e.at
        }
        return acc.map { (model, a) -> ModelUsage(model, a.five, a.seven, a.turns, a.last) }
            .sortedByDescending { it.sevenDay.total }
    }

    /** Breakdown over every indexed transcript whose last activity is within [since]. */
    fun breakdownSince(since: Instant): UsageBreakdown {
        val acc = Breakdown()
        var totals = TokenTotals.ZERO
        for (f in files.values) {
            synchronized(f) {
                if (f.lastEventAt?.isAfter(since) != true) return@synchronized
                f.breakdown.fold(acc)
                for (e in f.events) if (!e.at.isBefore(since)) totals += e.totals
            }
        }
        return acc.toModel(totals)
    }

    /** Skill, agent, slash-command and MCP usage since [since], across every indexed transcript. */
    fun usageCounts(since: Instant): UsageCounts {
        val skills = HashMap<String, UsageStat>(); val agents = HashMap<String, UsageStat>()
        val commands = HashMap<String, UsageStat>(); val mcp = HashMap<String, UsageStat>()
        for (f in files.values) {
            synchronized(f) {
                for ((key, times) in f.uses) {
                    val recent = times.filter { !it.isBefore(since) }
                    if (recent.isEmpty()) continue
                    val stat = UsageStat(recent.size, recent.maxOrNull())
                    val name = key.substringAfter(':')
                    val target = when (key.substringBefore(':')) { "skill" -> skills; "agent" -> agents; "command" -> commands; else -> mcp }
                    target[name] = (target[name] ?: UsageStat(0, null)) + stat
                }
            }
        }
        return UsageCounts(skills, agents, commands, mcp)
    }

    /**
     * The session the user is most likely looking at: the most recently active main transcript, preferring
     * sessions started inside [projectBasePath] when one was active in the last six hours.
     */
    fun activeSession(projectBasePath: String?, contextWindowFor: (model: String?) -> Long): SessionInfo? {
        val mains = files.values.filter { !it.isSubagent && it.lastEventAt != null }
        if (mains.isEmpty()) return null
        val encoded = projectBasePath?.let(::encodeProjectPath)
        val recentCutoff = Instant.now().minus(Duration.ofHours(6))
        val preferred = if (encoded != null) {
            mains.filter { (it.projectDirName == encoded || it.projectDirName.startsWith("$encoded-")) && it.lastEventAt!!.isAfter(recentCutoff) }
        } else emptyList()
        val chosen = (preferred.ifEmpty { mains }).maxByOrNull { it.lastEventAt!! } ?: return null
        return buildSession(chosen, contextWindowFor)
    }

    private fun buildSession(f: FileState, contextWindowFor: (String?) -> Long): SessionInfo {
        val events: List<TurnEvent>
        val mainTotals: TokenTotals
        val breakdown: Breakdown
        val agentRuns: List<AgentRun>
        synchronized(f) {
            events = f.events.sortedBy { it.at }
            mainTotals = f.totals()
            breakdown = Breakdown().also { f.breakdown.fold(it) }
            agentRuns = f.agentRuns.toList()
        }
        val main = events.filter { !it.isSidechain }
        val last = main.lastOrNull() ?: events.lastOrNull()
        val subRuns = files.values
            .filter { it.isSubagent && it.sessionIdFromPath == f.sessionId }
            .map { s -> synchronized(s) { SubagentRun(s.firstEventAt, s.totals(), s.path.name.removeSuffix(".jsonl").removePrefix("agent-")) } }
            .sortedBy { it.firstAt ?: Instant.EPOCH }
        val subTotals = subRuns.fold(TokenTotals.ZERO) { a, r -> a + r.totals }
        val model = last?.model
        return SessionInfo(
            sessionId = f.sessionId,
            projectDirName = f.projectDirName,
            filePath = f.path.toString(),
            title = f.title,
            cwd = f.cwd,
            gitBranch = f.gitBranch,
            claudeVersion = f.version,
            model = model,
            contextTokens = last?.contextTokens ?: 0,
            contextWindow = contextWindowFor(model),
            lastTurnAt = f.lastEventAt,
            firstTurnAt = events.firstOrNull()?.at,
            turns = main.size,
            totals = mainTotals,
            subagentTotals = subTotals,
            contextHistory = main.takeLast(400).map { it.at to it.contextTokens },
            breakdown = breakdown.toModel(mainTotals + subTotals),
            userTurns = breakdown.userTurns,
            outputHistory = main.map { it.at to it.totals.output },
            agentRuns = agentRuns.sortedBy { it.at },
            subagentRuns = subRuns,
        )
    }

    companion object {
        private val LOG = logger<TranscriptIndex>()
        private const val CHUNK = 4L * 1024 * 1024
        private val COMMAND_RE = Regex("<command-name>(/[^<\\s]+)</command-name>")

        const val SOURCE_USER = "User & system prompts"
        const val SOURCE_ASSISTANT_TEXT = "Assistant text"
        const val SOURCE_TOOL_CALLS = "Tool call arguments"
        const val SOURCE_ATTACHMENTS = "Images & documents"
        const val SOURCE_THINKING = "Thinking"

        /** Claude Code names a project folder after its cwd with every non-alphanumeric character replaced by `-`. */
        fun encodeProjectPath(path: String): String = path.replace(Regex("[^A-Za-z0-9]"), "-")

        fun estimateTokens(chars: Int): Long = ((chars + 3) / 4).toLong()

        /** Groups a tool name: MCP tools by server, built-in tools by family. */
        fun toolGroup(name: String): String {
            if (name.startsWith("mcp__")) {
                val server = name.removePrefix("mcp__").substringBefore("__").ifBlank { "unknown" }
                return "MCP · $server"
            }
            return when (name) {
                "Read", "Write", "Edit", "MultiEdit", "NotebookEdit", "Glob", "Grep", "LS" -> "Files & search"
                "Bash", "PowerShell", "BashOutput", "KillShell", "KillBash", "Monitor" -> "Shell"
                "WebFetch", "WebSearch" -> "Web"
                "Agent", "Task", "SendMessage", "TaskOutput", "TaskStop", "Workflow" -> "Subagents"
                "Skill", "SlashCommand" -> "Skills"
                "TodoWrite", "AskUserQuestion", "ExitPlanMode", "EnterPlanMode", "ToolSearch" -> "Planning & meta"
                else -> "Other built-in tools"
            }
        }

        /** `mcp__server__tool` -> `tool`; built-in names unchanged. */
        fun displayToolName(name: String): String =
            if (name.startsWith("mcp__")) name.removePrefix("mcp__").substringAfter("__", name) else name
    }
}

private fun JsonObject.str(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.long(name: String): Long =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: 0L
