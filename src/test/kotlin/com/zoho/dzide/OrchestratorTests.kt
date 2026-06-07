package com.zoho.dzide

import com.zoho.dzide.orchestrator.*
import org.junit.Assert.*
import org.junit.Test

class ActionOrchestratorTest {

    @Test
    fun `ProgressReporter tracks progress monotonically`() {
        val reporter = ProgressReporter()
        reporter.reportProgress(10, "Step 1")
        assertEquals(10, reporter.currentProgress)

        reporter.reportProgress(5, "Step lower")
        assertEquals(10, reporter.currentProgress)

        reporter.reportProgress(50, "Step 2")
        assertEquals(50, reporter.currentProgress)
    }

    @Test
    fun `ProgressReporter listener receives updates`() {
        val updates = mutableListOf<Pair<Int, String>>()
        val reporter = ProgressReporter()
        reporter.onProgress { p, m -> updates.add(p to m) }

        reporter.reportProgress(25, "Quarter")
        reporter.reportProgress(50, "Half")
        reporter.reportComplete()

        assertEquals(3, updates.size)
        assertEquals(25 to "Quarter", updates[0])
        assertEquals(50 to "Half", updates[1])
        assertEquals(100 to "Complete", updates[2])
    }

    @Test
    fun `runStep returns null on success`() {
        val step = object : OrchestratorStep {
            override val progress = 10
            override val message = "Test step"
        }
        val result = (null as ProgressReporter?).runStep(step) { true }
        assertNull(result)
    }

    @Test
    fun `runStep returns Failure on false`() {
        val step = object : OrchestratorStep {
            override val progress = 10
            override val message = "Failing step"
        }
        val result = (null as ProgressReporter?).runStep(step) { false }
        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `runStep returns Failure on exception`() {
        val reporter = ProgressReporter()
        val step = object : OrchestratorStep {
            override val progress = 10
            override val message = "Throwing step"
        }
        val result = reporter.runStep(step) { throw RuntimeException("test error") }
        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("Throwing step"))
    }

    @Test
    fun `cancelled reporter short-circuits step`() {
        val reporter = ProgressReporter()
        reporter.isCancelled = true
        val step = object : OrchestratorStep {
            override val progress = 10
            override val message = "Should not run"
        }
        var ran = false
        val result = reporter.runStep(step) { ran = true; true }
        assertFalse(ran)
        assertTrue(result is ActionResult.Failure)
    }
}

class BuildUpdateStepTest {

    @Test
    fun `BuildUpdateStep has correct progress ordering`() {
        val steps = listOf(
            BuildUpdateStep.StopServer,
            BuildUpdateStep.CopyBuildZip,
            BuildUpdateStep.ExtractBuildZip,
            BuildUpdateStep.ProcessWarFiles,
            BuildUpdateStep.ExecutePreCreateHook,
            BuildUpdateStep.ExecutePostCreateHook,
            BuildUpdateStep.ExecuteZideModuleHook,
            BuildUpdateStep.PatchConfigs,
            BuildUpdateStep.Complete,
        )
        for (i in 1 until steps.size) {
            assertTrue(
                "Step ${steps[i].message} (${steps[i].progress}) should be >= ${steps[i - 1].message} (${steps[i - 1].progress})",
                steps[i].progress >= steps[i - 1].progress
            )
        }
        assertEquals(100, BuildUpdateStep.Complete.progress)
    }
}
