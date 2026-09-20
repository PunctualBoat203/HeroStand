# HeroStand

HeroStand is a lightweight Forge 1.20.1 suit-display mod built around a non-ticking block entity instead of a persistent armor stand or living entity.

## Design goals

- Four armor slots: helmet, chestplate, leggings, boots
- No persistent world entity for the display
- Safe from traditional `/kill @e` and entity-purge cleanup
- Smooth-diorite mannequin body with a clean iron base
- Mannequin body sections hide beneath equipped armor
- Safe equipment storage and drops
- Player-facing placement
- Standalone core with optional compatibility paths for modded armor/Palladium
- Low server overhead: no AI, health, movement, or block-entity ticker

Target: Minecraft 1.20.1 / Forge 47.x / Java 17.

Work in progress.