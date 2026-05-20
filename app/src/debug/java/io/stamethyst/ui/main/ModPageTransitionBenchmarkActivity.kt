package io.stamethyst.ui.main

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import io.stamethyst.config.LauncherThemeColor
import io.stamethyst.config.LauncherThemeMode
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.LAUNCHER_DOCK_ITEM_TAG_PREFIX
import io.stamethyst.ui.theme.LauncherTheme

class ModPageTransitionBenchmarkActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uiState = buildBenchmarkState(BENCHMARK_MOD_COUNT)
        setContent {
            LauncherTheme(
                themeMode = LauncherThemeMode.LIGHT,
                themeColor = LauncherThemeColor.COLORLESS
            ) {
                var showMods by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true }
                ) {
                    Surface(
                        modifier = Modifier
                            .height(56.dp)
                            .testTag(LAUNCHER_DOCK_ITEM_TAG_PREFIX + "Mods")
                            .clickable { showMods = true },
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "模组",
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    if (showMods) {
                        LauncherModsScreenContent(
                            modifier = Modifier.weight(1f),
                            uiState = uiState,
                            actions = MainScreenActions(isHostAvailable = true)
                        )
                    } else {
                        LauncherGameScreenContent(
                            modifier = Modifier.weight(1f),
                            uiState = uiState,
                            actions = MainScreenActions(isHostAvailable = true)
                        )
                    }
                }
            }
        }
    }

    private companion object {
        private const val BENCHMARK_MOD_COUNT = 300
    }
}

private fun buildBenchmarkState(modCount: Int): MainScreenViewModel.UiState {
    val mods = List(modCount) { index ->
        ModItemUi(
            modId = "benchmark_mod_$index",
            manifestModId = "benchmark_mod_$index",
            storagePath = "/benchmark/mod-$index.jar",
            name = "Benchmark Mod $index",
            version = "1.$index.0",
            description = "Synthetic benchmark mod with enough text to exercise card composition and text layout.",
            dependencies = if (index % 7 == 0) listOf("basemod") else emptyList(),
            required = false,
            installed = true,
            enabled = index % 3 != 0,
            explicitPriority = if (index % 5 == 0) index % 10 else null,
            effectivePriority = index % 10,
            favorite = index % 11 == 0
        )
    }
    return MainScreenViewModel.UiState(
        initializing = false,
        dependencyMods = emptyList(),
        optionalMods = mods,
        controlsEnabled = true,
        showModFileName = true,
        modFolders = listOf(MainScreenViewModel.ModFolder(id = BENCHMARK_FOLDER_ID, name = "Benchmark Mods")),
        folderAssignments = mods.associate { it.storagePath to BENCHMARK_FOLDER_ID },
        folderCollapsed = mapOf(BENCHMARK_FOLDER_ID to false),
        unassignedCollapsed = true,
        dependencyFolderCollapsed = true,
        dragLocked = false,
        unassignedFolderOrder = 1
    )
}

private const val BENCHMARK_FOLDER_ID = "benchmark-folder"
