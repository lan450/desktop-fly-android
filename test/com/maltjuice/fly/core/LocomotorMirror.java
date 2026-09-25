package com.maltjuice.fly.core;

import com.maltjuice.fly.core.CircuitData.Bundle;
import com.maltjuice.fly.core.CircuitData.LocomotorCircuitFile;
import com.maltjuice.fly.core.LegDynamics.LegBodyMotion;
import com.maltjuice.fly.core.LegDynamics.LegFeedback;
import com.maltjuice.fly.core.LegDynamics.LegMotorCommand;
import com.maltjuice.fly.core.LegDynamics.SixLegDynamics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JVM port of `DesktopFly --locomotortest` (LocomotorTests.swift): the causal
 * evaluation of the bundled cord graph and articulated body, plus the live
 * brain-to-body chain, legacy-bypass guard, thermal wiring, and the five
 * pose-continuity transition checks. The joint-continuity metric uses the
 * render pose triplet (hip/elevation/knee radians) instead of SceneKit
 * quaternions; thresholds carry over.
 */
public final class LocomotorMirror {
    static final double TICK = 1.0 / 120;
    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println((ok ? "PASS " : "FAIL ") + name + ": " + detail);
    }

    // ---- LocomotorTrial ----
    static final class Trial {
        double forward, lateral, yaw, lateDistance, lateForward, lateYaw;
        int[] contacts = new int[6];
        double[] ranges = new double[6];
        float[] motorRates = new float[6];
        int motorSpikes, sensorySpikes;
        boolean finite = true;
    }

    interface Configure { void apply(LocomotorSim cord); }

    static Trial evaluate(LocomotorCircuitFile circuit, float forwardHz, float leftHz,
                          float rightHz, float backwardHz, double onset,
                          double duration, double hz, Configure configure) {
        LocomotorSim cord = new LocomotorSim(circuit);
        if (configure != null) configure.apply(cord);
        SixLegDynamics body = new SixLegDynamics(Fly.desktopGeometries());
        cord.setDescending("DNp09", "left", forwardHz);
        cord.setDescending("DNp09", "right", forwardHz);
        Trial r = new Trial();
        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        double acc = 0, milliseconds = 0;
        int tickIndex = 0;
        int frames = (int) (duration * hz);
        for (int f = 0; f < frames; f++) {
            acc += 1 / hz;
            while (acc + 1e-10 >= TICK) {
                acc -= TICK;
                if (tickIndex * TICK + 1e-9 >= onset) {
                    for (String side : new String[]{"left", "right"}) {
                        cord.setDescending("DNa01", side, side.equals("left") ? leftHz : rightHz);
                        cord.setDescending("DNa02", side, side.equals("left") ? leftHz : rightHz);
                        cord.setDescending("MDN", side, backwardHz);
                    }
                }
                LegFeedback[] before = body.feedback();
                cord.feedback = before;
                milliseconds += TICK * 1000;
                int steps = (int) (milliseconds + 1e-6);
                milliseconds -= steps;
                cord.step(steps);
                LegBodyMotion motion = body.advance(cord.commands, TICK, true);
                r.forward += motion.forward;
                r.lateral += motion.lateral;
                r.yaw += motion.yaw;
                boolean late = tickIndex * TICK + 1e-9 >= 3;
                if (late) {
                    r.lateDistance += Math.hypot(motion.forward, motion.lateral);
                    r.lateForward += motion.forward;
                    r.lateYaw += motion.yaw;
                }
                LegFeedback[] after = body.feedback();
                for (int i = 0; i < 6; i++) {
                    if (late && after[i].contact && !before[i].contact) r.contacts[i]++;
                    if (late) {
                        lo[i] = Math.min(lo[i], after[i].kneeAngle);
                        hi[i] = Math.max(hi[i], after[i].kneeAngle);
                    }
                    r.finite = r.finite && Double.isFinite(after[i].kneeAngle)
                            && Double.isFinite(after[i].hipAngle)
                            && after[i].footHeight > -0.001 && Double.isFinite(after[i].footHeight);
                    r.motorRates[i] = (float) Math.max(r.motorRates[i], cord.meanRate("motor", i));
                }
                tickIndex++;
            }
        }
        for (int i = 0; i < 6; i++) r.ranges[i] = Math.max(0, hi[i] - lo[i]);
        r.motorSpikes = cord.motorSpikes;
        r.sensorySpikes = cord.sensorySpikes;
        return r;
    }

    static Trial evaluate(LocomotorCircuitFile c, Configure cfg) {
        return evaluate(c, 40, 0, 0, 0, 0, 8, 120, cfg);
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0]
                : "/Users/maltjuice/Projects/desktop-fly/data");
        FlyMath.reset("locomotortest");
        Bundle data = CircuitData.parseAll(
                Files.readString(dataDir.resolve("brain_points.json")),
                Files.readString(dataDir.resolve("circuit.json")),
                Files.readString(dataDir.resolve("locomotor_circuit.json")));
        var cordFile = data.locomotor;
        double W = 1512, H = 982;   // reference bounds, faithful to the Swift suite

        // 1. quiet without descending or sensory drive
        Trial rest = evaluate(cordFile, 0, 0, 0, 0, 0, 4, 120, c -> c.feedbackEnabled = false);
        check("quiet without drive", rest.motorSpikes == 0 && rest.lateDistance < 0.001,
                "motor spikes " + rest.motorSpikes + ", drift " + rest.lateDistance);

        // 2-3. recruitment and sustained walking
        Trial walking = evaluate(cordFile, null);
        StringBuilder rates = new StringBuilder();
        for (float v : walking.motorRates) rates.append(String.format("%.1f,", v));
        check("recruits motor neurons in all six legs",
                walking.motorRates[0] > 0.5 && walking.motorRates[1] > 0.5 && walking.motorRates[2] > 0.5
                        && walking.motorRates[3] > 0.5 && walking.motorRates[4] > 0.5 && walking.motorRates[5] > 0.5,
                "peak Hz " + rates);
        boolean contactsOk = true;
        for (int c : walking.contacts) contactsOk &= c >= 2;
        check("sustains walking after settling", walking.forward > 10 && walking.lateDistance > 10
                        && contactsOk && walking.finite,
                String.format("forward %.2f, late path %.2f, contacts %s", walking.forward,
                        walking.lateDistance, java.util.Arrays.toString(walking.contacts)));

        // 4. synapses cut
        Trial disconnected = evaluate(cordFile, c -> c.synapsesEnabled = false);
        check("cutting synapses abolishes response", disconnected.motorSpikes == 0
                        && disconnected.lateDistance < 0.001,
                "motor spikes " + disconnected.motorSpikes);

        // 5. motor lesion
        Trial lesion = evaluate(cordFile, c -> c.setSilenced(c.indices("motor", null), true));
        check("motor lesion abolishes propulsion", lesion.motorSpikes == 0
                        && lesion.lateDistance < 0.001,
                "late path " + lesion.lateDistance);

        // 6. bilateral steering
        Trial straight = evaluate(cordFile, 30, 0, 0, 0, 0, 10, 120, null);
        Trial left = evaluate(cordFile, 30, 70, 0, 0, 3, 10, 120, null);
        Trial right = evaluate(cordFile, 30, 0, 70, 0, 3, 10, 120, null);
        check("steering perturbations change yaw", left.lateYaw - straight.lateYaw > 0.10
                        && right.lateYaw - straight.lateYaw < -0.10,
                String.format("straight %+.3f, left %+.3f, right %+.3f rad",
                        straight.lateYaw, left.lateYaw, right.lateYaw));

        // 7. MDN backward stepping
        Trial backward = evaluate(cordFile, 0, 0, 0, 70, 0, 10, 120, null);
        check("MDN produces backward stepping", backward.lateForward < -5,
                String.format("late forward %+.2f units", backward.lateForward));

        // 8. feedback changes circuit activity
        Trial openLoop = evaluate(cordFile, c -> c.feedbackEnabled = false);
        check("feedback changes native activity", walking.sensorySpikes > 0
                        && walking.motorSpikes != openLoop.motorSpikes,
                "sensory " + walking.sensorySpikes + ", motor closed/open "
                        + walking.motorSpikes + "/" + openLoop.motorSpikes);

        // 9. 60/120 Hz independence
        Trial sixty = evaluate(cordFile, 40, 0, 0, 0, 0, 8, 60, null);
        boolean contactsEqual = true;
        for (int i = 0; i < 6; i++) contactsEqual &= sixty.contacts[i] == walking.contacts[i];
        check("independent of 60/120 Hz display",
                Math.abs(sixty.forward - walking.forward) < 1e-6 && contactsEqual
                        && sixty.motorSpikes == walking.motorSpikes,
                String.format("forward %.6f / %.6f, spikes %d / %d", sixty.forward,
                        walking.forward, sixty.motorSpikes, walking.motorSpikes));

        // 10. complete live brain-to-body chain
        FlyMath.reset("complete live brain-to-body chain sustains stepping");
        LIFSim sim = new LIFSim(data.circuit, new LocomotorSim(cordFile));
        SignalBuilder builder = new SignalBuilder();
        Fly fly = new Fly(0, 0);
        fly.state = Fly.State.WALKING; fly.speed = 0; fly.heading = 0;
        sim.stimulate(sim.fwd, 0.15f, 10_000);
        double appDistance = 0;
        int[] chainContacts = new int[6];
        LegFeedback[] previous = fly.legFeedback();
        for (int frame = 0; frame < 1200; frame++) {
            sim.legFeedback = fly.legFeedback();
            sim.step(frame % 3 == 2 ? 9 : 8);
            BrainSignals s = builder.make(sim, TICK);
            s.escape = false; s.groomDrive = 0; s.nervous = 0; s.arousal = 0;
            double oldX = fly.posx, oldY = fly.posy;
            fly.update(TICK, W, H, null, null, s);
            if (frame >= 360) {
                appDistance += Math.hypot(fly.posx - oldX, fly.posy - oldY);
                LegFeedback[] now = fly.legFeedback();
                for (int i = 0; i < 6; i++) {
                    if (now[i].contact != previous[i].contact) chainContacts[i]++;
                }
            }
            previous = fly.legFeedback();
        }
        boolean chainOk = appDistance > 20;
        for (int c : chainContacts) chainOk &= c >= 4;
        check("live brain-to-body chain sustains stepping", chainOk,
                String.format("late path %.2f, contacts %s", appDistance,
                        java.util.Arrays.toString(chainContacts)));

        // 11. legacy speed/turning cannot bypass motor silence
        FlyMath.reset("legacy speed and turning cannot bypass motor silence");
        Fly unpowered = new Fly(0, 0);
        unpowered.state = Fly.State.WALKING; unpowered.speed = 70; unpowered.heading = 0;
        BrainSignals zero = new BrainSignals();
        zero.walkDrive = 1; zero.turnBias = 1;
        zero.legCommands = new LegMotorCommand[6];
        for (int i = 0; i < 6; i++) zero.legCommands[i] = new LegMotorCommand();
        unpowered.update(1 / 60.0, W, H, null, null, zero);
        check("legacy drive cannot bypass motor silence",
                Math.hypot(unpowered.posx, unpowered.posy) < 1e-7 && Math.abs(unpowered.heading) < 1e-7,
                String.format("position (%.4f,%.4f), heading %.4f", unpowered.posx, unpowered.posy,
                        unpowered.heading));

        // 12. thermal tempo reaches active motor mechanics
        double thermalError = 0;
        for (double tempo : new double[]{0.5, 1, 2}) {
            FlyMath.reset("thermal tempo reaches active motor mechanics: " + tempo);
            Fly animal = new Fly(0, 0);
            animal.state = Fly.State.WALKING; animal.speed = 0;
            SixLegDynamics reference = new SixLegDynamics(Fly.desktopGeometries());
            BrainSignals s = new BrainSignals();
            s.walkDrive = 1; s.tempo = tempo;
            s.legCommands = new LegMotorCommand[6];
            for (int i = 0; i < 6; i++) {
                s.legCommands[i] = new LegMotorCommand();
                s.legCommands[i].retract = 0.3; s.legCommands[i].depress = 0.2; s.legCommands[i].flex = 0.2;
            }
            reference.advance(s.legCommands, TICK * tempo, true);
            animal.update(TICK, W, H, null, null, s);
            LegFeedback[] fb = animal.dynamics().feedback();
            LegFeedback[] ref = reference.feedback();
            for (int i = 0; i < 6; i++) {
                thermalError = Math.max(thermalError, Math.abs(fb[i].kneeAngle - ref[i].kneeAngle));
            }
        }
        check("thermal tempo reaches motor mechanics", thermalError < 1e-9,
                String.format("joint error %.10f", thermalError));

        // 13-17. pose continuity across state transitions
        transition(data, "groom and resume",
                new Phase[]{new Phase(120, 0, 1, false), new Phase(240, 1, 0, false)},
                new Fly.State[]{Fly.State.WALKING, Fly.State.GROOMING, Fly.State.IDLE, Fly.State.WALKING});
        transition(data, "idle sleep and wake",
                new Phase[]{new Phase(120, 0, 0, false), new Phase(120, 0, 0, true), new Phase(240, 1, 0, false)},
                new Fly.State[]{Fly.State.WALKING, Fly.State.IDLE, Fly.State.SLEEPING,
                        Fly.State.GROOMING, Fly.State.IDLE, Fly.State.WALKING});
        transition(data, "flight and landing",
                new Phase[]{new Phase(480, 0, 0, false), new Phase(180, 1, 0, false)},
                new Fly.State[]{Fly.State.WALKING, Fly.State.FLYING, Fly.State.IDLE, Fly.State.WALKING});
        transition(data, "nervous turn",
                new Phase[]{new Phase(120, 1, 0, false)}, new Fly.State[]{Fly.State.WALKING});
        transition(data, "ledge endpoint",
                new Phase[]{new Phase(120, 1, 0, false)}, new Fly.State[]{Fly.State.WALKING, Fly.State.FLYING});

        System.out.println(failures == 0 ? "ALL LOCOMOTOR MIRROR TESTS PASS"
                : failures + " LOCOMOTOR MIRROR FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    static final class Phase {
        final int ticks; final double walk, groom; final boolean sleep;
        Phase(int t, double w, double g, boolean s) { ticks = t; walk = w; groom = g; sleep = s; }
    }

    static void transition(Bundle data, String kind, Phase[] phases, Fly.State[] expected) {
        FlyMath.reset("active pose continuity: " + kind);
        LIFSim sim = new LIFSim(data.circuit, new LocomotorSim(data.locomotor));
        SignalBuilder builder = new SignalBuilder();
        Fly animal = new Fly(0, 0);
        animal.state = Fly.State.WALKING; animal.speed = 0; animal.heading = 0;
        sim.stimulate(sim.fwd, 0.15f, 20_000);
        double W = 1512, H = 982;
        int[] frame = {0};
        class Sig {
            BrainSignals make() {
                sim.legFeedback = animal.legFeedback();
                sim.step(frame[0] % 3 == 2 ? 9 : 8);
                frame[0]++;
                BrainSignals s = builder.make(sim, TICK);
                s.escape = false; s.nervous = 0; s.arousal = 0;
                s.groomDrive = 0; s.walkDrive = 1; s.backward = false;
                return s;
            }
        }
        Sig sig = new Sig();
        for (int i = 0; i < 360; i++) animal.update(TICK, W, H, null, null, sig.make());
        Fly.Ledge edge = null;
        if (kind.equals("ledge endpoint")) {
            for (int i = 0; i < 600 && animal.state != Fly.State.WALKING; i++) {
                animal.update(TICK, W, H, null, null, sig.make());
            }
            edge = new Fly.Ledge(0, -40, 40, 42);
            animal.terrain.add(edge);
            animal.ledge = edge;
            animal.posx = 39; animal.posy = 0; animal.heading = 0;
        }
        double[] oldPose = pose(animal);
        double[] oldToes = toes(animal);
        double oldHeading = animal.heading, oldPitch = animal.pitch;
        double jointJump = 0, toeJump = 0, headingJump = 0, pitchJump = 0, supportShift = 0;
        java.util.List<Fly.State> states = new java.util.ArrayList<>();
        states.add(animal.state);
        boolean endpointReversed = !kind.equals("ledge endpoint");
        boolean movedSupportTakeoff = !kind.equals("ledge endpoint");
        int tick = 0;
        for (Phase phase : phases) {
            for (int t = 0; t < phase.ticks; t++) {
                BrainSignals s = sig.make();
                s.walkDrive = phase.walk; s.groomDrive = phase.groom; s.sleep = phase.sleep;
                Double mouseX = null, mouseY = null;
                if (tick == 0 && (kind.equals("flight and landing") || kind.equals("nervous turn"))) {
                    mouseX = animal.posx + 180 * Math.cos(animal.heading);
                    mouseY = animal.posy + 180 * Math.sin(animal.heading);
                    s.escape = kind.equals("flight and landing");
                    s.nervous = kind.equals("nervous turn") ? 0.9 : 0;
                }
                if (kind.equals("ledge endpoint") && tick == 60) {
                    endpointReversed = animal.state == Fly.State.WALKING
                            && Math.abs(FlyMath.angleDiff(0, animal.heading)) > 0.5;
                    // Move an attached support at unchanged height: the fly must
                    // leave it, never clamp its x position onto the window.
                    animal.ledge = new Fly.Ledge(0, -40, 40, 42);
                    animal.terrain.clear();
                    animal.terrain.add(new Fly.Ledge(0, 360, 440, 42));
                }
                double oldX = animal.posx, oldY = animal.posy;
                animal.update(TICK, W, H, mouseX, mouseY, s);
                if (kind.equals("ledge endpoint") && tick == 60) {
                    supportShift = Math.hypot(animal.posx - oldX, animal.posy - oldY);
                    movedSupportTakeoff = animal.state == Fly.State.FLYING && supportShift < 1;
                }
                double[] nextPose = pose(animal), nextToes = toes(animal);
                for (int i = 0; i < 6; i++) {
                    for (int k = 0; k < 3; k++) {
                        jointJump = Math.max(jointJump, Math.abs(nextPose[i * 3 + k] - oldPose[i * 3 + k]));
                    }
                }
                for (int i = 0; i < 6; i++) {
                    for (int k = 0; k < 3; k++) {
                        toeJump = Math.max(toeJump, Math.abs(nextToes[i * 3 + k] - oldToes[i * 3 + k]));
                    }
                }
                headingJump = Math.max(headingJump, Math.abs(FlyMath.angleDiff(oldHeading, animal.heading)));
                pitchJump = Math.max(pitchJump, Math.abs(animal.pitch - oldPitch));
                if (states.get(states.size() - 1) != animal.state) states.add(animal.state);
                oldPose = nextPose; oldToes = nextToes;
                oldHeading = animal.heading; oldPitch = animal.pitch;
                tick++;
            }
        }
        int reached = 0;
        for (Fly.State st : states) {
            if (reached < expected.length && st == expected[reached]) reached++;
        }
        String supportDetail = kind.equals("ledge endpoint")
                ? String.format("; endpoint reversed %s, moved support takeoff %s, delta %.3f",
                        endpointReversed ? "yes" : "NO", movedSupportTakeoff ? "yes" : "NO", supportShift)
                : "";
        check(kind, reached == expected.length && endpointReversed && movedSupportTakeoff
                        && jointJump < 0.35 && toeJump < 3 && headingJump < 0.18 && pitchJump < 0.08,
                String.format("joint %.3f, toe %.3f, heading %.3f, pitch %.3f per tick; states %s%s",
                        jointJump, toeJump, headingJump, pitchJump, states, supportDetail));
    }

    /** Per-leg render pose triplet (hip, elevation, knee) — the Java analog of
     *  the SceneKit joint orientations checked by the reference suite. */
    static double[] pose(Fly fly) {
        double[] out = new double[18];
        for (int i = 0; i < 6; i++) {
            out[i * 3] = fly.legs[i].angle;
            out[i * 3 + 1] = fly.legs[i].lift;
            out[i * 3 + 2] = fly.legs[i].kneeAngle;
        }
        return out;
    }

    static double[] toes(Fly fly) {
        double[] out = new double[18];
        for (int i = 0; i < 6; i++) {
            double[] foot = Fly.fkFoot(fly.legs[i], fly.legs[i].angle,
                    fly.legs[i].lift, fly.legs[i].kneeAngle);
            out[i * 3] = foot[0]; out[i * 3 + 1] = foot[1]; out[i * 3 + 2] = foot[2];
        }
        return out;
    }
}
