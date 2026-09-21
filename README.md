# HeroStand — Development Handoff

## Project

HeroStand is a standalone Minecraft **Forge 1.20.1** mod for optimized superhero/modded armor displays, especially Palladium suits.

Repository: `PunctualBoat203/HeroStand`  
Author / owner: **PunctualBoat**  
Java: **17**  
Forge: **47.4.10**  
Current test build: **0.2.0**

## IMPORTANT — current development line

Active renderer rebuild branch:

`rebuild/0.2.0-native-snapshot`

0.2.0 is a **ground-up rendering/visual rebuild**. It is intentionally not another layer on top of the 0.1.10–0.1.19 renderer experiments.

The rebuild starts conceptually from the **0.1.8/0.1.9 behavior the user liked**, while replacing the Palladium visual renderer itself.

Known reference points:
- **0.1.8** user-provided JAR: original newer rendering reference.
- **0.1.9**: known baseline with configurable client/server render distance and working cached wall occlusion.
- **0.1.17**: Mark One visual issue confirmed fixed, but mixed-suit FPS still poor.
- **0.1.18**: roughly 49 FPS / 63% GPU / 476 MiB/s allocation in the mixed-suit wall.
- **0.1.19**: no meaningful improvement and may have been worse.
- **0.2.0**: clean renderer reset described below.

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

## 0.2.0 validation priorities

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
   - use the same viewpoint used for the 0.1.17–0.1.19 screenshots;
   - wait several seconds for snapshot warm-up;
   - record FPS, GPU %, and allocation rate;
   - repeated identical suits should share snapshots.

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

0.2.0 Actions reference:
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
