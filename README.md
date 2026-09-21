# HeroStand — Development Handoff

## Project

HeroStand is a standalone Minecraft **Forge 1.20.1** mod for optimized superhero/modded armor displays, especially Palladium suits.

Repository: `PunctualBoat203/HeroStand`  
Author / owner: **PunctualBoat**  
Java: **17**  
Forge: **47.4.10**  
Current test build: **0.2.1**

## IMPORTANT — current development line

Active renderer rebuild branch:

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
- **0.2.1**: cache-hot-path correction + F3 diagnostics to measure whether complete suit snapshots are actually being used.

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
- thruster layers;
- lightning-spark layers;
- unknown/custom render-layer classes;
- ExtraAnimatedModel armor/layer models;
- textures/layers that still require true partial-alpha translucent sorting;
- any snapshot build that fails or cannot be classified safely.

This is intentional. Visual correctness wins over cache coverage.

### Alpha handling

Palladium commonly uses blended translucent RenderTypes even for armor textures that are effectively cutout/opaque.

For snapshot capture only:
- static texture resources are scanned once;
- textures containing only alpha 0 or 255 may use vanilla cutout/no-cull rendering;
- any alpha value 1–254 remains truly translucent;
- generated/dynamic textures that cannot be proven safe remain live.

True camera-relative translucent geometry is not stored in a reusable snapshot.

### Snapshot lifecycle / VRAM limits

The snapshot cache is bounded:
- maximum **192** complete suit/light snapshots;
- maximum **4 lighting variants per unique suit**;
- maximum **2 new snapshot builds per game tick** so a showroom warms progressively;
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

## 0.2.1 validation priorities

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

0.2.1 Actions reference:
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
