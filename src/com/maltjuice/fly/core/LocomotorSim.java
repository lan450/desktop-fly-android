package com.maltjuice.fly.core;

import com.maltjuice.fly.core.CircuitData.LocomotorCircuitFile;
import com.maltjuice.fly.core.CircuitData.LocomotorNeuronFile;
import com.maltjuice.fly.core.LegDynamics.LegMotorCommand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MaleCNS v1.0 nerve-cord circuit. Anatomy and synapse counts are measured;
 * LIF parameters, rate transfer between specimens, sensory tuning and muscle
 * activation are explicit modeling assumptions. Translation of Locomotor.swift.
 */
public final class LocomotorSim {

    public static final class Parameters {
        public double synapticGain = 2.4;
        public double baseline = 0.022;
        public double adaptationKick = 0.01;
    }

    public final LocomotorCircuitFile circuit;
    public final int n;
    private final double[] voltage, adaptation, rates, excitatory, inhibitory;
    private final double[] nextExcitatory, nextInhibitory, drive, sensoryDrive;
    private final int[] refractory;
    private final int[] rowStart, targets;
    private final double[] weights;
    private final Map<String, List<Integer>> commandGroups = new HashMap<>();
    private final Map<String, List<Integer>>[] motorGroups = new HashMap[6];
    private final List<Integer> sensory = new ArrayList<>();
    public final LegMotorCommand[] commands = new LegMotorCommand[6];
    public int totalSpikes, motorSpikes, sensorySpikes, simMs;
    public LegDynamics.LegFeedback[] feedback = new LegDynamics.LegFeedback[0];
    /** Lesion flags (diagnostic interventions), checked in the ms hot loop. */
    public final boolean[] silenced;
    public boolean synapsesEnabled = true, feedbackEnabled = true;
    public final Parameters parameters;

    @SuppressWarnings("unchecked")
    public LocomotorSim(LocomotorCircuitFile circuit) { this(circuit, new Parameters()); }

    @SuppressWarnings("unchecked")
    public LocomotorSim(LocomotorCircuitFile circuit, Parameters parameters) {
        if (!circuit.validate()) throw new IllegalArgumentException("invalid MaleCNS locomotor circuit");
        this.circuit = circuit;
        this.parameters = parameters;
        n = circuit.neurons.length;
        silenced = new boolean[n];
        voltage = new double[n]; adaptation = new double[n]; refractory = new int[n];
        rates = new double[n]; excitatory = new double[n]; inhibitory = new double[n];
        nextExcitatory = new double[n]; nextInhibitory = new double[n];
        drive = new double[n]; sensoryDrive = new double[n];
        int[] counts = new int[n];
        double[] inputTotal = new double[n];
        for (float[] e : circuit.edges) {
            counts[(int) e[0]] += 1;
            inputTotal[(int) e[1]] += Math.abs(e[2]);
        }
        rowStart = new int[n + 1];
        for (int i = 0; i < n; i++) rowStart[i + 1] = rowStart[i] + counts[i];
        targets = new int[circuit.edges.length];
        weights = new double[circuit.edges.length];
        int[] fill = rowStart.clone();
        for (float[] e : circuit.edges) {
            int pre = (int) e[0], post = (int) e[1], slot = fill[pre];
            targets[slot] = post;
            weights[slot] = parameters.synapticGain * e[2] / Math.max(60, inputTotal[post]);
            fill[pre] += 1;
        }
        for (int i = 0; i < 6; i++) motorGroups[i] = new HashMap<>();
        for (int i = 0; i < circuit.neurons.length; i++) {
            LocomotorNeuronFile nr = circuit.neurons[i];
            if ("descending".equals(nr.role)) {
                commandGroups.computeIfAbsent(nr.type + ":" + nr.side, k -> new ArrayList<>()).add(i);
            }
            if ("sensory".equals(nr.role) && nr.leg != null) sensory.add(i);
            if ("motor".equals(nr.role) && nr.leg != null && nr.motorChannel != null) {
                motorGroups[nr.leg].computeIfAbsent(nr.motorChannel, k -> new ArrayList<>()).add(i);
            }
        }
        for (int leg = 0; leg < 6; leg++) commands[leg] = new LegMotorCommand();
    }

    /** Modeled homologous population-rate interface between the female FlyWire
     *  brain and the male CNS cord: adds current, never fabricated graph edges. */
    public void setDescending(String type, String side, float rate) {
        List<Integer> ids = commandGroups.get(type + ":" + side);
        if (ids == null) return;
        double driveValue = Math.min(0.35, Math.max(0, rate) * 0.004);
        for (int i : ids) drive[i] = driveValue;
    }

    // ---- test/diagnostic helpers (faithful ports of the Swift meanRate/indices) ----

    public List<Integer> indices(String role, Integer leg) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            LocomotorNeuronFile nr = circuit.neurons[i];
            if (role.equals(nr.role) && (leg == null || (nr.leg != null && nr.leg == leg))) out.add(i);
        }
        return out;
    }

    public double meanRate(String role, Integer leg) {
        List<Integer> ids = indices(role, leg);
        double sum = 0;
        for (int i : ids) sum += rates[i];
        return sum / Math.max(1, ids.size());
    }

    public void setSilenced(List<Integer> ids, boolean value) {
        for (int i : ids) silenced[i] = value;
    }

    public void step(int ms) {
        if (ms <= 0) return;
        for (int m = 0; m < ms; m++) {
            simMs += 1;
            // Leg-local sensory transduction (contact loads, joint proprioception)
            for (int i : sensory) sensoryDrive[i] = 0;
            if (feedbackEnabled && feedback.length == 6) {
                for (int i : sensory) {
                    LocomotorNeuronFile nr = circuit.neurons[i];
                    LegDynamics.LegFeedback f = feedback[nr.leg];
                    double value;
                    if ("campaniform".equals(nr.sensoryKind) || "contact".equals(nr.sensoryKind)) {
                        value = f.contact ? Math.min(1, f.load * 6) : 0;
                    } else if ("hair_plate".equals(nr.sensoryKind)) {
                        value = Math.min(1, Math.abs(f.hipAngle) / LegDynamics.HIP_LIMIT
                                + Math.abs(f.elevationVelocity) / 20);
                    } else {
                        value = Math.min(1, Math.abs(f.kneeVelocity) / 20 + Math.abs(f.hipVelocity) / 16
                                + Math.abs(f.kneeAngle - LegDynamics.REST_KNEE) * 0.35);
                    }
                    sensoryDrive[i] = value * 0.10;
                }
            }
            for (int i = 0; i < n; i++) {
                excitatory[i] = excitatory[i] * 0.8187308 + nextExcitatory[i];
                inhibitory[i] = inhibitory[i] * 0.9048374 + nextInhibitory[i];
                nextExcitatory[i] = 0; nextInhibitory[i] = 0;
            }
            for (int i = 0; i < n; i++) {
                rates[i] *= 0.9048374;        // 10 ms rate constant for fast muscles
                adaptation[i] *= 0.9950125;    // 200 ms spike-frequency adaptation
                if (silenced[i]) { voltage[i] = 0; rates[i] = 0; continue; }
                if (refractory[i] > 0) { refractory[i] -= 1; continue; }
                voltage[i] = Math.max(-1, voltage[i] * 0.9512294 + excitatory[i] + inhibitory[i]
                        + parameters.baseline + drive[i] + sensoryDrive[i] - adaptation[i]);
                if (voltage[i] >= 1) {
                    voltage[i] = 0; refractory[i] = 2;
                    adaptation[i] += parameters.adaptationKick;
                    rates[i] += 95.16258;
                    totalSpikes += 1;
                    if ("motor".equals(circuit.neurons[i].role)) motorSpikes += 1;
                    if ("sensory".equals(circuit.neurons[i].role)) sensorySpikes += 1;
                    if (synapsesEnabled) {
                        for (int e = rowStart[i]; e < rowStart[i + 1]; e++) {
                            if (weights[e] >= 0) nextExcitatory[targets[e]] += weights[e];
                            else nextInhibitory[targets[e]] += weights[e];
                        }
                    }
                }
            }
        }
        for (int leg = 0; leg < 6; leg++) {
            commands[leg].protract = Math.max(activity(leg, "coxa_promotor"), activity(leg, "coxa_anterior_rotator"));
            commands[leg].retract = Math.max(activity(leg, "coxa_remotor"), activity(leg, "coxa_posterior_rotator"));
            commands[leg].lift = activity(leg, "trochanter_flexor");
            commands[leg].depress = activity(leg, "trochanter_extensor");
            commands[leg].flex = activity(leg, "tibia_flexor");
            commands[leg].extend = activity(leg, "tibia_extensor");
        }
    }

    private double activity(int leg, String channel) {
        List<Integer> ids = motorGroups[leg].get(channel);
        if (ids == null || ids.isEmpty()) return 0;
        double rate = 0;
        for (int i : ids) rate += rates[i];
        rate /= Math.max(1, ids.size());
        return rate / (rate + 50);
    }
}
