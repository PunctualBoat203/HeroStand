# HeroStand — Development Handoff

## Project
HeroStand is a standalone Minecraft **Forge 1.20.1** mod for optimized superhero/modded armor displays, especially Palladium suits.

Repository: `PunctualBoat203/HeroStand`  
Java: **17**  
Forge: **47.4.10**  
Current test line: **0.1.5**

## Core architecture
HeroStand must remain a **block + non-ticking block entity**, not a persistent ArmorStand/LivingEntity/world entity.

The block entity stores four equipment slots:
- Head
- Chest
- Legs
- Feet

The client renderer currently uses a reusable, temporary **client-only ArmorStand render context** to make modded/Palladium armor render through its normal equipment renderer. This object is never added to the world/entity list and does not tick server-side.

Important goals:
- No AI, health, movement, collision/entity ticking, or powers running on a fake wearer.
- Safe equipment persistence and multiplayer synchronization.
- Breaking the stand returns its equipment.
- Entity purge commands such as `/kill @e` cannot target the HeroStand itself.
- No hard dependency on Palladium, HeroClock, or OmniOptimizer.

## Current visual direction
Use the user-provided reference: a clean player-shaped superhero mannequin on a simple rectangular pedestal.

The user is now **happy with the mannequin/body being invisible by default**. Do not spend time restoring a visible white mannequin unless requested later. A suit can appear to stand on the pedestal by itself.

The base should remain simple, clean, light/white iron-like.

## Current state / confirmed fixes
Earlier builds rendered as Minecraft's purple/black missing model. The major cause was invalid block-model geometry extending to Y=34. The model was repaired to stay within valid baked-model bounds.

As of 0.1.5 (user-tested):
- Missing purple/black model issue was substantially improved/fixed in testing.
- Static mannequin geometry was removed from the equipped-suit path; pedestal remains.
- Client ArmorStand render context is reused instead of allocated every render call.
- Inventory persistence was hardened.
- Equipment drops were restricted to server side.
- Dedicated creative tab exists.
- Recipe is:
  ` D `
  ` D `
  `III`
  where D = polished diorite and I = iron block.
- Renderer view distance is 48 blocks.

## CURRENT BUG — highest priority
**Suit orientation is still wrong and currently jitters/vibrates.**

Latest user report from 0.1.5:
> stand being default invis is fine / preferred, but suit orientation is not fixed and it is “vibrating trying to fix itself.”

This must be fixed before adding new features.

Likely cause: the temporary ArmorStand's entity yaw/head/body rotations and/or the yaw supplied to `EntityRenderDispatcher.render(...)` are fighting each other. The renderer currently derives a yaw from the block's `FACING`, writes entity rotation fields, and also passes yaw into the dispatcher. A modded armor renderer may additionally read interpolated body/head rotation, producing oscillation or a 90° mismatch.

### Recommended next investigation
Do not keep guessing yaw offsets blindly.

1. Inspect Minecraft 1.20.1 ArmorStand/LivingEntity rendering rotation conventions.
2. Give the temporary render context a **stable rotation state** every frame:
   - YRot and yRotO
   - YHeadRot and yHeadRotO
   - yBodyRot and yBodyRotO where available
3. Avoid applying the same orientation twice. Prefer one stable entity orientation strategy and a fixed dispatcher yaw, or a PoseStack rotation strategy if that is more compatible with modded armor.
4. Verify all four block facings: north, east, south, west.
5. Ensure partial-tick interpolation cannot interpolate between stale rotations from the previously rendered HeroStand. The renderer reuses one ArmorStand context across multiple block entities, so **every current + previous rotation field must be overwritten before every render**. This is a particularly plausible source of the reported vibration when multiple stands/facings are rendered.
6. If Palladium reads player/LivingEntity-specific rotation state, keep that state deterministic too.

## Current renderer behavior
`HeroStandRenderer`:
- Reuses one client-only ArmorStand.
- Copies the HeroStand's four equipment slots into it.
- Sets it invisible, no base plate, arms shown.
- Computes yaw from block `FACING`.
- Renders via Minecraft's EntityRenderDispatcher.
- This compatibility path should be preserved if possible because the user's modded suit geometry renders successfully through it.

## Important design decisions
- User prefers the invisible mannequin effect now. Preserve it.
- Do **not** return to a giant static humanoid block JSON.
- Do not add persistent entities just to simplify rendering.
- Do not claim orientation is fixed until tested in-game.
- Keep the pedestal block model inside valid Minecraft model bounds.
- Continue using versioned test builds; next build should be **0.1.6**.
- Build through GitHub Actions and hand the user the compiled Forge JAR.
- Validate downloaded build artifact/JAR before handing it over.

## Recipe
3 iron blocks across the bottom row + 2 polished diorite vertically in the center:

```
 D 
 D 
III
```

## Future work after orientation is stable
Potential improvements, in rough dependency order:
- Verify Palladium/modded suit rendering across several suits.
- Better pedestal proportions/texture if requested.
- Optional poses/rotation controls.
- Frustum/distance culling already applies; consider cheap/cached occlusion only if profiling shows value.
- Never add per-frame raycasts or large entity scans.
- Further renderer allocation/state cleanup and profiling.

## Testing checklist for next build
Test:
- Empty stand: pedestal only is acceptable/preferred.
- Full suit: no mannequin geometry pokes through.
- Place stand facing each cardinal direction and verify suit faces exactly the same way.
- Watch suit for several seconds: **no vibration/jitter/rotation fighting**.
- Test two or more nearby stands facing different directions to catch reused-render-context interpolation bugs.
- Equip/remove each slot.
- Break equipped stand and verify each item drops once.
- Save/reload world and verify equipment persists.
- Multiplayer sync if available.
- Creative-tab icon/model remains valid (no purple/black missing model).

## User preference
Prioritize working/testable builds over speculative features. Fix rendering correctness first, then optimize and expand.
