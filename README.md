# HeroStand — Development Handoff

## Project
HeroStand is a standalone Minecraft **Forge 1.20.1** mod for optimized superhero/modded armor displays, especially Palladium suits.

Repository: `PunctualBoat203/HeroStand`  
Java: **17**  
Forge: **47.4.10**  
Current test build: **0.1.9**

## IMPORTANT — active development line
The current rendering work is **not on `main`**.

Active branch:
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

## Current build — 0.1.9
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
- Do not remove the 0.1.8/0.1.9 Palladium fast path unless deliberately debugging it.
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
For renderer changes, test against the **0.1.8 reference JAR** and current 0.1.9 build:

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
3. Prefer the active `optimize/0.1.7-palladium-culling` line until it is merged/superseded.
4. Do not silently substitute a `main` artifact.
5. Validate the downloaded artifact/JAR before delivery.

## User preference
Prioritize working/testable builds over speculative features.

Preserve the 0.1.8 rendering behavior as the reference point. Optimize or extend it incrementally, and treat regressions in suit visibility, Palladium rendering, orientation, or occlusion as higher priority than new features.
