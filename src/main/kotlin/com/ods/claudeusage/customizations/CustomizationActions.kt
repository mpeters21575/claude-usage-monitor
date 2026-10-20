package com.ods.claudeusage.customizations

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import java.awt.Desktop
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/**
 * File operations on customizations. Everything is plain java.nio so it works for both scopes; callers
 * refresh the IDE's virtual file system and rescan afterwards.
 */
class CustomizationActions(private val userHome: Path, private val projectBase: Path?) {

    class ActionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    fun root(scope: Scope): Path = when (scope) {
        Scope.USER -> userHome
        Scope.PROJECT -> (projectBase ?: throw ActionException("No project open")).resolve(".claude")
    }

    // ---- enable / disable -------------------------------------------------------------------------------------

    fun setEnabled(item: CustomizationItem, enabled: Boolean) {
        if (item.enabled == enabled) return
        when (item.type) {
            CustomizationType.SKILL, CustomizationType.AGENT, CustomizationType.COMMAND -> {
                val unit = item.unit
                val target = if (enabled) unit.resolveSibling(unit.name.removeSuffix(CustomizationScanner.DISABLED_SUFFIX))
                else unit.resolveSibling(unit.name + CustomizationScanner.DISABLED_SUFFIX)
                move(unit, target)
            }
            CustomizationType.HOOK -> editJson(item.file) { json ->
                val (event, matcher, index) = hookKey(item)
                val from = if (enabled) CustomizationScanner.DISABLED_HOOKS else "hooks"
                val to = if (enabled) "hooks" else CustomizationScanner.DISABLED_HOOKS
                val entry = removeHook(json, from, event, matcher, index) ?: throw ActionException("Hook entry not found")
                addHook(json, to, event, entry)
            }
            CustomizationType.MCP -> editJson(item.file) { json ->
                val server = mcpEntry(json, item) ?: throw ActionException("MCP server not found")
                if (enabled) server.remove("disabled") else server.addProperty("disabled", true)
            }
        }
    }

    // ---- rename / duplicate / move / delete -------------------------------------------------------------------

    fun rename(item: CustomizationItem, newName: String) {
        val name = newName.trim().removePrefix("/")
        if (name.isEmpty()) throw ActionException("Name must not be empty")
        when (item.type) {
            CustomizationType.SKILL -> {
                val dir = item.unit
                val target = dir.resolveSibling(name + if (!item.enabled) CustomizationScanner.DISABLED_SUFFIX else "")
                move(dir, target)
                setFrontmatterName(target.resolve("SKILL.md"), name)
            }
            CustomizationType.AGENT, CustomizationType.COMMAND -> {
                val f = item.unit
                val target = f.resolveSibling(name.replace(':', '/') + ".md" + if (!item.enabled) CustomizationScanner.DISABLED_SUFFIX else "")
                move(f, target)
                if (item.type == CustomizationType.AGENT) setFrontmatterName(target, name)
            }
            CustomizationType.MCP -> editJson(item.file) { json ->
                val servers = mcpServers(json, item) ?: throw ActionException("MCP server not found")
                val entry = servers.remove(item.name) ?: throw ActionException("MCP server not found")
                servers.add(name, entry)
            }
            CustomizationType.HOOK -> throw ActionException("Hooks are identified by event and matcher; edit settings.json to change them.")
        }
    }

    fun duplicate(item: CustomizationItem) {
        when (item.type) {
            CustomizationType.SKILL -> {
                val dir = item.unit
                val target = unique(dir.resolveSibling(dir.name.removeSuffix(CustomizationScanner.DISABLED_SUFFIX) + "-copy"))
                copyTree(dir, target)
                setFrontmatterName(target.resolve("SKILL.md"), target.name)
            }
            CustomizationType.AGENT, CustomizationType.COMMAND -> {
                val f = item.unit
                val base = f.name.removeSuffix(CustomizationScanner.DISABLED_SUFFIX).removeSuffix(".md")
                val target = unique(f.resolveSibling("$base-copy.md"))
                Files.copy(f, target)
                if (item.type == CustomizationType.AGENT) setFrontmatterName(target, target.name.removeSuffix(".md"))
            }
            CustomizationType.HOOK -> editJson(item.file) { json ->
                val (event, matcher, index) = hookKey(item)
                val holder = if (item.enabled) "hooks" else CustomizationScanner.DISABLED_HOOKS
                val entry = findHook(json, holder, event, matcher, index) ?: throw ActionException("Hook entry not found")
                addHook(json, holder, event, entry.deepCopy())
            }
            CustomizationType.MCP -> editJson(item.file) { json ->
                val servers = mcpServers(json, item) ?: throw ActionException("MCP server not found")
                val entry = servers.get(item.name) ?: throw ActionException("MCP server not found")
                var n = item.name + "-copy"; var i = 2
                while (servers.has(n)) { n = item.name + "-copy$i"; i++ }
                servers.add(n, entry.deepCopy())
            }
        }
    }

    fun moveScope(item: CustomizationItem) {
        val target = if (item.scope == Scope.USER) Scope.PROJECT else Scope.USER
        val targetRoot = root(target)
        when (item.type) {
            CustomizationType.SKILL -> move(item.unit, targetRoot.resolve("skills").resolve(item.unit.name))
            CustomizationType.AGENT -> move(item.unit, targetRoot.resolve("agents").resolve(item.unit.name))
            CustomizationType.COMMAND -> {
                val rel = item.file.parent.parent.let { commandsDir -> commandsDir.relativize(item.file) }
                move(item.unit, targetRoot.resolve("commands").resolve(rel))
            }
            CustomizationType.HOOK -> {
                val (event, matcher, index) = hookKey(item)
                val holder = if (item.enabled) "hooks" else CustomizationScanner.DISABLED_HOOKS
                var moved: JsonObject? = null
                editJson(item.file) { json -> moved = removeHook(json, holder, event, matcher, index) ?: throw ActionException("Hook entry not found") }
                val targetFile = targetRoot.resolve("settings.json")
                editJson(targetFile, create = true) { json -> addHook(json, holder, event, moved!!) }
            }
            CustomizationType.MCP -> {
                var entry: JsonObject? = null
                editJson(item.file) { json ->
                    val servers = mcpServers(json, item) ?: throw ActionException("MCP server not found")
                    entry = servers.remove(item.name)?.takeIf { it.isJsonObject }?.asJsonObject ?: throw ActionException("MCP server not found")
                }
                val targetFile = if (target == Scope.PROJECT) (projectBase ?: throw ActionException("No project open")).resolve(".mcp.json")
                else userHome.parent.resolve(".claude.json")
                editJson(targetFile, create = true) { json ->
                    val servers = json.get("mcpServers")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { json.add("mcpServers", it) }
                    servers.add(item.name, entry)
                }
            }
        }
    }

    /** Moves the file or directory to the recycle bin when the platform supports it, otherwise deletes it. */
    fun delete(item: CustomizationItem) {
        when (item.type) {
            CustomizationType.SKILL, CustomizationType.AGENT, CustomizationType.COMMAND -> trash(item.unit)
            CustomizationType.HOOK -> editJson(item.file) { json ->
                val (event, matcher, index) = hookKey(item)
                removeHook(json, if (item.enabled) "hooks" else CustomizationScanner.DISABLED_HOOKS, event, matcher, index) ?: throw ActionException("Hook entry not found")
            }
            CustomizationType.MCP -> editJson(item.file) { json ->
                val servers = mcpServers(json, item) ?: throw ActionException("MCP server not found")
                servers.remove(item.name) ?: throw ActionException("MCP server not found")
            }
        }
    }

    // ---- scaffolding --------------------------------------------------------------------------------------------

    /** Creates a new item from a template and returns the file to open. */
    fun scaffold(type: CustomizationType, scope: Scope, rawName: String): Path {
        val name = rawName.trim().removePrefix("/").replace(Regex("[^A-Za-z0-9:_-]"), "-").ifEmpty { throw ActionException("Name must not be empty") }
        val r = root(scope)
        val today = LocalDate.now()
        return when (type) {
            CustomizationType.SKILL -> {
                val dir = r.resolve("skills").resolve(name)
                if (Files.exists(dir)) throw ActionException("Skill '$name' already exists")
                Files.createDirectories(dir)
                val f = dir.resolve("SKILL.md")
                Files.writeString(f, "---\nname: $name\ndescription: Describe when Claude should use this skill.\n---\n\n# $name\n\n## When to apply\n\n## Workflow\n\n1. \n2. \n\n## Rules\n\n- \n", StandardCharsets.UTF_8)
                f
            }
            CustomizationType.AGENT -> {
                val f = r.resolve("agents").resolve("$name.md")
                if (Files.exists(f)) throw ActionException("Agent '$name' already exists")
                Files.createDirectories(f.parent)
                Files.writeString(f, "---\nname: $name\ndescription: Describe what this agent does and when to dispatch it.\ntools: Read, Grep, Glob\nmodel: inherit\n---\n\nYou are $name. Describe the role, the inputs it expects and the report format.\n", StandardCharsets.UTF_8)
                f
            }
            CustomizationType.COMMAND -> {
                val f = r.resolve("commands").resolve(name.replace(':', '/') + ".md")
                if (Files.exists(f)) throw ActionException("Command '/$name' already exists")
                Files.createDirectories(f.parent)
                Files.writeString(f, "---\ndescription: Describe what /$name does.\nargument-hint: [arguments]\n---\n\nInstructions for /$name. Use \$ARGUMENTS for the arguments.\n", StandardCharsets.UTF_8)
                f
            }
            CustomizationType.HOOK -> {
                val f = r.resolve("settings.json")
                editJson(f, create = true) { json ->
                    val entry = JsonObject().apply {
                        addProperty("matcher", name)
                        add("hooks", JsonArray().apply { add(JsonObject().apply { addProperty("type", "command"); addProperty("command", "echo hook created $today") }) })
                    }
                    addHook(json, "hooks", "PreToolUse", entry)
                }
                f
            }
            CustomizationType.MCP -> {
                val f = if (scope == Scope.PROJECT) (projectBase ?: throw ActionException("No project open")).resolve(".mcp.json") else userHome.parent.resolve(".claude.json")
                editJson(f, create = true) { json ->
                    val servers = json.get("mcpServers")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { json.add("mcpServers", it) }
                    if (servers.has(name)) throw ActionException("MCP server '$name' already exists")
                    servers.add(name, JsonObject().apply { addProperty("type", "stdio"); addProperty("command", "npx"); add("args", JsonArray().apply { add("-y"); add("<package>") }) })
                }
                f
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private fun hookKey(item: CustomizationItem): Triple<String, String, Int> {
        val parts = (item.key ?: throw ActionException("Not a hook")).split('|')
        return Triple(parts[0], parts.getOrElse(1) { "" }, parts.getOrElse(2) { "0" }.toIntOrNull() ?: 0)
    }

    private fun findHook(json: JsonObject, holder: String, event: String, matcher: String, index: Int): JsonObject? {
        val arr = json.get(holder)?.takeIf { it.isJsonObject }?.asJsonObject?.get(event)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val byIndex = (if (index in 0 until arr.size()) arr.get(index) else null)?.takeIf { it.isJsonObject }?.asJsonObject
        if (byIndex != null && (byIndex.get("matcher")?.takeIf { it.isJsonPrimitive }?.asString ?: "") == matcher) return byIndex
        return arr.firstOrNull { it.isJsonObject && (it.asJsonObject.get("matcher")?.takeIf { m -> m.isJsonPrimitive }?.asString ?: "") == matcher }?.asJsonObject
    }

    private fun removeHook(json: JsonObject, holder: String, event: String, matcher: String, index: Int): JsonObject? {
        val entry = findHook(json, holder, event, matcher, index) ?: return null
        val arr = json.getAsJsonObject(holder).getAsJsonArray(event)
        arr.remove(entry)
        if (arr.isEmpty) json.getAsJsonObject(holder).remove(event)
        if (json.getAsJsonObject(holder).size() == 0) json.remove(holder)
        return entry
    }

    private fun addHook(json: JsonObject, holder: String, event: String, entry: JsonObject) {
        val h = json.get(holder)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { json.add(holder, it) }
        val arr = h.get(event)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray().also { h.add(event, it) }
        arr.add(entry)
    }

    private fun mcpServers(json: JsonObject, item: CustomizationItem): JsonObject? {
        val key = item.key ?: return null
        if (key.startsWith("projects/")) {
            val base = key.removePrefix("projects/").substringBeforeLast('/')
            val projects = json.get("projects")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val normalizedBase = try { Path.of(base).toAbsolutePath().normalize() } catch (_: Exception) { return null }
            for ((projectKey, value) in projects.entrySet()) {
                val same = try { Path.of(projectKey).toAbsolutePath().normalize() == normalizedBase } catch (_: Exception) { false }
                if (!same || !value.isJsonObject) continue
                val servers = value.asJsonObject.get("mcpServers") ?: return null
                return if (servers.isJsonObject) servers.asJsonObject else null
            }
            return null
        }
        return json.get("mcpServers")?.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun mcpEntry(json: JsonObject, item: CustomizationItem): JsonObject? =
        mcpServers(json, item)?.get(item.name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun editJson(file: Path, create: Boolean = false, edit: (JsonObject) -> Unit) {
        val json = if (Files.isRegularFile(file)) CustomizationScanner.readJson(file) ?: throw ActionException("$file is not a JSON object")
        else if (create) JsonObject().also { Files.createDirectories(file.parent) } else throw ActionException("$file does not exist")
        edit(json)
        try {
            val tmp = file.resolveSibling(file.name + ".tmp")
            CustomizationScanner.writeJson(tmp, json)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw ActionException("Cannot write $file: ${e.message}", e)
        }
    }

    /** Keeps the `name:` frontmatter in step with the file or folder name, which is what Claude Code shows. */
    private fun setFrontmatterName(file: Path, name: String) {
        if (!Files.isRegularFile(file)) return
        val text = try { Files.readString(file, StandardCharsets.UTF_8) } catch (_: IOException) { return }
        if (!text.startsWith("---")) return
        val end = text.indexOf("\n---", 3).takeIf { it >= 0 } ?: return
        val m = Regex("^(name:\\s*).*$", RegexOption.MULTILINE).find(text) ?: return
        if (m.range.first > end) return
        val updated = text.replaceRange(m.range, m.groupValues[1] + name)
        if (updated != text) Files.writeString(file, updated, StandardCharsets.UTF_8)
    }

    private fun move(from: Path, to: Path) {
        if (Files.exists(to)) throw ActionException("${to.name} already exists")
        try {
            Files.createDirectories(to.parent)
            Files.move(from, to)
        } catch (e: IOException) {
            throw ActionException("Cannot move ${from.name}: ${e.message}", e)
        }
    }

    private fun unique(p: Path): Path {
        var candidate = p; var i = 2
        val base = p.name.removeSuffix(".md"); val ext = if (p.name.endsWith(".md")) ".md" else ""
        while (Files.exists(candidate)) { candidate = p.resolveSibling("$base$i$ext"); i++ }
        return candidate
    }

    private fun copyTree(from: Path, to: Path) {
        try {
            Files.walk(from).use { s -> s.forEach { src -> val dst = to.resolve(from.relativize(src).toString()); if (Files.isDirectory(src)) Files.createDirectories(dst) else Files.copy(src, dst) } }
        } catch (e: IOException) {
            throw ActionException("Cannot copy ${from.name}: ${e.message}", e)
        }
    }

    private fun trash(p: Path) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                if (!Desktop.getDesktop().moveToTrash(p.toFile())) throw ActionException("The system refused to move ${p.name} to the recycle bin")
            } else {
                Files.walk(p).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
            }
        } catch (e: IOException) {
            throw ActionException("Cannot delete ${p.name}: ${e.message}", e)
        }
    }

    private val Path.name: String get() = fileName.toString()

    companion object {
        private val LOG = logger<CustomizationActions>()
    }
}
