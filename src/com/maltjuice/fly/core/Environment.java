package com.maltjuice.fly.core;

/**
 * Drosophila circadian activity: morning and evening peaks, midday siesta,
 * night quiescence. Returns a multiplier for the sim's baseline drive.
 * (circadianActivity in Environment.swift.)
 */
public final class Environment {
    private static final double[] HOURS = {0, 5, 8, 10, 13, 15, 17, 20, 23, 24};
    private static final float[] LEVELS = {0.25f, 0.25f, 1.0f, 1.0f, 0.55f, 0.55f, 1.0f, 1.0f, 0.3f, 0.25f};

    public static float circadianActivity(double hour) {
        for (int i = 0; i < HOURS.length - 1; i++) {
            if (hour >= HOURS[i] && hour <= HOURS[i + 1]) {
                double t = (hour - HOURS[i]) / Math.max(0.001, HOURS[i + 1] - HOURS[i]);
                return LEVELS[i] + (LEVELS[i + 1] - LEVELS[i]) * (float) t;
            }
        }
        return 0.25f;
    }

    private Environment() {}
}
