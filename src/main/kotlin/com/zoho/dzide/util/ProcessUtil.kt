package com.zoho.dzide.util

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import java.nio.charset.StandardCharsets

object ProcessUtil {

    fun executeCapturing(
        command: List<String>,
        workingDir: String? = null,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Int = 30_000
    ): CapturedOutput {
        val commandLine = GeneralCommandLine(command)
            .withCharset(StandardCharsets.UTF_8)
        workingDir?.let { commandLine.withWorkDirectory(it) }
        env.forEach { (k, v) -> commandLine.withEnvironment(k, v) }

        val handler = CapturingProcessHandler(commandLine)
        val output = handler.runProcess(timeoutMs)
        return CapturedOutput(
            stdout = output.stdout,
            stderr = output.stderr,
            exitCode = output.exitCode
        )
    }

    fun executeStreaming(
        command: List<String>,
        workingDir: String? = null,
        env: Map<String, String> = emptyMap(),
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {},
        onExit: (Int) -> Unit = {}
    ): OSProcessHandler {
        val commandLine = GeneralCommandLine(command)
            .withCharset(StandardCharsets.UTF_8)
        workingDir?.let { commandLine.withWorkDirectory(it) }
        env.forEach { (k, v) -> commandLine.withEnvironment(k, v) }

        val handler = OSProcessHandler(commandLine)
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = event.text ?: return
                if (text.isEmpty()) return
                if (outputType.toString() == "stderr") {
                    onStderr(text)
                } else {
                    onStdout(text)
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                onExit(event.exitCode)
            }
        })
        handler.startNotify()
        return handler
    }

    /**
     * Runs a process with streaming output and blocks until exit (or [timeoutMs]).
     * Invokes [shouldCancel] periodically; destroys the process when it returns true.
     */
    fun executeStreamingAndWait(
        command: List<String>,
        workingDir: String? = null,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 600_000,
        pollMs: Long = 200,
        shouldCancel: () -> Boolean = { false },
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {}
    ): CapturedOutput {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode = -1
        val handler = executeStreaming(
            command = command,
            workingDir = workingDir,
            env = env,
            onStdout = {
                stdout.append(it)
                onStdout(it)
            },
            onStderr = {
                stderr.append(it)
                onStderr(it)
            },
            onExit = { code -> exitCode = code }
        )

        val deadline = System.currentTimeMillis() + timeoutMs
        while (!handler.isProcessTerminated) {
            if (shouldCancel()) {
                handler.destroyProcess()
                break
            }
            if (System.currentTimeMillis() > deadline) {
                handler.destroyProcess()
                throw IllegalStateException("Process timed out after ${timeoutMs}ms: ${command.joinToString(" ")}")
            }
            try {
                Thread.sleep(pollMs)
            } catch (_: InterruptedException) {
                handler.destroyProcess()
                Thread.currentThread().interrupt()
                break
            }
        }
        handler.waitFor(5_000)
        if (exitCode < 0) {
            exitCode = handler.exitCode ?: -1
        }
        return CapturedOutput(stdout.toString(), stderr.toString(), exitCode)
    }

    data class CapturedOutput(
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )
}
