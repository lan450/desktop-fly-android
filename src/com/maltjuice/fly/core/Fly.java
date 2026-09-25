package com.maltjuice.fly.core;

import com.maltjuice.fly.core.LegDynamics.LegBodyMotion;
import com.maltjuice.fly.core.LegDynamics.LegFeedback;
import com.maltjuice.fly.core.LegDynamics.LegGeometry;
import com.maltjuice.fly.core.LegDynamics.LegMotorCommand;
import com.maltjuice.fly.core.LegDynamics.SixLegDynamics;

import java.util.ArrayList;
import java.util.List;

import static com.maltjuice.fly.core.FlyMath.PI;
import static com.maltjuice.fly.core.FlyMath.SACCADE_DUR;
import static com.maltjuice.fly.core.FlyMath.SACCADE_MAX;
import static com.maltjuice.fly.core.FlyMath.SACCADE_MIN;
import static com.maltjuice.fly.core.FlyMath.SWING_DUR;
import static com.maltjuice.fly.core.FlyMath.WANDER_JITTER;
import static com.maltjuice.fly.core.FlyMath.angleDiff;
import static com.maltjuice.fly.core.FlyMath.clampf;
import static com.maltjuice.fly.core.FlyMath.lag;
import static com.maltjuice.fly.core.FlyMath.rnd;
import static com.maltjuice.fly.core.FlyMath.smoothstep;

/**
 * Procedural fruit-fly body + per-fly behavior, translated from FlyModel.swift.
 * The 3D SceneKit nodes are replaced by plain render state (angles, altitude,
 * wing phase) that a 2D top-down canvas draws; every behavior decision and
 * every physical constant is unchanged.
 */
public final class Fly {

    public static final class Ledge {
        public final double y, x0, x1;
        public final int id;
        public Ledge(double y, double x0, double x1, int id) {
            this.y = y; this.x0 = x0; this.x1 = x1; this.id = id;
        }
    }

    public enum State { WALKING, IDLE, GROOMING, FLYING, SLEEPING }

    public static final double FLY_SCALE = 1.15;
    public static final double EDGE_MARGIN = 50;

    /** One leg: geometry + gait identity + the render pose the canvas reads. */
    public static final class Leg {
        public final LegGeometry geometry;
        public final double baseYaw, swingSign, phase;
        public final boolean isFront;
        public double angle, lift, kneeAngle = 0.75;

        Leg(LegGeometry geometry, double baseYaw, double swingSign, double phase, boolean isFront) {
            this.geometry = geometry; this.baseYaw = baseYaw;
            this.swingSign = swingSign; this.phase = phase; this.isFront = isFront;
        }

        void apply() {}

        void apply(LegFeedback f) {
            angle = f.hipAngle;
            lift = f.elevationAngle;
            kneeAngle = f.kneeAngle;
        }
    }

    public final Leg[] legs;
    private final SixLegDynamics legDynamics;
    private LegFeedback[] sensedLegFeedback = new LegFeedback[0];
    public LegFeedback[] legFeedback() {
        return sensedLegFeedback.length == legs.length ? sensedLegFeedback : legDynamics.feedback();
    }
    private boolean motorWalking = false;
    private State renderedLegState = null;
    private boolean renderedMotorControl = false;
    private double[] blendFromHip, blendFromLift, blendFromKnee;
    private double legBlendTime = 0;
    private Double turnTarget = null;
    private double turnTargetTime = 0, turnVelocity = 0;
    private Double ledgeHeading = null;
    private double wingFlightAmount = 0;

    public double posx, posy;
    public double heading = rnd(0, 2 * PI);
    public double speed = 30;
    public State state = State.WALKING;
    public double stateTimer = rnd(1.5, 4);
    public double gaitPhase = rnd(0, 1);
    public double time = rnd(0, 100);
    public double scareCooldown = 0, dartCooldown = 0, backwardTimer = 0;
    private double saccade = 0, saccadeRate = 0;
    public double dartTimer = 0, stateAge = 0;
    public List<Ledge> terrain = new ArrayList<>();
    public Ledge ledge = null;

    public double getGaitPhase() { return gaitPhase; }
    public double walkingIntensity() {
        return state == State.WALKING ? clampf(Math.abs(backwardTimer > 0 ? 22 : speed) / 60, 0, 1) : 0;
    }

    public double flightFromX = 0, flightFromY = 0;
    public double flightToX = 0, flightToY = 0;
    public double flightT = 0, flightDur = 1;
    public double flightEffort = 0.6, effortCurrent = 0.6;
    public double alt = 0;           // 0 ground .. 1 max altitude
    public double pitch = 0;         // body pitch while climbing/descending
    public double flapPhase = 0;
    public double wingRaise = 0;     // grounded threat posture
    public double bodyZ = 0;         // render height (walk bob / flight altitude)
    private boolean brainLive = false;
    private double liveArousal = 0, liveWing = 0;

    public Fly(double x, double y) {
        this(x, y, desktopGeometries());
    }

    /** Leg geometry spec table from buildFlyModel(): side, attach(x,y,z), yawOff,
     *  phase, isFront, femur, tibia, tarsus — RF, LF, RM, LM, RH, LH. */
    static LegGeometry[] desktopGeometries() {
        double[][] specs = {
                { 1,  3.1,  5.3, 4.5,  0.95, 0.0, 1, 4.2, 4.8, 3.2},
                {-1, -3.1,  5.3, 4.5,  0.95, 0.5, 1, 4.2, 4.8, 3.2},
                { 1,  3.7,  2.0, 4.5, -0.10, 0.5, 0, 4.8, 5.6, 3.8},
                {-1, -3.7,  2.0, 4.5, -0.10, 0.0, 0, 4.8, 5.6, 3.8},
                { 1,  3.3, -1.2, 4.5, -0.95, 0.0, 0, 5.8, 7.0, 4.6},
                {-1, -3.3, -1.2, 4.5, -0.95, 0.5, 0, 5.8, 7.0, 4.6},
        };
        LegGeometry[] geometries = new LegGeometry[6];
        for (int i = 0; i < 6; i++) {
            double[] s = specs[i];
            double side = s[0];
            double baseYaw = side > 0 ? s[4] : (PI - s[4]);
            geometries[i] = new LegGeometry(s[1], s[2], s[3], baseYaw, side, s[7], s[8], s[9]);
        }
        return geometries;
    }

    private Fly(double x, double y, LegGeometry[] geometries) {
        // attach/yaw/phase/isFront per the same spec table, sharing geometry
        // objects between the rendered legs and the physics body
        double[][] specs = {
                { 1,  3.1,  5.3, 4.5,  0.95, 0.0, 1},
                {-1, -3.1,  5.3, 4.5,  0.95, 0.5, 1},
                { 1,  3.7,  2.0, 4.5, -0.10, 0.5, 0},
                {-1, -3.7,  2.0, 4.5, -0.10, 0.0, 0},
                { 1,  3.3, -1.2, 4.5, -0.95, 0.0, 0},
                {-1, -3.3, -1.2, 4.5, -0.95, 0.5, 0},
        };
        legs = new Leg[6];
        for (int i = 0; i < 6; i++) {
            double[] s = specs[i];
            double side = s[0];
            double baseYaw = side > 0 ? s[4] : (PI - s[4]);
            legs[i] = new Leg(geometries[i], baseYaw, side, s[5], s[6] == 1);
        }
        legDynamics = new SixLegDynamics(geometries);
        LegFeedback[] initial = legDynamics.feedback();
        for (int i = 0; i < 6; i++) legs[i].apply(initial[i]);
        sensedLegFeedback = new LegFeedback[0];
        posx = x; posy = y;
    }

    /** Physics body accessor for regression tests (thermal wiring, pose checks). */
    public SixLegDynamics dynamics() { return legDynamics; }

    public void startFlight(double boundsW, double boundsH, Double threatX, Double threatY,
                            boolean escape, Double effortOverride) {
        setState(State.FLYING);
        ledge = null;
        ledgeHeading = null;
        turnTarget = null;
        flightEffort = clampf(effortOverride != null ? effortOverride
                : (escape ? 1.0 : rnd(0.4, 0.75)), 0.25, 1);
        effortCurrent = flightEffort;
        flightFromX = posx; flightFromY = posy;
        double hw = boundsW / 2 - EDGE_MARGIN, hh = boundsH / 2 - EDGE_MARGIN;
        double tx = 0, ty = 0;
        boolean chosen = false;
        // casual flights often land on a ledge
        if (!escape && threatX == null && !terrain.isEmpty() && rnd(0, 1) < 0.45) {
            Ledge L = terrain.get(FlyMath.rndInt(0, terrain.size() - 1));
            if (L.x1 - L.x0 > 90) {
                tx = rnd(L.x0 + 25, L.x1 - 25);
                ty = L.y;
                chosen = Math.hypot(tx - posx, ty - posy) > 180;
            }
        }
        if (!chosen) {
            for (int attempt = 0; attempt < 16; attempt++) {
                tx = rnd(-hw, hw); ty = rnd(-hh, hh);
                boolean far = Math.hypot(tx - posx, ty - posy) > (escape ? 350 : 260);
                if (!far) continue;
                if (threatX != null) {
                    double toTx = tx - posx, toTy = ty - posy;
                    double toAx = threatX - posx, toAy = threatY - posy;
                    if (toTx * toAx + toTy * toAy > 0) continue;
                }
                break;
            }
        }
        flightToX = tx; flightToY = ty;
        double dist = Math.hypot(tx - posx, ty - posy);
        flightDur = escape ? clampf(dist / 650, 0.45, 1.2) : clampf(dist / 420, 0.7, 2.0);
        flightT = 0;
        scareCooldown = escape ? 2.0 : 2.5;
    }

    private void land() {
        setState(State.IDLE);
        stateTimer = rnd(0.3, 0.8);
        speed = 0;
        alt = 0;
        pitch = 0;
        bodyZ = 0;
    }

    private void startSaccade() {
        saccade = (rnd(0, 1) < 0.5 ? -1 : 1) * rnd(SACCADE_MIN, SACCADE_MAX);
        saccadeRate = saccade / SACCADE_DUR;
    }

    private void stepSaccade(double dt) {
        if (saccade == 0) return;
        double step = saccadeRate * dt;
        if (Math.abs(step) >= Math.abs(saccade)) {
            heading += saccade;
            saccade = 0;
        } else {
            heading += step;
            saccade -= step;
        }
    }

    private void turnToward(double target, double dt) {
        double error = angleDiff(heading, target);
        double desired = clampf(error * 16, -8, 8);
        turnVelocity += clampf(desired - turnVelocity, -60 * dt, 60 * dt);
        double step = turnVelocity * dt;
        if (step * error >= 0 && Math.abs(step) >= Math.abs(error)) {
            heading += error;
            turnVelocity = 0;
        } else {
            heading += step;
        }
    }

    private void prepareMotorControl(double tempo) {
        if (!motorWalking) {
            legDynamics.adoptPose(legFeedback(), true, 1 / tempo);
        }
        motorWalking = true;
    }

    private void pickNextState() {
        double r = rnd(0, 1);
        switch (state) {
            case WALKING:
                if (r < 0.30) { state = State.IDLE; stateTimer = rnd(0.8, 3); speed = 0; }
                else if (r < 0.55) {
                    stateTimer = rnd(0.3, 0.8); speed = rnd(95, 150); startSaccade();
                } else { stateTimer = rnd(1.5, 5); speed = rnd(18, 45); }
                break;
            case IDLE:
                if (r < 0.35) { state = State.GROOMING; stateTimer = rnd(1.0, 2.5); }
                else { state = State.WALKING; stateTimer = rnd(1.5, 5); speed = rnd(18, 45); startSaccade(); }
                break;
            case GROOMING:
                state = State.IDLE; stateTimer = rnd(0.3, 1.0);
                break;
            default:
                break;
        }
    }

    public void update(double dt, double boundsW, double boundsH,
                       Double mouseX, Double mouseY, BrainSignals signals) {
        time += dt;
        scareCooldown = Math.max(0, scareCooldown - dt);
        dartCooldown = Math.max(0, dartCooldown - dt);
        backwardTimer = Math.max(0, backwardTimer - dt);
        stateAge += dt;
        dartTimer = Math.max(0, dartTimer - dt);
        turnTargetTime = Math.max(0, turnTargetTime - dt);
        if (turnTargetTime == 0) turnTarget = null;

        brainLive = signals != null;
        liveArousal = signals != null ? signals.arousal : 0;
        liveWing = signals != null ? signals.wingDrive : 0;
        double tempo = signals != null ? signals.tempo : 1;
        double motorTempo = Double.isFinite(tempo) ? clampf(tempo, 0.5, 2) : 1;
        double motorDT = dt * motorTempo;

        if (state == State.FLYING) {
            saccade = 0;   // airborne heading is geometric, not a walk saccade
            updateFlight(dt);
        } else if (signals != null) {
            if (signals.legCommands == null) stepSaccade(dt);
            brainBehavior(signals, dt, boundsW, boundsH, mouseX, mouseY);
            if (state == State.WALKING) {
                if (signals.legCommands != null && signals.legCommands.length == legs.length) {
                    prepareMotorControl(motorTempo);
                    saccade = 0;
                    LegBodyMotion motion = legDynamics.advance(signals.legCommands, motorDT, true);
                    speed = Math.abs(motion.forward) / Math.max(0.001, dt);
                    updateWalk(dt, boundsW, boundsH, motion);
                } else {
                    motorWalking = false;
                    updateWalk(dt, boundsW, boundsH, null);
                }
            }
        }

        boolean hasCommands = signals != null && signals.legCommands != null
                && signals.legCommands.length == legs.length;
        if (!hasCommands || (state != State.WALKING && state != State.IDLE && state != State.SLEEPING)) {
            motorWalking = false;
        }
        if (hasCommands && (state == State.IDLE || state == State.SLEEPING)) {
            prepareMotorControl(motorTempo);
            LegMotorCommand[] idle = new LegMotorCommand[6];
            for (int i = 0; i < 6; i++) idle[i] = new LegMotorCommand();
            legDynamics.advance(idle, motorDT, true);
            motorWalking = true;   // retain the articulated standing pose
        }
        if (!motorWalking) legDynamics.resetContact(state != State.FLYING);
        updateLegs(dt);
        sampleLegFeedback(dt);
        updateWings(dt);
    }

    private void setState(State s) {
        if (s == state) return;
        state = s;
        stateAge = 0;
        if (s != State.WALKING) turnTarget = null;
    }

    // Every behavioral decision here reads a real neuron population's rate.
    private void brainBehavior(BrainSignals s, double dt, double boundsW, double boundsH,
                               Double mouseX, Double mouseY) {
        if (s.escape && scareCooldown == 0) {
            startFlight(boundsW, boundsH, mouseX, mouseY, true, null);
            return;
        }
        if (s.sleep) {
            if (state != State.SLEEPING) {
                setState(State.SLEEPING); speed = 0; dartTimer = 0; backwardTimer = 0;
            }
            return;
        } else if (state == State.SLEEPING) {
            setState(State.GROOMING);   // flies groom after waking
            return;
        }
        if (s.nervous > 0.40 && dartCooldown == 0) {
            ledge = null;
            setState(State.WALKING);
            if (mouseX != null) {
                saccade = 0;
                turnTarget = Math.atan2(posy - mouseY, posx - mouseX) + rnd(-0.4, 0.4);
            } else {
                startSaccade();
            }
            speed = rnd(110, 155);
            dartTimer = rnd(0.4, 0.9);
            turnTargetTime = dartTimer;
            dartCooldown = 1.2;
        }
        if (state != State.WALKING || dartTimer == 0) {
            if (state != State.GROOMING && s.groomDrive > 0.5 && s.nervous < 0.3 && stateAge > 0.4) {
                setState(State.GROOMING);
            } else if (state == State.GROOMING && s.groomDrive < 0.3 && stateAge > 0.6) {
                setState(State.IDLE);
            }
        }
        if (state == State.IDLE && s.walkDrive > 0.22 && stateAge > 0.4) {
            setState(State.WALKING);
            startSaccade();
        } else if (state == State.WALKING && dartTimer == 0 && s.walkDrive < 0.08 && stateAge > 0.5) {
            setState(State.IDLE);
            speed = 0;
        }
        if (s.backward && backwardTimer == 0 && dartTimer == 0) {
            if (state != State.WALKING) { setState(State.WALKING); speed = 0; }
            backwardTimer = 0.5;
        }
        if (state == State.WALKING) {
            if (s.legCommands == null && dartTimer == 0 && backwardTimer == 0) {
                double target = (14 + s.walkDrive * 55) * s.tempo;
                speed += (target - speed) * lag(3, dt);
            }
            if (s.legCommands == null && ledge == null) heading += s.turnBias * dt;
        }
        double flightChance = s.arousal > 0.5 ? 0.6 : 0.005;
        if (state == State.WALKING && rnd(0, 1) < lag(flightChance, dt)) {
            startFlight(boundsW, boundsH, null, null, false, 0.35 + s.arousal * 0.6);
        }
    }

    private double effectiveSpeed() { return backwardTimer > 0 ? -22 : speed; }

    private void updateWalk(double dt, double boundsW, double boundsH, LegBodyMotion motorMotion) {
        if (ledge != null) {
            Ledge cur = null;
            for (Ledge t : terrain) {
                if (t.id == ledge.id && Math.abs(t.y - ledge.y) < 40
                        && posx >= t.x0 - 6 && posx <= t.x1 + 6) { cur = t; break; }
            }
            if (cur != null) {
                ledge = cur;
            } else {
                ledge = null;
                startFlight(boundsW, boundsH, null, null, false, null);  // ground vanished
                return;
            }
        }
        if (ledge != null) {
            Ledge L = ledge;
            if (motorMotion == null) heading += rnd(-1, 1) * FlyMath.LEDGE_JITTER * Math.sqrt(dt);
            if (ledgeHeading == null) ledgeHeading = Math.cos(heading) >= 0 ? 0.0 : PI;
            if (posx <= L.x0 + 6) ledgeHeading = 0.0;
            if (posx >= L.x1 - 6) ledgeHeading = PI;
            turnToward(ledgeHeading, dt);
            posx += Math.cos(heading) * (motorMotion != null ? motorMotion.forward : effectiveSpeed() * dt);
            posy += (L.y - posy) * lag(10, dt);
            posx = clampf(posx, L.x0, L.x1);
            if (rnd(0, 1) < lag(0.05, dt)) ledge = null;   // wander off the edge
        } else {
            ledgeHeading = null;
            if (turnTarget != null) {
                turnToward(turnTarget, dt);
                if (Math.abs(angleDiff(heading, turnTarget)) < 0.001) turnTarget = null;
            }
            double startHeading = heading;
            if (motorMotion != null) heading += motorMotion.yaw;
            else heading += rnd(-1, 1) * WANDER_JITTER * Math.sqrt(dt);
            double hw = boundsW / 2 - EDGE_MARGIN, hh = boundsH / 2 - EDGE_MARGIN;
            if (Math.abs(posx) > hw || Math.abs(posy) > hh) {
                double toCenter = Math.atan2(-posy, -posx);
                heading += angleDiff(heading, toCenter) * lag(4, dt);
            }
            double forward = motorMotion != null ? motorMotion.forward : effectiveSpeed() * dt;
            double lateral = motorMotion != null ? motorMotion.lateral : 0;
            double translationHeading = motorMotion == null ? heading : startHeading;
            posx += Math.cos(translationHeading) * forward + Math.sin(translationHeading) * lateral;
            posy += Math.sin(translationHeading) * forward - Math.cos(translationHeading) * lateral;
            posx = clampf(posx, -boundsW / 2 + 20, boundsW / 2 - 20);
            posy = clampf(posy, -boundsH / 2 + 20, boundsH / 2 - 20);
            for (Ledge L : terrain) {
                if (posx > L.x0 - 8 && posx < L.x1 + 8 && Math.abs(posy - L.y) < 20) {
                    if (rnd(0, 1) < lag(0.9, dt)) {
                        ledge = L;
                        ledgeHeading = Math.cos(heading) >= 0 ? 0.0 : PI;
                        break;
                    }
                }
            }
        }
        bodyZ = motorMotion == null ? 0.35 * Math.abs(Math.sin(gaitPhase * PI * 2)) : 0;
    }

    private void updateFlight(double dt) {
        flightT = Math.min(1, flightT + dt / flightDur);
        if (flightT >= 1) {
            // touchdown flare: hover over the target and settle down
            double settle = Math.min(1, alt / 0.2);
            posx = flightToX + Math.sin(time * 26) * 1.2 * settle;
            posy = flightToY + Math.cos(time * 22) * settle;
            pitch += (clampf(alt * 0.4, 0, 0.35) - pitch) * lag(12, dt);
            alt += (0 - alt) * lag(9, dt);
            bodyZ = 90 * alt;
            if (alt < 0.003) { posx = flightToX; posy = flightToY; land(); }
            return;
        }
        double e = smoothstep(flightT);
        double dx = flightToX - flightFromX, dy = flightToY - flightFromY;
        double len = Math.max(1, Math.hypot(dx, dy));
        double px = -dy / len, py = dx / len;
        double wob = Math.sin(time * 32) * 4 * Math.sin(flightT * PI);
        posx = flightFromX + dx * e + px * wob;
        posy = flightFromY + dy * e + py * wob;
        turnToward(Math.atan2(dy, dx) + Math.sin(time * 18) * 0.12, dt);
        effortCurrent = brainLive
                ? clampf(Math.max(flightEffort,
                        flightEffort * 0.55 + liveArousal * 0.25 + liveWing * 0.6), 0.25, 1.3)
                : flightEffort;
        double riseEnv = Math.min(flightT / 0.25, 1);
        double fallEnv = Math.min((1 - flightT) / 0.3, 1);
        double target = effortCurrent * Math.min(riseEnv, fallEnv) * (0.85 + 0.15 * Math.sin(time * 7));
        pitch += (clampf((target - alt) * 2.5, -0.45, 0.45) - pitch) * lag(12, dt);
        alt += (target - alt) * lag(6, dt);
        bodyZ = 90 * alt;
    }

    private void updateLegs(double dt) {
        if (motorWalking) {
            LegFeedback[] fb = legDynamics.feedback();
            for (int i = 0; i < legs.length; i++) legs[i].apply(fb[i]);
            renderedLegState = state;
            renderedMotorControl = true;
            return;
        }
        boolean needBlendStart = renderedLegState != state || renderedMotorControl;
        if (needBlendStart) {
            blendFromHip = new double[6]; blendFromLift = new double[6]; blendFromKnee = new double[6];
            for (int i = 0; i < 6; i++) {
                blendFromHip[i] = legs[i].angle;
                blendFromLift[i] = legs[i].lift;
                blendFromKnee[i] = legs[i].kneeAngle;
            }
            legBlendTime = 0;
        }
        renderedLegState = state;
        renderedMotorControl = false;
        legBlendTime = Math.min(0.18, legBlendTime + dt);
        double blend = smoothstep(legBlendTime / 0.18);
        double v = Math.abs(effectiveSpeed());
        boolean walking = state == State.WALKING && v > 1;
        double amp = clampf(0.20 + v * 0.0022, 0.20, 0.50);
        double freq = clampf(v / Math.max(5, 2 * amp * 13), 3, 11);
        if (walking) gaitPhase = (gaitPhase + freq * dt) % 1;
        double stanceFrac = clampf(1 - SWING_DUR * freq, 0.35, 0.9);
        for (int i = 0; i < legs.length; i++) {
            Leg leg = legs[i];
            double angle = 0, lift = 0, knee = 0.95;
            if (walking) {
                knee = 0.75;
                double p = (gaitPhase + leg.phase) % 1;
                if (p < stanceFrac) {
                    angle = amp * (1 - 2 * p / stanceFrac);
                } else {
                    double phase = (p - stanceFrac) / (1 - stanceFrac);
                    angle = -amp + 2 * amp * smoothstep(phase);
                    lift = Math.sin(phase * PI) * 0.55;
                }
                if (backwardTimer > 0) angle = -angle;
            } else if (state == State.GROOMING) {
                knee = 0.75;
                if (leg.isFront) {
                    angle = 0.45 + 0.25 * Math.sin(time * 20 + leg.swingSign * 1.3);
                    lift = 0.55 + 0.15 * Math.sin(time * 22);
                }
            } else if (state == State.FLYING) {
                angle = -0.35; lift = 0.5; knee = 0.75;
            }
            angle = clampf(angle, -LegDynamics.HIP_LIMIT, LegDynamics.HIP_LIMIT);
            lift = clampf(lift, LegDynamics.ELEV_MIN, LegDynamics.ELEV_MAX);
            if (state != State.FLYING) {
                lift = Math.max(lift, LegDynamics.groundElevation(leg.geometry, knee));
            }
            leg.angle = blendFromHip[i] + (angle - blendFromHip[i]) * blend;
            leg.kneeAngle = blendFromKnee[i] + (knee - blendFromKnee[i]) * blend;
            leg.lift = blendFromLift[i] + (lift - blendFromLift[i]) * blend;
            if (state != State.FLYING) {
                leg.lift = Math.max(leg.lift, LegDynamics.groundElevation(leg.geometry, leg.kneeAngle));
            }
        }
    }

    private void sampleLegFeedback(double dt) {
        LegFeedback[] previous = sensedLegFeedback;
        LegFeedback[] physical = legDynamics.feedback();
        LegFeedback[] sensed = new LegFeedback[legs.length];
        for (int i = 0; i < legs.length; i++) {
            Leg leg = legs[i];
            LegFeedback value = new LegFeedback();
            value.hipAngle = leg.angle;
            value.kneeAngle = leg.kneeAngle;
            value.elevationAngle = leg.lift;
            double prevHip = previous.length == legs.length ? previous[i].hipAngle : leg.angle;
            double prevKnee = previous.length == legs.length ? previous[i].kneeAngle : leg.kneeAngle;
            double prevElev = previous.length == legs.length ? previous[i].elevationAngle : leg.lift;
            value.hipVelocity = (value.hipAngle - prevHip) / Math.max(0.001, dt);
            value.kneeVelocity = (value.kneeAngle - prevKnee) / Math.max(0.001, dt);
            value.elevationVelocity = (value.elevationAngle - prevElev) / Math.max(0.001, dt);
            // forward kinematics of the rendered pose (same math as updateFoot)
            double[] foot = fkFoot(leg, value.hipAngle, value.elevationAngle, value.kneeAngle);
            value.footX = foot[0]; value.footY = foot[1];
            value.footHeight = foot[2] + bodyZ;
            value.contact = state != State.FLYING && value.footHeight <= 0.015;
            sensed[i] = value;
        }
        int contacts = 0;
        for (LegFeedback f : sensed) if (f.contact) contacts++;
        int supports = Math.max(1, contacts);
        for (int i = 0; i < sensed.length; i++) {
            LegFeedback f = sensed[i];
            f.load = f.contact ? (motorWalking ? physical[i].load : 1.0 / supports) : 0;
        }
        sensedLegFeedback = sensed;
    }

    /** Top-down forward kinematics: foot [x, y, height] in body-local frame. */
    public static double[] fkFoot(Leg leg, double hip, double elevation, double knee) {
        LegGeometry g = leg.geometry;
        double reach = g.femur * Math.cos(elevation) + g.tibia * Math.cos(elevation - knee)
                + g.tarsus * Math.cos(elevation - knee - LegDynamics.ANKLE_ANGLE);
        double yaw = leg.baseYaw + leg.swingSign * hip;
        return new double[]{
                g.attachX + Math.cos(yaw) * reach,
                g.attachY + Math.sin(yaw) * reach,
                g.attachZ + g.femur * Math.sin(elevation) + g.tibia * Math.sin(elevation - knee)
                        + g.tarsus * Math.sin(elevation - knee - LegDynamics.ANKLE_ANGLE)
        };
    }

    private void updateWings(double dt) {
        boolean flying = state == State.FLYING;
        wingFlightAmount += ((flying ? 1 : 0) - wingFlightAmount) * lag(18, dt);
        if (!flying && wingFlightAmount < 0.0001) wingFlightAmount = 0;
        double raiseTarget = (!flying && state != State.SLEEPING
                && (liveWing > 0.7 || (brainLive && dartTimer > 0))) ? 1 : 0;
        wingRaise += (raiseTarget - wingRaise) * lag(8, dt);
        if (flying || wingFlightAmount > 0) {
            flapPhase += dt * (22 + 10 * effortCurrent);
        }
    }

    public double getWingFlightAmount() { return wingFlightAmount; }
}
