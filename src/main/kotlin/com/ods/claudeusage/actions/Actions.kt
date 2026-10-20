package com.ods.claudeusage.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.ods.claudeusage.notify.ThresholdNotifier
import com.ods.claudeusage.service.UsageMonitorService
import com.ods.claudeusage.settings.UsageConfigurable

class RefreshUsageAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) = UsageMonitorService.getInstance().refreshNow()
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class RefreshTokenAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        UsageMonitorService.getInstance().refreshTokenNow { result ->
            val group = NotificationGroupManager.getInstance().getNotificationGroup(ThresholdNotifier.GROUP_ID) ?: return@refreshTokenNow
            result.fold(
                onSuccess = { group.createNotification("Claude access token refreshed", "Valid until ${com.ods.claudeusage.ui.Fmt.clock(it.expiresAt)}.", NotificationType.INFORMATION).notify(project) },
                onFailure = { group.createNotification("Claude token refresh failed", it.message ?: it.javaClass.simpleName, NotificationType.ERROR).notify(project) },
            )
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = UsageMonitorService.getInstance().state.credentialState.credentialsOrNull?.canRefresh == true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class OpenSettingsAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, UsageConfigurable::class.java)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
