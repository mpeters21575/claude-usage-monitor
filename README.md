# Claude Usage Monitor for JetBrains Rider

A tool window, status bar widget and alert system that shows what your Claude subscription is doing while
you work with Claude Code: rate-limit utilisation per window and per model, the model and context size of the
active session, a forecast of when limits (and the context window) run out, where the tokens went, and the
account you are signed in with.

The plugin only depends on the IntelliJ Platform, so it also runs in IntelliJ IDEA, WebStorm, PyCharm and the
other JetBrains IDEs. Rider 2026.2 is the primary target (platform build 252+).

## Design (1.1)

The tool window implements the "Claude Usage Panel IntUI" design (Claude Design handoff 1.0 → 1.1, 2026-10-12):
JetBrains Int UI look with colours taken from the IDE theme (`JBColor`, `UIUtil`, `JBUI.CurrentTheme`) so Darcula,
Light and custom themes work; the IDE UI font and editor font; collapsible sections whose state is persisted per
project; one card for the context window with a combined history + forecast chart; clickable stat tiles with a
single open detail panel; the Customizations section; and a models section with a 5 h / 7 d switch. The widgets
live in `ui/intui` and paint themselves in CSS pixels mapped through the IDE scale.

### Customizations

The section lists everything in `~/.claude` (user scope) and `<project>/.claude` (project scope):

| Type | Where | Enable / disable |
|---|---|---|
| Skills | `skills/<name>/SKILL.md` | rename the folder to `<name>.disabled` |
| Agents | `agents/<name>.md` | rename to `<name>.md.disabled` |
| Commands | `commands/**/<name>.md` (folders become `/dir:name`) | rename to `<name>.md.disabled` |
| Hooks | `settings.json` and `settings.local.json` → `hooks` | moved to `_disabledHooks` in the same file |
| MCP servers | `.mcp.json` (project), `~/.claude.json` top level (user) and `projects.<path>` (project) | `"disabled": true` on the entry |

Actions per item: enable switch, Open in Rider (editor tab), rename, duplicate, move between scopes, copy path,
show in Explorer, delete (files go to the Recycle Bin), and New (scaffolds a skill, agent, command, hook or MCP
server and opens it). Usage counts come from the transcripts of the last 7 days: `Skill` tool calls, `Agent`
dispatches, `<command-name>` markers in user messages and `mcp__server__*` tool names. Skill → agent orchestration
is detected from the `agents:` frontmatter list and back-ticked agent names in the skill body.

The list refreshes from a VFS listener (both `.claude` folders are watch roots) and a 10-second rescan.

## What it shows

| Area | Source | Notes |
|---|---|---|
| 5-hour, 7-day and per-model utilisation, reset times | `GET https://api.anthropic.com/api/oauth/usage` with the Claude Code OAuth token | Same data as `/usage` in Claude Code. Any new window Anthropic adds appears automatically. |
| Subscription plan and rate-limit tier | `~/.claude/.credentials.json` (`subscriptionType`, `rateLimitTier`) and the OAuth profile endpoint | Shown in the header, e.g. `name@company.com · Team · Max 5x`. |
| Active session: model, context size, turns, tokens, branch | `~/.claude/projects/**/*.jsonl` transcripts | The session that was active most recently inside the open Rider project; falls back to the most recent session anywhere. |
| "Start a new session" banner | context tokens of the last turn vs. the context window | Threshold configurable (default 75%). 1M-context models are detected from `[1m]` in the model name or in Claude Code's `settings.json`. |
| Forecast | least-squares slope over the last hour of samples | Tells you whether you hit 100% before the window resets, and when the context reaches the threshold. |
| Estimated tokens left per window | tokens used in the window (transcripts) ÷ utilisation | An estimate: Anthropic weights tokens by model and cache state. Labelled with ≈ everywhere. |
| Where tokens go | content blocks in the transcripts | User and system prompts, assistant text, thinking, tool call arguments, tool results grouped by MCP server or built-in tool family, images. Estimated at four characters per token; the exact input/output/cache split from the API counters is shown next to it. |
| Models | transcripts | Tokens per model in the current 5-hour and 7-day windows, plus the per-model limit left when Anthropic reports one. |

## Account handling

* Credentials are looked up in this order: `CLAUDE_CODE_OAUTH_TOKEN`, `$CLAUDE_CONFIG_DIR/.credentials.json`,
  `~/.claude/.credentials.json`, and on macOS the `Claude Code-credentials` keychain item.
* The credentials file is watched every few seconds. Signing in or switching accounts in Claude Code (the Rider
  plugin or the CLI) is picked up immediately; the dashboard resets its usage data, re-resolves the account and
  shows a notification.
* Expired access tokens are refreshed automatically (default on, configurable). The plugin waits two minutes
  after expiry and skips the refresh while Claude Code is writing the file itself, so it never races the CLI.
  Refresh tokens rotate, therefore the new tokens are written back to `.credentials.json` for Claude Code.
  A manual "Refresh token now" button is on the banner as well.
* Nothing leaves your machine except the calls to Anthropic above.

## Build

Requirements: JDK 21+ on `JAVA_HOME` (the JetBrains Runtime shipped with Rider works), network access for
Gradle and the IntelliJ Platform artifacts.

```bash
./gradlew buildPlugin
```

The distributable ZIP lands in `build/distributions/`. Install it in Rider via
*Settings → Plugins → ⚙ → Install Plugin from Disk…*.

To build against a local Rider installation instead of downloading the SDK:

```bash
./gradlew buildPlugin -PplatformLocalPath="C:/Program Files/JetBrains/JetBrains Rider 2026.2.2"
```

To run a sandboxed IDE with the plugin: `./gradlew runIde`.

### Verification

```bash
./gradlew test verifyPlugin
```

* `test` runs the JUnit suite: usage JSON parsing, forecasting math, transcript indexing (including a scan of the
  real `~/.claude/projects` when present) and a headless render of the dashboard components to
  `build/render/dashboard-{light,dark}.png` for a visual check without starting an IDE.
* `verifyPlugin` runs JetBrains' Plugin Verifier against the target IDE and reports API compatibility problems.

## Settings

*Settings → Tools → Claude Usage Monitor*: Claude home directory, poll intervals, context window sizes, new-session
threshold, chart time window, alert thresholds, status bar widget, automatic token refresh.

## Rate limiting of the usage endpoint

Anthropic's usage endpoint answers HTTP 429 for minutes after a handful of calls in a short time (measured on
2026-10-06: twelve calls in a row all got 429 with `Retry-After: 0`, one call a minute earlier succeeded). The
plugin therefore:

* polls every 5 minutes by default (minimum 2 minutes in the settings),
* backs off exponentially on 429 (2, 4, 8, then 15 minutes, or longer when `Retry-After` says so),
* only re-queries immediately when the access token in `.credentials.json` actually changed, not on every rewrite
  of that file,
* keeps showing the last successful data as "Live · N ago · rate limited" and explains the situation in a banner.

Context size, token counts and the breakdown do not depend on the endpoint; they refresh from the local
transcripts every few seconds.

## Limitations

* The usage and profile endpoints are the ones Claude Code itself uses; they are not part of the public API and
  may change. The parser is tolerant (it keeps every window it finds), but a breaking change would show up as
  "Reconnecting" in the header.
* Token estimates for the breakdown and the "tokens left" figures are estimates by design; the exact figures are
  the API counters shown in the type breakdown.
* Chart history is kept for 48 hours in the IDE system directory (`claude-usage-monitor/history.jsonl`).
