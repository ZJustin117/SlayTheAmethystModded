package io.stamethyst.macrobenchmark

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModPageEnterInteractionTest {
    @Test
    fun enterModsPageSyntheticStress() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val durations = LongArray(ITERATIONS)

        repeat(ITERATIONS) { index ->
            device.executeShellCommand(
                "am start -S -W -n $TARGET_PACKAGE/.ui.main.ModPageTransitionBenchmarkActivity"
            )
            assertTrue(
                "Timed out waiting for mods dock entry",
                device.wait(Until.hasObject(By.res(MODS_DOCK_ITEM_RES)), STARTUP_TIMEOUT_MS)
            )

            val startMs = SystemClock.elapsedRealtime()
            val modsEntryBounds = device.findObject(By.res(MODS_DOCK_ITEM_RES)).visibleBounds
            device.click(modsEntryBounds.centerX(), modsEntryBounds.centerY())
            assertTrue(
                "Timed out waiting for mods content",
                device.wait(Until.hasObject(By.res(MODS_CONTENT_READY_RES)), PAGE_READY_TIMEOUT_MS)
            )
            durations[index] = SystemClock.elapsedRealtime() - startMs
            instrumentation.waitForIdleSync()
            Log.i(TAG, "enterModsPageSyntheticStress iteration=${index + 1} durationMs=${durations[index]}")
        }

        val sorted = durations.sorted()
        Log.i(
            TAG,
            "enterModsPageSyntheticStress summary iterations=$ITERATIONS " +
                "minMs=${sorted.first()} medianMs=${sorted[ITERATIONS / 2]} maxMs=${sorted.last()} " +
                "allMs=${durations.joinToString(prefix = "[", postfix = "]")}")
    }

    private companion object {
        private const val TAG = "ModPageEnterInteraction"
        private const val TARGET_PACKAGE = "io.stamethyst"
        private const val ITERATIONS = 5
        private const val STARTUP_TIMEOUT_MS = 5_000L
        private const val PAGE_READY_TIMEOUT_MS = 20_000L
        private const val MODS_DOCK_ITEM_RES = "launcher_dock_item_Mods"
        private const val MODS_CONTENT_READY_RES = "mods_content_ready"
    }
}
