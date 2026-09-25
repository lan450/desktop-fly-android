package com.maltjuice.fly.core;

import com.maltjuice.fly.core.CircuitData.Bundle;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bit-faithful JVM port of `DesktopFly --simtest` (main.swift runSimtest):
 * no nerve cord attached, TestRandom seeded with "desktop-fly-tests", all
 * seven phases in the same order (gaitDrive lingers after phase 3, exactly
 * like the reference). Baseline numbers from the macOS reference run are
 * asserted alongside the pass criteria; sin() libm ulp differences may flip
 * at most a sample or two in the gait-driven phases, so those get ±1
 * tolerance while GF spike counts are asserted exact.
 */
public final class SimTestMirror {
    static int failures = 0;

    static void check(boolean ok, String label) {
        if (!ok) failures++;
        System.out.println((ok ? "PASS " : "FAIL ") + label);
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0]
                : "/Users/maltjuice/Projects/desktop-fly/data");
        FlyMath.reset("desktop-fly-tests");
        Bundle data = CircuitData.parseAll(
                Files.readString(dataDir.resolve("brain_points.json")),
                Files.readString(dataDir.resolve("circuit.json")),
                Files.readString(dataDir.resolve("locomotor_circuit.json")));
        LIFSim sim = new LIFSim(data.circuit, null);   // Swift: LIFSim(circuit:spikeBus:)

        // Phase 1: 4 s spontaneous activity
        int gfSpont = 0;
        for (int i = 0; i < 40; i++) {
            sim.step(100);
            if (sim.consumeGF()) gfSpont++;
        }
        float popHz = sim.totalSpikes / 4.0f / sim.n;
        System.out.printf("spontaneous 4s: pop %.2f Hz/neuron, LC %.1f Hz, DNa02 L/R %.1f/%.1f Hz, MDN %.1f Hz, GF spikes: %d%n",
                popHz, sim.rateLoom, sim.rateDNaL, sim.rateDNaR, sim.rateMDN, gfSpont);
        check(gfSpont == 0, "spontaneous: GF silent (want 0, baseline 0)");
        check(Math.abs(popHz - 5.21f) < 0.15f, "spontaneous: pop " + String.format("%.2f", popHz) + " (baseline 5.21)");

        // Phase 2: abrupt loom, as produced by a cursor lunge (step, not ramp)
        int gfLatencyMs = -1, gfLoom = 0;
        for (int ms = 0; ms < 400; ms++) {
            sim.loomL = 1.0f;
            sim.loomR = 0.5f;
            sim.step(1);
            if (sim.consumeGF()) {
                gfLoom++;
                if (gfLatencyMs < 0) gfLatencyMs = ms;
            }
        }
        sim.loomL = 0; sim.loomR = 0;
        System.out.printf("abrupt loom 0.4s: LC %.1f Hz, GF spikes %d, first at %d ms%n",
                sim.rateLoom, gfLoom, gfLatencyMs);
        check(gfLoom == 2 && gfLatencyMs == 4, "loom: GF 2 spikes, first 4 ms (baseline 2 @ 4 ms)");

        // Phase 3: 20 s with walking proprioception; do behavior states emerge?
        int walkOn = 0, groomOn = 0, samples = 0;
        float fwdMin = Float.MAX_VALUE, fwdMax = 0;
        for (int ms = 0; ms < 20_000; ms++) {
            sim.gaitDrive = 0.5f;
            sim.gaitPhase = (ms % 125) / 125f;   // 8 Hz gait
            sim.step(1);
            if (ms % 10 == 0) {
                samples++;
                if (sim.rateFwd / 10 > 0.22) walkOn++;
                if (sim.rateGroom / 8 > 0.5) groomOn++;
                fwdMin = Math.min(fwdMin, sim.rateFwd); fwdMax = Math.max(fwdMax, sim.rateFwd);
            }
        }
        System.out.printf("behavior 20s: walk-drive on %d%%, groom-drive on %d%%, DNp09 %.1f-%.1f Hz, pop %.1f Hz%n",
                100 * walkOn / samples, 100 * groomOn / samples, fwdMin, fwdMax, sim.ratePop);
        check(Math.abs(100 * walkOn / samples - 42) <= 1, "behavior: walk-on " + (100 * walkOn / samples)
                + "% (baseline 42%, ±1 sample for libm sin ulp)");
        check(Math.abs(100 * groomOn / samples - 11) <= 1, "behavior: groom-on " + (100 * groomOn / samples) + "% (baseline 11%)");

        // Phase 3b: midday siesta must slow the fly down, not paralyze it
        sim.activityScale = 1 - (1 - 0.55f) * 0.35f;   // 0.84, the compressed siesta scale
        int siestaWalkOn = 0, siestaSamples = 0;
        for (int ms = 0; ms < 15_000; ms++) {
            sim.step(1);
            if (ms % 10 == 0) {
                siestaSamples++;
                if (sim.rateFwd / 10 > 0.22) siestaWalkOn++;
            }
        }
        sim.activityScale = 1;
        int siestaPct = 100 * siestaWalkOn / siestaSamples;
        System.out.println("siesta 15s (scale 0.84): walk-drive on " + siestaPct + "%");
        check(Math.abs(siestaPct - 17) <= 1, "siesta: walk-on " + siestaPct + "% (baseline 17%)");

        // Phase 4: air puff (fast cursor whoosh) for 1 s — wind startle pathway
        int gfPuff = 0;
        for (int i = 0; i < 1000; i++) {
            sim.airPuff = 1.0f;
            sim.step(1);
            if (sim.consumeGF()) gfPuff++;
        }
        sim.airPuff = 0;
        System.out.println("air puff 1s: GF spikes " + gfPuff);
        check(gfPuff == 13, "air puff: GF " + gfPuff + " (baseline 13)");

        // Phase 5: gentle left-eye-only loom 1 s — steering response probe
        for (int i = 0; i < 500; i++) { sim.step(1); sim.consumeGF(); }   // settle
        float diff0 = sim.rateDNaL - sim.rateDNaR;
        for (int i = 0; i < 1000; i++) {
            sim.loomL = 0.30f; sim.loomR = 0;
            sim.step(1);
            sim.consumeGF();
        }
        float diff1 = sim.rateDNaL - sim.rateDNaR;
        sim.loomL = 0;
        System.out.printf("left-eye loom: DNa L-R rate diff %+.1f -> %+.1f Hz, LC %.1f Hz%n",
                diff0, diff1, sim.rateLoom);
        check(Math.abs(diff1 - (-3.0f)) < 0.2f, "left-eye loom: diff " + String.format("%+.1f", diff1) + " (baseline -3.0)");

        // Phase 6: click-stimulation probes (what the interactive brain window does)
        sim.stimulate(sim.gf, 0.5f, 40);
        sim.step(60);
        boolean gfStim = sim.consumeGF();
        sim.stimulate(sim.groom, 0.25f, 400);
        sim.step(400);
        float groomStim = sim.rateGroom;
        System.out.printf("click probes: GF cluster -> spike %s, DNg11 cluster -> groom rate %.0f Hz%n",
                gfStim ? "yes" : "NO", groomStim);
        check(gfStim, "click: GF cluster spikes");
        check(Math.abs(groomStim - 193) < 3, "click: groom rate " + String.format("%.0f", groomStim) + " (baseline 193)");

        boolean pass = gfSpont == 0 && gfLoom > 0 && walkOn > 0 && gfStim && siestaPct > 3;
        check(pass, "simtest pass criteria (Swift formula)");
        System.out.println(failures == 0 ? "SIMTEST MIRROR: ALL BASELINES MATCH"
                : failures + " BASELINE MISMATCHES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
