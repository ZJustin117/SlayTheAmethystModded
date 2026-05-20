package io.stamethyst.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModPageEnterBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun enterModsPageFromGamePage() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = null,
        iterations = 1,
        setupBlock = {
            startLauncherActivity()
            device.wait(Until.hasObject(By.res(MODS_DOCK_ITEM_RES)), STARTUP_TIMEOUT_MS)
        }
    ) {
        device.findObject(By.res(MODS_DOCK_ITEM_RES))?.click() ?: device.click(120, 80)
        device.wait(Until.hasObject(By.res(MODS_CONTENT_READY_RES)), PAGE_READY_TIMEOUT_MS)
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.startLauncherActivity() {
        val intent = Intent().apply {
            setClassName(TARGET_PACKAGE, "$TARGET_PACKAGE.ui.main.ModPageTransitionBenchmarkActivity")
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivityAndWait(intent)
    }

    private companion object {
        private const val TARGET_PACKAGE = "io.stamethyst"
        private const val STARTUP_TIMEOUT_MS = 5_000L
        private const val PAGE_READY_TIMEOUT_MS = 5_000L
        private const val MODS_DOCK_ITEM_RES = "launcher_dock_item_Mods"
        private const val MODS_CONTENT_READY_RES = "mods_content_ready"
    }
}
