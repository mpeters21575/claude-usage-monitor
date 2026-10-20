package com.ods.claudeusage.customizations

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.isDirectory
import kotlin.io.path.name

enum class CustomizationType(val label: String, val plural: String) {
    SKILL("Skill", "Skills"), AGENT("Agent", "Agents"), COMMAND("Command", "Commands"), HOOK("Hook", "Hooks"), MCP("MCP server", "MCP")
}

enum class Scope(val label: String) { USER("user"), PROJECT("project") }

/** One entry of `~/.claude` or `./.claude`: a skill, agent, slash command, hook or MCP server. */
data class CustomizationItem(
    val type: CustomizationType,
    val name: String,
    val scope: Scope,
    /** The file that holds it. For hooks and MCP servers this is the JSON file; [key] identifies the entry. */
    val file: Path,
    /** Entry key inside [file] for hooks ("Event|matcher") and MCP servers (server name); null for file-based items. */
    val key: String? = null,
    val description: String = "",
    val enabled: Boolean = true,
    val modified: Instant? = null,
    /** Agents a skill dispatches, in order. */
    val agents: List<String> = emptyList(),
    /** "sequential" or "on demand" for skills with agents. */
    val mode: String? = null,
    val note: String? = null,
) {
    val id: String get() = "${scope.name}:${type.name}:${file}:${key ?: ""}"
    val displayPath: String get() = file.toString() + (if (key != null) "#$key" else "")
    val isFileBased: Boolean get() = type == CustomizationType.SKILL || type == CustomizationType.AGENT || type == CustomizationType.COMMAND

    /** The directory (skill) or file (agent, command) to rename, move, duplicate or delete. */
    val unit: Path get() = if (type == CustomizationType.SKILL) file.parent else file
}

/**
 * Finds customizations in the user scope (`<claude home>`, plus `~/.claude.json` for MCP servers) and the
 * project scope (`<project>/.claude`, plus `<project>/.mcp.json`).
 */
class CustomizationScanner {

    fun scan(userHome: Path, userClaudeJson: Path?, projectBase: Path?): List<CustomizationItem> {
        val items = ArrayList<CustomizationItem>()
        items += scanRoot(userHome, Scope.USER)
        if (userClaudeJson != null) items += scanMcpJson(userClaudeJson, Scope.USER, projectBase)
        if (projectBase != null) {
            val root = projectBase.resolve(".claude")
            items += scanRoot(root, Scope.PROJECT)
            items += scanMcpJson(projectBase.resolve(".mcp.json"), Scope.PROJECT, null)
        }
        // Resolve dispatch relationships: agent names referenced by skills must exist as agents.
        val agentNames = items.filter { it.type == CustomizationType.AGENT }.map { it.name }.toSet()
        return items.map { item ->
            if (item.type == CustomizationType.SKILL && item.agents.isNotEmpty()) {
                val known = item.agents.filter { it in agentNames }
                item.copy(agents = known, mode = if (known.isEmpty()) null else item.mode, note = if (known.isEmpty()) null else item.note)
            } else item
        }
    }

    private fun scanRoot(root: Path, scope: Scope): List<CustomizationItem> {
        val out = ArrayList<CustomizationItem>()
        if (!Files.isDirectory(root)) return out
        // skills
        val skills = root.resolve("skills")
        if (Files.isDirectory(skills)) {
            Files.list(skills).use { dirs ->
                dirs.filter { it.isDirectory() && !it.name.startsWith(".") }.sorted().forEach { dir ->
                    val disabled = dir.name.endsWith(DISABLED_SUFFIX)
                    val skillFile = dir.resolve("SKILL.md")
                    if (!Files.isRegularFile(skillFile)) return@forEach
                    val front = parseFrontmatter(skillFile)
                    val name = front.first["name"] ?: dir.name.removeSuffix(DISABLED_SUFFIX)
                    val (agents, mode, note) = detectOrchestration(front.first, front.second)
                    out += CustomizationItem(
                        CustomizationType.SKILL, name, scope, skillFile, null, front.first["description"] ?: "",
                        enabled = !disabled, modified = mtime(skillFile), agents = agents, mode = mode, note = note,
                    )
                }
            }
        }
        // agents
        val agents = root.resolve("agents")
        if (Files.isDirectory(agents)) {
            Files.list(agents).use { files ->
                files.filter { Files.isRegularFile(it) && (it.name.endsWith(".md") || it.name.endsWith(".md$DISABLED_SUFFIX")) }.sorted().forEach { f ->
                    val disabled = f.name.endsWith(DISABLED_SUFFIX)
                    val front = parseFrontmatter(f)
                    val name = front.first["name"] ?: f.name.removeSuffix(DISABLED_SUFFIX).removeSuffix(".md")
                    out += CustomizationItem(CustomizationType.AGENT, name, scope, f, null, front.first["description"] ?: "", !disabled, mtime(f))
                }
            }
        }
        // commands (nested folders become namespaces)
        val commands = root.resolve("commands")
        if (Files.isDirectory(commands)) {
            Files.walk(commands, 3).use { files ->
                files.filter { Files.isRegularFile(it) && (it.name.endsWith(".md") || it.name.endsWith(".md$DISABLED_SUFFIX")) }.sorted().forEach { f ->
                    val disabled = f.name.endsWith(DISABLED_SUFFIX)
                    val rel = commands.relativize(f).toString().replace('\\', '/').removeSuffix(DISABLED_SUFFIX).removeSuffix(".md")
                    val front = parseFrontmatter(f)
                    out += CustomizationItem(CustomizationType.COMMAND, "/" + rel.replace('/', ':'), scope, f, null, front.first["description"] ?: "", !disabled, mtime(f))
                }
            }
        }
        // hooks in settings.json (+ settings.local.json for the project scope)
        for (settingsName in listOf("settings.json", "settings.local.json")) {
            val settings = root.resolve(settingsName)
            if (!Files.isRegularFile(settings)) continue
            out += scanHooks(settings, scope)
        }
        return out
    }

    private fun scanHooks(settings: Path, scope: Scope): List<CustomizationItem> {
        val out = ArrayList<CustomizationItem>()
        val json = readJson(settings) ?: return out
        val modified = mtime(settings)
        for ((holder, enabled) in listOf("hooks" to true, DISABLED_HOOKS to false)) {
            val hooks = json.get(holder)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            for ((event, entries) in hooks.entrySet()) {
                if (!entries.isJsonArray) continue
                entries.asJsonArray.forEachIndexed { i, entry ->
                    if (!entry.isJsonObject) return@forEachIndexed
                    val e = entry.asJsonObject
                    val matcher = e.get("matcher")?.takeIf { it.isJsonPrimitive }?.asString
                    val commands = e.get("hooks")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { h ->
                        h.takeIf { it.isJsonObject }?.asJsonObject?.get("command")?.takeIf { it.isJsonPrimitive }?.asString
                    } ?: emptyList()
                    val name = if (matcher.isNullOrBlank()) event else "$event · $matcher"
                    out += CustomizationItem(
                        CustomizationType.HOOK, name, scope, settings, "$event|${matcher ?: ""}|$i",
                        description = commands.joinToString("  ·  ").ifBlank { "(no command)" }, enabled = enabled, modified = modified,
                    )
                }
            }
        }
        return out
    }

    /** `mcpServers` at the top level (`.mcp.json`, `~/.claude.json`) and, for `~/.claude.json`, under `projects.<base>`. */
    private fun scanMcpJson(file: Path, scope: Scope, projectBase: Path?): List<CustomizationItem> {
        val out = ArrayList<CustomizationItem>()
        if (!Files.isRegularFile(file)) return out
        val json = readJson(file) ?: return out
        val modified = mtime(file)
        fun collect(servers: JsonObject?, keyPrefix: String, itemScope: Scope) {
            servers ?: return
            for ((name, v) in servers.entrySet()) {
                if (!v.isJsonObject) continue
                val s = v.asJsonObject
                val disabled = s.get("disabled")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
                val desc = s.get("url")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: listOfNotNull(s.get("command")?.takeIf { it.isJsonPrimitive }?.asString, s.get("args")?.takeIf { it.isJsonArray }?.asJsonArray?.joinToString(" ") { it.asString })
                        .joinToString(" ").ifBlank { "MCP server" }
                out += CustomizationItem(CustomizationType.MCP, name, itemScope, file, "$keyPrefix$name", desc, !disabled, modified)
            }
        }
        collect(json.get("mcpServers")?.takeIf { it.isJsonObject }?.asJsonObject, "", scope)
        if (projectBase != null) {
            val projects = json.get("projects")?.takeIf { it.isJsonObject }?.asJsonObject
            val entry = projects?.entrySet()?.firstOrNull { samePath(it.key, projectBase) }?.value?.takeIf { it.isJsonObject }?.asJsonObject
            collect(entry?.get("mcpServers")?.takeIf { it.isJsonObject }?.asJsonObject, "projects/${projectBase}/", Scope.PROJECT)
        }
        return out
    }

    private fun samePath(a: String, b: Path): Boolean = try {
        Path.of(a).toAbsolutePath().normalize() == b.toAbsolutePath().normalize()
    } catch (_: Exception) { false }

    companion object {
        private val LOG = logger<CustomizationScanner>()
        const val DISABLED_SUFFIX = ".disabled"
        const val DISABLED_HOOKS = "_disabledHooks"
        val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

        fun readJson(file: Path): JsonObject? = try {
            JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            LOG.debug("Cannot parse $file", e); null
        }

        fun writeJson(file: Path, json: JsonObject) {
            Files.writeString(file, GSON.toJson(json) + "\n", StandardCharsets.UTF_8)
        }

        fun mtime(p: Path): Instant? = try { Files.getLastModifiedTime(p).toInstant() } catch (_: Exception) { null }

        /** Returns (frontmatter key -> value, body). Folded `>` descriptions are joined. */
        fun parseFrontmatter(file: Path): Pair<Map<String, String>, String> {
            val text = try { Files.readString(file, StandardCharsets.UTF_8) } catch (_: Exception) { return emptyMap<String, String>() to "" }
            return parseFrontmatterText(text)
        }

        fun parseFrontmatterText(text: String): Pair<Map<String, String>, String> {
            val lines = text.lines()
            if (lines.firstOrNull()?.trim() != "---") return emptyMap<String, String>() to text
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end < 0) return emptyMap<String, String>() to text
            val front = lines.subList(1, end + 1)
            val body = lines.drop(end + 2).joinToString("\n")
            val map = LinkedHashMap<String, String>()
            var key: String? = null
            val value = StringBuilder()
            fun flush() { key?.let { map[it] = value.toString().trim() }; value.clear() }
            for (line in front) {
                val m = Regex("^([A-Za-z0-9_-]+):\\s*(.*)$").find(line)
                if (m != null && !line.startsWith(" ") && !line.startsWith("\t")) {
                    flush()
                    key = m.groupValues[1]
                    val v = m.groupValues[2].trim()
                    value.append(if (v == ">" || v == "|" || v == ">-" || v == "|-") "" else v)
                } else if (key != null) {
                    val t = line.trim()
                    if (t.startsWith("- ")) value.append(if (value.isEmpty()) "" else "\n").append(t.removePrefix("- ").trim())
                    else if (t.isNotEmpty()) value.append(if (value.isEmpty()) "" else " ").append(t)
                }
            }
            flush()
            return map to body
        }

        /** Agents a skill dispatches: frontmatter `agents:` list plus back-ticked names in the body. */
        fun detectOrchestration(front: Map<String, String>, body: String): Triple<List<String>, String?, String?> {
            val agents = LinkedHashSet<String>()
            front["agents"]?.split('\n', ',')?.map { it.trim().trim('"', '\'') }?.filter { it.isNotEmpty() }?.forEach { agents += it }
            Regex("`([A-Za-z0-9:_-]+)`").findAll(body).forEach { agents += it.groupValues[1] }
            if (agents.isEmpty()) return Triple(emptyList(), null, null)
            val numbered = body.lines().filter { Regex("^\\s*\\d+\\.").containsMatchIn(it) }
            val mentioned = agents.filter { a -> numbered.any { it.contains("`$a`") } }
            val mode = if (mentioned.size >= 2) "sequential" else "on demand"
            val note = if (mode == "sequential") mentioned.joinToString(" → ") else "dispatched when needed"
            return Triple(agents.toList(), mode, note)
        }
    }
}
