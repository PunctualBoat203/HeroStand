# HeroStand — Development Handoff

## Project
HeroStand is a standalone Minecraft **Forge 1.20.1** mod for optimized superhero/modded armor displays, especially Palladium suits.

Repository: `PunctualBoat203/HeroStand`  
Java: **17**  
Forge: **47.4.10**  
Current test build: **0.1.14**

## IMPORTANT — active development line
The current rendering work is **not on `main`**.

Active test branch:
`render/0.1.14-gpu-backends`

0.1.13 mixed-suit branch:
`optimize/0.1.13-static-palladium-cache`

0.1.12 compatibility branch:
`compat/0.1.12-suitstand-parent`

0.1.11 performance branch:
`optimize/0.1.11-direct-palladium-armor`

0.1.10 comparison branch:
`optimize/0.1.10-visible-path`

Draft validation PR: **#3**

Known-good 0.1.9 baseline branch:
`optimize/0.1.7-palladium-culling`

Relevant version points:
- **0.1.8** commit: `9af1dc0ec57c7d6b0287ba78f17ba5e8f1e5f99a`
- **0.1.9** branch head: `9438cb8f50b52ebf8834efb5977c56735f610076`
- **0.1.9** GitHub Actions build completed successfully.
- The user-provided **0.1.8 JAR is the rendering reference baseline**. Do not replace it with a build from stale `main`.

The default `main` branch currently contains the older 0.1.6 renderer generation. A JAR built from `main` was tested after 0.1.8 and the user reported that rendering became worse and behaved incorrectly around block occlusion. Do not hand out a `main` build as the latest HeroStand build unless the active rendering branch has first been merged or deliberately superseded.

## Core architecture
HeroStand must remain a **block + non-ticking block entity**, not a persistent ArmorStand/LivingEntity/world entity.

The block entity stores four equipment slots:
- Head
- Chest
- Legs
- Feet

Client-side rendering may use temporary/reusable ArmorStand render contexts, but these are never added to the world entity list and never tick server-side.

Important goals:
- No AI, health, movement, collision/entity ticking, or powers running on a fake wearer.
- Safe equipment persistence and multiplayer synchronization.
- Breaking the stand returns its equipment.
- Entity purge commands such as `/kill @e` cannot target the HeroStand itself.
- No hard dependency on Palladium, HeroClock, or OmniOptimizer.

## Current visual direction
The user prefers the mannequin/body to remain **invisible by default**. A suit should appear to stand on the pedestal by itself.

Do not restore a visible white mannequin unless specifically requested.

The base should remain simple, clean, and light/white iron-like.

## Rendering baseline — 0.1.8
0.1.8 is the current user-provided reference JAR for correct/newer rendering behavior.

Its renderer generation includes:
- A reusable client-only ArmorStand render context.
- Stable current + previous rotation state to avoid interpolation jitter.
- Facing applied once through the PoseStack rather than fighting entity/dispatcher yaw.
- A Palladium-specific fast path for compatible armor.
- Reflective Palladium integration so Palladium remains optional.
- Cached Palladium armor renderer metadata for static suit displays.
- A fallback through Minecraft's normal EntityRenderDispatcher for unsupported/non-Palladium armor.
- Cached block-occlusion visibility checks.
- A 21-block suit render distance in 0.1.8.
- Invisible mannequin / pedestal-only visual direction.

Relevant 0.1.8 commits:
- `82f3c06ddd3f3a328aee52de1bbca44e7bbed9e0` — optional Palladium fast render bridge
- `dcf77f9566836843158bb6819b1943539e2fd268` — Palladium fast path + cached occlusion culling
- `6f993b53fb5f2c4aca7814cca355dc048915ecd0` — prepare 0.1.7
- `a2c8d20a1bd2e4e2c436b691ea5671cbc7584edc` — match Palladium suit stand pose / reduce static render work
- `950798e807f836dbfbeb2c8b71691a9a5457ac3d` — cache Palladium renderer metadata
- `9af1dc0ec57c7d6b0287ba78f17ba5e8f1e5f99a` — prepare 0.1.8

## Current baseline — 0.1.9
0.1.9 continues from the 0.1.8 rendering path. It does **not** revert to the old 0.1.6 renderer.

Changes after 0.1.8 are primarily render-distance/configuration work:
- Client render-distance config.
- Client config registration.
- Server-authoritative maximum suit render distance.
- Server config registration.
- Client uses the lower of client render distance and server maximum.
- Version bumped to 0.1.9.

Relevant commits after 0.1.8:
- `88727fe91f419389d17be2fe50094006b4c41482` — configurable HeroStand render distance
- `ad99ea2a7124cc66c8701863b1d70e8c79a4a25a` — register client config
- `73cf15f102583cd0e447ed8b880f375be371985e` — use configurable suit render distance
- `669d9a801f92f7902a09c523da51fd414184bf7c` — server-authoritative render limits
- `e2e5ec0a5d412919d89c4830be4927e1ae851f22` — register server config
- `d4ca28b1e04a96c086853ccd901808b6649a1c34` — enforce server render-distance cap on clients
- `9438cb8f50b52ebf8834efb5977c56735f610076` — prepare 0.1.9

## 0.1.10 performance test build

0.1.10 is a regression-focused visible-stand performance pass built directly from the 0.1.9 renderer line. It intentionally preserves the distance cutoff, wall occlusion, Palladium transforms, orientation strategy, and fallback renderer.

Changes in 0.1.10:
- Reuse Palladium armor-slot `DataContext` objects for the reusable fast-path ArmorStand instead of allocating a new DataContext + HashMap for every equipped slot on every stand on every frame.
- Update only the mutable ITEM value in each cached Palladium DataContext before rendering the current stand.
- Cache per-item fast-path eligibility.
- Cache the last per-slot Palladium renderer metadata for repeated identical suits.
- Reduce repeated static clear-air visibility raycasts for already-visible stands.
- Stagger visibility refreshes by block position so a large wall of stands does not all raycast on the same tick.
- Keep occlusion refreshes fast when the camera moves, preserving wall hide/reveal behavior.
- Avoid one per-frame center Vec3 allocation in distance checks.
- Version bumped to 0.1.10.

Stress-test reference from the user:
- Many visible stands at once: approximately mid-80s FPS in 0.1.9.
- Beyond the configured render distance: approximately 118 FPS and stands correctly de-render.
- Behind a solid wall: approximately 118 FPS and stands correctly occlude.
- Therefore 0.1.10 must optimize only the visible path and must not weaken the working distance/wall culling behavior.

## 0.1.11 direct Palladium armor test

The user's 0.1.10 stress test remained around **80 FPS**, with no meaningful improvement over the roughly mid-80s FPS 0.1.9 result when many stands were directly visible. This strongly indicates that visibility bookkeeping and DataContext allocation were not the dominant cost.

0.1.11 therefore targets the actual armor draw setup:
- Keeps the 0.1.10 distance and wall-occlusion behavior unchanged.
- Keeps the same Palladium pack layers and suit transforms.
- For standard Palladium custom armor, bypasses the full HumanoidArmorLayer wrapper and directly follows Palladium's own ArmorRendererData model/texture path.
- Resolves the Palladium armor model/texture once per identical slot per render frame instead of once per visible stand.
- Copies the static neutral HeroStand pose to the resolved armor model once per frame/item/slot.
- Still applies normal slot visibility, Palladium's translucent armor RenderType, dye colors/overlay, packed light, and foil/glint.
- Detects Palladium Gecko armor and falls back to the established HumanoidArmorLayer path rather than forcing the direct renderer.
- If the direct path encounters an incompatible Palladium internal, it disables itself and falls back to the established renderer.
- Pack-render layers still render normally after the base armor.

Validation priority for 0.1.11:
- Compare FPS from the same many-visible-stands viewpoint used for 0.1.9/0.1.10.
- Confirm armor textures/models are visually identical.
- Confirm glow/transparency/glint/dyed armor if present.
- Confirm wall occlusion and render-distance de-render still behave exactly as before.
- If FPS is still near 80, the remaining bottleneck is primarily geometry/pack-layer vertex rendering rather than renderer setup.

## 0.1.12 suit-model compatibility test

While broad-testing additional suits on **0.1.10**, the user found one suit set whose helmet/head geometry renders far above the torso while the body remains correctly positioned. Most other tested suits render correctly, so this is a custom-model compatibility issue rather than a global HeroStand transform failure.

Root cause identified in the fast path:
- HeroStand used `ArmorStandArmorModel` as the parent model for Palladium armor/pack-layer property copying.
- Palladium's real `SuitStandRenderer` uses `SuitStandBasePlateModel`, which is built from normal `HumanoidModel.createMesh(...)` pivots.
- In Minecraft 1.20.1, `ArmorStandArmorModel` changes part positions (notably head Y=1 and legs Y=11), while normal humanoid/SuitStand pivots use head Y=0 and legs Y=12.
- Palladium's custom model path calls `HumanoidModel.copyPropertiesTo(...)`, which copies part positions as well as rotations. Custom authored suit geometry can therefore inherit the wrong parent pivots and become visibly detached.

0.1.12 changes:
- Uses a standard humanoid parent model with normal player/SuitStand pivots for Palladium fast-path armor and pack layers.
- Keeps the reusable ArmorStand entity only as the data/equipment context.
- Preserves the same PoseStack world transform, orientation, distance culling, wall occlusion, and Palladium layer rendering.
- Includes the 0.1.11 direct-armor performance experiment, but the primary purpose of 0.1.12 is compatibility/visual correctness.

Validation priority:
- Retest the suit set with the floating/detached helmet.
- Recheck several previously-correct suits to ensure their head/body/leg placement did not regress.
- Re-run the many-visible-stands FPS test.
- Confirm distance and solid-wall culling still work.

## 0.1.13 mixed-suit performance pass

A broader 0.1.10 test with many **different** superhero/Palladium suit sets exposed a substantially worse visible-render case:
- Approximately **55 FPS** with the large mixed-suit wall visible.
- GPU usage around **76%**.
- Allocation rate around **507 MiB/s**.
- This is materially worse than the roughly 80–85 FPS repeated/less-varied suit test.
- Distance culling and solid-wall occlusion still recover performance correctly, so those systems are not the primary bottleneck.

This changes the performance diagnosis: HeroStand must optimize Palladium's **per-layer visual-state resolution**, not just stand visibility or repeated identical-item lookups.

0.1.13 changes:
- Includes the 0.1.12 normal-humanoid parent-pivot compatibility fix for detached custom helmet geometry.
- Converts the hottest reflective Palladium calls from `Method.invoke(...)` varargs to typed `MethodHandle` calls, avoiding argument-array/primitive-boxing churn in the per-layer render loop.
- Keeps reusable Palladium `DataContext` objects per armor slot.
- Adds a dedicated static-layer accelerator for normal `PackRenderLayer`, `SkinOverlayPackRenderLayer`, and fully-supported compound layers.
- Reuses the existing slot DataContext when evaluating cached layer conditions instead of allowing Palladium helper paths to construct new DataContext/HashMap objects for each condition.
- Caches resolved model selector, dynamic texture result, tint, glint decision, and layer activation for roughly 2–3 seconds per item/NBT/slot variant, with staggered refreshes.
- Still runs the actual model pose setup and vertex emission every frame, so this is not a visual impostor/LOD shortcut.
- Unknown or genuinely dynamic layer types fall back to Palladium's original renderer unchanged.
- Base Palladium armor model/texture resolution is also cached across frames instead of only within one frame.
- Cached Palladium models have the HeroStand parent pose reapplied before every draw because Palladium shares model instances globally.

Why this specifically targets mixed suit types:
- Even a unique suit only needs its expensive model/texture/condition selection refreshed occasionally; it should not resolve the same static display state 50–120 times per second.
- Palladium condition helpers can allocate new DataContext/HashMap objects repeatedly. HeroStand now evaluates supported static-layer conditions against its already-reused DataContext.
- Different suit definitions still emit their own real geometry and textures, preserving appearance.

0.1.13 validation priorities:
- Reproduce the mixed-suit wall from the ~55 FPS / ~507 MiB/s screenshot.
- Compare **FPS, allocation rate, and GPU usage** after standing still for several seconds.
- Verify the previously detached helmet suit is aligned.
- Check several different suits for missing pack layers, wrong textures/tints, glint, or model variants.
- Check any suit with obviously animated thrusters/effects; unsupported dynamic layers must remain on Palladium's original path.
- Confirm distance de-render and solid-wall occlusion remain unchanged.

### 0.1.13 user result — failed performance/compatibility pass

The user tested the 0.1.13 mixed-suit wall and reported:
- roughly **40–50 FPS**
- roughly **83% GPU usage**
- roughly **471 MiB/s allocation rate**

That is not a useful FPS improvement and confirms that lookup/allocation caching alone is insufficient. The render path is now strongly limited by actual suit/layer drawing and GPU work when many different suits are visible.

The same test also corrected the previous compatibility diagnosis for one custom suit:
- the head remains detached/floating
- the **chest, legs, and boots are also rendered too small**
- the body should occupy the normal HeroStand/SuitStand humanoid silhouette

Do not describe 0.1.13 as a successful compatibility fix or performance fix.

## 0.1.14 GPU backend + soft-fail architecture

0.1.14 introduces a three-backend renderer instead of assuming one manual Palladium path is correct for every GPU and every suit.

Backends:
- **NVIDIA_FAST** — preferred first on NVIDIA. Uses HeroStand's aggressive direct armor + static Palladium layer acceleration.
- **AMD_BALANCED** — preferred first on AMD. Uses Palladium's normal armor-layer hooks and normal pack-layer rendering while retaining HeroStand's lightweight block-entity path.
- **GENERIC_NATIVE** — compatibility backend. Renders a reusable client-only Palladium SuitStand through Palladium's real SuitStandRenderer.

GPU routing:
- Detects OpenGL vendor/renderer at runtime.
- NVIDIA order: NVIDIA_FAST -> AMD_BALANCED -> GENERIC_NATIVE.
- AMD/Radeon order: AMD_BALANCED -> NVIDIA_FAST -> GENERIC_NATIVE.
- Unknown/other GPU order: GENERIC_NATIVE -> AMD_BALANCED -> NVIDIA_FAST.
- If a backend soft-fails, HeroStand immediately attempts the next backend for that stand.
- Repeatedly failing backends are quarantined for the renderer session instead of repeatedly throwing every frame.
- Distance culling and wall occlusion remain outside the backend system, so a renderer fallback cannot disable those proven optimizations.

Custom-suit correctness change:
- Palladium-backed rendering now uses a **real client-only Palladium SuitStand object as the render context** when Palladium is installed.
- It is never spawned into the world, never enters the entity list, and never ticks.
- This lets custom Palladium model selectors, DataContexts, model types, and pack layers see the entity type they were authored for instead of a plain ArmorStand.
- The manual parent model is explicitly reset to Palladium SuitStand's neutral humanoid pivots rather than running normal living-entity idle animation setup.
- GENERIC_NATIVE is the final correctness escape hatch and uses Palladium's actual SuitStandRenderer.

0.1.14 priorities:
- Retest the custom suit with the floating head and undersized chest/legs/boots.
- Retest the large mixed-suit wall and record FPS/GPU/allocation.
- Verify the log identifies NVIDIA on NVIDIA hardware and AMD/Radeon on AMD hardware.
- Verify a backend failure falls through instead of crashing the client.
- Verify distance culling and solid-wall occlusion remain unchanged.

Important: vendor-specific routing is an **ordering/tuning policy**, not a promise that NVIDIA or AMD require proprietary rendering code. Correctness must remain capability-based and the native compatibility backend must always remain available.

## Current renderer behavior
`HeroStandRenderer` on the active branch:
- Skips rendering when the stand has no armor.
- Uses a Palladium fast path when every equipped item supports it.
- Uses a normal EntityRenderDispatcher fallback otherwise.
- Copies the four equipment slots into reusable ArmorStand render contexts.
- Pins YRot/YHeadRot/yBodyRot/XRot and their previous values to deterministic zero state.
- Applies block `FACING` once through the PoseStack.
- Mirrors Palladium's SuitStand model transforms in the fast path.
- Uses several visibility sample points for cached block-occlusion checks.
- Caches visible/occluded results briefly and refreshes more quickly when the camera moves.
- Uses an effective render distance controlled by client config and capped by server config in 0.1.9.

`PalladiumRenderBridge`:
- Accesses Palladium reflectively.
- Keeps HeroStand loadable when Palladium is absent.
- Caches Palladium renderer/layer metadata per registry Item.
- Renders armor pack layers for the static display.
- Does not intentionally run player powers/AI/world entity behavior.

## Regression warning
Do **not** treat the stale 0.1.5/0.1.6 README state on old commits as current guidance.

In particular:
- Do not rebuild from `main` and call that the latest JAR.
- Do not remove the 0.1.8/0.1.9/0.1.10 Palladium fast path unless deliberately debugging it.
- Do not remove cached occlusion behavior without an explicit replacement/test.
- Do not reintroduce double-applied entity + dispatcher yaw.
- Do not claim a renderer change is better until compared in-game against the user's 0.1.8 reference JAR.

## Known history
Earlier builds had:
- Purple/black missing-model problems caused by invalid block-model geometry extending beyond valid baked-model bounds.
- A visible/static mannequin approach that is no longer wanted.
- Suit orientation mismatch and interpolation vibration.
- Renderer state reuse issues caused by stale current/previous rotations.

Those issues led to the stabilized rotation strategy and then the newer 0.1.8 Palladium/occlusion renderer line.

## Recipe
3 iron blocks across the bottom row + 2 polished diorite vertically in the center:

```
 D
 D
III
```

D = polished diorite  
I = iron block

## Testing checklist
For renderer changes, test against the **0.1.8 reference JAR**, 0.1.9 baseline, 0.1.10/0.1.11/0.1.12/0.1.13 results, and current 0.1.14 GPU-backend build:

- Empty stand: pedestal only is acceptable/preferred.
- Full Palladium suit renders completely.
- Non-Palladium/unsupported armor still uses the fallback path.
- No mannequin geometry pokes through.
- North/east/south/west facings match the block exactly.
- No vibration or interpolation fighting over several seconds.
- Two or more nearby stands facing different directions remain stable.
- Suits do not disappear incorrectly when partly visible around block edges.
- Fully hidden suits are culled as intended.
- Camera movement does not leave a stale occlusion result for an excessive time.
- Client/server render-distance cap behaves as configured.
- Equip/remove each slot.
- Break equipped stand and verify each item drops once.
- Save/reload and verify equipment persists.
- Multiplayer sync if available.
- Creative-tab icon/model remains valid.

## Build / artifact handoff
Build through GitHub Actions and hand the user the compiled Forge JAR.

Before handing over a JAR:
1. Confirm the build came from the intended branch/commit.
2. Confirm the embedded mod version.
3. Use `render/0.1.14-gpu-backends` for the current GPU/failover test artifact; keep `optimize/0.1.13-static-palladium-cache` as the failed 0.1.13 comparison, `compat/0.1.12-suitstand-parent` for compatibility history, and `optimize/0.1.7-palladium-culling` as the 0.1.9 baseline.
4. Do not silently substitute a `main` artifact.
5. Validate the downloaded artifact/JAR before delivery.

## User preference
Prioritize working/testable builds over speculative features.

Preserve the 0.1.8 rendering behavior as the reference point. Optimize or extend it incrementally, and treat regressions in suit visibility, Palladium rendering, orientation, or occlusion as higher priority than new features.
