package com.ods.claudeusage.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class UsageToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = UsageDashboardPanel(project)
        Disposer.register(toolWindow.disposable, panel)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        val am = ActionManager.getInstance()
        toolWindow.setTitleActions(listOfNotNull(am.getAction("ClaudeUsage.Refresh"), am.getAction("ClaudeUsage.OpenSettings")))
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
