package com.zoho.dzide.orchestrator

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

object BackgroundTaskLauncher {

    fun launch(
        project: Project,
        title: String,
        cancellable: Boolean = true,
        action: Action
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, cancellable) {
            override fun run(indicator: ProgressIndicator) {
                val reporter = ProgressReporter()
                reporter.onProgress { progress, message ->
                    indicator.fraction = progress / 100.0
                    indicator.text = message
                }

                indicator.isIndeterminate = false
                val result = action.execute(reporter)

                if (result is ActionResult.Failure) {
                    indicator.text = "Failed: ${result.message}"
                }
            }
        })
    }

    fun launch(
        project: Project,
        title: String,
        cancellable: Boolean = true,
        block: (ProgressReporter) -> ActionResult
    ) {
        val inlineAction = object : Action {
            override fun execute(progressReporter: ProgressReporter?): ActionResult {
                return block(progressReporter ?: ProgressReporter())
            }
        }
        launch(project, title, cancellable, inlineAction)
    }
}
