package com.maltjuice.fly.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Circuit data files, mirroring the Swift Decodable structs in Sim.swift /
 * Locomotor.swift. Loaded from assets on Android, or from disk in JVM tests.
 */
public final class CircuitData {

    public static final class BrainPointsFile {
        public final String[] classes;
        public final float[][] points;   // [x, y, z, classIndex]
        public BrainPointsFile(String[] classes, float[][] points) {
            this.classes = classes; this.points = points;
        }
    }

    public static final class CircuitNeuronFile {
        public final String id, type, role, side;
        public CircuitNeuronFile(String id, String type, String role, String side) {
            this.id = id; this.type = type; this.role = role; this.side = side;
        }
    }

    public static final class CircuitFile {
        public final CircuitNeuronFile[] neurons;
        public final float[][] edges;    // [preIdx, postIdx, signedSynCount]
        public CircuitFile(CircuitNeuronFile[] neurons, float[][] edges) {
            this.neurons = neurons; this.edges = edges;
        }
    }

    public static final class LocomotorNeuronFile {
        public final String id, type, role, side;
        public final Integer leg;                // nullable
        public final String motorChannel;        // nullable
        public final String sensoryKind;         // nullable
        public LocomotorNeuronFile(String id, String type, String role, String side,
                                   Integer leg, String motorChannel, String sensoryKind) {
            this.id = id; this.type = type; this.role = role; this.side = side;
            this.leg = leg; this.motorChannel = motorChannel; this.sensoryKind = sensoryKind;
        }
    }

    public static final class LocomotorCircuitFile {
        public final LocomotorNeuronFile[] neurons;
        public final float[][] edges;

        public LocomotorCircuitFile(LocomotorNeuronFile[] neurons, float[][] edges) {
            this.neurons = neurons; this.edges = edges;
        }

        /** Same integrity check as the Swift validate(). */
        public boolean validate() {
            if (neurons.length == 0 || edges.length == 0) return false;
            Map<String, Boolean> ids = new HashMap<>();
            for (LocomotorNeuronFile n : neurons) {
                if (ids.put(n.id, Boolean.TRUE) != null) return false;
                if (n.leg != null && (n.leg < 0 || n.leg >= 6)) return false;
            }
            int count = neurons.length;
            for (float[] e : edges) {
                if (e.length != 3) return false;
                if (!Float.isFinite(e[0]) || !Float.isFinite(e[1]) || !Float.isFinite(e[2])) return false;
                if (e[0] < 0 || e[1] < 0 || e[0] >= count || e[1] >= count) return false;
                if (Math.rint(e[0]) != e[0] || Math.rint(e[1]) != e[1]) return false;
            }
            for (int leg = 0; leg < 6; leg++) {
                for (String channel : new String[]{"tibia_flexor", "tibia_extensor",
                        "trochanter_flexor", "trochanter_extensor"}) {
                    boolean found = false;
                    for (LocomotorNeuronFile n : neurons) {
                        if (n.leg != null && n.leg == leg && channel.equals(n.motorChannel)) {
                            found = true; break;
                        }
                    }
                    if (!found) return false;
                }
            }
            return true;
        }
    }

    /** Parsed bundle of everything the app needs. */
    public static final class Bundle {
        public final BrainPointsFile points;
        public final CircuitFile circuit;
        public final LocomotorCircuitFile locomotor;
        public Bundle(BrainPointsFile points, CircuitFile circuit, LocomotorCircuitFile locomotor) {
            this.points = points; this.circuit = circuit; this.locomotor = locomotor;
        }
    }

    public static Bundle parseAll(String brainPointsJson, String circuitJson, String locomotorJson) {
        return new Bundle(parseBrainPoints(brainPointsJson),
                parseCircuit(circuitJson), parseLocomotor(locomotorJson));
    }

    public static BrainPointsFile parseBrainPoints(String json) {
        Map<String, Object> root = MiniJson.obj(MiniJson.parse(json));
        Object[] classesRaw = MiniJson.arr(root.get("classes"));
        String[] classes = new String[classesRaw.length];
        for (int i = 0; i < classesRaw.length; i++) classes[i] = MiniJson.str(classesRaw[i]);
        Object[] pointsRaw = MiniJson.arr(root.get("points"));
        float[][] points = new float[pointsRaw.length][];
        for (int i = 0; i < pointsRaw.length; i++) {
            Object[] p = MiniJson.arr(pointsRaw[i]);
            points[i] = new float[p.length];
            for (int j = 0; j < p.length; j++) points[i][j] = (float) MiniJson.num(p[j]);
        }
        return new BrainPointsFile(classes, points);
    }

    public static CircuitFile parseCircuit(String json) {
        Map<String, Object> root = MiniJson.obj(MiniJson.parse(json));
        Object[] neuronsRaw = MiniJson.arr(root.get("neurons"));
        List<CircuitNeuronFile> neurons = new ArrayList<>(neuronsRaw.length);
        for (Object o : neuronsRaw) {
            Map<String, Object> n = MiniJson.obj(o);
            neurons.add(new CircuitNeuronFile(
                    MiniJson.str(n.get("id")), MiniJson.str(n.get("type")),
                    MiniJson.str(n.get("role")), MiniJson.str(n.get("side"))));
        }
        Object[] edgesRaw = MiniJson.arr(root.get("edges"));
        float[][] edges = parseFloatRows(edgesRaw);
        return new CircuitFile(neurons.toArray(new CircuitNeuronFile[0]), edges);
    }

    public static LocomotorCircuitFile parseLocomotor(String json) {
        Map<String, Object> root = MiniJson.obj(MiniJson.parse(json));
        Object[] neuronsRaw = MiniJson.arr(root.get("neurons"));
        List<LocomotorNeuronFile> neurons = new ArrayList<>(neuronsRaw.length);
        for (Object o : neuronsRaw) {
            Map<String, Object> n = MiniJson.obj(o);
            neurons.add(new LocomotorNeuronFile(
                    MiniJson.str(n.get("id")), MiniJson.str(n.get("type")),
                    MiniJson.str(n.get("role")), MiniJson.str(n.get("side")),
                    MiniJson.intOrNull(n.get("leg")),
                    MiniJson.strOrNull(n.get("motorChannel")),
                    MiniJson.strOrNull(n.get("sensoryKind"))));
        }
        Object[] edgesRaw = MiniJson.arr(root.get("edges"));
        float[][] edges = parseFloatRows(edgesRaw);
        return new LocomotorCircuitFile(neurons.toArray(new LocomotorNeuronFile[0]), edges);
    }

    private static float[][] parseFloatRows(Object[] rows) {
        float[][] out = new float[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            Object[] r = MiniJson.arr(rows[i]);
            out[i] = new float[r.length];
            for (int j = 0; j < r.length; j++) out[i][j] = (float) MiniJson.num(r[j]);
        }
        return out;
    }

    private CircuitData() {}
}
