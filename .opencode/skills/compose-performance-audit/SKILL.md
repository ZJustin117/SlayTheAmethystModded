---
name: compose-performance-audit
description: Audit Jetpack Compose performance by inspecting recomposition risk, state reads, stability, lazy layout keys, effects, derived state, allocation hotspots, and measurement evidence. Use when asked to diagnose slow Compose screens, review Compose code for performance issues, or propose targeted optimizations with verification steps.
---

# Compose Performance Audit

## Quick Start

1. Start from the reported symptom: slow first render, jank while scrolling, excessive recomposition, input lag, animation stutter, memory churn, or battery/CPU pressure.
2. Inspect the smallest relevant Compose surface first: screen composable, state holder, lazy list item, animation, or custom layout.
3. Separate measured problems from theoretical risks. Prefer evidence from traces, compiler reports, layout inspector, recomposition counts, macrobenchmarks, or profiler captures.
4. Propose minimal fixes that preserve behavior and fit the project's architecture.
5. End with verification commands or manual checks that prove the performance issue improved.

## Workflow

1. Locate the hot path.
- Identify the composables rendered during the slow interaction.
- Find state reads that can invalidate broad parts of the tree.
- Check whether expensive work happens during composition, layout, draw, or effects.
- For scrolling issues, inspect item composables, list keys, content types, image loading, and nested scroll behavior.

2. Audit recomposition scope.
- Move volatile state reads as low as practical in the tree.
- Prefer passing stable values or lambdas only when they avoid broad invalidation and match existing project style.
- Use `derivedStateOf` only for values that change less often than their inputs and where recomposition reduction is meaningful.
- Avoid adding `remember`, `rememberUpdatedState`, `key`, `LaunchedEffect`, `DisposableEffect`, `SideEffect`, or `snapshotFlow` mechanically; each must have a concrete reason.

3. Audit stability and parameters.
- Look for mutable collections, unstable data classes, anonymous objects, changing lambdas, and large parameter bundles passed through many levels.
- Prefer immutable model types or persistent collections where the project already supports them.
- Do not add stability annotations to hide real mutability problems unless the contract is true and documented.
- Avoid premature `@Stable` or `@Immutable`; incorrect annotations can make UI stale.

4. Audit lazy layouts.
- Ensure `LazyColumn`, `LazyRow`, grids, and paged lists use stable keys when items can move, insert, or be removed.
- Provide `contentType` when many item types share a list and reuse matters.
- Keep item lambdas small and avoid per-item allocations, formatting, sorting, filtering, or decoding in composition.
- Avoid nested same-direction scroll containers unless there is a deliberate bounded layout.

5. Audit expensive work.
- Move sorting, filtering, mapping, date formatting, image decoding, text measurement setup, and I/O out of composition.
- Cache calculations only when inputs and lifecycle make caching correct.
- Prefer upstream state preparation in ViewModel or state holder when the computation is screen data, not UI-only derivation.
- Check painter/image loading size, placeholders, prefetching, and cache behavior for image-heavy screens.

6. Audit effects and coroutines.
- Ensure effect keys are specific and stable.
- Avoid restarting work on every recomposition due to unstable keys.
- Use `rememberUpdatedState` for callbacks captured by long-lived effects when the effect should not restart.
- Cancel work when leaving composition and avoid launching duplicate collectors.

7. Verify with evidence.
- Use Macrobenchmark, Baseline Profiles, Perfetto/System Trace, Android Studio profiler, Compose layout inspector, or Compose compiler metrics when available.
- Compare before/after on the same device, build variant, and scenario.
- Report residual risks when changes are based on static review only.

## Common Fix Patterns

- Defer state reads to child composables when only a child depends on fast-changing state.
- Replace composition-time list transformations with prepared UI state.
- Add lazy list keys for mutable or reorderable data.
- Add `contentType` for heterogeneous lazy lists.
- Use `remember` for object creation only when the object is safe to retain for the composable lifecycle.
- Use `derivedStateOf` for throttling recomposition from high-frequency state, such as scroll threshold booleans.
- Keep animations bounded and avoid animating layout-heavy properties when draw-layer alternatives are sufficient.

## Response Rules

- Findings first, ordered by likely performance impact.
- Include file and line references for code audits.
- Distinguish confirmed bottlenecks from plausible risks.
- Prefer the smallest behavior-preserving change.
- Include verification steps, and state clearly when no benchmark or trace was run.
