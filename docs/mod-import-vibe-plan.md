# Mod Import Vibe Development Plan

This document captures the Vibe-style implementation plan for rewriting optional mod jar import. The goal is to make the import process explicit, inspectable, recoverable, and extensible instead of continuing the current chain of mixed checks, patching, and stacked dialogs.

## Product Intent

Users should be able to import one or more optional mod jars and understand, before files are written, exactly which files will be imported, skipped, blocked, replaced, or patched.

The launcher should handle common mobile compatibility work automatically, but the process must stay visible and controllable.

## Decisions Already Made

- Game body import is out of scope. `desktop-1.0.jar` and valid STS game jars continue through the existing game jar import path.
- Optional mod jar import gets a new unified flow used by the main screen, settings screen, and external share/open intent.
- `MtsLaunchManifestValidator` failures block the affected jar from being imported.
- In batch import, a blocked file should block only that file. Other valid files in the same batch can still be imported.
- Mod-specific patches such as Frieren, Downfall, and VupShion must be modular and extensible. The import executor must not contain hard-coded `if (modId == ...)` branches.
- The user-facing import UI should be one Compose-driven import wizard, not a sequence of native `AlertDialog` prompts.

## Current Problems

- `ModImportFlowCoordinator` mixes duplicate checks, atlas downscale prompts, import execution, folder prompts, and result dialogs.
- `SettingsFileService.importModJar` performs migration, copying, validation, patching, duplicate replacement, final file movement, and metadata writing in one path.
- `JarImportInspectionService.prepareImportedJar` and `inspectPreparedModJar` can mutate temporary jars while being used as inspection helpers.
- External jar open/share in `LauncherActivity` has its own preview path and then re-enters the normal import path, causing duplicated work and inconsistent user experience.
- Existing patch result metadata is field-based and grows every time a new patch is added.
- Existing import result UI is fragmented across toasts and multiple dialogs, so users cannot see the final plan before import begins.

## Scope

In scope:

- Optional mod jar import.
- Batch import.
- External open/share of mod jars after game body jar detection is ruled out.
- Duplicate detection and resolution.
- Compatibility patch planning and execution.
- Unified Compose import wizard.
- MTS launch manifest validation as a blocking import check.
- Modular patch registry and generic patch metadata.

Out of scope:

- Rewriting STS game body jar import.
- Rewriting save import/export.
- Changing ModTheSpire launch behavior beyond rejecting invalid imports earlier.
- Changing default compatibility behavior unless required by the new structure.

## Target User Flow

1. User selects one or more files.
2. Launcher creates an import session and copies the files into a session cache.
3. Launcher scans every file and classifies it.
4. Launcher shows a review step with importable, skipped, blocked, and warning files.
5. If duplicate mod IDs exist, user resolves each duplicate group.
6. If configurable patches exist, user reviews and configures them.
7. User confirms the final import plan.
8. Launcher executes import with progress.
9. Launcher shows a single result screen with successes, failures, skips, and applied patches.
10. User finishes and returns to the mod list.

## Blocking Rules

The planner should mark these items as blocked and exclude them from execution:

- File cannot be opened.
- File is not a jar.
- File is a compressed archive such as zip, rar, 7z, tar, or gz selected by mistake.
- Jar cannot be read as a zip/jar.
- `ModTheSpire.json` cannot be found or resolved after planned structural compatibility handling.
- `modid` cannot be resolved.
- The mod is a core component such as BaseMod, StSLib, ModTheSpire, or Amethyst Runtime Compat.
- `MtsLaunchManifestValidator` fails on the final planned jar shape.

The UI must show the specific blocking reason and any available detail from the validator or exception.

## Import Pipeline

The new backend pipeline should be split into explicit stages.

### 1. Prepare Sources

Inputs:

- `List<Uri>`
- Host `Context`

Outputs:

- `PreparedImportSource`

Responsibilities:

- Resolve display name, MIME type, and size when available.
- Copy each URI into a session-scoped cache file once.
- Preserve source identity for diagnostics and UI.
- Do not write to the optional mod library.
- Do not delete or replace installed mods.

### 2. Classify Sources

Outputs:

- `ImportableJar`
- `CompressedArchive`
- `UnsupportedFile`
- `UnreadableFile`

Responsibilities:

- Detect common archive mistakes.
- Detect unreadable jars early.
- Keep skipped and blocked files visible in the review step instead of only showing a toast.

### 3. Inspect Manifest

Responsibilities:

- Read manifest information for display.
- Support planned structural fixes such as manifest root flattening without mutating the final library.
- Use the tolerant parser for display metadata.
- Produce a strict launch validation result with `MtsLaunchManifestValidator`.

Important rule:

- If a structural patch such as manifest root flattening is needed, launch validation should be evaluated against the planned final jar shape, not only the original raw jar.

### 4. Detect Reserved Components

Reserved components must be blocked from optional mod import:

- `basemod`
- `stslib`
- `modthespire`
- `amethystruntimecompat`

These remain managed by the launcher core dependency flow, not by optional mod import.

### 5. Detect Duplicates

Duplicate detection must include:

- Existing installed optional mods with the same normalized mod ID.
- Multiple files in the same import batch with the same normalized mod ID.

User decisions should be per conflict group with a batch apply option:

- Replace existing.
- Keep multiple.
- Skip new item.

Replace options should support:

- Reuse old filename.
- Reuse old folder assignment.
- Preserve enabled state.
- Preserve priority where applicable.

### 6. Plan Patches

Patch planning must be read-only. It should produce `ImportPatchPlan` objects that the UI can display.

Patch planning should answer:

- Is this patch applicable?
- Is it required, recommended, optional, or experimental?
- Is it user configurable?
- What files or entries will be affected?
- What happens if the patch fails?

### 7. Build Execution Plan

The execution plan freezes:

- Which files will import.
- Which files will skip or remain blocked.
- Duplicate decisions.
- Target filenames.
- Folder assignments.
- Patch decisions.
- Metadata writes.

Execution must not introduce new user decisions after this point.

### 8. Execute Import

Execution should operate on a working copy per item:

1. Create working jar from prepared session file.
2. Apply patch modules in registry order.
3. Run final MTS launch manifest validation.
4. Apply duplicate replacement actions.
5. Move the final jar into the optional mod library atomically where possible.
6. Update selection, priority, folder assignment, and patch metadata.
7. Report per-item progress and result.

Single-item failure should not abort other valid items unless a shared infrastructure failure makes continued import unsafe.

### 9. Cleanup

Session temporary files should be cleaned when the session completes, fails, or is cancelled.

The result screen should retain structured summaries, not temp file paths required for recovery.

## Backend Package Shape

Suggested package:

```text
app/src/main/java/io/stamethyst/backend/mods/importing/
```

Suggested files:

```text
ModImportSession.kt
ModImportPlanner.kt
ModImportExecutor.kt
ModImportPlan.kt
ModImportDecision.kt
ModImportIssue.kt
ModImportProgress.kt
ModImportResult.kt
ModImportSettingsStore.kt
```

Patch package:

```text
app/src/main/java/io/stamethyst/backend/mods/importing/patches/
```

Suggested files:

```text
ImportPatchModule.kt
ImportPatchRegistry.kt
ImportPatchContext.kt
ImportPatchPlan.kt
ImportPatchDecision.kt
ImportPatchResult.kt
ZipEntryNormalizePatchModule.kt
ManifestRootPatchModule.kt
AtlasFilterPatchModule.kt
AtlasOfflineDownscalePatchModule.kt
FrierenImportPatchModule.kt
DownfallImportPatchModule.kt
VupShionImportPatchModule.kt
```

## Core Models

Suggested item status:

```kotlin
enum class ModImportItemStatus {
    PendingScan,
    Importable,
    NeedsDecision,
    Skipped,
    Blocked,
    Importing,
    Imported,
    Failed
}
```

Suggested blocking reason:

```kotlin
enum class ModImportBlockingReason {
    UnsupportedFile,
    CompressedArchive,
    UnreadableJar,
    MissingManifest,
    MissingModId,
    ReservedCoreComponent,
    InvalidMtsLaunchManifest
}
```

Suggested issue severity:

```kotlin
enum class ModImportIssueSeverity {
    Info,
    Warning,
    Blocking
}
```

Suggested duplicate decision:

```kotlin
enum class DuplicateImportDecision {
    ReplaceExisting,
    KeepMultiple,
    SkipNew
}
```

## Modular Patch System

The import flow should know only about patch modules, not concrete mod names.

### Patch Module Contract

Recommended interface shape:

```kotlin
interface ImportPatchModule {
    val id: String
    val version: Int
    val displayName: UiText
    val summary: UiText
    val category: ImportPatchCategory
    val defaultEnabled: Boolean
    val userConfigurable: Boolean
    val order: Int
    val failurePolicy: ImportPatchFailurePolicy

    fun isAvailable(context: Context): Boolean

    fun plan(
        context: ImportPatchContext,
        item: ImportCandidate
    ): ImportPatchPlan

    fun apply(
        context: ImportPatchContext,
        workingJar: File,
        plan: ImportPatchPlan,
        decision: ImportPatchDecision
    ): ImportPatchResult
}
```

Recommended categories:

```kotlin
enum class ImportPatchCategory {
    Structural,
    Texture,
    Shader,
    Bytecode,
    ModSpecific,
    Safety
}
```

Recommended failure policies:

```kotlin
enum class ImportPatchFailurePolicy {
    BlockImport,
    SkipPatchContinueImport
}
```

Do not allow a patch to ask the user a new question during execution. Any required decision must be represented during planning and confirmation.

### Patch Matching

Patch modules should match targets using, in order of reliability:

1. Normalized `modid`.
2. Manifest name and version.
3. Required jar entries such as classes or resources.
4. Optional jar entries for confidence.
5. Filename only as a fallback or diagnostic hint.

Suggested target model:

```kotlin
data class ImportPatchTarget(
    val modIds: Set<String> = emptySet(),
    val names: Set<String> = emptySet(),
    val requiredEntries: Set<String> = emptySet(),
    val optionalEntries: Set<String> = emptySet()
)
```

### Patch Registry

The registry owns ordering and availability:

```kotlin
object ImportPatchRegistry {
    fun modules(context: Context): List<ImportPatchModule> {
        return listOf(
            ZipEntryNormalizePatchModule,
            ManifestRootPatchModule,
            AtlasFilterPatchModule,
            AtlasOfflineDownscalePatchModule,
            FrierenImportPatchModule,
            DownfallImportPatchModule,
            VupShionImportPatchModule,
        ).filter { it.isAvailable(context) }
            .sortedBy { it.order }
    }
}
```

Recommended order bands:

```text
100  duplicate zip entry normalize
200  manifest root flatten
300  atlas filter
400  atlas offline downscale
500  shader patches
600  mod-specific bytecode/resource patches
900  final safety checks
```

### Existing Patchers as Adapters

First implementation should wrap existing patchers instead of rewriting them immediately:

- `DuplicateZipEntryNormalizer`
- `ModManifestRootCompatPatcher`
- `ModAtlasFilterCompatPatcher`
- `ModAtlasOfflineDownscalePatcher`
- `FrierenModCompatPatcher`
- `DownfallImportCompatPatcher`
- `VupShionModCompatPatcher`
- `JacketNoAnoKoModCompatPatcher`, only if re-enabled later

The new executor should call patch modules. Patch modules may call the existing patcher classes internally.

### Adding a Future Mod Patch

Future patch additions should require only:

1. Add one `SomeModImportPatchModule.kt`.
2. Implement `plan` and `apply`.
3. Register it in `ImportPatchRegistry`.
4. Add string resources.
5. Add planner and apply tests.

The import wizard, executor control flow, and metadata schema should not need a new hard-coded branch.

## Patch Settings

Avoid adding one launcher preference method per patch. Use generic patch settings keyed by patch ID.

Suggested storage API:

```kotlin
interface ModImportPatchSettingsStore {
    fun isPatchEnabled(context: Context, patchId: String, defaultEnabled: Boolean): Boolean
    fun setPatchEnabled(context: Context, patchId: String, enabled: Boolean)
}
```

Suggested key shape:

```text
import_patch_enabled.mod.frieren.anti_pirate=false
import_patch_enabled.mod.downfall.mobile_layout=true
```

Default behavior should initially match the existing import flow:

- Manifest root compatibility enabled.
- Atlas filter compatibility enabled.
- Frieren compatibility enabled.
- Downfall compatibility enabled.
- VupShion compatibility enabled.
- JacketNoAnoKo remains disabled unless deliberately restored.

## Patch Metadata

The current fixed-field `ImportedModPatchInfo` structure does not scale. New metadata should use generic patch entries.

Suggested model:

```kotlin
data class ImportedPatchEntry(
    val moduleId: String,
    val moduleVersion: Int,
    val displayName: String,
    val applied: Boolean,
    val summary: String,
    val details: List<String>
)
```

Suggested v2 JSON shape:

```json
{
  "version": 2,
  "entries": {
    "/path/to/mod.jar": {
      "modId": "downfall",
      "modName": "Downfall",
      "patches": [
        {
          "moduleId": "mod.downfall.mobile_layout",
          "moduleVersion": 1,
          "displayName": "Downfall mobile layout patch",
          "applied": true,
          "summary": "Adjusted Downfall mobile layout compatibility points.",
          "details": [
            "patched merchant class entries: 1",
            "patched hexaghost body entries: 1",
            "patched boss mechanic panel entries: 1"
          ]
        }
      ]
    }
  }
}
```

Compatibility requirement:

- Read existing v1 metadata.
- Write v2 metadata after the new import flow lands.
- Convert v1 fixed fields into synthetic `ImportedPatchEntry` items for display where feasible.

## Compose UI Plan

Suggested package:

```text
app/src/main/java/io/stamethyst/ui/modimport/
```

Suggested components:

```text
ModImportHost.kt
ModImportSessionViewModel.kt
ModImportWizardDialog.kt
ImportStepScaffold.kt
ImportProgressHeader.kt
ImportFileCard.kt
ImportStatusChip.kt
ImportIssueCard.kt
DuplicateConflictCard.kt
PatchRuleCard.kt
AtlasDownscaleStrategySelector.kt
FolderAssignmentSelector.kt
ImportSummaryPanel.kt
ImportProgressStep.kt
ImportResultStep.kt
ImportErrorDetailsSheet.kt
```

### UI Shape

Phone:

- Use a full-screen dialog or full-height modal surface.
- Keep the bottom action bar persistent.
- Use lazy lists for file and patch lists.

Tablet or large landscape:

- Use centered dialog with max width.
- Keep the same state model and steps.

### Wizard Steps

```text
Scanning
Review
ResolveDuplicates
ConfigurePatches
Confirm
Executing
Result
```

Step behavior:

- `Scanning`: shows current scan progress and allows cancellation.
- `Review`: shows importable, skipped, blocked, and warning files.
- `ResolveDuplicates`: appears only when duplicate decisions are required.
- `ConfigurePatches`: appears only when configurable patch decisions exist.
- `Confirm`: shows frozen plan summary and folder assignment.
- `Executing`: shows per-item progress. No new decisions.
- `Result`: shows success, partial success, skipped, blocked, failed, and patch details.

### UI State

Suggested state model:

```kotlin
data class ModImportSessionUiState(
    val visible: Boolean,
    val phase: ModImportPhase,
    val currentStep: ModImportStep,
    val files: List<ImportFileUi>,
    val duplicateGroups: List<DuplicateConflictUi>,
    val patchOptions: List<PatchRuleUi>,
    val folderOptions: List<ImportFolderOptionUi>,
    val decisions: ModImportDecisionsUi,
    val progress: ModImportProgressUi?,
    val result: ModImportResultUi?,
    val canGoBack: Boolean,
    val canContinue: Boolean,
    val primaryAction: ImportPrimaryAction
)
```

Suggested phase enum:

```kotlin
enum class ModImportPhase {
    Idle,
    Preparing,
    Reviewing,
    ResolvingConflicts,
    ConfiguringPatches,
    Confirming,
    Executing,
    Completed,
    Failed,
    Cancelled
}
```

### UI Rules

- Do not stack dialogs during import.
- Do not show import decisions through Toast.
- Do not ask new questions during execution.
- Use `AlertDialog` only for cancelling an in-progress execution if needed.
- Each blocked file should have an expandable technical detail section.
- Patch cards should be generated from patch module plan data.
- The UI must not special-case Frieren, Downfall, or VupShion.

## Entry Integration

### Main Screen

The import button continues to launch a document picker. Picker result should start the new import session instead of calling the old coordinator directly.

### Settings Screen

The settings import button continues to use `OpenMultipleDocuments`. Picker result should start the same import session.

### External Intent

`LauncherActivity` should keep early game body jar detection.

If the external file is not a game body jar, it should open the mod import wizard with that URI.

The old external mod preview dialog should be removed after the new wizard is in place.

## Migration Plan

### Phase 0: Models and Tests First

Goal:

- Add backend import models and patch module contracts without changing UI behavior.

Tasks:

1. Add importing package and core data classes.
2. Add patch module interface and registry.
3. Wrap existing patchers as modules where possible.
4. Add unit tests for model decisions and patch registry ordering.

### Phase 1: Planner

Goal:

- Build a read-only planner that can scan selected files and produce a full import plan.

Tasks:

1. Move source preparation into session cache.
2. Move manifest inspection into planner.
3. Add blocking MTS launch manifest validation.
4. Add duplicate detection.
5. Add patch planning.
6. Add planner tests for happy path, blocked files, duplicates, and patch applicability.

### Phase 2: Executor

Goal:

- Execute a frozen plan with the same default behavior as the old import flow.

Tasks:

1. Implement working-copy execution.
2. Apply patch modules in registry order.
3. Add final MTS validation.
4. Move final jar into optional mod library.
5. Write v2 patch metadata with v1 read compatibility.
6. Preserve old replacement and folder behavior.
7. Add executor tests for success, partial failure, replace, keep multiple, and cleanup.

### Phase 3: Compose Wizard

Goal:

- Replace stacked dialogs with one import wizard.

Tasks:

1. Add `ModImportSessionViewModel`.
2. Add wizard scaffold and steps.
3. Add file cards, issue cards, duplicate cards, patch cards, and result list.
4. Add Compose previews for all important states.
5. Wire wizard events to planner and executor.

### Phase 4: Entry Wiring

Goal:

- Route all optional mod imports through the new session.

Tasks:

1. Main screen import picker result starts new session.
2. Settings screen import picker result starts new session.
3. External mod jar intent starts new session after game body detection is ruled out.
4. Old `ModImportFlowCoordinator` remains only as a temporary fallback if needed.

### Phase 5: Cleanup

Goal:

- Remove old import flow code and reduce `SettingsFileService` responsibility.

Tasks:

1. Remove or retire old duplicate and atlas prompt dialogs.
2. Remove `AtlasDownscaleImportDialog` after Compose replacement is live.
3. Delete old coordinator when all entry points are migrated.
4. Keep lower-level file helpers only where still needed.
5. Remove obsolete hard-coded patch result fields after metadata migration is stable.

## Acceptance Criteria

Functional acceptance:

- Importing one normal optional mod succeeds.
- Batch importing multiple valid mods succeeds.
- Batch import with one blocked mod still imports the valid mods.
- Compressed archives are shown as skipped or blocked with a clear reason.
- Core components are blocked from optional mod import.
- A jar failing `MtsLaunchManifestValidator` is blocked and cannot be imported.
- Duplicate mod IDs can be resolved by replacing, keeping multiple, or skipping new files.
- Existing default compatibility patches still run.
- Patch results appear in the result screen and installed mod patch details.
- External share/open of a mod jar uses the same wizard.
- External share/open of a game body jar still uses the game body import flow.

UX acceptance:

- No stacked dialogs during mod import.
- No toast-only import decisions.
- User can see all blocking reasons before execution.
- User can see all planned patches before execution.
- Execution progress identifies the current file and current operation.
- Result screen summarizes imported, skipped, blocked, failed, and patched items.

Architecture acceptance:

- The executor does not special-case Frieren, Downfall, VupShion, or future mod IDs.
- Adding a new mod-specific patch requires adding a module and registering it, not editing the import executor or wizard structure.
- Patch metadata is generic enough for future modules.
- Planner is read-only with respect to the installed optional mod library.
- Executor uses a frozen plan and does not introduce new user decisions.

## Test Matrix

Planner tests:

- Empty URI list.
- Single valid mod jar.
- Multiple valid mod jars.
- Non-jar file.
- Zip/rar/7z/tar selected by mistake.
- Unreadable jar.
- Missing `ModTheSpire.json`.
- Manifest nested under one root directory.
- Missing `modid`.
- Reserved component mod ID.
- Invalid MTS launch manifest.
- Duplicate against installed mod.
- Duplicate within same batch.
- Frieren patch applicability.
- Downfall patch applicability.
- VupShion patch applicability.
- Atlas downscale candidate discovery.

Executor tests:

- Import valid mod.
- Replace existing mod.
- Keep multiple duplicate mods.
- Skip duplicate new mod.
- Preserve folder assignment on replace.
- Preserve enabled state on replace.
- Apply patch module order.
- Patch failure with `BlockImport`.
- Patch failure with `SkipPatchContinueImport`.
- Final MTS validation failure blocks final move.
- Partial success in batch.
- Session cleanup after success.
- Session cleanup after failure.

UI previews:

- Scanning.
- Review with all valid files.
- Review with skipped archive.
- Review with blocked MTS manifest failure.
- Duplicate resolution.
- Patch configuration with atlas downscale.
- Confirm summary.
- Executing progress.
- Partial success result.
- Full failure result.

Manual validation:

- Main screen single mod import.
- Main screen batch import.
- Settings screen batch import.
- External share/open normal mod jar.
- External share/open game body jar.
- Import BaseMod/StSLib/ModTheSpire as optional mod and confirm it is blocked.
- Import a jar with invalid `ModTheSpire.json` and confirm it is blocked.
- Import Frieren and confirm patch metadata is recorded.
- Import Downfall and confirm patch metadata is recorded.
- Import VupShion and confirm patch metadata is recorded.
- Import a texture-heavy mod and test atlas downscale apply/skip.

## Implementation Guardrails

- Prefer small, staged changes. Do not replace all import code in one large step.
- Keep old behavior as a fallback until new planner and executor are verified.
- Do not add new mod-specific branches to the import executor.
- Do not make UI depend on concrete patch module classes.
- Do not write metadata during planning.
- Do not mutate installed mods before the user confirms the execution plan.
- Keep game body jar import separate.
- Keep MTS launch manifest validation blocking for optional mod import.

## Important Existing Code Locations

- `app/src/main/java/io/stamethyst/ui/settings/ModImportFlowCoordinator.kt`
  - Current import coordinator with duplicate, atlas, folder, and result dialogs.
- `app/src/main/java/io/stamethyst/ui/settings/SettingsFileService.kt`
  - Current import implementation and many file helpers.
- `app/src/main/java/io/stamethyst/ui/settings/JarImportInspectionService.kt`
  - Current URI-to-temp and inspection helper.
- `app/src/main/java/io/stamethyst/backend/mods/MtsLaunchManifestValidator.kt`
  - Strict ModTheSpire launch manifest validation.
- `app/src/main/java/io/stamethyst/backend/mods/ModJarManifestParser.kt`
  - Tolerant manifest parser used for display and duplicate detection.
- `app/src/main/java/io/stamethyst/backend/mods/ImportedModPatchRegistry.kt`
  - Current patch metadata persistence.
- `app/src/main/java/io/stamethyst/backend/mods/FrierenModCompatPatcher.kt`
  - Existing Frieren patch implementation.
- `app/src/main/java/io/stamethyst/backend/mods/DownfallImportCompatPatcher.kt`
  - Existing Downfall patch implementation.
- `app/src/main/java/io/stamethyst/backend/mods/VupShionModCompatPatcher.kt`
  - Existing VupShion patch implementation.
- `app/src/main/java/io/stamethyst/ui/settings/AtlasDownscaleImportDialog.kt`
  - Old native atlas downscale prompt to replace with Compose.
- `app/src/main/java/io/stamethyst/LauncherActivity.kt`
  - External intent entry and game body jar split point.

## Final Target

The final implementation should make optional mod import a predictable state machine:

```text
Select files
  -> Plan
  -> Review
  -> Resolve decisions
  -> Confirm
  -> Execute frozen plan
  -> Result
```

Patch behavior should be a registry of independent modules:

```text
Import flow -> Patch registry -> Applicable modules -> Plans -> Decisions -> Results
```

The import flow should remain stable when future mod-specific patches are added.
