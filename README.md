# HeroStand — Development Handoff

## Handoff checkpoint — 2026-09-21

This README is the authoritative HeroStand development handoff.

The current implementation branch is `rebuild/0.2.9-render-plan-cache`. The latest branch build passes, but **0.2.9 is not yet user-validated in-game**.

The most important discovery from the latest deep dive is that the Satsu Iron Man 3.5.3 wall is dominated by a very large Palladium/GeckoLib render graph rather than ordinary base armor. Previous visual-capture/VBO experiments repeatedly failed, artifacted, or crashed and are explicitly ruled out below. The 0.2.9 direction therefore optimizes repeated Palladium/Satsu bookkeeping while preserving exactly one native visual render per layer.

## Project

HeroStand is a standalone **Minecraft Forge 1.20.1** mod for efficient superhero/modded armor displays, especially Palladium/Satsu Iron Man suits.

Repository: `PunctualBoat203/HeroStand`  
Author / owner: **PunctualBoat**  
Java: **17**  
Forge: **47.4.10**  
Current test build: **0.2.9** — build-passing, awaiting PunctualBoat in-game validation

## Active development line

Current branch:

`rebuild/0.2.9-render-plan-cache`

0.2.9 was rebuilt from the **stable 0.2.6 visual path**, not from the artifacting 0.2.8 experiment.

Do not call a stale `main` code build the newest renderer unless this branch has been merged. The handoff on `main` is intentionally kept current even when implementation work remains on a test branch.

## What PunctualBoat has validated / wants preserved

- HeroStand remains a **block + non-ticking block entity**.
- Four equipment slots: head, chest, legs, feet.
- Invisible mannequin.
- Clean light/white pedestal.
- Correct facing with no double-yaw/jitter.
- Mark One/custom suit proportions must stay correct.
- Client render-distance option plus server cap.
- Solid-wall occlusion and distance de-render must keep working.
- Partial visibility around block edges must not wrongly hide suits.
- Palladium remains optional at runtime.
- Author / owner attribution is **PunctualBoat**.
- Prefer measured changes over speculative renderer rewrites.

## Reference visual baseline

The user-provided **0.1.8** JAR remains the original known-good visual reference.

**0.1.9** added configurable client/server distance limits while preserving the preferred rendering behavior.

Later 0.1.x/0.2.x work focused on the mixed-suit performance wall.

## Mixed-wall problem

PunctualBoat's repeatable stress scene is a large wall of mixed Satsu/Palladium superhero suits.

Observed behavior across many builds:
- usually about **45–55 FPS** while looking directly at the wall;
- GPU often roughly **60–90%**;
- allocation frequently around **450–520 MiB/s**;
- FPS recovers when distance or wall occlusion removes the stands.

This means culling works; the remaining problem is the cost of visible suit rendering.

## Important measured results

- **0.2.0:** stable ~49–50 FPS but no meaningful speedup.
- **0.2.1:** diagnostics proved whole-suit snapshot use was **0.0%**.
- **0.2.2:** `snap=0`, `cache=0`, `cap=106`; two-pass probing introduced screen/sky artifacting.
- **0.2.3:** only one useful snapshot / about **1.9%** coverage; artifacting remained.
- **0.2.4:** `snap=0`, `cache=0`, sorted Palladium RenderType blocker; runtime GL texture-readback experiment caused black/top-screen artifacting.
- **0.2.5:** crashed in HeroStand's translucent index-resort path (`StaticSuitSnapshotCache$MeshPart.resort -> VertexBuffer.upload -> MemoryUtil.memSlice`). Do not reuse it.
- **0.2.6:** stable split renderer. Base-armor cache only reached about **1.8%** while pack-layer calls exceeded **154,000**. This proved base armor is not the main cost.
- **0.2.7:** `pack=0.0%`, `build=0`, `cache=0`, `why=unsupported`; using Palladium `createSnapshot()` as general eligibility was wrong.
- **0.2.8:** `pack=0.0%`, `build=0`, `cache=0`, `why=empty`; artifacting returned. Later source inspection proved Gecko layers bypassed HeroStand's capture buffer and wrote to Minecraft's global world buffer during the extra probe renders.

## DO NOT LOOP BACK TO THESE APPROACHES

These have already been tested and are ruled out unless new evidence specifically justifies revisiting them:

1. Whole SuitStand snapshots.
2. Any extra/probing Palladium or Gecko render calls during the world render pass.
3. Two-time-sample visual rendering to decide whether something is static.
4. Runtime OpenGL texture readback / `glGetTexImage`.
5. Custom whole-suit VBO replay.
6. HeroStand-managed translucent index re-sorting/upload.
7. Base-armor-only caching as the primary optimization.
8. Treating Palladium `createSnapshot()` as a general static-layer eligibility API.
9. CPU “capture” that assumes every Palladium/Gecko layer uses the supplied `MultiBufferSource`.

## Exact Satsu Iron Man 3.5.3 deep dive

The exact Satsu 3.5.3 artifact used by the test instance was inspected directly.

Important findings:

- Satsu 3.5.3 ships **no Java classes of its own**.
- Its rendering complexity is supplied through **Palladium resource/data definitions plus GeckoLib compatibility**.
- It contains **457 Palladium render-layer JSON files**.
- **447** of those top-level resources are compound layers.
- Recursive expansion produced about **1,815 leaf render layers**, including roughly **1,782 GeckoLib layers**.
- Many advanced suits expand into **60–80 Gecko sublayers**.
- Large numbers of those layers use dynamic alpha masks, glow passes, ability/integer-property conditions, animation metadata, or separate geometry pieces.
- Satsu explicitly targets display entities: its `satsu_iron_man_addon:stands` entity tag contains both `minecraft:armor_stand` and `palladium:suit_stand`.

This means HeroStand's temporary Palladium SuitStand intentionally enters Satsu's stand-specific visual path. The expensive wall is not merely “base Palladium armor”; it is a very large Satsu-defined Palladium/Gecko render graph.

## Gecko/Palladium root cause discovered after 0.2.8

Palladium's Gecko compatibility is materially different from ordinary pack layers.

`GeckoRenderLayerModel.renderToBuffer()` obtains:

`Minecraft.getInstance().levelRenderer.renderBuffers.bufferSource()`

directly.

It does **not** reliably remain inside a custom `MultiBufferSource` supplied by HeroStand.

This explains previous confusing results:
- capture buffers often appeared empty;
- “CPU-only” probe renders were not actually CPU-only;
- extra Gecko probes emitted real geometry into the active world buffer;
- duplicate/global-buffer writes caused the top-screen artifacting.

Therefore HeroStand 0.2.9 performs **exactly one visual render per Palladium/Satsu layer** and does not capture/probe Gecko geometry.

## Simple hot-path problem found in Palladium

Two pieces of repeated bookkeeping are safe to optimize without changing visuals.

### 1. Layer discovery

`PackRenderLayerManager.forEachLayer()` rebuilds the layer traversal every render:
- runs all registered providers;
- checks ability-provided layers;
- walks every equipment slot;
- creates armor `DataContext` objects;
- checks addon-item render-layer containers;
- looks up `ArmorRendererData`;
- walks each renderer's pack layers.

A HeroStand's four ItemStacks are static until the player changes the stand, so rediscovering the same layer graph every frame is wasted work.

### 2. Condition DataContext allocation

Palladium 4.5.9 `IPackRenderLayer.conditionsFulfilled()` creates a fresh:

`DataContext.forEntity(livingEntity)`

for **each individual condition**.

`DataContext` owns a new `HashMap`, so a Satsu suit with dozens of nested layers and many ability/property conditions can create a large amount of short-lived garbage every frame.

This is a strong candidate for the observed ~450–500 MiB/s allocation rate.

## HeroStand 0.2.9 design

0.2.9 is intentionally a **bookkeeping optimization, not a rendering rewrite**.

### Cached Palladium render plan

For each HeroStand, HeroStand caches the exact discovered:

`(DataContext, IPackRenderLayer)`

plan.

The plan is rebuilt only when:
- the stand's armor changes;
- the block entity reloads/syncs;
- the client level changes;
- resources/caches are explicitly cleared.

Every actual layer still renders through Palladium/Gecko exactly once.

### Reused condition context

0.2.9 includes an **optional Palladium client mixin**.

During HeroStand's pack-layer pass, one entity-only Palladium `DataContext` is reused for repeated Gecko-layer condition checks instead of constructing a new `DataContext + HashMap` for every individual condition.

The mixin deliberately redirects the condition call inside the concrete Palladium `GeckoRenderLayer.render()` method rather than injecting into the static `IPackRenderLayer` interface helper. This avoids Sponge Mixin's unsupported static-interface injector edge case and directly targets the Satsu-heavy path. Outside HeroStand's render scope, Gecko condition evaluation builds one normal context per helper call.

Palladium remains optional:
- there is no mandatory Palladium entry in `mods.toml`;
- compile-time Palladium access is only for the optional mixin;
- the bridge remains guarded by Palladium presence.

### What 0.2.9 does NOT do

0.2.9 does not:
- render a layer twice;
- capture Gecko geometry;
- read GL textures;
- own suit VBOs;
- replace Palladium RenderTypes;
- re-sort translucent geometry;
- freeze animations;
- alter Satsu's model/texture/condition logic.

## 0.2.9 F3 diagnostics

The debug line reports:

`HeroStand 0.2.9 planHit=... build=... plans=... layers=... calls=... condReuse=... condNew=... fallback=...`

Interpretation:
- `planHit` should rise rapidly after the initial per-stand plan creation.
- `build` should remain small and mostly track stand equipment changes/initial discovery.
- `plans` should roughly reflect active HeroStand render plans.
- `layers` is the number of discovered top-level Palladium layer entries across plans.
- `calls` is the count of actual layer render invocations; each should still be a real native render.
- `condReuse` confirms condition checks are using the shared context.
- `condNew` is fallback condition-context construction.
- `fallback` reports bridge failures.

The important comparison is **allocation rate first**, then FPS/GPU load. 0.2.9 is specifically intended to remove repeated Java bookkeeping/garbage; it is not claimed to reduce Gecko geometry cost.

## Build / artifact handoff

Build via GitHub Actions and provide PunctualBoat the compiled Forge JAR, not merely the artifact ZIP.

Before handing over a JAR:
1. verify the intended branch/commit;
2. verify embedded mod version;
3. verify author is **PunctualBoat**;
4. verify expected renderer/mixin classes are present;
5. do not silently substitute `main`.

0.2.9 build reference:
- branch: `rebuild/0.2.9-render-plan-cache`
- latest successful Actions run: **#113**
- run ID: `35567230354`
- latest successful branch head: `03f0471f03a59666655d20ebf2088a6541cee7b5`
- status: **build passes; PunctualBoat has not yet validated 0.2.9 in-game**
- do **not** claim the FPS issue is fixed until the same mixed-suit wall test confirms it

## Next testing order

1. Verify **no black/top-screen artifacting**.
2. Verify **no crash**.
3. Check Mark One and several other Satsu suits visually.
4. Face the same mixed-suit wall for ~10 seconds.
5. Capture F3 showing:
   - FPS;
   - GPU %;
   - allocation rate;
   - the full HeroStand 0.2.9 diagnostic line.
6. Compare allocation rate against the prior ~450–500 MiB/s baseline.
7. If allocation drops substantially but FPS stays ~50, the next target is the actual Gecko geometry/animation path and should be profiled rather than guessed.
8. If allocation does not drop and `condReuse` is high, condition allocation is not the dominant cost; do not keep optimizing it.

## Development preference

PunctualBoat wants measured, testable changes.

For future work:
- read this handoff before touching the renderer;
- do not repeat ruled-out approaches;
- preserve native Palladium/Satsu/Gecko visual correctness;
- preserve distance/wall culling;
- use diagnostics/profiling to prove the next bottleneck before adding another renderer subsystem.
