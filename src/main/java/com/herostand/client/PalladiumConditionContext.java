package com.herostand.client;

/**
 * Render-thread-only bridge used by HeroStand's optional Palladium mixin.
 *
 * The stored object is intentionally untyped so HeroStand keeps no hard runtime dependency on
 * Palladium. When Palladium is present the object is its DataContext for the reusable SuitStand.
 */
public final class PalladiumConditionContext {
    private static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();

    private static long reusedConditionChecks;
    private static long fallbackContextBuilds;
    private static long heroStandGeckoRenders;

    private PalladiumConditionContext() {}

    public static void begin(Object context) {
        CURRENT.set(context);
    }

    public static Object current() {
        return CURRENT.get();
    }

    public static void end() {
        CURRENT.remove();
    }

    public static void noteReuse(int checks) {
        reusedConditionChecks += Math.max(0, checks);
    }

    public static void noteFallbackBuild() {
        fallbackContextBuilds++;
    }

    /**
     * Counts concrete GeckoRenderLayer render attempts while HeroStand's shared
     * condition context is active. This is intentionally just a counter: 0.2.10
     * profiles the enclosing pack-render wall time without adding a timer to every
     * Gecko leaf render.
     */
    public static void noteHeroStandGeckoRender() {
        heroStandGeckoRenders++;
    }

    public static long reusedConditionChecks() {
        return reusedConditionChecks;
    }

    public static long fallbackContextBuilds() {
        return fallbackContextBuilds;
    }

    public static long heroStandGeckoRenders() {
        return heroStandGeckoRenders;
    }

    public static void resetStats() {
        reusedConditionChecks = 0L;
        fallbackContextBuilds = 0L;
        heroStandGeckoRenders = 0L;
        CURRENT.remove();
    }
}
