package com.maltjuice.fly.core;

import java.util.Random;

/**
 * Shared math helpers, translated from FlyModel.swift. lag() preserves the
 * desktop-fly frame-rate-independence fix: at dt = 1/60 it returns exactly
 * k/60, the value every behavior constant was tuned against.
 *
 * Randomness ports the Swift TestRandom (FNV-1a label -> LCG32) so JVM tests
 * are deterministic and can be diffed against the macOS reference bit-for-bit.
 * Without reset() the stream falls back to unseeded java.util.Random, exactly
 * like TestRandom's nil state falling back to SystemRandomNumberGenerator.
 * Two precision paths matter for reproducibility: rnd() is Double (Swift's
 * cgFloat path), rndF() is Float (Swift's float path) — used respectively by
 * behavior code and by the LIF noise/baseline draws.
 */
public final class FlyMath {
    /** Reference frame rate the constants were tuned at. */
    public static final double TUNED_HZ = 60;
    public static final double PI = Math.PI;
    /** Heading random-walk amplitude, rad/sqrt(s). */
    public static final double WANDER_JITTER = 1.6 / Math.sqrt(TUNED_HZ);
    public static final double LEDGE_JITTER = 0.2 / Math.sqrt(TUNED_HZ);
    /** Body-saccade kinematics (Geurten et al. 2014). */
    public static final double SACCADE_MIN = 0.09, SACCADE_MAX = 0.44, SACCADE_DUR = 0.09;
    public static final double SWING_DUR = 0.035;

    private static final Random FALLBACK = new Random();
    private static Integer lcgState = null;   // null = unseeded, like Swift's nil state

    private FlyMath() {}

    /** Seed the deterministic stream: FNV-1a over the label's UTF-16 units. */
    public static void reset(String label) {
        int seed = (int) 2166136261L;   // FNV-1a offset basis (0x811C9DC5)
        for (int i = 0; i < label.length(); i++) {
            seed = (seed ^ label.charAt(i)) * 16777619;   // int ops wrap mod 2^32
        }
        lcgState = seed;
    }

    /** One uniform in [0,1) from the active stream. */
    private static double unit() {
        if (lcgState == null) return FALLBACK.nextDouble();
        lcgState = lcgState * 1664525 + 1013904223;       // LCG32, wraps mod 2^32
        return (lcgState & 0xFFFFFFFFL) / 4294967296.0;
    }

    /** Uniform in [lo, hi], Double precision (Swift cgFloat path). */
    public static double rnd(double lo, double hi) { return lo + (hi - lo) * unit(); }

    /** Uniform in [lo, hi], Float precision (Swift float path). */
    public static float rndF(float lo, float hi) { return lo + (hi - lo) * (float) unit(); }

    /** Uniform in [0,1), Float precision. */
    public static float rndFloat() { return rndF(0f, 1f); }

    /** Integer in [lo, hiInclusive], Swift's min formula. */
    public static int rndInt(int lo, int hiInclusive) {
        int count = hiInclusive - lo + 1;
        if (lcgState == null) return lo + FALLBACK.nextInt(count);
        return lo + Math.min(count - 1, (int) (unit() * count));
    }

    /** Current LCG state (hex), for asserting the reference seed in tests. */
    public static long stateBits() { return lcgState == null ? -1 : lcgState & 0xFFFFFFFFL; }

    public static double lag(double k, double dt) {
        double perFrame = Math.min(1, k / TUNED_HZ);
        if (perFrame >= 1) return 1;
        return 1 - Math.pow(1 - perFrame, TUNED_HZ * dt);
    }

    public static double clampf(double v, double lo, double hi) { return Math.min(hi, Math.max(lo, v)); }

    public static double angleDiff(double from, double to) {
        double d = (to - from) % (2 * PI);
        if (d > PI) d -= 2 * PI;
        if (d < -PI) d += 2 * PI;
        return d;
    }

    public static double smoothstep(double t) {
        double x = clampf(t, 0, 1);
        return x * x * (3 - 2 * x);
    }
}
