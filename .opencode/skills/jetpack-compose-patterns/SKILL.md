---
name: jetpack-compose-patterns
description: Apply pragmatic Jetpack Compose implementation patterns for Android UI code, including state hoisting, unidirectional data flow, side effects, navigation boundaries, modifiers, theming, previews, accessibility, and testable composable structure. Use when asked to build, refactor, review, or explain Compose screens and components.
---

# Jetpack Compose Patterns

## Quick Start

1. Locate the smallest relevant Compose surface: screen, route, state holder, reusable component, modifier, preview, or test.
2. Preserve the project's existing architecture, Material version, theme, navigation style, DI, and state management conventions.
3. Separate route/stateful composables from stateless UI composables when it improves testability and reuse.
4. Keep state ownership explicit: UI state flows down, events flow up, and side effects live at clear lifecycle boundaries.
5. Prefer minimal, behavior-preserving changes over broad architectural rewrites.

## Workflow

1. Understand the Compose boundary.
- Identify whether the code is a screen route, a reusable UI component, a design-system primitive, or a one-off layout.
- Check whether state comes from a ViewModel, presenter, state holder, navigation arguments, saved state, or local UI interaction.
- Match existing project patterns before introducing new abstractions.

2. Structure composables clearly.
- Use route-level composables for ViewModel collection, navigation callbacks, permission launchers, snackbars, and other app wiring.
- Use stateless content composables for rendering UI from immutable state and event callbacks.
- Keep parameters focused on what the composable needs; avoid passing a full ViewModel into reusable UI.
- Do not split every small layout into a new composable unless it improves readability, reuse, previewability, or testability.

3. Manage state deliberately.
- Hoist state to the lowest common owner that needs to read or change it.
- Use `rememberSaveable` for local user input or UI choices that should survive configuration changes and process recreation when practical.
- Use `remember` only for values tied to composition lifecycle and safe to retain.
- Avoid duplicating source-of-truth state between ViewModel and local composition unless there is a deliberate draft/editing model.
- Use immutable UI state models for screen data when the project supports them.

4. Handle events and side effects.
- Model user actions as callbacks from UI to the owner, such as `onRetry`, `onSubmit`, or `onItemClick`.
- Use `LaunchedEffect`, `DisposableEffect`, `SideEffect`, `produceState`, and `snapshotFlow` only when their lifecycle semantics match the work.
- Keep effect keys specific and stable so effects neither restart too often nor miss required restarts.
- Use `rememberUpdatedState` for callbacks captured by long-lived effects when the effect should not restart.
- Do not launch coroutines directly during composition.

5. Build layouts idiomatically.
- Put `modifier: Modifier = Modifier` on reusable composables and apply it to the outermost meaningful layout node.
- Prefer modifier chains over wrapper layouts when they express the same behavior clearly.
- Use `Scaffold` only when the screen actually needs coordinated bars, snackbars, drawers, FABs, or insets.
- Handle system bars, IME, and gesture navigation insets according to the project's edge-to-edge strategy.
- Prefer lazy layouts for long or unbounded collections and provide stable keys when items can move, insert, or be removed.

6. Apply theme and design-system rules.
- Use project theme tokens for colors, typography, shapes, elevation, and spacing when available.
- Avoid hard-coded colors and dimensions unless they are local, intentional, and consistent with existing code.
- Keep Material 2 and Material 3 components separated unless the project already mixes them deliberately.
- Use composition locals sparingly and only for cross-cutting values with clear ownership.

7. Cover states and accessibility.
- Represent loading, empty, content, error, refreshing, permission, and disabled states explicitly when they are part of the user flow.
- Provide meaningful semantics for icon-only buttons, custom controls, images with content meaning, and dynamic status text.
- Preserve 48dp touch targets for interactive controls unless the existing design system has a documented exception.
- Support font scaling, screen readers, keyboard focus, and non-color-only state communication.

8. Verify with previews and tests.
- Add or update previews when they are already used and help validate important states.
- Prefer stateless content previews that do not require DI, navigation controllers, or live ViewModels.
- For tests, assert visible behavior and events rather than implementation details.
- Run the smallest relevant build, lint, unit, screenshot, or Compose UI test command available in the project.

## Common Patterns

- Route/content split: `FeatureRoute` collects state and handles side effects; `FeatureScreen` renders state and emits events.
- State holder pattern: use a local state holder for complex component-only state that does not belong in a ViewModel.
- Event callbacks: expose intent-focused callbacks instead of leaking implementation-specific lambdas.
- Slot APIs: use composable slots for flexible reusable components, but avoid slot-heavy APIs for simple one-off UI.
- Saver pattern: provide a `Saver` only when local state must survive recreation and is not automatically saveable.
- Preview fixtures: use small fake UI state objects for deterministic previews.
- Lazy list identity: use stable `key` and `contentType` for reorderable or heterogeneous lists.

## Anti-Patterns To Avoid

- Passing `NavController`, `ViewModel`, `Context`, or mutable repositories into broadly reusable UI composables.
- Performing I/O, sorting large lists, date formatting setup, image decoding, or analytics calls during composition.
- Creating unstable collections or objects repeatedly in frequently recomposed code without a reason.
- Using `LaunchedEffect(Unit)` for work that should be keyed to changing inputs.
- Adding `remember`, `derivedStateOf`, or stability annotations mechanically without a concrete correctness or performance reason.
- Hiding important UI state inside deeply nested composables where tests and previews cannot drive it.

## Response Rules

- Cite file paths and line numbers when reviewing or refactoring existing code.
- Recommend the smallest Compose pattern that solves the concrete problem.
- Explain ownership of state and side effects when proposing a structure.
- Include accessibility and state coverage notes for screen-level UI changes.
- Include verification steps, and clearly state when verification was not run.
