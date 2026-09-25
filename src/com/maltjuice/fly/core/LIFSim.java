package com.maltjuice.fly.core;

import com.maltjuice.fly.core.CircuitData.CircuitFile;
import com.maltjuice.fly.core.CircuitData.CircuitNeuronFile;

import java.util.ArrayList;
import java.util.List;

import static com.maltjuice.fly.core.FlyMath.PI;
import static com.maltjuice.fly.core.FlyMath.rnd;
import static com.maltjuice.fly.core.FlyMath.rndInt;

/**
 * Leaky-integrate-and-fire simulation of the escape/steering circuit
 * (LC4/LPLC2 -> DNp01 giant fiber, DNa02 steering, MDN backward walking)
 * with real signed synapse weights from FlyWire v783. Translation of Sim.swift.
 */
public final class LIFSim {

    public final LocomotorSim locomotor;
    public LegDynamics.LegFeedback[] legFeedback = new LegDynamics.LegFeedback[0];
    private final String[] cordSourceType, cordSourceSide;
    private final int[] cordSourceCount;
    private final int[] cordSourceOf;
    private final float[] cordSourceRates;
    public final int n;
    public final String[] roles, types;

    // LIF state
    private final float[] v, refr, baseline;
    // CSR adjacency, weights pre-scaled
    private final int[] rowStart;
    private final int[] colIdx;
    private final float[] w;

    // groups
    public final List<Integer> loomLeft = new ArrayList<>(), loomRight = new ArrayList<>();
    public final List<Integer> gf = new ArrayList<>();
    public final List<Integer> dnaL = new ArrayList<>(), dnaR = new ArrayList<>();
    public final List<Integer> mdn = new ArrayList<>();
    public final List<Integer> fwd = new ArrayList<>();
    public final List<Integer> groom = new ArrayList<>();
    public final List<Integer> escw = new ArrayList<>();
    public final List<Integer> ascend = new ArrayList<>();
    public final List<Integer> sens = new ArrayList<>();
    private final float[] ascendPhase;
    private final boolean[] inDnaL, inDnaR;

    // inputs (0..1)
    public float loomL, loomR;
    public float gaitDrive, gaitPhase, airPuff;
    public float activityScale = 1, sensoryGate = 1;

    // outputs
    public float rateLoom, rateDNaL, rateDNaR, rateMDN, rateFwd, rateGroom, rateEscW, ratePop;
    private boolean gfLatch;
    public int simMs, totalSpikes;

    // delayed chemical inhibition; electrical LC->GF coupling is instantaneous
    private static final int INH_DELAY_MS = 4;
    private final float[][] inhQueue;
    private int qHead;

    // params
    private static final float DECAY = 0.9512f;       // 20 ms membrane tau, 1 ms step
    private static final float THRESHOLD = 1.0f;
    private static final float REFRACTORY_MS = 2;
    private static final float WEIGHT_SCALE = 0.0008f;
    private static final float P_NOISE = 0.0022f;
    private static final float NOISE_KICK = 0.42f;
    private static final float LOOM_GAIN = 0.30f;
    private static final float RATE_ALPHA = 1.0f / 120.0f;
    private int burstUntil = 0, burstNext = 12_000;

    // "optogenetic" stimulation (kept synchronized: may be called from anywhere)
    private static final class Stim {
        final int[] idx; final float strength; final int durationMs; long untilMs;
        Stim(int[] idx, float strength, int durationMs) {
            this.idx = idx; this.strength = strength; this.durationMs = durationMs;
        }
    }
    private final List<Stim> pendingStims = new ArrayList<>();
    private final List<Stim> activeStims = new ArrayList<>();

    public void stimulate(List<Integer> indices, float strength, int durationMs) {
        if (indices == null || indices.isEmpty()) return;
        int[] idx = new int[indices.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = indices.get(i);
        synchronized (this) {
            pendingStims.add(new Stim(idx, strength, durationMs));
            if (pendingStims.size() > 8) pendingStims.remove(0);
        }
    }

    public LIFSim(CircuitFile circuit, LocomotorSim locomotorSim) {
        this.locomotor = locomotorSim;
        n = circuit.neurons.length;
        roles = new String[n];
        types = new String[n];
        v = new float[n];
        refr = new float[n];
        inhQueue = new float[5][n];
        inDnaL = new boolean[n];
        inDnaR = new boolean[n];

        for (int i = 0; i < n; i++) {
            CircuitNeuronFile nr = circuit.neurons[i];
            roles[i] = nr.role;
            types[i] = nr.type;
            switch (nr.role == null ? "" : nr.role) {
                case "lc4": case "lplc2":
                    if ("left".equals(nr.side)) loomLeft.add(i); else loomRight.add(i);
                    break;
                case "gf": gf.add(i); break;
                case "dna01": case "dna02":
                    if ("left".equals(nr.side)) { dnaL.add(i); inDnaL[i] = true; }
                    else { dnaR.add(i); inDnaR[i] = true; }
                    break;
                case "mdn": mdn.add(i); break;
                case "dnp09": fwd.add(i); break;
                case "dng11": groom.add(i); break;
                case "escw": escw.add(i); break;
                case "other":
                    if ("ascending".equals(nr.type)) ascend.add(i);
                    else if ("sensory".equals(nr.type)) sens.add(i);
                    break;
                default: break;
            }
        }
        ascendPhase = new float[ascend.size()];
        for (int k = 0; k < ascendPhase.length; k++) {
            ascendPhase[k] = FlyMath.rndF(0f, 6.2831855f);   // Float(2π), Swift float path
        }

        // Heterogeneous baseline drive: interneurons crackle at a few Hz;
        // sensory and command neurons stay quiet unless driven.
        baseline = new float[n];
        for (int i = 0; i < n; i++) {
            String role = roles[i] == null ? "" : roles[i];
            switch (role) {
                case "other": baseline[i] = FlyMath.rndF(0.010f, 0.070f); break;
                case "lc4": case "lplc2": baseline[i] = 0.004f; break;
                case "dna01": case "dna02": case "mdn": case "dng11": case "escw":
                    baseline[i] = 0.036f; break;
                case "dnp09": baseline[i] = 0.038f; break;
                default: baseline[i] = 0.002f; break;   // gf: quiet unless driven
            }
        }

        // DN command pools feeding the cord across the specimen interface
        cordSourceOf = new int[n];
        java.util.Arrays.fill(cordSourceOf, -1);
        List<String> groupType = new ArrayList<>(), groupSide = new ArrayList<>();
        List<Integer> groupCount = new ArrayList<>();
        java.util.HashMap<String, Integer> groupByKey = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            String t = circuit.neurons[i].type;
            if ("DNp09".equals(t) || "DNa01".equals(t) || "DNa02".equals(t) || "MDN".equals(t)) {
                String key = t + ":" + circuit.neurons[i].side;
                Integer group = groupByKey.get(key);
                if (group == null) {
                    group = groupType.size();
                    groupByKey.put(key, group);
                    groupType.add(t); groupSide.add(circuit.neurons[i].side);
                    groupCount.add(0);
                }
                groupCount.set(group, groupCount.get(group) + 1);
                cordSourceOf[i] = group;
            }
        }
        cordSourceType = groupType.toArray(new String[0]);
        cordSourceSide = groupSide.toArray(new String[0]);
        cordSourceCount = new int[groupCount.size()];
        for (int i = 0; i < cordSourceCount.length; i++) cordSourceCount[i] = groupCount.get(i);
        cordSourceRates = new float[groupCount.size()];

        // CSR adjacency; electrical (gap-junction) drive onto GF is boosted
        int[] counts = new int[n];
        for (float[] e : circuit.edges) counts[(int) e[0]] += 1;
        rowStart = new int[n + 1];
        for (int i = 0; i < n; i++) rowStart[i + 1] = rowStart[i] + counts[i];
        colIdx = new int[circuit.edges.length];
        w = new float[circuit.edges.length];
        final float gapJunctionBoost = 6.0f;
        int[] fill = rowStart.clone();
        for (float[] e : circuit.edges) {
            int pre = (int) e[0], post = (int) e[1];
            float weight = e[2] * WEIGHT_SCALE;
            boolean electrical = "lc4".equals(roles[pre]) || "lplc2".equals(roles[pre])
                    || ("other".equals(roles[pre]) && "sensory".equals(types[pre]));
            if (electrical && "gf".equals(roles[post])) weight *= gapJunctionBoost;
            colIdx[fill[pre]] = post;
            w[fill[pre]] = weight;
            fill[pre] += 1;
        }
    }

    public boolean consumeGF() {
        boolean s = gfLatch;
        gfLatch = false;
        return s;
    }

    private final List<Integer> spikedBuffer = new ArrayList<>(256);

    public void step(int ms) {
        if (ms <= 0) return;
        if (locomotor != null) locomotor.feedback = legFeedback;
        synchronized (this) {
            for (Stim p : pendingStims) {
                p.untilMs = simMs + p.durationMs;
                activeStims.add(p);
            }
            pendingStims.clear();
        }
        activeStims.removeIf(s -> simMs >= s.untilMs);

        for (int m = 0; m < ms; m++) {
            simMs += 1;
            if (simMs >= burstNext) {
                burstUntil = simMs + 400;
                burstNext = simMs + rndInt(15_000, 40_000);
            }
            float p = ((simMs < burstUntil) ? P_NOISE * 6 : P_NOISE) * activityScale;

            for (int i = 0; i < n; i++) {
                if (refr[i] > 0) { refr[i] -= 1; v[i] *= DECAY; continue; }
                float vi = v[i] * DECAY + baseline[i] * activityScale;
                if (FlyMath.rndFloat() < p) vi += NOISE_KICK;
                v[i] = vi;
            }
            if (loomL > 0.001f) for (int i : loomLeft) v[i] += loomL * LOOM_GAIN * sensoryGate;
            if (loomR > 0.001f) for (int i : loomRight) v[i] += loomR * LOOM_GAIN * sensoryGate;
            // body -> brain: gait rhythm into ascending proprioceptive neurons
            // (only used when no MaleCNS cord is attached)
            if (locomotor == null && gaitDrive > 0.001f) {
                float ph = gaitPhase * 2 * (float) PI;
                for (int k = 0; k < ascend.size(); k++) {
                    int i = ascend.get(k);
                    v[i] += gaitDrive * 0.09f * (0.5f + 0.5f * (float) Math.sin(ph + ascendPhase[k]));
                }
            }
            // fast air movement near the fly -> sensory pathway
            if (airPuff > 0.001f) for (int i : sens) v[i] += airPuff * 0.12f * sensoryGate;
            // direct stimulation
            for (Stim s : activeStims) {
                if (simMs < s.untilMs) for (int i : s.idx) v[i] += s.strength;
            }

            // deliver delayed inhibition scheduled for this millisecond
            float[] slot = inhQueue[qHead];
            for (int j = 0; j < n; j++) {
                if (slot[j] != 0) {
                    v[j] = Math.max(-2, v[j] + slot[j]);
                    slot[j] = 0;
                }
            }

            spikedBuffer.clear();
            for (int i = 0; i < n; i++) {
                if (refr[i] <= 0 && v[i] >= THRESHOLD) {
                    v[i] = 0;
                    refr[i] = REFRACTORY_MS;
                    spikedBuffer.add(i);
                }
            }
            totalSpikes += spikedBuffer.size();
            int inhSlot = (qHead + INH_DELAY_MS) % inhQueue.length;
            for (int i : spikedBuffer) {
                for (int k = rowStart[i]; k < rowStart[i + 1]; k++) {
                    int j = colIdx[k];
                    if (w[k] >= 0) v[j] = Math.max(-2, v[j] + w[k]);
                    else inhQueue[inhSlot][j] += w[k];
                }
            }
            qHead = (qHead + 1) % inhQueue.length;

            // group rates (Hz per neuron, EMA)
            int cLoom = 0, cDL = 0, cDR = 0, cM = 0, cF = 0, cG = 0, cW = 0;
            for (int i : spikedBuffer) {
                String role = roles[i] == null ? "" : roles[i];
                switch (role) {
                    case "lc4": case "lplc2": cLoom += 1; break;
                    case "dna01": case "dna02":
                        if (inDnaL[i]) cDL += 1; else cDR += 1;
                        break;
                    case "mdn": cM += 1; break;
                    case "dnp09": cF += 1; break;
                    case "dng11": cG += 1; break;
                    case "escw": cW += 1; break;
                    case "gf": gfLatch = true; break;
                    default: break;
                }
            }
            float nLoom = Math.max(1, loomLeft.size() + loomRight.size());
            rateLoom += (cLoom * 1000f / nLoom - rateLoom) * RATE_ALPHA;
            rateDNaL += (cDL * 1000f / Math.max(1, dnaL.size()) - rateDNaL) * RATE_ALPHA;
            rateDNaR += (cDR * 1000f / Math.max(1, dnaR.size()) - rateDNaR) * RATE_ALPHA;
            rateMDN += (cM * 1000f / Math.max(1, mdn.size()) - rateMDN) * RATE_ALPHA;
            rateFwd += (cF * 1000f / Math.max(1, fwd.size()) - rateFwd) * RATE_ALPHA;
            rateGroom += (cG * 1000f / Math.max(1, groom.size()) - rateGroom) * RATE_ALPHA;
            rateEscW += (cW * 1000f / Math.max(1, escw.size()) - rateEscW) * RATE_ALPHA;
            ratePop += (spikedBuffer.size() * 1000f / Math.max(1, n) - ratePop) * RATE_ALPHA;

            if (locomotor != null) {
                for (int i = 0; i < cordSourceRates.length; i++) {
                    cordSourceRates[i] *= 1 - RATE_ALPHA;
                }
                for (int i : spikedBuffer) {
                    if (cordSourceOf[i] >= 0) {
                        int group = cordSourceOf[i];
                        cordSourceRates[group] += 1000 * RATE_ALPHA / cordSourceCount[group];
                    }
                }
                for (int i = 0; i < cordSourceType.length; i++) {
                    locomotor.setDescending(cordSourceType[i], cordSourceSide[i], cordSourceRates[i]);
                }
                locomotor.step(1);
            }
        }
    }
}
