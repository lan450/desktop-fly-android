package com.maltjuice.fly.core;

import com.maltjuice.fly.core.CircuitData.Bundle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Long-run behavior + timing check: 5 minutes of simulated closed loop on the
 * JVM. Verifies the state machine keeps moving (no deadlock/divergence) and
 * measures per-frame cost to sanity-check 60 fps on a phone CPU.
 */
public final class PerfTest {
    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0]
                : "/Users/maltjuice/Projects/desktop-fly/data");
        FlyMath.reset("android-perftest");
        Bundle data = CircuitData.parseAll(
                Files.readString(dataDir.resolve("brain_points.json")),
                Files.readString(dataDir.resolve("circuit.json")),
                Files.readString(dataDir.resolve("locomotor_circuit.json")));
        LIFSim sim = new LIFSim(data.circuit, new LocomotorSim(data.locomotor));
        SignalBuilder builder = new SignalBuilder();
        Fly fly = new Fly(0, 0);
        double dt = 1.0 / 60.0;
        double W = 393, H = 873;   // Pixel-class phone, dp — the real deployment arena
        fly.terrain.add(new Fly.Ledge(H / 2 - 60, -W / 2 + 60, W / 2 - 60, 1));
        fly.terrain.add(new Fly.Ledge(-(H / 2 - 60), -W / 2 + 60, W / 2 - 60, 2));
        sim.step(400);
        sim.consumeGF();

        // JIT warm-up: run 10 s of loop before timing, so worst-frame reflects
        // steady state rather than interpreter startup
        for (int f = 0; f < 600; f++) {
            sim.legFeedback = fly.legFeedback();
            sim.step((int) Math.round(dt * 1000));
            fly.update(dt, W, H, null, null, builder.make(sim, dt));
        }

        Map<Fly.State, Integer> timeIn = new EnumMap<>(Fly.State.class);
        int frames = 5 * 60 * 60;   // 5 simulated minutes
        double maxDist = 0;
        long t0 = System.nanoTime();
        double worstFrameMs = 0;
        for (int f = 0; f < frames; f++) {
            long ft0 = System.nanoTime();
            // periodic poke so wake/groom/escape paths get exercised
            if (f % 3600 == 1800) sim.stimulate(sim.sens, 0.4f, 130);
            sim.legFeedback = fly.legFeedback();
            sim.step((int) Math.round(dt * 1000));
            BrainSignals s = builder.make(sim, dt);
            fly.update(dt, W, H, null, null, s);
            long ft1 = System.nanoTime();
            worstFrameMs = Math.max(worstFrameMs, (ft1 - ft0) / 1e6);
            timeIn.merge(fly.state, 1, Integer::sum);
            maxDist = Math.max(maxDist, Math.hypot(fly.posx, fly.posy));
        }
        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("5 min closed loop: %.2fx realtime on this JVM (%.1f ms sim time / s), worst frame %.2f ms%n",
                300_000.0 / totalMs, totalMs / 300.0, worstFrameMs);
        System.out.printf("position bounded: max |r| = %.0f (screen diag/2 = %.0f)%n",
                maxDist, Math.hypot(W, H) / 2);
        for (Map.Entry<Fly.State, Integer> e : timeIn.entrySet()) {
            System.out.printf("  %-8s %5.1f%% (%d frames)%n", e.getKey(),
                    100.0 * e.getValue() / frames, e.getValue());
        }
        boolean moved = timeIn.size() >= 3;
        boolean bounded = maxDist < Math.hypot(W, H);
        boolean fastEnough = worstFrameMs < 8;   // phone budget ~16.7 ms/frame
        System.out.println(moved && bounded && fastEnough
                ? "PERF/BHEAVIOR PASS" : "PERF/BEHAVIOR FAIL");
        System.exit(moved && bounded && fastEnough ? 0 : 1);
    }
}
