# Ram Saver

This bundled mod carries Ram Saver's texture-lifetime changes as a launcher-managed ModTheSpire component, so the Android runtime can ship the same RAM-saving behavior and Amethyst-specific diagnostics without requiring a user-imported workshop jar.

## Included fixes

1. `com.badlogic.gdx.graphics.Texture`, `com.badlogic.gdx.graphics.RealTexture`, and `optispire.RamSaver`
Replace normal file-backed texture construction with fake/lazy textures that keep file identity and dimensions but defer real `RealTexture` creation until rendering or API calls need an actual GL handle. This addresses the symptom where heavily modded runs keep too many image assets loaded at once and exhaust Java/native/GPU memory. Type: memory-management workaround implemented by the Ram Saver texture replacement and asset manager.

2. `optispire.RamSaver`
Ages managed texture and atlas-region holders in small rotating buckets, disposes stale assets, and processes weak-reference notifications so logical image assets can be released after they are no longer strongly referenced. This addresses long-session memory growth from cached card, UI, atlas, and region assets. Type: memory-management workaround implemented by `RamSaver.update`, `ManagedAsset`, and the `FileTextureSupplier` path.

3. `optispire.patches.HandleRenderingFakes` and `optispire.patches.G3dBindRealTextures`
Materialize fake textures only at SpriteBatch, PolygonSpriteBatch, and g3d bind sites, then restore the fake texture object after drawing where needed. This addresses rendering paths that require real GL texture handles while preserving lazy texture residency elsewhere. Type: rendering compatibility workaround implemented by `HandleRenderingFakes` and `G3dBindRealTextures`.

4. `optispire.patches.ChangeSpriterLoader`
Disables Spriter pixmap packing and routes Spriter sprite creation through lazy texture-backed sprites. This addresses Spriter animation loading paths that would otherwise force eager pixmap/texture allocation. Type: memory-management workaround implemented by `ChangeSpriterLoader`.

5. `optispire.patches.PixmapLessAngry`
Prevents double-disposed pixmaps from throwing when the disposed flag is already set, printing a diagnostic message instead. This addresses cleanup paths made more common by aggressive image lifetime management. Type: compatibility workaround implemented by `PixmapLessAngry`.

6. `optispire.patches.LessColors`
Reuses a shared white `Color` instance for repeated card-image color-copy calls during `AbstractCard.createCardImage`. This addresses avoidable temporary object churn in card image creation paths that are exercised more often when images are lazily reloaded. Type: memory-management workaround implemented by `LessColors`.

7. `optispire.patches.AggressiveGC`
Requests Java GC from selected lifecycle points to accelerate cleanup after Ram Saver has made assets collectible. This addresses the symptom where released fake/real texture wrappers can otherwise remain in heap until a later GC cycle. Type: memory-pressure workaround implemented by `AggressiveGC`.

8. `optispire.RamSaverDiag`
Adds `[ram-saver]` diagnostic logging gated by the launcher's `amethyst.gdx.gpu_resource_diag` property or the explicit `ramsaver.diag.enabled` property, with full verbose traces requiring `ramsaver.diag.verbose=true`. This addresses the need to attribute render-thread materialization stalls and repeated texture creation without flooding normal gameplay logs. Type: diagnostic hook implemented by `RamSaverDiag` and instrumentation in the texture/materialization paths.

9. `optispire.RamSaver` and `com.badlogic.gdx.graphics.Texture`
Caches missing/rejected texture paths and detects repeated fake texture creation from render-like stacks, logging `repeated_render_texture_create` milestones while suppressing repeated full fake-texture creation stacks after the first hot threshold. This addresses long-session stutter caused by mods repeatedly constructing missing or duplicate textures during render, such as repeated `TimeEaterImg/img/clock/alarm.png` lookup attempts from `Clock.AbstractClock.render`. Type: performance diagnostic and mitigation implemented by `RamSaver.isTextureRejected`, `RamSaver.markTextureRejected`, `RamSaver.recordFakeTextureCreate`, and the file-backed `Texture` constructor.

10. `optispire.RamSaver` and `com.badlogic.gdx.graphics.Texture`
Keeps shared fake-texture state per file path, including cached dimensions, supplier identity, rejected status, and repeated-render construction state. This addresses the symptom where generic render-path `new Texture(path)` loops repeatedly redo registration, header-size probing, and state setup even though the path is identical. Type: performance mitigation implemented by `RamSaver.FakeTextureState`, `RamSaver.cacheTextureSize`, `RamSaver.getCachedTextureSize`, `RamSaver.registerTexture`, and the file-backed `Texture` constructor.

11. `optispire.RamSaverDiag` and `optispire.RamSaver`
Stops treating RAM Saver fake wrappers as live GPU textures in Amethyst's GDX diagnostic counters and samples render-path creation stacks only at low-frequency milestones once a path is known to be repeated. This addresses the symptom where GPU diagnostics amplify bad render-path texture construction by logging thousands of fake-wrapper `GLTexture construct_repeat` stacks and by repeatedly calling `Thread.getStackTrace` on the render thread. Type: diagnostic/performance mitigation implemented by `RamSaverDiag.markFakeTextureWrapperConstructed`, `RamSaver.recordFakeTextureCreate`, and `RamSaver.findRenderTextureCreationSignature`.

## Maintenance rule

If you add another runtime/gameplay fix through this mod, update this README in the same change and describe:

- what symptom the fix addresses
- which patch class implements it
- whether it is a memory-management workaround, compatibility workaround, crash fix, or diagnostic hook
