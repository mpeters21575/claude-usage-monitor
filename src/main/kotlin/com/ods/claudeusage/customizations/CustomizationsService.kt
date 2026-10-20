package com.ods.claudeusage.customizations

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import com.ods.claudeusage.settings.UsageSettings
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

fun interface CustomizationsListener {
    fun customizationsChanged(items: List<CustomizationItem>)
}

/**
 * Keeps the list of customizations of the user scope and this project's scope current: a VFS listener
 * reacts to edits inside the IDE, and a periodic rescan (every 10 s) catches changes made by Claude Code or
 * other tools outside the IDE. Both `.claude` directories are added as watch roots so external changes also
 * reach the VFS.
 */
@Service(Service.Level.PROJECT)
class CustomizationsService(private val project: Project) : Disposable {

    private val executor = AppExecutorUtil.createBoundedScheduledExecutorService("Claude Customizations", 1)
    private val scanner = CustomizationScanner()

    @Volatile
    var items: List<CustomizationItem> = emptyList()
        private set

    private var future: ScheduledFuture<*>? = null
    private var pending = false

    val userHome: Path get() = UsageSettings.getInstance().claudeHomePath
    val userClaudeJson: Path get() = userHome.parent.resolve(".claude.json")
    val projectBase: Path? get() = project.basePath?.let { Path.of(it) }

    val actions: CustomizationActions get() = CustomizationActions(userHome, projectBase)

    init {
        for (p in listOfNotNull(userHome, projectBase?.resolve(".claude"))) {
            try { LocalFileSystem.getInstance().addRootToWatch(p.toString(), true) } catch (e: Throwable) { LOG.debug("Cannot watch $p", e) }
        }
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    if (events.any { e -> e.path.contains("/.claude/") || e.path.endsWith("/.claude.json") || e.path.endsWith("/.mcp.json") }) rescanSoon()
                }
            },
        )
        future = executor.scheduleWithFixedDelay(::rescan, 0, 10, TimeUnit.SECONDS)
    }

    /** Rescans within a second (coalesces bursts of file events). */
    fun rescanSoon() {
        if (pending) return
        pending = true
        executor.schedule({ pending = false; rescan() }, 700, TimeUnit.MILLISECONDS)
    }

    private fun rescan() {
        try {
            val fresh = scanner.scan(userHome, userClaudeJson, projectBase)
            if (fresh != items) {
                items = fresh
                if (!project.isDisposed) project.messageBus.syncPublisher(TOPIC).customizationsChanged(fresh)
            }
        } catch (t: Throwable) {
            LOG.warn("Customization scan failed", t)
        }
    }

    /** Runs a file action on the background thread, refreshes the VFS and rescans; errors go to [onError]. */
    fun run(onError: (String) -> Unit, action: CustomizationActions.() -> Unit) {
        executor.execute {
            try {
                actions.action()
                for (p in listOfNotNull(userHome, projectBase)) LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p)?.refresh(true, true)
            } catch (e: CustomizationActions.ActionException) {
                onError(e.message ?: "Action failed")
            } catch (t: Throwable) {
                LOG.warn("Customization action failed", t)
                onError(t.message ?: t.javaClass.simpleName)
            }
            rescan()
        }
    }

    override fun dispose() {
        future?.cancel(false)
        executor.shutdownNow()
    }

    companion object {
        private val LOG = logger<CustomizationsService>()

        @JvmField
        val TOPIC: Topic<CustomizationsListener> = Topic.create("Claude Customizations", CustomizationsListener::class.java)

        fun getInstance(project: Project): CustomizationsService = project.getService(CustomizationsService::class.java)
    }
}
