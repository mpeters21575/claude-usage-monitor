package com.ods.claudeusage.customizations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CustomizationScannerTest {

    private fun write(p: Path, text: String) { Files.createDirectories(p.parent); Files.writeString(p, text) }

    @Test
    fun `scans skills agents commands hooks and mcp servers in both scopes`(@TempDir tmp: Path) {
        val home = tmp.resolve("home/.claude")
        val project = tmp.resolve("proj")
        write(home.resolve("skills/ds-code-fix-workflow/SKILL.md"), "---\nname: ds-code-fix-workflow\ndescription: >\n  Fix a bug from a board item.\nagents:\n  - explore\n  - verifier\n---\n\n# Flow\n\n1. Dispatch `explore` first.\n2. Fix.\n3. Dispatch `verifier`; block on failure.\n")
        write(home.resolve("skills/.trash/old/SKILL.md"), "---\nname: old\n---\n")
        write(home.resolve("agents/explore.md"), "---\nname: explore\ndescription: Read-only exploration.\n---\nbody")
        write(home.resolve("agents/verifier.md.disabled"), "---\nname: verifier\ndescription: Verifies.\n---\nbody")
        write(project.resolve(".claude/commands/pr.md"), "---\ndescription: Create a PR\n---\nbody")
        write(project.resolve(".claude/commands/sub/deploy.md"), "body only")
        write(project.resolve(".claude/settings.json"), """{"hooks":{"PreToolUse":[{"matcher":"Edit|Write","hooks":[{"type":"command","command":"dotnet format"}]}]},"_disabledHooks":{"Stop":[{"hooks":[{"type":"command","command":"notify.ps1"}]}]}}""")
        write(project.resolve(".mcp.json"), """{"mcpServers":{"rider":{"command":"npx","args":["-y","@jetbrains/mcp-proxy"]},"off":{"url":"http://x","disabled":true}}}""")
        write(tmp.resolve("home/.claude.json"), """{"mcpServers":{"chrome":{"type":"stdio","command":"chrome-mcp"}},"projects":{"${project.toString().replace("\\", "\\\\")}":{"mcpServers":{"local":{"command":"x"}}}}}""")

        val items = CustomizationScanner().scan(home, tmp.resolve("home/.claude.json"), project)
        val byName = items.associateBy { "${it.type}:${it.name}" }

        val skill = byName["SKILL:ds-code-fix-workflow"]
        assertNotNull(skill)
        assertEquals(Scope.USER, skill!!.scope)
        assertEquals("Fix a bug from a board item.", skill.description)
        assertEquals(listOf("explore", "verifier"), skill.agents)
        assertEquals("sequential", skill.mode)
        assertFalse(items.any { it.name == "old" }, "hidden .trash folder is skipped")

        assertTrue(byName["AGENT:explore"]!!.enabled)
        assertFalse(byName["AGENT:verifier"]!!.enabled)
        assertEquals("/pr", byName["COMMAND:/pr"]!!.name)
        assertEquals("Create a PR", byName["COMMAND:/pr"]!!.description)
        assertEquals("/sub:deploy", byName["COMMAND:/sub:deploy"]!!.name)

        val hook = byName["HOOK:PreToolUse · Edit|Write"]!!
        assertTrue(hook.enabled); assertEquals("dotnet format", hook.description); assertEquals(Scope.PROJECT, hook.scope)
        assertFalse(byName["HOOK:Stop"]!!.enabled)

        assertTrue(byName["MCP:rider"]!!.enabled)
        assertFalse(byName["MCP:off"]!!.enabled)
        assertEquals(Scope.USER, byName["MCP:chrome"]!!.scope)
        assertEquals(Scope.PROJECT, byName["MCP:local"]!!.scope)
    }

    @Test
    fun `actions rename duplicate disable move and delete`(@TempDir tmp: Path) {
        val home = tmp.resolve("home/.claude")
        val project = tmp.resolve("proj")
        write(home.resolve("agents/explore.md"), "---\nname: explore\n---\n")
        write(project.resolve(".claude/settings.json"), """{"hooks":{"Stop":[{"hooks":[{"type":"command","command":"a"}]}]}}""")
        write(project.resolve(".mcp.json"), """{"mcpServers":{"rider":{"command":"x"}}}""")
        val scanner = CustomizationScanner()
        val actions = CustomizationActions(home, project)
        fun scan() = scanner.scan(home, null, project)

        val agent = scan().first { it.type == CustomizationType.AGENT }
        actions.setEnabled(agent, false)
        assertTrue(Files.exists(home.resolve("agents/explore.md.disabled")))
        actions.setEnabled(scan().first { it.type == CustomizationType.AGENT }, true)
        assertTrue(Files.exists(home.resolve("agents/explore.md")))

        actions.duplicate(scan().first { it.type == CustomizationType.AGENT })
        assertTrue(Files.exists(home.resolve("agents/explore-copy.md")))

        actions.rename(scan().first { it.name == "explore-copy" }, "scout")
        assertTrue(Files.exists(home.resolve("agents/scout.md")))

        actions.moveScope(scan().first { it.name == "scout" })
        assertTrue(Files.exists(project.resolve(".claude/agents/scout.md")))

        val hook = scan().first { it.type == CustomizationType.HOOK }
        actions.setEnabled(hook, false)
        val settings = Files.readString(project.resolve(".claude/settings.json"))
        assertTrue(settings.contains("_disabledHooks") && !settings.contains("\"hooks\": {\n    \"Stop\""), settings)
        actions.setEnabled(scan().first { it.type == CustomizationType.HOOK }, true)
        assertFalse(Files.readString(project.resolve(".claude/settings.json")).contains("_disabledHooks"))

        val mcp = scan().first { it.type == CustomizationType.MCP }
        actions.setEnabled(mcp, false)
        assertTrue(Files.readString(project.resolve(".mcp.json")).contains("\"disabled\": true"))
        actions.rename(scan().first { it.type == CustomizationType.MCP }, "jetbrains")
        assertTrue(Files.readString(project.resolve(".mcp.json")).contains("\"jetbrains\""))
        actions.delete(scan().first { it.type == CustomizationType.MCP })
        assertFalse(Files.readString(project.resolve(".mcp.json")).contains("jetbrains"))

        val f = actions.scaffold(CustomizationType.SKILL, Scope.PROJECT, "my skill")
        assertTrue(Files.exists(project.resolve(".claude/skills/my-skill/SKILL.md")))
        assertEquals("my-skill", scan().first { it.type == CustomizationType.SKILL }.name)
        assertEquals(f, project.resolve(".claude/skills/my-skill/SKILL.md"))
    }

    @Test
    fun `frontmatter parser handles folded and list values`() {
        val (front, body) = CustomizationScanner.parseFrontmatterText("---\nname: x\ndescription: >\n  Line one\n  line two.\nagents:\n  - a\n  - b\n---\nBody")
        assertEquals("x", front["name"])
        assertEquals("Line one line two.", front["description"])
        assertEquals("a\nb", front["agents"])
        assertEquals("Body", body)
    }
}
