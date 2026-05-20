package io.stamethyst.ui.quickstart

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.steam.SteamStsJarDownloadPhase
import io.stamethyst.ui.resolve
import io.stamethyst.ui.settings.SettingsScreenViewModel
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun QuickStartScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
    onOpenSteamLogin: () -> Unit,
    onImportSuccess: () -> Unit
) {
    val activity = requireNotNull(LocalActivity.current)
    val uriHandler = LocalUriHandler.current
    val quickStartDownloadUrl = stringResource(R.string.update_dialog_quark_download_url)
    val uiState = viewModel.uiState
    var imported by rememberSaveable { mutableStateOf(false) }
    var showJarSourceOverlay by rememberSaveable { mutableStateOf(false) }
    var showJarSourceDialog by rememberSaveable { mutableStateOf(false) }
    val noopInteraction = remember { MutableInteractionSource() }
    val closeJarSourceDialog = {
        showJarSourceOverlay = false
        showJarSourceDialog = false
    }
    val pickJarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.onJarPicked(activity, uri) { success ->
                if (success) {
                    imported = true
                    closeJarSourceDialog()
                }
            }
        }
    }

    fun startSteamImport() {
        if (uiState.busy) {
            return
        }
        if (!uiState.steamCloudRefreshTokenConfigured) {
            onOpenSteamLogin()
            return
        }
        onOpenSteamLogin()
    }

    LaunchedEffect(Unit) {
        viewModel.bind(activity)
    }

    LaunchedEffect(showJarSourceDialog, imported) {
        if (showJarSourceDialog && !imported) {
            showJarSourceOverlay = false
            delay(100)
            if (showJarSourceDialog && !imported) {
                showJarSourceOverlay = true
            }
        } else {
            showJarSourceOverlay = false
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 380.dp)
                    .animateContentSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AnimatedContent(
                    targetState = imported,
                    label = "quickstart-title"
                ) { isImported ->
                    Text(
                        text = stringResource(
                            if (isImported) {
                                R.string.quick_start_title_done
                            } else {
                                R.string.quick_start_welcome_title
                            }
                        ),
                        style = if (isImported) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )
                }

                AnimatedContent(
                    targetState = imported,
                    label = "quickstart-subtitle"
                ) { isImported ->
                    Text(
                        text = if (isImported) {
                            ""
                        } else {
                            stringResource(R.string.quick_start_welcome_subtitle)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                AnimatedVisibility(visible = !imported, label = "quickstart-start-options") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        QuickStartChoiceCard(
                            title = stringResource(R.string.quick_start_start_from_file_title),
                            subtitle = stringResource(R.string.quick_start_start_from_file_subtitle),
                            enabled = !uiState.busy,
                            onClick = {
                                pickJarLauncher.launch(
                                    arrayOf("application/java-archive", "application/octet-stream", "*/*")
                                )
                            }
                        )
                        QuickStartChoiceCard(
                            title = stringResource(R.string.quick_start_start_from_steam_title),
                            subtitle = stringResource(R.string.quick_start_start_from_steam_subtitle),
                            enabled = !uiState.busy,
                            onClick = ::startSteamImport
                        )
                    }
                }

                AnimatedVisibility(visible = !imported, label = "quickstart-jar-source-toggle") {
                    Text(
                        text = stringResource(R.string.quick_start_missing_jar_link),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable(enabled = !uiState.busy) {
                            showJarSourceDialog = true
                        }
                    )
                }

                if (uiState.busy) {
                    val busyProgress = uiState.busyProgressPercent
                        ?.coerceIn(0, 100)
                        ?.div(100f)
                    if (busyProgress != null) {
                        LinearProgressIndicator(
                            progress = { busyProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    uiState.busyMessage?.let { busyMessage ->
                        Text(
                            text = busyMessage.resolve(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                AnimatedVisibility(visible = imported, label = "quickstart-continue-button") {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(
                                enabled = !uiState.busy,
                                onClick = onImportSuccess
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "→",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !imported && showJarSourceOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            label = "quickstart-jar-source-overlay"
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(onClick = closeJarSourceDialog)
            )
        }

        AnimatedVisibility(
            visible = !imported && showJarSourceDialog,
            enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessHigh)) +
                scaleIn(
                    initialScale = 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
            exit = fadeOut() + scaleOut(targetScale = 0.95f),
            label = "quickstart-jar-source-dialog"
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .widthIn(max = 360.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp
                ) {
                    Box(
                        modifier = Modifier
                            .clickable(
                                interactionSource = noopInteraction,
                                indication = null
                            ) { }
                            .padding(horizontal = 18.dp, vertical = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.quick_start_missing_jar_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.quick_start_missing_jar_intro),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.quick_start_missing_jar_howto),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.quick_start_missing_jar_step1),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.quick_start_missing_jar_step2),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(
                                        R.string.quick_start_missing_jar_download_link,
                                        quickStartDownloadUrl
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier.clickable {
                                        uriHandler.openUri(quickStartDownloadUrl)
                                    }
                                )
                            }
                            Text(
                                text = stringResource(R.string.quick_start_acknowledge),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline,
                                modifier = Modifier.clickable(onClick = closeJarSourceDialog)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickStartSteamDownloadScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
    onImportSuccess: () -> Unit,
) {
    val activity = requireNotNull(LocalActivity.current)
    val uiState = viewModel.uiState
    var imported by rememberSaveable { mutableStateOf(false) }
    var downloadStarted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.bind(activity)
    }

    LaunchedEffect(uiState.steamCloudRefreshTokenConfigured, uiState.busy, imported, downloadStarted) {
        if (uiState.steamCloudRefreshTokenConfigured && !uiState.busy && !downloadStarted && !imported) {
            downloadStarted = true
            viewModel.onStartQuickStartSteamImport(activity) { success ->
                if (success) {
                    imported = true
                } else {
                    downloadStarted = false
                }
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .animateContentSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(
                        if (imported) R.string.quick_start_title_done else R.string.quick_start_steam_download_page_title
                    ),
                    style = if (imported) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
                if (!imported) {
                    Text(
                        text = stringResource(R.string.quick_start_steam_download_page_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    SteamAccelerationSwitchCard(
                        checked = uiState.workshopWattAccelerationEnabled,
                        enabled = true,
                        onCheckedChange = { enabled ->
                            viewModel.onQuickStartSteamAccelerationChanged(activity, enabled)
                        }
                    )
                    QuickStartSteamDownloadProgressCard(uiState = uiState)
                }
                AnimatedVisibility(visible = imported, label = "quickstart-steam-continue-button") {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(onClick = onImportSuccess),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "→",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SteamAccelerationSwitchCard(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(
                        if (checked) {
                            R.string.quick_start_steam_acceleration_enabled_title
                        } else {
                            R.string.quick_start_steam_acceleration_disabled_title
                        }
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.quick_start_steam_acceleration_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun QuickStartSteamDownloadProgressCard(
    uiState: SettingsScreenViewModel.UiState,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val progress = uiState.busyProgressPercent
                ?.coerceIn(0, 100)
                ?.div(100f)
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            uiState.busyMessage?.let { busyMessage ->
                Text(
                    text = busyMessage.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start
                )
            }
            val totalBytes = uiState.quickStartSteamTotalBytes
            if (totalBytes != null && totalBytes > 0L) {
                HorizontalDivider()
                Text(
                    text = stringResource(
                        R.string.quick_start_steam_download_detail,
                        formatQuickStartBytes(uiState.quickStartSteamDownloadedBytes),
                        formatQuickStartBytes(totalBytes),
                        uiState.busyProgressPercent ?: 0
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (uiState.quickStartSteamDownloadPhase == SteamStsJarDownloadPhase.CONNECTING ||
                uiState.quickStartSteamDownloadPhase == SteamStsJarDownloadPhase.RESOLVING
            ) {
                Text(
                    text = stringResource(R.string.quick_start_steam_download_waiting_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatQuickStartBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.coerceAtLeast(0L).toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}

@Composable
private fun QuickStartChoiceCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f)
                }
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.46f)
                }
            )
        }
    }
}
