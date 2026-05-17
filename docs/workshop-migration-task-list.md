# Workshop Migration Task List

## Scope
- Target only Slay the Spire.
- Keep a single game version; do not migrate game library logic.
- Reuse the launcher's existing Steam login state.
- Reuse the launcher's existing Steam acceleration chain.
- Support browsing and downloading Steam Workshop mods for STS.
- After download, either auto-import by default or wait for user import based on settings.
- Preserve Workshop metadata after import so updates can be checked later.
- When the same `modid` already exists locally, replace the existing mod.
- Do not implement public export.
- Do not reuse WorkshopOnAndroid frontend screens.
- Keep the download center minimal.

## Global Agent Rules
- Follow tasks in order.
- Finish one task domain before starting the next unless a dependency blocks it.
- Do not invent new product scope.
- If a task depends on an unresolved interface, stop and record the blocker.
- After each task, verify the implementation with the listed checks.
- Keep changes small and reviewable.

## Phase 1. Core Workshop Protocol and Download Engine

### Task 1.1: Import Steam protocol layer
- Goal: bring the Steam Workshop protocol code into the launcher codebase.
- Inputs: `WorkshopOnAndroid` protocol sources.
- Output: launcher-owned protocol classes under the current package namespace.
- Dependencies: none.
- Acceptance:
  - PublishedFile metadata can be queried for STS Workshop items.
  - The code compiles in the launcher project.
  - No game library logic is imported.

### Task 1.2: Import workshop download engine
- Goal: bring the download engine into the launcher codebase.
- Inputs: `WorkshopOnAndroid` download-core sources.
- Output: launcher-owned download engine classes under the current package namespace.
- Dependencies: Task 1.1.
- Acceptance:
  - Download requests can be executed for `AppID 646570`.
  - The engine supports download progress, completion, and failure events.
  - No WorkshopOnAndroid UI code is imported.

### Task 1.3: Reduce the engine to STS-only mode
- Goal: hard-code or centralize STS-only behavior.
- Inputs: imported protocol and engine classes.
- Output: a launcher-specific STS workshop entry point.
- Dependencies: Task 1.2.
- Acceptance:
  - All workshop requests use `AppID 646570`.
  - No game selector or multi-game repository exists.
  - Build still succeeds.

## Phase 2. Steam Login and Acceleration Fusion

### Task 2.1: Adapt launcher Steam auth for workshop access
- Goal: make workshop download use the existing launcher Steam login.
- Inputs: `SteamCloudAuthStore`, current login flow, and current auth material format.
- Output: a workshop auth adapter that can provide the engine with a logged-in Steam session.
- Dependencies: Phase 1.
- Acceptance:
  - Logging in once in the launcher is enough for workshop access.
  - No second Steam login UI is added.
  - If auth is missing or expired, the UI routes to the existing launcher login flow.

### Task 2.2: Reuse the existing Steam acceleration chain
- Goal: make workshop browsing and downloading use the launcher's Steam acceleration path.
- Inputs: current Steam Cloud accelerated HTTP stack.
- Output: a workshop HTTP client factory or integration layer that uses the same acceleration behavior.
- Dependencies: Task 2.1.
- Acceptance:
  - Browse and download requests go through the launcher's acceleration chain.
  - No separate WorkshopOnAndroid acceleration setting is introduced.
  - Host coverage is sufficient for workshop browse, detail, and download traffic.

## Phase 3. Minimal Workshop Browse UI

### Task 3.1: Add workshop navigation entry points
- Goal: expose STS Workshop browse and detail screens in the launcher navigation.
- Inputs: current navigation system and launcher screen layout.
- Output: routes and entry points for workshop browse, detail, and download center.
- Dependencies: Phase 2.
- Acceptance:
  - The launcher can navigate to workshop browse and detail screens.
  - No WorkshopOnAndroid frontend is copied.
  - Existing launcher flows continue to work.

### Task 3.2: Implement STS workshop browse screen
- Goal: provide a minimal browse experience for STS Workshop.
- Inputs: workshop metadata/query APIs.
- Output: a launcher-native browse screen with search, paging, and item selection.
- Dependencies: Task 3.1.
- Acceptance:
  - Users can browse workshop items.
  - Users can search workshop items.
  - The UI follows launcher styling, not WorkshopOnAndroid styling.

### Task 3.3: Implement workshop detail screen
- Goal: show item details before download.
- Inputs: item metadata and preview/description data.
- Output: a detail page with download action.
- Dependencies: Task 3.2.
- Acceptance:
  - Users can open a workshop item and read its details.
  - The page exposes a download action.
  - The page does not introduce game-library concepts.

## Phase 4. Minimal Download Center

### Task 4.1: Add minimal download task model
- Goal: track workshop download state in the launcher.
- Inputs: download engine events.
- Output: a task model with progress, status, logs, and error information.
- Dependencies: Phase 1.
- Acceptance:
  - Download tasks can be created, tracked, paused, resumed, and cancelled.
  - The model only covers the minimal launcher needs.

### Task 4.2: Add minimal download center UI
- Goal: present active and recent workshop downloads.
- Inputs: the download task model.
- Output: a small launcher-native download center screen.
- Dependencies: Task 4.1.
- Acceptance:
  - Users can see current task progress.
  - Users can see failure messages and retry.
  - Users can open the downloaded item for import.

### Task 4.3: Persist download tasks
- Goal: keep active downloads recoverable across process recreation when practical.
- Inputs: task model and persistence storage.
- Output: persisted task state and restore logic.
- Dependencies: Task 4.1.
- Acceptance:
  - Task state survives launcher restarts where supported.
  - Persistence does not pull in unnecessary WorkshopOnAndroid features.

## Phase 5. Import Flow and Metadata Retention

### Task 5.1: Add workshop download result handling
- Goal: detect downloaded mod files and prepare them for import.
- Inputs: completed download output directory.
- Output: a result handler that separates importable JARs from non-importable files.
- Dependencies: Phase 4.
- Acceptance:
  - Download completion identifies the mod artifacts correctly.
  - The result handler can drive either auto-import or manual import.

### Task 5.2: Add auto-import toggle with default enabled
- Goal: control whether download completion imports immediately.
- Inputs: launcher settings storage.
- Output: a setting that defaults to enabled.
- Dependencies: Task 5.1.
- Acceptance:
  - Default behavior is auto-import.
  - If disabled, the download completes and waits for a user click on Import.

### Task 5.3: Preserve Workshop metadata during import
- Goal: keep workshop identity and version metadata after import.
- Inputs: downloaded workshop metadata and existing mod storage structures.
- Output: persisted metadata linked to the installed mod.
- Dependencies: Task 5.1.
- Acceptance:
  - Imported mods retain `appId`, `publishedFileId`, title, description, version, and update timestamp.
  - The metadata is available for later update checks.

### Task 5.4: Replace existing mod on modid conflict
- Goal: make workshop import replace any already installed mod with the same `modid`.
- Inputs: existing mod storage and import decisions.
- Output: deterministic replacement behavior.
- Dependencies: Task 5.3.
- Acceptance:
  - If the same `modid` already exists, import replaces the existing entry.
  - No duplicate mod entries remain for the same `modid`.

## Phase 6. Update Checking

### Task 6.1: Store workshop index data for update checks
- Goal: keep a local workshop index that can be queried later.
- Inputs: imported workshop metadata and current local mod records.
- Output: a local repository/index for workshop mods.
- Dependencies: Task 5.3.
- Acceptance:
  - Local workshop mods can be enumerated.
  - Each entry can be matched back to a workshop item.

### Task 6.2: Implement workshop update check logic
- Goal: compare local workshop metadata with remote workshop metadata.
- Inputs: `publishedFileId`, stored version data, workshop metadata APIs.
- Output: update status for each installed workshop mod.
- Dependencies: Task 6.1.
- Acceptance:
  - The launcher can tell whether a workshop mod has an update.
  - The result is available for UI display.

### Task 6.3: Surface update status in the launcher
- Goal: make workshop updates visible to users.
- Inputs: update check results.
- Output: UI feedback or badges for updated workshop mods.
- Dependencies: Task 6.2.
- Acceptance:
  - Users can see which workshop mods have updates.
  - Update checking does not require a separate game library view.

## Phase 7. Integration and Verification

### Task 7.1: Wire workshop entry points into the launcher UI
- Goal: make workshop browsing reachable from the main launcher.
- Inputs: route entries, browse screen, detail screen, download center.
- Output: launcher UI integration.
- Dependencies: Phases 3 and 4.
- Acceptance:
  - Users can reach browse, detail, and download center screens from the launcher.

### Task 7.2: Verify login, acceleration, browse, and download paths
- Goal: validate the end-to-end workshop flow.
- Inputs: built launcher and a logged-in Steam account.
- Output: verified runtime behavior.
- Dependencies: Phases 2 through 6.
- Acceptance:
  - Login once in the launcher.
  - Browse STS Workshop.
  - Download a mod.
  - Auto-import works by default.
  - Manual import works when auto-import is disabled.
  - Same `modid` replacement works.
  - Update checking works for imported workshop mods.

### Task 7.3: Keep the implementation minimal and reviewable
- Goal: prevent scope drift during implementation.
- Inputs: completed code changes.
- Output: small, reviewable patches.
- Dependencies: all prior tasks.
- Acceptance:
  - No public export code is added.
  - No multi-game library logic is added.
  - No WorkshopOnAndroid UI is copied.
