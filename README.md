# HeroStand — Development Handoff

## Project

HeroStand is a standalone Minecraft **Forge 1.20.1** mod for optimized superhero/modded armor displays, especially Palladium suits.

Repository: `PunctualBoat203/HeroStand`  
Author / owner: **PunctualBoat**  
Java: **17**  
Forge: **47.4.10**  
Current test build: **0.2.5**

## IMPORTANT — current development line

Active renderer rebuild branch:

`rebuild/0.2.5-translucent-index-cache`

0.2.4 direct-capture diagnostics branch:

`rebuild/0.2.4-direct-capture-diag`

0.2.3 single-capture branch:

`rebuild/0.2.3-single-capture`

0.2.2 stability experiment branch:

`rebuild/0.2.2-stable-snapshot`

0.2.1 diagnostics branch:

`rebuild/0.2.1-cache-diagnostics`

0.2.0 renderer reset branch:

`rebuild/0.2.0-native-snapshot`

0.2.0 is a **ground-up rendering/visual rebuild**. It is intentionally not another layer on top of the 0.1.10–0.1.19 renderer experiments.

The rebuild starts conceptually from the **0.1.8/0.1.9 behavior the user liked**, while replacing the Palladium visual renderer itself.

Known reference points:
- **0.1.8** user-provided JAR: original newer rendering reference.
- **0.1.9**: known baseline with configurable client/server render distance and working cached wall occlusion.
- **0.1.17**: Mark One visual issue confirmed fixed, but mixed-suit FPS still poor.
- **0.1.18**: roughly 49 FPS / 63% GPU / 476 MiB/s allocation in the mixed-suit wall.
- **0.1.19**: no meaningful improvement and may have been worse.
- **0.2.0**: clean renderer reset; user test was a very stable **49–50 FPS**, about **79% GPU**, and about **511 MiB/s allocation** at the mixed-suit wall.
- **0.2.1**: cache-hot-path correction + F3 diagnostics. User test proved the snapshot cache was being used **0.0%** of the time.
- **0.2.2**: unknown-layer rejection was removed, but the user's F3 test still showed **0.0% snapshot usage**, **cache=0**, **cap=106**, about **48 FPS / 83% GPU**, and it introduced visible sky flicker. The two-pass capture/sorting-rejection design is therefore abandoned.
- **0.2.3**: finally produced a snapshot, but only **1 cached entry / ~1.9% snapshot usage** in the wall test. User still saw roughly **46–48 FPS**, **~81% GPU**, and a new top-of-screen flicker/artifact. F3 showed **cap=133 / cache=1**, so nearly every candidate still failed capture.
- **0.2.4**: direct renderer capture + rejection diagnostics. User test still showed **0.0% snapshot usage / cache=0 / cap=101**, with `why=sorted:RenderType[palladium:arm...]`. Performance remained about **51 FPS / 79% GPU / ~521 MiB/s allocation**. The black/flickering top-of-screen artifact also remained even with zero cached snapshots, strongly implicating the runtime OpenGL texture-readback experiment rather than snapshot replay.
- **0.2.5**: removes all runtime `glGetTexImage`/texture-binding inspection and implements Minecraft-style cached translucent geometry: vertices are uploaded once, `BufferBuilder.SortState` is retained, and only the translucent index order is regenerated/re-uploaded for the current camera before each draw.

Do not call an old `main` code build the latest renderer. The handoff on `main` may describe a newer test branch than the code currently merged there.

## What must remain

These are validated user preferences / working behaviors and should be preserved unless PunctualBoat explicitly asks otherwise:

- HeroStand remains a **block + non-ticking block entity**, not a persistent world ArmorStand/LivingEntity.
- Four equipment slots: head, chest, legs, feet.
- The mannequin/body is **invisible by default**.
- The visible base remains a simple clean light/white iron-like pedestal.
- Block facing controls suit facing exactly once; do not reintroduce interpolation/yaw fighting.
- No vibration/jitter.
- Distance de-render works and is configurable.
- Server can cap the client's suit render distance.
- Solid-wall occlusion works and FPS recovers when stands are hidden.
- Partial visibility around wall/block edges should not incorrectly hide a suit.
- Palladium remains optional; HeroStand must still load without it.
- Mark One/custom suit proportions must remain correct.
- Breaking the stand returns its equipment once.
- `/kill @e` and entity purge logic must not target HeroStand itself.
- Author / owner attribution is **PunctualBoat**.

## Why the 0.1.x optimization stack was abandoned

The user stress-tested a large mixed wall of Palladium suits.

Observed 0.1.x results included:
- roughly 40–55 FPS with many visible mixed suits;
- GPU load frequently 60–90%;
- allocation around 470–500 MiB/s;
- several Java-side caching/VBO/batching experiments produced little or no real FPS gain.

The important conclusion is that HeroStand should stop trying to manually reimplement more and more of Palladium's renderer while still drawing every suit as a dynamic entity every frame.

0.2.0 therefore resets the visual architecture.

## 0.2.0 ground-up renderer

### Core rule: Palladium owns Palladium visuals

HeroStand no longer manually recreates Palladium's suit transforms/models/layer math for the normal Palladium path.

When Palladium armor is present, HeroStand creates a **real client-only Palladium SuitStand object** reflectively and lets Palladium's actual `SuitStandRenderer` produce the visual.

The temporary SuitStand:
- is never added to the world entity list;
- never ticks as a world entity;
- has no AI/gameplay role;
- exists only as a client render context;
- is invisible so the HeroStand block/pedestal remains the visible base.

This is the visual-correctness baseline for custom suits such as Mark One.

### Complete static suit snapshots

For suits that are safe to treat as static, HeroStand captures the **complete native Palladium SuitStand visual** into GPU-resident static buffers.

This is different from the failed 0.1.15/0.1.16 VBO experiment:
- the old experiment cached only part of the suit while Palladium pack layers still rendered live;
- 0.2.0 captures the **whole known-static native visual**, including base armor and known-static pack layers.

A cached snapshot is keyed by:
- all four equipped armor item identities;
- armor damage/NBT state;
- packed light.

Repeated stands wearing the exact same suit under the same packed-light value reuse the same snapshot.

Facing is applied outside the snapshot, so north/east/south/west stands do not require duplicate mesh caches.

### What is NOT frozen

HeroStand refuses to snapshot visuals that are not proven safe.

These stay on Palladium's live native renderer:
- known thruster layers;
- known lightning-spark layers;
- ExtraAnimatedModel armor/layer models;
- any capture path that performs unsupported/non-buffered behavior;
- any snapshot build that fails.

Important 0.2.5 correction: a RenderType requiring camera-relative translucent sorting is **no longer automatically live-only**. Static translucent geometry can be cached while its index order is re-sorted for the current camera.

Important correction: **unknown/custom render-layer classes are no longer rejected merely because HeroStand does not recognize their Java class name.** Palladium explicitly supports third-party render-layer parsers. Unknown/add-on layers may attempt native capture; known animated behavior remains live.

This is intentional. Visual correctness wins over cache coverage.

### Alpha / translucent handling

Palladium commonly uses sorted translucent RenderTypes for base armor, including runtime/generated textures.

0.2.4 attempted to inspect runtime OpenGL texture pixels with `glGetTexImage`. The user still had **cache=0**, while the screen developed a black/flickering top artifact. That GL readback path is removed completely in 0.2.5.

0.2.5 follows Minecraft's own translucent chunk strategy instead:
- ordinary resource-pack textures may still be classified as binary-alpha and converted to cutout/no-cull when safely provable;
- runtime/generated Palladium textures are **not** rebound/read from OpenGL;
- if a captured RenderType still requests sorting, HeroStand keeps the original translucent RenderType;
- the captured vertex geometry is uploaded once;
- the original `BufferBuilder.SortState` / quad centers are retained;
- before drawing a cached stand, HeroStand computes the camera position in that stand's local coordinates;
- only the translucent **index order** is regenerated with `VertexSorting.byDistance(...)`;
- the resulting index-only buffer is uploaded to the existing VBO; vertex data/model geometry is not rebuilt.

This is specifically intended to avoid both previous failure modes:
1. rejecting every Palladium armor snapshot just because its RenderType is sorted; and
2. freezing one stale translucent sort order, which produced visible artifacts.

### Snapshot lifecycle / VRAM limits

The snapshot cache is bounded:
- maximum **192** complete suit/light snapshots;
- maximum **4 lighting variants per unique suit**;
- maximum **1 new snapshot build per game tick** so a showroom warms progressively without large capture spikes;
- unused snapshots are retired after **120 seconds**;
- sweep runs every **5 seconds**, including while stands are offscreen;
- world changes clear snapshots;
- logout clears snapshots;
- resource reload clears snapshots;
- every eviction explicitly closes the OpenGL `VertexBuffer`.

The hot path for an already-cached suit is only:
1. compute compact suit/light identity;
2. find snapshot;
3. replay GPU buffers.

It does not redo Palladium renderer/model safety inspection on every cache hit.

## 0.2.1 cache-hot-path correction and diagnostics

The 0.2.0 user test was visually stable but did not materially improve throughput:
- **49–50 FPS**
- approximately **79% GPU**
- approximately **511 MiB/s allocation**
- FPS was notably stable, but still far below the target when looking directly at the mixed-suit wall.

That result strongly suggests most visible suits were still reaching Palladium's live renderer instead of the complete static snapshot path.

Two concrete 0.2.0 inefficiencies were found:

1. **Snapshot lookup happened too late.**  
   HeroStand first identified the Palladium suit, updated the reusable SuitStand equipment, and performed renderer/context work before checking for an already-built snapshot.

2. **Known-unsnapshotable suits were safety-inspected every frame.**  
   The cache already remembered capture failures for 30 seconds, but a suit rejected by the higher-level dynamic-model/layer safety check was never added to that cooldown. Dynamic/unsafe suits therefore repeated reflective model/layer inspection every visible frame.

0.2.1 changes:
- Compute the compact suit/light snapshot key directly from the HeroStand block entity's four stored ItemStacks.
- Try the snapshot cache **before Palladium detection, reflection, SuitStand preparation, or equipment copying**.
- A cache hit now goes directly from HeroStand state -> snapshot lookup -> GPU draw.
- If a suit fails the Palladium snapshot-safety check, remember that suit identity as uncacheable for 30 seconds instead of reflectively re-checking it every frame.
- Keep native Palladium live rendering as the correctness fallback.

### F3 diagnostic line

0.2.1 adds a HeroStand line to the normal F3 debug screen.

It reports cumulative renderer behavior for the current renderer session:
- snapshot percentage;
- snapshot cache hits;
- snapshots built and drawn;
- Palladium live renders;
- blocked/known-dynamic renders;
- safety rejects;
- capture rejects;
- build-budget deferrals;
- current snapshot cache entry count.

Example shape:

`HeroStand 0.2.1: snap 85.0% (... hit/... built) live=... blocked=... safetyReject=... captureReject=... defer=... cache=...`

This line is intentionally diagnostic. The next mixed-wall screenshot should include it.

How to interpret it:
- **High snapshot % (ideally most static suits)** but FPS remains ~50: the problem is snapshot replay/draw submission/GPU cost, not Palladium model construction.
- **Low snapshot % + high safetyReject/blocked**: the static/dynamic classifier is too conservative for the user's suit pack.
- **Low snapshot % + high captureReject**: RenderTypes/translucency are preventing snapshots.
- **Low snapshot % + high live but low rejects**: suit classification/context routing is missing expected Palladium suits.
- **High defer during initial warmup only** is normal because builds are intentionally throttled.
- Allocation should drop materially once snapshot hits dominate; if it does not, inspect work performed outside the suit renderer.

## 0.2.1 measured result — definitive cache miss diagnosis

The first F3 diagnostic test finally identified why the 0.2 snapshot architecture had not improved FPS.

User screenshot at the mixed-suit wall showed approximately:
- **47 FPS**
- **77% GPU**
- approximately **501 MiB/s allocation**
- `snap 0.0%`
- `0 hit / 0 built`
- `live=163672`
- `blocked=163619`
- `safetyReject=53`

The arithmetic is decisive: the 53 visible HeroStands were each safety-rejected, then almost every later render went through the 30-second blocked/live fallback. The complete suit snapshot cache was doing **zero useful work**.

The mistake was HeroStand's pre-cache policy, not the cache replay implementation: it assumed an unknown/add-on Palladium render-layer class was unsafe. That is incompatible with real Palladium add-on ecosystems where custom static layer implementations are normal.

## 0.2.2 stable-output snapshot policy

0.2.2 changes the safety model from **class-name trust** to **observed render-output stability**.

Preflight now rejects only behavior HeroStand positively knows is time-varying:
- Palladium thruster layers;
- Palladium lightning-spark layers;
- armor/layer models implementing `ExtraAnimatedModel`.

Unknown/add-on/custom layer classes are allowed to attempt a snapshot.

For every candidate snapshot, HeroStand renders the real Palladium SuitStand twice:
1. first native capture at the current entity tick with partial tick 0.0;
2. second native capture at entity tick + 7 with partial tick 0.5.

During both captures HeroStand hashes:
- emitted vertex positions;
- vertex colors/alpha;
- UV coordinates;
- overlay coordinates;
- packed light coordinates;
- normals;
- vertex count;
- resolved RenderType/texture identity;
- whether partial vertex alpha was emitted.

The snapshot is accepted only when:
- both captures are render-type safe;
- neither still needs camera-relative translucent sorting;
- both complete emitted signatures match exactly.

If they differ, the suit remains on Palladium's native live renderer. This catches time-varying custom layers without needing HeroStand to know the add-on's Java classes.

This deliberately targets the real 0.2.1 failure: static third-party layers should finally enter the snapshot cache, while genuinely animated output remains live.

The F3 line is shortened in 0.2.2 so the useful fields fit on screen:

`HeroStand 0.2.2 snap=... hit=... build=... live=... dyn=... cap=... cache=...`

For the same wall, the key success signal is no longer just FPS. First verify that `snap` climbs substantially above 0% and `cache` becomes nonzero. Only then does the snapshot architecture deserve an FPS comparison.

## 0.2.2 measured result — capture-stage rejection

PunctualBoat's 0.2.2 wall screenshot showed approximately:
- **48 FPS**
- **83% GPU**
- approximately **470 MiB/s allocation**
- `snap=0.0%`
- `hit=0`
- `build=0`
- `live=86933`
- `dyn=0`
- `cap=106`
- `cache=0`

This proves the old safety classifier was no longer the blocker (`dyn=0`), but every attempted snapshot was still rejected at the **capture stage**.

The main cause is Palladium's runtime/dynamic texture system:
- many resolved textures are runtime `ResourceLocation` values registered directly with Minecraft's TextureManager;
- they may not exist as normal ResourceManager files;
- HeroStand's alpha classifier therefore cannot prove them binary/cutout;
- Palladium commonly maps otherwise-static armor/layers to translucent RenderTypes;
- 0.2.2 then rejected those RenderTypes because they requested quad sorting.

The two-time-sample capture also caused **sky flicker** in PunctualBoat's test and must not be reintroduced.

## 0.2.3 single-capture / sorted snapshot correction

0.2.3 removes the failed two-pass capture experiment.

New policy:
- render the native Palladium SuitStand **once** when building a snapshot;
- known thruster/lightning/ExtraAnimatedModel content still stays live;
- unknown/custom static add-on layers may still attempt capture;
- a RenderType requesting translucent sorting is **not grounds for rejecting the entire suit**;
- those local-space quads are sorted once during snapshot upload with Minecraft's current VertexSorting;
- the resulting GPU buffer is reused like other static suit geometry;
- snapshot warm-up is limited to **one new suit per tick** to reduce one-frame spikes/state disturbance;
- once a snapshot exists, the ItemStack-key hot path still bypasses Palladium reflection/context setup.

The F3 line is now:

`HeroStand 0.2.3 snap=... hit=... build=... live=... dyn=... cap=... cache=...`

The first success condition for 0.2.3 is simple:
- `cache` must become nonzero;
- `build` must become nonzero;
- after warm-up, `hit` should rise quickly and `snap` should become a substantial percentage;
- repeated `cap` growth with `cache=0` means capture is still failing and should be treated as a bug, not as a performance result.

## Distance and wall occlusion

0.2.0 intentionally keeps the known 0.1.9 culling behavior separate from the visual renderer.

Current behavior:
- skip empty stands;
- use the lower of client render distance and server maximum;
- cached visibility sampling at head/chest/legs/sides;
- solid occluding blocks hide fully blocked suits;
- non-occluding hits can be skipped;
- visible/hidden results refresh on short intervals;
- camera movement accelerates refresh;
- cache is cleared when the render level changes.

Do not replace this simply because the visual renderer was rebuilt.

## Non-Palladium armor

If the equipped set is not a compatible Palladium suit, HeroStand uses a reusable invisible vanilla ArmorStand render context through Minecraft's normal `EntityRenderDispatcher`.

Palladium is still an optional dependency.

## Removed 0.1.x rendering experiments

The 0.2.0 branch is based from the clean 0.1.9 line and does **not** carry forward the stacked 0.1.10–0.1.19 renderer implementation.

In particular, the old manual `PalladiumRenderBridge` was removed from the 0.2.0 branch.

Do not re-add the old GPU-vendor router, partial base-only VBO cache, fast manual model emitter, or layered renderer experiments unless a specific measured reason justifies doing so.

## 0.2.3 validation priorities

Test in this order:

1. **Mark One/custom suit correctness**
   - normal head placement;
   - chest/legs/boots full normal SuitStand scale;
   - no detached helmet;
   - no mannequin geometry poking through.

2. **Previously-correct Palladium suits**
   - several Iron Man/superhero sets;
   - different model shapes;
   - glint/emissive effects;
   - transparent/glass pieces.

3. **Mixed-suit performance wall**
   - use the same viewpoint used for the 0.1.17–0.2.0 screenshots;
   - wait several seconds for snapshot warm-up;
   - record FPS, GPU %, allocation rate, **and the HeroStand F3 diagnostic line**;
   - repeated identical suits should share snapshots;
   - do not judge the next architecture until the diagnostic line shows whether snapshot coverage is high or low.

4. **Dynamic effects**
   - thrusters/lightning/animated suit layers must remain animated and live.

5. **Culling**
   - move beyond configured distance and verify de-render/FPS recovery;
   - hide stands behind a solid wall and verify culling;
   - partially expose a stand and verify it remains visible.

6. **Lifecycle**
   - F3+T resource reload;
   - leave/re-enter world;
   - change equipment;
   - change lighting;
   - verify stale snapshots do not survive incorrectly.

## Recipe

3 iron blocks across the bottom row + 2 polished diorite vertically in the center:

```
 D
 D
III
```

D = polished diorite  
I = iron block

## Build / artifact handoff

Build through GitHub Actions and hand PunctualBoat the compiled Forge JAR.

Before handing over a JAR:
1. confirm the intended branch/commit;
2. confirm the embedded mod version;
3. confirm `mods.toml` author is **PunctualBoat**;
4. validate the artifact/JAR contents;
5. do not silently substitute a stale `main` build.

0.2.3 Actions reference:
- branch: `rebuild/0.2.3-single-capture`
- successful latest-head build: **run #90**
- build commit: `9da80ff04e672203d4a7f4b3e68a3d896e15e355`

0.2.2 reference:
- branch: `rebuild/0.2.2-stable-snapshot`
- successful build: **run #87**
- build commit: `614b76f803bae429fafd8ee051a58378f6905bc4`

0.2.1 reference:
- branch: `rebuild/0.2.1-cache-diagnostics`
- successful build: **run #84**
- build commit: `0bb878d8b5b4df4c0fbd17275f258f9f042dcec8`

0.2.0 reference:
- branch: `rebuild/0.2.0-native-snapshot`
- successful build: **run #82**
- build commit: `845c0a2f30b2b1e2e01086ccdda60d79d7a82831`

## Development preference

PunctualBoat prefers working/testable builds and measured changes.

For future performance work:
- measure before adding another optimization layer;
- preserve native Palladium visual correctness;
- cache complete static work rather than optimizing tiny lookup fragments;
- keep dynamic visuals live;
- do not regress the working distance/wall culling behavior;
- prefer simple architecture over accumulating renderer hacks.


## 0.2.4 direct capture / rejection diagnostics

0.2.4 captured Palladium's actual renderer directly and added `why=<reason>` to F3.

PunctualBoat's wall test showed:
- about **51 FPS**;
- about **79% GPU**;
- about **521 MiB/s allocation**;
- `snap=0.0%`;
- `hit=0`;
- `build=0`;
- `live=258616`;
- `dyn=0`;
- `cap=101`;
- `cache=0`;
- `why=sorted:RenderType[palladium:arm...]`.

This proves the remaining cache blocker is Palladium's base armor RenderType being sorted/translucent, not the old dynamic-layer safety classifier.

The same test still showed the black/flickering artifact at the top of the screen even though `cache=0`. Therefore the artifact cannot be caused by replaying a cached VBO. The runtime OpenGL texture-readback/binding experiment introduced in 0.2.4 is treated as the regression source and is removed in 0.2.5.

## 0.2.5 translucent index cache

0.2.5 changes the cache model instead of trying to force Palladium's runtime armor textures into cutout rendering.

For sorted/translucent captured geometry:
- capture the native Palladium suit geometry once;
- call `BufferBuilder.setQuadSorting(...)` at capture time and keep its `SortState`;
- upload vertex geometry once into a GPU `VertexBuffer`;
- use a dynamic index buffer for sorted parts;
- on each cached draw, transform the camera into the stand's local coordinates;
- restore the saved `SortState`;
- regenerate only sorted quad indices with `VertexSorting.byDistance(...)`;
- upload the index-only `RenderedBuffer` to the existing VertexBuffer;
- then draw with Palladium's original RenderType.

This mirrors Minecraft 1.20.1's translucent chunk re-sort approach: geometry stays resident while camera-dependent index order changes.

0.2.5 also:
- removes all runtime `glGetTexImage` calls;
- does not manually bind/read Palladium's uploaded textures;
- keeps direct `SuitStandRenderer` capture rather than recursive EntityRenderDispatcher capture;
- keeps known thruster/lightning/ExtraAnimatedModel behavior live;
- preserves the pre-Palladium ItemStack snapshot lookup hot path;
- preserves distance culling and cached wall occlusion;
- keeps author / owner metadata as **PunctualBoat**.

Validation priorities:
1. confirm the black/top-screen flicker is gone;
2. face the same mixed-suit wall for 10–20 seconds;
3. verify `cache`, `build`, and then `hit` become nonzero and continue increasing;
4. verify `snap` rises materially above the previous 0–1.9%;
5. check transparent/glass suit layers while moving sideways so camera-dependent ordering can be observed;
6. compare FPS, GPU%, and allocation only after snapshots have warmed.

