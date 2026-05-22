package io.stamethyst.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopUpdateCheckCoordinator
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.stamethyst.navigation.LocalNavigator
import io.stamethyst.navigation.Route
import io.stamethyst.navigation.rememberAppNavigator
import io.stamethyst.backend.feedback.FeedbackInboxCoordinator
import io.stamethyst.ui.compatibility.LauncherCompatibilityScreen
import io.stamethyst.ui.feedback.LauncherFeedbackScreen
import io.stamethyst.ui.feedback.LauncherFeedbackConversationScreen
import io.stamethyst.ui.feedback.LauncherFeedbackIssueBrowserScreen
import io.stamethyst.ui.feedback.LauncherFeedbackIssuePreviewScreen
import io.stamethyst.ui.feedback.LauncherFeedbackSubscriptionsScreen
import io.stamethyst.ui.feedback.FeedbackSubmissionNotice
import io.stamethyst.ui.main.LauncherMainScreen
import io.stamethyst.ui.main.LauncherCrashRecoveryScreen
import io.stamethyst.ui.main.LauncherModsScreen
import io.stamethyst.ui.main.MainScreenViewModel
import io.stamethyst.ui.modimport.ModImportHost
import io.stamethyst.ui.workshop.WorkshopScreen
import io.stamethyst.ui.workshop.WorkshopDownloadCenterScreen
import io.stamethyst.ui.workshop.WorkshopDetailScreen
import io.stamethyst.ui.workshop.WorkshopListMode
import io.stamethyst.ui.workshop.WorkshopViewModel
import io.stamethyst.ui.quickstart.QuickStartScreen
import io.stamethyst.ui.quickstart.QuickStartJarImportScreen
import io.stamethyst.ui.quickstart.QuickStartSteamDownloadScreen
import io.stamethyst.ui.settings.LauncherFirstRunSetupScreen
import io.stamethyst.ui.settings.LauncherDeveloperSettingsScreen
import io.stamethyst.ui.settings.LauncherMobileGluesSettingsScreen
import io.stamethyst.ui.settings.LauncherNativeLibraryMarketScreen
import io.stamethyst.ui.settings.LauncherSettingsScreen
import io.stamethyst.ui.settings.LauncherSteamCloudGuardScreen
import io.stamethyst.ui.settings.LauncherSteamCloudLoginScreen
import io.stamethyst.ui.settings.LauncherSteamCloudSaveSettingsScreen
import io.stamethyst.ui.settings.LauncherSteamCloudSyncBlacklistSettingsScreen
import io.stamethyst.ui.settings.SettingsScreenViewModel
import io.stamethyst.ui.preferences.LauncherPreferences

private const val PAGE_TRANSITION_DURATION_MS = 420
internal const val LAUNCHER_DOCK_ITEM_TAG_PREFIX = "launcher_dock_item_"

@Composable
fun LauncherContent(
    initialRoute: Route = Route.Main,
    mainViewModel: MainScreenViewModel,
    settingsViewModel: SettingsScreenViewModel,
    onMainScreenOpened: () -> Unit = {},
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = rememberAppNavigator(initialRoute)
    val uriHandler = LocalUriHandler.current
    val transientNoticeHostState = remember { SnackbarHostState() }
    var showBasicTutorialNotice by remember(activity) {
        mutableStateOf(!LauncherPreferences.isBasicTutorialNoticeDismissed(activity))
    }
    var pendingFeedbackNotice by remember {
        mutableStateOf<FeedbackSubmissionNotice?>(null)
    }
    val feedbackInboxState by FeedbackInboxCoordinator.uiState.collectAsState()
    val mainUiState = mainViewModel.uiState
    val settingsUiState = settingsViewModel.uiState
    val workshopViewModel: WorkshopViewModel = viewModel()
    val currentRoute = navigator.backStack.lastOrNull() as? Route
    val showLauncherDock = isLauncherDockRoute(currentRoute)
    var forwardPageTransition by remember { mutableStateOf(true) }
    var modsBatchSelectionMode by remember { mutableStateOf(false) }
    val showAnimatedLauncherDock = showLauncherDock && !modsBatchSelectionMode
    val launcherDockHazeState = rememberHazeState()
    val isBlockingBusyInteractionLocked =
        mainUiState.busyOperation.usesBlockingOverlay() ||
            settingsUiState.busyOperation.usesBlockingOverlay()
    val shouldShowBlockingBusyWindow =
        isBlockingBusyInteractionLocked &&
            currentRoute != Route.QuickStart &&
            currentRoute != Route.QuickStartSteamDownload
    val blockingBusyMessage = when {
        mainUiState.busyOperation.usesBlockingOverlay() -> mainUiState.busyMessage
        settingsUiState.busyOperation.usesBlockingOverlay() -> settingsUiState.busyMessage
        else -> null
    }
    val blockingBusyProgressPercent = when {
        mainUiState.busyOperation.usesBlockingOverlay() ->
            mainUiState.busyProgressPercent
        settingsUiState.busyOperation.usesBlockingOverlay() ->
            settingsUiState.busyProgressPercent
        else -> null
    }

    LaunchedEffect(currentRoute) {
        if (currentRoute != Route.Mods) {
            modsBatchSelectionMode = false
        }
    }

    LaunchedEffect(mainUiState.crashRecovery) {
        if (mainUiState.crashRecovery != null && currentRoute != Route.CrashRecovery) {
            navigator.push(Route.CrashRecovery)
        }
    }

    LaunchedEffect(currentRoute, mainUiState.crashRecovery) {
        if (currentRoute == Route.CrashRecovery && mainUiState.crashRecovery == null) {
            navigator.goBack()
        }
    }

    CompositionLocalProvider(
        LocalNavigator provides navigator,
    ) {
        LaunchedEffect(activity) {
            FeedbackInboxCoordinator.bind(activity.applicationContext)
            FeedbackInboxCoordinator.syncOnLauncherStart(activity.applicationContext)
        }
        LaunchedEffect(activity, currentRoute) {
            if (currentRoute == Route.Main) {
                onMainScreenOpened()
            }
        }
        LaunchedEffect(activity, transientNoticeHostState) {
            LauncherTransientNoticeBus.requests.collect { request ->
                val result = transientNoticeHostState.showSnackbar(
                    message = request.message.resolve(activity),
                    actionLabel = request.actionLabel?.resolve(activity),
                    duration = when (request.duration) {
                        LauncherTransientNoticeDuration.SHORT -> SnackbarDuration.Short
                        LauncherTransientNoticeDuration.LONG -> SnackbarDuration.Long
                    }
                )
                if (result == SnackbarResult.ActionPerformed) {
                    request.onAction?.invoke()
                }
            }
        }
        LaunchedEffect(activity) {
            WorkshopUpdateCheckCoordinator.completionNotices.collect { completion ->
                LauncherTransientNoticeBus.show(
                    UiText.DynamicString(completion.toNoticeMessage())
                )
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true },
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                ) { scaffoldPadding ->
                    NavDisplay(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(scaffoldPadding)
                            .hazeSource(state = launcherDockHazeState),
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    onBack = {
                        if (!isBlockingBusyInteractionLocked) {
                            if (currentRoute == Route.CrashRecovery) {
                                mainViewModel.dismissCrashRecovery()
                            } else {
                                navigator.goBack()
                            }
                        }
                    },
                    backStack = navigator.backStack,
                    transitionSpec = {
                        horizontalPageTransition(forward = forwardPageTransition)
                    },
                    popTransitionSpec = {
                        horizontalPageTransition(forward = false)
                    },
                    entryProvider = entryProvider {
                        entry<Route.QuickStart> {
                            QuickStartScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                onOpenSteamLogin = { navigator.push(Route.QuickStartSteamLogin) },
                                onOpenJarImport = { navigator.push(Route.QuickStartJarImport) },
                                onOpenSteamDownload = { navigator.push(Route.QuickStartSteamDownload) },
                                onImportSuccess = {
                                    navigator.resetRoot(
                                        if (LauncherPreferences.isFirstRunSetupCompleted(activity)) {
                                            Route.Main
                                        } else {
                                            Route.FirstRunSetup
                                        }
                                    )
                                }
                            )
                        }

                        entry<Route.QuickStartJarImport> {
                            QuickStartJarImportScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                onImportSuccess = {
                                    navigator.resetRoot(
                                        if (LauncherPreferences.isFirstRunSetupCompleted(activity)) {
                                            Route.Main
                                        } else {
                                            Route.FirstRunSetup
                                        }
                                    )
                                }
                            )
                        }

                        entry<Route.QuickStartSteamDownload> {
                            QuickStartSteamDownloadScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                onImportSuccess = {
                                    navigator.resetRoot(
                                        if (LauncherPreferences.isFirstRunSetupCompleted(activity)) {
                                            Route.Main
                                        } else {
                                            Route.FirstRunSetup
                                        }
                                    )
                                }
                            )
                        }

                        entry<Route.FirstRunSetup> {
                            LauncherFirstRunSetupScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.Main> {
                            LauncherMainScreen(
                                viewModel = mainViewModel,
                                modifier = Modifier.fillMaxSize(),
                                onOpenSettings = { navigator.resetRoot(Route.Settings) },
                                onOpenFeedback = { navigator.push(Route.Feedback) },
                                onOpenWorkshop = { navigator.resetRoot(Route.Workshop) },
                                feedbackUnreadCount = feedbackInboxState.unreadIssueCount,
                                onOpenFeedbackUpdates = {
                                    val unreadIssues = feedbackInboxState.subscriptions
                                        .filter { it.unread }
                                    when {
                                        unreadIssues.size == 1 -> {
                                            navigator.push(
                                                Route.FeedbackConversation(unreadIssues.first().issueNumber)
                                            )
                                        }

                                        else -> {
                                            navigator.push(Route.FeedbackSubscriptions)
                                        }
                                    }
                                }
                            )
                        }

                        entry<Route.CrashRecovery> {
                            LauncherCrashRecoveryScreen(
                                viewModel = mainViewModel,
                                modifier = Modifier.fillMaxSize(),
                                onBack = {
                                    mainViewModel.dismissCrashRecovery()
                                },
                                onOpenSettings = { navigator.push(Route.Settings) },
                                onOpenFeedback = { navigator.push(Route.Feedback) },
                                onReturnToMainMenu = {
                                    mainViewModel.dismissCrashRecovery()
                                    navigator.resetRoot(Route.Main)
                                }
                            )
                        }

                        entry<Route.Mods> {
                            LauncherModsScreen(
                                viewModel = mainViewModel,
                                modifier = Modifier.fillMaxSize(),
                                onOpenSettings = { navigator.resetRoot(Route.Settings) },
                                onOpenFeedback = { navigator.push(Route.Feedback) },
                                onOpenWorkshop = { navigator.resetRoot(Route.Workshop) },
                                feedbackUnreadCount = feedbackInboxState.unreadIssueCount,
                                onOpenFeedbackUpdates = {
                                    val unreadIssues = feedbackInboxState.subscriptions
                                        .filter { it.unread }
                                    when {
                                        unreadIssues.size == 1 -> {
                                            navigator.push(
                                                Route.FeedbackConversation(unreadIssues.first().issueNumber)
                                            )
                                        }

                                        else -> {
                                            navigator.push(Route.FeedbackSubscriptions)
                                        }
                                    }
                                },
                                onBatchSelectionModeChange = { modsBatchSelectionMode = it }
                            )
                        }

                        entry<Route.Settings> {
                            LauncherSettingsScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                showBackButton = false,
                                feedbackSubmissionNotice = pendingFeedbackNotice,
                                onDismissFeedbackSubmissionNotice = {
                                    pendingFeedbackNotice = null
                                }
                            )
                        }

                        entry<Route.Workshop> {
                            WorkshopScreen(
                                viewModel = workshopViewModel,
                                modifier = Modifier.fillMaxSize(),
                                showBackButton = false,
                                showSubscriptionsButton = true,
                                onBack = { navigator.goBack() },
                                onOpenSteamLogin = { navigator.push(Route.SteamCloudLogin) },
                                onOpenDownloadCenter = { navigator.push(Route.WorkshopDownloadCenter) },
                                onOpenSubscriptions = { navigator.push(Route.WorkshopSubscriptions) },
                                onOpenDetails = { item ->
                                    navigator.push(
                                        Route.WorkshopDetail(
                                            publishedFileId = item.publishedFileId.toString(),
                                            appId = item.appId.toLong(),
                                        )
                                    )
                                },
                            )
                        }

                        entry<Route.WorkshopSubscriptions> {
                            WorkshopScreen(
                                viewModel = workshopViewModel,
                                modifier = Modifier.fillMaxSize(),
                                showBackButton = true,
                                initialListMode = WorkshopListMode.Subscriptions,
                                title = stringResource(R.string.workshop_subscriptions_title),
                                subtitle = stringResource(R.string.workshop_subscriptions_subtitle),
                                onBack = { navigator.goBack() },
                                onOpenSteamLogin = { navigator.push(Route.SteamCloudLogin) },
                                onOpenDownloadCenter = { navigator.push(Route.WorkshopDownloadCenter) },
                                onOpenDetails = { item ->
                                    navigator.push(
                                        Route.WorkshopDetail(
                                            publishedFileId = item.publishedFileId.toString(),
                                            appId = item.appId.toLong(),
                                        )
                                    )
                                },
                            )
                        }

                        entry<Route.WorkshopDetail> { route ->
                            val publishedFileId = route.publishedFileId.toULongOrNull() ?: 0u
                            WorkshopDetailScreen(
                                appId = route.appId.toUInt(),
                                publishedFileId = publishedFileId,
                                viewModel = workshopViewModel,
                                modifier = Modifier.fillMaxSize(),
                                onBack = { navigator.goBack() },
                                onOpenDownloadCenter = { navigator.push(Route.WorkshopDownloadCenter) },
                                onOpenDetails = { item ->
                                    navigator.push(
                                        Route.WorkshopDetail(
                                            publishedFileId = item.publishedFileId.toString(),
                                            appId = item.appId.toLong(),
                                        )
                                    )
                                },
                            )
                        }

                        entry<Route.WorkshopDownloadCenter> {
                            val context = LocalContext.current
                            WorkshopDownloadCenterScreen(
                                modifier = Modifier.fillMaxSize(),
                                onBack = { navigator.goBack() },
                                onPause = { workshopViewModel.pauseDownload(context.applicationContext, it) },
                                onResume = { workshopViewModel.resumeDownload(context.applicationContext, it) },
                                onCancel = { workshopViewModel.cancelDownload(context.applicationContext, it) },
                                onRetry = { workshopViewModel.retryDownload(context.applicationContext, it) },
                            )
                        }

                        entry<Route.SteamCloudLogin> {
                            LauncherSteamCloudLoginScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.QuickStartSteamLogin> {
                            LauncherSteamCloudLoginScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                challengeRoute = Route.QuickStartSteamGuard,
                                onLoginCompleted = {
                                    navigator.resetRoot(Route.QuickStartSteamDownload)
                                },
                            )
                        }

                        entry<Route.SteamCloudGuard> {
                            LauncherSteamCloudGuardScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.QuickStartSteamGuard> {
                            LauncherSteamCloudGuardScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                returnToLoginRoute = Route.QuickStartSteamLogin,
                                onLoginCompleted = {
                                    navigator.resetRoot(Route.QuickStartSteamDownload)
                                },
                            )
                        }

                        entry<Route.SteamCloudSaveSettings> {
                            LauncherSteamCloudSaveSettingsScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.SteamCloudSyncBlacklistSettings> {
                            LauncherSteamCloudSyncBlacklistSettingsScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.DeveloperSettings> {
                            LauncherDeveloperSettingsScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.NativeLibraryMarket> {
                            LauncherNativeLibraryMarketScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.Compatibility> {
                            LauncherCompatibilityScreen(
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.MobileGluesSettings> {
                            LauncherMobileGluesSettingsScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.Feedback> {
                            LauncherFeedbackScreen(
                                modifier = Modifier.fillMaxSize(),
                                onSubmissionCompleted = { notice ->
                                    pendingFeedbackNotice = notice
                                    navigator.goBack()
                                },
                            )
                        }

                        entry<Route.FeedbackSubscriptions> {
                            LauncherFeedbackSubscriptionsScreen(
                                modifier = Modifier.fillMaxSize(),
                                onOpenConversation = { issueNumber ->
                                    navigator.push(Route.FeedbackConversation(issueNumber))
                                }
                            )
                        }

                        entry<Route.FeedbackIssueBrowser> {
                            LauncherFeedbackIssueBrowserScreen(
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        entry<Route.FeedbackConversation> { route ->
                            LauncherFeedbackConversationScreen(
                                modifier = Modifier.fillMaxSize(),
                                issueNumber = route.issueNumber
                            )
                        }

                        entry<Route.FeedbackIssuePreview> { route ->
                            LauncherFeedbackIssuePreviewScreen(
                                modifier = Modifier.fillMaxSize(),
                                issueNumber = route.issueNumber
                            )
                        }
                    }
                    )
                }
                AnimatedVisibility(
                    visible = showAnimatedLauncherDock,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(
                        animationSpec = tween(durationMillis = 220),
                        initialOffsetY = { fullHeight -> fullHeight }
                    ),
                    exit = slideOutVertically(
                        animationSpec = tween(durationMillis = 220),
                        targetOffsetY = { fullHeight -> fullHeight }
                    )
                ) {
                    LauncherDockBar(
                        hazeState = launcherDockHazeState,
                        currentRoute = currentRoute,
                        onSelectRoute = { route ->
                            if (currentRoute != route || navigator.stackSize > 1) {
                                forwardPageTransition = isForwardDockTransition(
                                    from = currentRoute.launcherDockRoute(),
                                    to = route.launcherDockRoute(),
                                )
                                navigator.resetRoot(route)
                            }
                        }
                    )
                }
                if (shouldShowBlockingBusyWindow) {
                    BlockingBusyInteractionBlocker(
                        message = blockingBusyMessage?.resolve()
                            ?: stringResource(R.string.mod_import_busy_message),
                        progressPercent = blockingBusyProgressPercent
                    )
                }
                ModImportHost(
                    onImportCompleted = {
                        mainViewModel.refresh(activity)
                        settingsViewModel.refreshStatus(activity)
                    }
                )
                SnackbarHost(
                    hostState = transientNoticeHostState,
                    snackbar = { snackbarData ->
                        Snackbar(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 56.dp),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(text = snackbarData.visuals.message)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        snackbarData.visuals.actionLabel?.let { actionLabel ->
                                            TextButton(onClick = { snackbarData.performAction() }) {
                                                Text(text = actionLabel)
                                            }
                                        }
                                        Button(onClick = { snackbarData.dismiss() }) {
                                            Text(text = stringResource(R.string.common_action_close))
                                        }
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                )
                settingsUiState.updatePromptState?.let { promptState ->
                    val quarkDownloadUrl = stringResource(R.string.update_dialog_quark_download_url)
                    var showDownloadChoiceDialog by remember(promptState) {
                        mutableStateOf(false)
                    }
                    var downloadMenuExpanded by remember(promptState) {
                        mutableStateOf(false)
                    }
                    var selectedDownloadSourceId by remember(promptState) {
                        mutableStateOf(promptState.defaultDownloadSourceId)
                    }
                    val selectedDownloadOption = promptState.downloadOptions.firstOrNull {
                        it.source.id == selectedDownloadSourceId
                    } ?: promptState.downloadOptions.firstOrNull()
                    if (showDownloadChoiceDialog) {
                        AlertDialog(
                            onDismissRequest = {
                                downloadMenuExpanded = false
                                showDownloadChoiceDialog = false
                            },
                            title = {
                                Text(stringResource(R.string.update_download_choice_dialog_title))
                            },
                            text = {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.update_download_choice_dialog_message
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.update_download_choice_dialog_source_label
                                        ),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(
                                            onClick = { downloadMenuExpanded = true },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(selectedDownloadOption?.label.orEmpty())
                                                Text(if (downloadMenuExpanded) "▲" else "▼")
                                            }
                                        }
                                        DropdownMenu(
                                            expanded = downloadMenuExpanded,
                                            onDismissRequest = { downloadMenuExpanded = false }
                                        ) {
                                            promptState.downloadOptions.forEach { option ->
                                                DropdownMenuItem(
                                                    text = { Text(option.label) },
                                                    onClick = {
                                                        selectedDownloadSourceId = option.source.id
                                                        downloadMenuExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        onClick = {
                                            downloadMenuExpanded = false
                                            showDownloadChoiceDialog = false
                                            settingsViewModel.dismissUpdatePrompt()
                                            uriHandler.openUri(quarkDownloadUrl)
                                        }
                                    ) {
                                        Text(stringResource(R.string.update_dialog_action_quark_download))
                                    }
                                    TextButton(
                                        enabled = selectedDownloadOption != null,
                                        onClick = {
                                            val targetUrl = selectedDownloadOption?.url
                                                ?: return@TextButton
                                            downloadMenuExpanded = false
                                            showDownloadChoiceDialog = false
                                            settingsViewModel.dismissUpdatePrompt()
                                            uriHandler.openUri(targetUrl)
                                        }
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.update_download_choice_dialog_action_direct_download
                                            )
                                        )
                                    }
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        downloadMenuExpanded = false
                                        showDownloadChoiceDialog = false
                                    }
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.update_download_choice_dialog_action_back
                                        )
                                    )
                                }
                            }
                        )
                    } else {
                        AlertDialog(
                            onDismissRequest = settingsViewModel::dismissUpdatePrompt,
                            title = { Text(stringResource(R.string.update_dialog_title)) },
                            text = {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 360.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.update_dialog_current_version,
                                            promptState.currentVersion
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.update_dialog_latest_version,
                                            promptState.latestVersion
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.update_dialog_download_source,
                                            promptState.downloadSourceDisplayName
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    SimpleMarkdownCard(
                                        title = stringResource(R.string.update_dialog_notes_title),
                                        markdown = promptState.notesText
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showDownloadChoiceDialog = true }) {
                                    Text(stringResource(R.string.update_dialog_action_download))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = settingsViewModel::dismissUpdatePrompt) {
                                    Text(stringResource(R.string.update_dialog_action_later))
                                }
                            }
                        )
                    }
                }
                if (showBasicTutorialNotice) {
                    val dismissBasicTutorialNotice = {
                        LauncherPreferences.setBasicTutorialNoticeDismissed(activity, true)
                        showBasicTutorialNotice = false
                    }
                    AlertDialog(
                        onDismissRequest = dismissBasicTutorialNotice,
                        title = { Text(stringResource(R.string.basic_tutorial_notice_title)) },
                        text = { Text(stringResource(R.string.basic_tutorial_notice_message)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    dismissBasicTutorialNotice()
                                    openBasicTutorial(activity)
                                }
                            ) {
                                Text(stringResource(R.string.basic_tutorial_notice_action_open))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = dismissBasicTutorialNotice) {
                                Text(stringResource(R.string.basic_tutorial_notice_action_dismiss))
                            }
                        }
                    )
                }
            feedbackInboxState.pendingNotice?.let { notice ->
                AlertDialog(
                    onDismissRequest = FeedbackInboxCoordinator::dismissUnreadNotice,
                    title = { Text(stringResource(R.string.main_feedback_notice_title)) },
                    text = {
                        Text(
                            if (notice.unreadIssueCount == 1) {
                                stringResource(R.string.main_feedback_notice_single)
                            } else {
                                stringResource(
                                    R.string.main_feedback_notice_multiple,
                                    notice.unreadIssueCount
                                )
                            }
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                FeedbackInboxCoordinator.dismissUnreadNotice()
                                val unreadIssues = feedbackInboxState.subscriptions.filter { it.unread }
                                when {
                                    unreadIssues.size == 1 -> {
                                        navigator.push(
                                            Route.FeedbackConversation(unreadIssues.first().issueNumber)
                                        )
                                    }

                                    else -> {
                                        navigator.push(Route.FeedbackSubscriptions)
                                    }
                                }
                            }
                        ) {
                            Text(stringResource(R.string.main_feedback_notice_open))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = FeedbackInboxCoordinator::dismissUnreadNotice) {
                            Text(stringResource(R.string.main_feedback_notice_later))
                        }
                    }
                )
            }
        }
    }
    }
}

@Composable
private fun LauncherDockBar(
    modifier: Modifier = Modifier,
    hazeState: HazeState,
    currentRoute: Route?,
    onSelectRoute: (Route) -> Unit,
) {
    val selectedRoute = currentRoute.launcherDockRoute() ?: Route.Main
    FrostedGlassChrome(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
        hazeState = hazeState,
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LauncherDockItem(
                selected = selectedRoute == Route.Main,
                route = Route.Main,
                iconResId = R.drawable.ic_dock_game,
                label = stringResource(R.string.main_dock_game),
                onSelectRoute = onSelectRoute,
            )
            LauncherDockItem(
                selected = selectedRoute == Route.Mods,
                route = Route.Mods,
                iconResId = R.drawable.ic_dock_mods,
                label = stringResource(R.string.main_dock_mods),
                onSelectRoute = onSelectRoute,
            )
            LauncherDockItem(
                selected = selectedRoute == Route.Workshop,
                route = Route.Workshop,
                iconResId = R.drawable.ic_dock_market,
                label = stringResource(R.string.main_dock_market),
                onSelectRoute = onSelectRoute,
            )
            LauncherDockItem(
                selected = selectedRoute == Route.Settings,
                route = Route.Settings,
                iconResId = R.drawable.ic_dock_settings,
                label = stringResource(R.string.main_dock_settings),
                onSelectRoute = onSelectRoute,
            )
        }
    }
}

@Composable
private fun RowScope.LauncherDockItem(
    selected: Boolean,
    route: Route,
    @androidx.annotation.DrawableRes iconResId: Int,
    label: String,
    onSelectRoute: (Route) -> Unit,
) {
    val dockItemShape = RoundedCornerShape(20.dp)
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .height(58.dp)
            .testTag(LAUNCHER_DOCK_ITEM_TAG_PREFIX + route.launcherDockTagSuffix())
            .clip(dockItemShape)
            .clickable { onSelectRoute(route) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                Color.Transparent
            },
            contentColor = contentColor,
        ) {
            Icon(
                painter = painterResource(iconResId),
                contentDescription = label,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}

private fun isLauncherDockRoute(route: Route?): Boolean {
    return route.launcherDockRoute() != null
}

private fun horizontalPageTransition(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return slideInHorizontally(
        animationSpec = tween(durationMillis = PAGE_TRANSITION_DURATION_MS),
        initialOffsetX = { fullWidth -> fullWidth * direction }
    ).togetherWith(
        slideOutHorizontally(
            animationSpec = tween(durationMillis = PAGE_TRANSITION_DURATION_MS),
            targetOffsetX = { fullWidth -> -fullWidth * direction }
        )
    )
}

private fun isForwardDockTransition(from: Route?, to: Route?): Boolean {
    val fromIndex = from.launcherDockIndex()
    val toIndex = to.launcherDockIndex()
    return if (fromIndex != null && toIndex != null && fromIndex != toIndex) {
        toIndex > fromIndex
    } else {
        true
    }
}

private fun Route?.launcherDockIndex(): Int? {
    return when (this) {
        Route.Main -> 0
        Route.Mods -> 1
        Route.Workshop -> 2
        Route.Settings -> 3
        else -> null
    }
}

private fun io.stamethyst.backend.workshop.WorkshopUpdateCheckCompletion.toNoticeMessage(): String {
    val error = errorSummary
    if (!error.isNullOrBlank()) {
        return "模组更新检查失败：$error"
    }
    val base = if (updateCount > 0) {
        "检查完成，发现 $updateCount 个可更新模组"
    } else {
        "检查完成，所有模组已为最新"
    }
    return if (failedCount > 0) {
        "$base，$failedCount 个检查失败"
    } else {
        base
    }
}

private fun Route?.launcherDockRoute(): Route? {
    return when (this) {
        Route.Main -> Route.Main
        Route.Mods -> Route.Mods
        Route.Workshop -> Route.Workshop
        Route.WorkshopSubscriptions -> Route.Workshop
        Route.Settings -> Route.Settings
        Route.CrashRecovery,
        is Route.WorkshopDetail,
        Route.WorkshopSubscriptions,
        Route.WorkshopDownloadCenter,
        Route.SteamCloudLogin,
        Route.SteamCloudGuard,
        Route.SteamCloudSaveSettings,
        Route.SteamCloudSyncBlacklistSettings,
        Route.DeveloperSettings,
        Route.NativeLibraryMarket,
        Route.Compatibility,
        Route.MobileGluesSettings,
        Route.QuickStart,
        Route.QuickStartSteamLogin,
        Route.QuickStartSteamGuard,
        Route.QuickStartSteamDownload,
        Route.QuickStartJarImport,
        Route.FirstRunSetup,
        Route.Feedback,
        Route.FeedbackSubscriptions,
        Route.FeedbackIssueBrowser,
        is Route.FeedbackConversation,
        is Route.FeedbackIssuePreview,
        null -> null
    }
}

private fun Route.launcherDockTagSuffix(): String = when (this) {
    Route.Main -> "Main"
    Route.Mods -> "Mods"
    Route.Workshop -> "Workshop"
    Route.Settings -> "Settings"
    else -> this::class.simpleName.orEmpty()
}

@Composable
private fun BlockingBusyInteractionBlocker(
    message: String,
    progressPercent: Int? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.24f))
            .pointerInteropFilter { true },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val progressFraction = progressPercent
                    ?.coerceIn(0, 100)
                    ?.div(100f)
                if (progressFraction != null) {
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
