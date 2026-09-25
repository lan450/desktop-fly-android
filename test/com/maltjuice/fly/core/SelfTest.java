package com.maltjuice.fly.core;

import com.maltjuice.fly.core.CircuitData.Bundle;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * App-configuration regression test: the LIFSim WITH the MaleCNS cord
 * attached (what FlyView runs), driven end-to-end through SignalBuilder into
 * the Fly body on a phone-sized arena. The exact `--simtest` protocol lives
 * in SimTestMirror (no cord); this suite covers the wired app path. Seeded
 * via FlyMath.reset, so results are deterministic.
 */
public final class SelfTest {
    static int failures = 0;

    static void check(boolean ok, String label) {
        if (!ok) failures++;
        System.out.println((ok ? "PASS " : "FAIL ") + label);
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0]
                : "/Users/maltjuice/Projects/desktop-fly/data");
        FlyMath.reset("android-selftest");
        Bundle data = CircuitData.parseAll(
                Files.readString(dataDir.resolve("brain_points.json")),
                Files.readString(dataDir.resolve("circuit.json")),
                Files.readString(dataDir.resolve("locomotor_circuit.json")));
        check(data.circuit.neurons.length == 668
                        && data.locomotor.neurons.length == 1045,
                "circuit sizes: brain " + data.circuit.neurons.length
                        + " (want 668), cord " + data.locomotor.neurons.length + " (want 1045)");
        check(data.locomotor.validate(), "locomotor circuit validates");

        LIFSim sim = new LIFSim(data.circuit, new LocomotorSim(data.locomotor));
        check(sim.loomLeft.size() == 162 && sim.loomRight.size() == 152 && sim.gf.size() == 2
                        && sim.dnaL.size() == 2 && sim.dnaR.size() == 2 && sim.mdn.size() == 4
                        && sim.fwd.size() == 2 && sim.groom.size() == 6 && sim.escw.size() == 6
                        && sim.ascend.size() == 27 && sim.sens.size() == 16,
                "group sizes match the reference (162/152/2/2/2/4/2/6/6/27/16)");

        // no-cord configuration must not crash (the Swift --simtest shape)
        try {
            LIFSim cordless = new LIFSim(data.circuit, null);
            cordless.step(5);
            check(true, "no-cord step() survives (B1 regression)");
        } catch (Exception e) {
            check(false, "no-cord step() survives (B1 regression): " + e);
        }

        // settle, then a loom must fire the giant fiber even with the cord on
        sim.step(400);
        sim.consumeGF();
        int gfLoom = 0;
        for (int ms = 0; ms < 400; ms++) {
            sim.loomL = 1.0f;
            sim.loomR = 0.5f;
            sim.step(1);
            if (sim.consumeGF()) gfLoom++;
        }
        sim.loomL = 0; sim.loomR = 0;
        check(gfLoom > 0, "loom triggers GF with cord attached (" + gfLoom + " spikes)");
        sim.step(600);
        sim.consumeGF();

        // end-to-end: DNp09 burst walks the body on a phone-sized arena
        SignalBuilder builder = new SignalBuilder();
        Fly fly = new Fly(0, 0);
        double dt = 1.0 / 60.0;
        double W = 393, H = 873;   // Pixel-class phone, dp
        sim.stimulate(sim.fwd, 0.25f, 1200);
        boolean walked = false;
        double maxSpeed = 0;
        for (int frame = 0; frame < 90; frame++) {
            sim.legFeedback = fly.legFeedback();
            sim.step((int) Math.round(dt * 1000));
            BrainSignals s = builder.make(sim, dt);
            s.groomDrive = 0;   // isolate forward response, as the Swift test does
            fly.update(dt, W, H, null, null, s);
            maxSpeed = Math.max(maxSpeed, fly.speed);
            if (fly.state == Fly.State.WALKING && fly.speed > 5) walked = true;
        }
        System.out.printf("end-to-end DNp09 burst: state=%s speed peak %.0f pt/s%n",
                fly.state, maxSpeed);
        check(walked, "real motor cord walks the body (peak speed " + (int) maxSpeed + " pt/s)");

        // GF burst -> escape flight end-to-end
        Fly fly2 = new Fly(0, 0);
        sim.stimulate(sim.gf, 0.5f, 40);
        boolean escaped = false;
        for (int frame = 0; frame < 60; frame++) {
            sim.legFeedback = fly2.legFeedback();
            sim.step((int) Math.round(dt * 1000));
            BrainSignals s = builder.make(sim, dt);
            fly2.update(dt, W, H, null, null, s);
            if (fly2.state == Fly.State.FLYING) { escaped = true; break; }
        }
        check(escaped, "GF burst triggers escape flight end-to-end");

        System.out.println(failures == 0 ? "ALL SELFTEST (APP CONFIG) PASS"
                : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
