package com.zoho.dzide.orchestrator

sealed class ActionResult {
    data object Success : ActionResult()
    data class Failure(val message: String, val cause: Exception? = null) : ActionResult()
}

interface OrchestratorStep {
    val progress: Int
    val message: String
}

class ProgressReporter {
    var currentProgress: Int = 0
        private set
    var currentMessage: String = ""
        private set
    var isCancelled: Boolean = false

    private var listener: ((Int, String) -> Unit)? = null

    fun onProgress(listener: (Int, String) -> Unit) {
        this.listener = listener
    }

    fun reportProgress(progress: Int, message: String) {
        if (progress > currentProgress) {
            currentProgress = progress
        }
        currentMessage = message
        listener?.invoke(currentProgress, currentMessage)
    }

    fun reportError(message: String) {
        currentMessage = "Error: $message"
        listener?.invoke(currentProgress, currentMessage)
    }

    fun reportComplete() {
        currentProgress = 100
        currentMessage = "Complete"
        listener?.invoke(100, "Complete")
    }
}

fun ProgressReporter?.runStep(step: OrchestratorStep, block: () -> Boolean): ActionResult? {
    this?.reportProgress(step.progress, step.message)
    if (this?.isCancelled == true) {
        return ActionResult.Failure("Cancelled")
    }
    return try {
        val success = block()
        if (!success) ActionResult.Failure("Step failed: ${step.message}") else null
    } catch (e: Exception) {
        this?.reportError("${step.message}: ${e.message}")
        ActionResult.Failure(step.message, e)
    }
}

interface Action {
    fun execute(progressReporter: ProgressReporter? = null): ActionResult
}
