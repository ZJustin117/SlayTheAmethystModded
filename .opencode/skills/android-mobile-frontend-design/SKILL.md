---
name: android-mobile-frontend-design
description: Design and review Android mobile frontend experiences with pragmatic attention to touch ergonomics, adaptive layouts, accessibility, state handling, and implementation handoff. Use when asked to design Android screens, improve mobile UI/UX, critique frontend flows, or translate product requirements into Android-ready interface guidance.
---

# Android Mobile Frontend Design

## Quick Start

1. Identify the product goal, primary user task, and Android surface: phone, tablet, foldable, embedded WebView, or Compose/View hybrid.
2. Ground the design in the existing app structure, theme, navigation model, and component system before proposing new patterns.
3. Optimize the first-use path, error states, empty states, loading states, and recovery paths, not only the happy path.
4. Check touch ergonomics, one-handed reach, visual hierarchy, content density, accessibility, and responsiveness across common screen sizes.
5. End with concrete implementation guidance: layout structure, component behavior, state model, and validation criteria.

## Workflow

1. Understand the screen and user job.
- State the main user intent in one sentence.
- Identify secondary actions and which actions should be visually quiet.
- Note constraints such as offline mode, permissions, login state, slow networks, and device rotation.

2. Fit the existing Android app.
- Reuse current typography, color roles, spacing scale, icon style, navigation, and component patterns when available.
- Prefer platform-consistent behavior for back handling, system bars, keyboard insets, gestures, dialogs, sheets, and permissions.
- Avoid introducing a novel design language unless the user explicitly asks for a redesign.

3. Design the layout.
- Put the primary task above decorative content.
- Keep primary actions reachable and persistent only when they are useful throughout the task.
- Use responsive constraints rather than fixed dimensions.
- Account for small phones, large phones, tablets, foldables, landscape, font scaling, and display cutouts.
- Keep text readable at high density; do not solve crowded layouts by shrinking type below comfortable mobile sizes.

4. Define interaction states.
- Cover loading, refreshing, pagination, empty, partial data, validation, permission denied, offline, retry, success, and destructive confirmation states.
- Make disabled states explainable or avoid disabled controls when the user cannot infer the reason.
- Ensure errors say what happened and what the user can do next.

5. Check accessibility and input.
- Use at least 48dp touch targets for interactive controls unless there is a documented exception.
- Ensure content works with screen readers, keyboard focus, switch access, dynamic type, high contrast, and reduced motion.
- Provide meaningful labels for icon-only actions.
- Do not rely on color alone to communicate state.

6. Prepare implementation guidance.
- Name the likely composables, fragments, views, or components to update.
- Specify state ownership and one-way data flow where relevant.
- Describe animations only when they clarify continuity or reduce perceived latency.
- Include acceptance checks that can be verified on device or emulator.

## Android Heuristics

- Respect edge-to-edge content by handling status bar, navigation bar, keyboard, and gesture insets deliberately.
- Prefer lazy lists for long or unbounded content.
- Keep modal surfaces focused; use bottom sheets for short contextual tasks and full screens for multi-step tasks.
- Avoid stacked dialogs and nested scroll conflicts.
- Persist user input across rotation and process recreation when losing it would be harmful.
- Consider haptics sparingly for confirmation, selection, and boundary feedback.

## Response Rules

- Give concrete screen-level recommendations, not generic UI advice.
- If reviewing an existing implementation, cite file paths and line numbers when available.
- If producing a design spec, include layout, states, accessibility, and verification checks.
- Keep visual polish grounded in the app's current design system unless asked to create a new direction.
