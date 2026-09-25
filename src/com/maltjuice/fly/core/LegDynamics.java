package com.maltjuice.fly.core;

import java.util.ArrayList;
import java.util.List;

import static com.maltjuice.fly.core.FlyMath.PI;
import static com.maltjuice.fly.core.FlyMath.clampf;

/**
 * Reduced articulated-body mechanics, NOT measured fly physics.
 * Direct translation of LegDynamics.swift. Local frame: +Y forward, +Z up.
 */
public final class LegDynamics {

    // ---- shared data records ----

    public static final class LegMotorCommand {
        public double protract, retract, lift, depress, flex, extend;
    }

    public static final class LegFeedback {
        public double hipAngle, hipVelocity;
        public double kneeAngle = 0.95, kneeVelocity;
        public boolean contact;
        public double load;
        public double footHeight;
        public double elevationAngle, elevationVelocity;
        public double footX, footY;

        public LegFeedback copy() {
            LegFeedback f = new LegFeedback();
            f.hipAngle = hipAngle; f.hipVelocity = hipVelocity;
            f.kneeAngle = kneeAngle; f.kneeVelocity = kneeVelocity;
            f.contact = contact; f.load = load; f.footHeight = footHeight;
            f.elevationAngle = elevationAngle; f.elevationVelocity = elevationVelocity;
            f.footX = footX; f.footY = footY;
            return f;
        }
    }

    public static final class LegGeometry {
        public final double attachX, attachY, attachZ;
        public final double baseYaw, side;
        public final double femur, tibia, tarsus;
        public LegGeometry(double attachX, double attachY, double attachZ,
                           double baseYaw, double side,
                           double femur, double tibia, double tarsus) {
            this.attachX = attachX; this.attachY = attachY; this.attachZ = attachZ;
            this.baseYaw = baseYaw; this.side = side;
            this.femur = femur; this.tibia = tibia; this.tarsus = tarsus;
        }
    }

    public static final class LegBodyMotion {
        public double forward, lateral, yaw;
    }

    public static final double HIP_LIMIT = 0.65;
    public static final double KNEE_MIN = 0.15, KNEE_MAX = 1.80;
    public static final double ELEV_MIN = -0.25, ELEV_MAX = 1.35;
    public static final double REST_KNEE = 0.95;
    public static final double ANKLE_ANGLE = 0.35;

    public final LegGeometry geometry;
    public final LegFeedback feedback = new LegFeedback();
    private final double restElevation;

    public LegDynamics(LegGeometry geometry) {
        this.geometry = geometry;
        restElevation = groundElevation(geometry, REST_KNEE);
        feedback.elevationAngle = restElevation;
        updateFoot(true);
    }

    public static double groundElevation(LegGeometry g, double knee) {
        double a = g.femur + g.tibia * Math.cos(knee) + g.tarsus * Math.cos(knee + ANKLE_ANGLE);
        double b = g.tibia * Math.sin(knee) + g.tarsus * Math.sin(knee + ANKLE_ANGLE);
        return Math.atan2(b, a)
                - Math.asin(clampf(g.attachZ / Math.max(0.001, Math.hypot(a, b)), -1, 1));
    }

    private void updateFoot(boolean grounded) {
        LegGeometry g = geometry;
        double e = feedback.elevationAngle, k = feedback.kneeAngle;
        double reach = g.femur * Math.cos(e) + g.tibia * Math.cos(e - k)
                + g.tarsus * Math.cos(e - k - ANKLE_ANGLE);
        double yaw = g.baseYaw + g.side * feedback.hipAngle;
        feedback.footX = g.attachX + Math.cos(yaw) * reach;
        feedback.footY = g.attachY + Math.sin(yaw) * reach;
        feedback.footHeight = g.attachZ + g.femur * Math.sin(e) + g.tibia * Math.sin(e - k)
                + g.tarsus * Math.sin(e - k - ANKLE_ANGLE);
        feedback.contact = grounded && feedback.footHeight <= 0.015;
        feedback.load = feedback.contact ? 1 : 0;
    }

    public void resetContact(boolean grounded) { updateFoot(grounded); }

    /** Hand control back from a scripted pose without restoring an old state. */
    public void adoptPose(LegFeedback pose, boolean grounded, double velocityScale) {
        if (!Double.isFinite(pose.hipAngle) || !Double.isFinite(pose.elevationAngle)
                || !Double.isFinite(pose.kneeAngle)) return;
        double scale = (Double.isFinite(velocityScale) && velocityScale > 0) ? velocityScale : 1;
        feedback.hipAngle = clampf(pose.hipAngle, -HIP_LIMIT, HIP_LIMIT);
        feedback.elevationAngle = clampf(pose.elevationAngle, ELEV_MIN, ELEV_MAX);
        feedback.kneeAngle = clampf(pose.kneeAngle, KNEE_MIN, KNEE_MAX);
        feedback.hipVelocity = clampf(pose.hipVelocity * scale, -20, 20);
        feedback.elevationVelocity = clampf(pose.elevationVelocity * scale, -20, 20);
        feedback.kneeVelocity = clampf(pose.kneeVelocity * scale, -40, 40);
        updateFoot(grounded);
        feedback.load = 0;
    }

    /** Semi-implicit damped joint integration; antagonist difference supplies torque. */
    public void step(LegMotorCommand command, double dt, boolean grounded) {
        double p = clampf(command.protract, 0, 1), r = clampf(command.retract, 0, 1);
        double l = clampf(command.lift, 0, 1), d = clampf(command.depress, 0, 1);
        double f = clampf(command.flex, 0, 1), e = clampf(command.extend, 0, 1);
        LegFeedback old = feedback.copy();
        feedback.hipVelocity += (300 * (p - r) - 26 * old.hipVelocity
                - (18 + 12 * Math.min(p, r)) * old.hipAngle) * dt;
        feedback.hipAngle = clampf(old.hipAngle + feedback.hipVelocity * dt, -HIP_LIMIT, HIP_LIMIT);
        feedback.elevationVelocity += (800 * (l - d) - 45 * old.elevationVelocity
                - (800 + 120 * Math.min(l, d)) * (old.elevationAngle - restElevation)
                - (grounded ? 80 : 0)) * dt;
        feedback.elevationAngle = clampf(old.elevationAngle + feedback.elevationVelocity * dt,
                ELEV_MIN, ELEV_MAX);
        feedback.kneeVelocity += (1140 * (f - e) - 36 * old.kneeVelocity
                - (240 + 80 * Math.min(f, e)) * (old.kneeAngle - REST_KNEE)) * dt;
        feedback.kneeAngle = clampf(old.kneeAngle + feedback.kneeVelocity * dt, KNEE_MIN, KNEE_MAX);
        updateFoot(grounded);
        double attemptedElevation = feedback.elevationAngle;
        double reaction = 0;
        if (grounded && feedback.footHeight < 0) {
            feedback.elevationAngle = Math.max(feedback.elevationAngle,
                    groundElevation(geometry, feedback.kneeAngle));
            reaction = Math.max(0, feedback.elevationAngle - attemptedElevation) / (dt * dt);
            updateFoot(grounded);
        }
        feedback.load = feedback.contact ? reaction : 0;
        feedback.hipVelocity = (feedback.hipAngle - old.hipAngle) / dt;
        feedback.elevationVelocity = (feedback.elevationAngle - old.elevationAngle) / dt;
        feedback.kneeVelocity = (feedback.kneeAngle - old.kneeAngle) / dt;
    }

    // ---- six-leg body ----

    public static final class SixLegDynamics {
        public static final double FIXED_DT = 1.0 / 600;
        public final LegDynamics[] legs;
        private double accumulator = 0;

        public SixLegDynamics(LegGeometry[] geometries) {
            legs = new LegDynamics[geometries.length];
            for (int i = 0; i < geometries.length; i++) legs[i] = new LegDynamics(geometries[i]);
        }

        /** Normalized support loads, fresh array each call (Swift returns a map). */
        public LegFeedback[] feedback() {
            double totalReaction = 0;
            for (LegDynamics leg : legs) {
                if (leg.feedback.contact) totalReaction += leg.feedback.load;
            }
            LegFeedback[] out = new LegFeedback[legs.length];
            for (int i = 0; i < legs.length; i++) {
                LegFeedback value = legs[i].feedback.copy();
                value.load = (value.contact && totalReaction > 0) ? value.load / totalReaction : 0;
                out[i] = value;
            }
            return out;
        }

        public void resetContact(boolean grounded) {
            for (LegDynamics leg : legs) leg.resetContact(grounded);
        }

        public void adoptPose(LegFeedback[] poses, boolean grounded, double velocityScale) {
            if (poses.length != legs.length) return;
            for (LegFeedback pose : poses) {
                if (!Double.isFinite(pose.hipAngle) || !Double.isFinite(pose.elevationAngle)
                        || !Double.isFinite(pose.kneeAngle)) return;
            }
            for (int i = 0; i < legs.length; i++) {
                legs[i].adoptPose(poses[i], grounded, velocityScale);
            }
            accumulator = 0;
        }

        public LegBodyMotion advance(LegMotorCommand[] commands, double dt, boolean grounded) {
            LegBodyMotion result = new LegBodyMotion();
            if (commands.length != legs.length || !Double.isFinite(dt) || dt <= 0) return result;
            accumulator += Math.min(dt, 0.1);
            double h = FIXED_DT;
            while (accumulator + 1e-10 >= h) {
                accumulator -= h;
                LegFeedback[] old = feedback();
                for (int i = 0; i < legs.length; i++) legs[i].step(commands[i], h, grounded);
                LegFeedback[] current = feedback();
                List<Integer> support = new ArrayList<>();
                for (int i = 0; i < legs.length; i++) {
                    if (old[i].contact && current[i].contact && old[i].load > 1e-8 && current[i].load > 1e-8) {
                        support.add(i);
                    }
                }
                if (!grounded || support.size() < 2) continue;
                double[] weights = new double[support.size()];
                double totalWeight = 0;
                for (int s = 0; s < support.size(); s++) {
                    int i = support.get(s);
                    weights[s] = Math.sqrt(old[i].load * current[i].load);
                    totalWeight += weights[s];
                }
                double mx = 0, my = 0, dx = 0, dy = 0;
                for (int s = 0; s < support.size(); s++) {
                    int i = support.get(s);
                    double weight = weights[s] / totalWeight;
                    mx += current[i].footX * weight; my += current[i].footY * weight;
                    dx += (current[i].footX - old[i].footX) * weight;
                    dy += (current[i].footY - old[i].footY) * weight;
                }
                double moment = 0, radius = 0;
                for (int s = 0; s < support.size(); s++) {
                    int i = support.get(s);
                    double weight = weights[s] / totalWeight;
                    double x = current[i].footX - mx, y = current[i].footY - my;
                    moment += weight * (x * (current[i].footY - old[i].footY - dy)
                            - y * (current[i].footX - old[i].footX - dx));
                    radius += weight * (x * x + y * y);
                }
                double yaw = clampf(-moment / Math.max(1, radius), -5 * h, 5 * h);
                double lateral = clampf(-dx + yaw * my, -150 * h, 150 * h);
                double forward = clampf(-dy - yaw * mx, -150 * h, 150 * h);
                result.lateral += lateral * Math.cos(result.yaw) - forward * Math.sin(result.yaw);
                result.forward += lateral * Math.sin(result.yaw) + forward * Math.cos(result.yaw);
                result.yaw += yaw;
            }
            return result;
        }
    }
}
