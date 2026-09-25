package com.maltjuice.fly.core;

import static com.maltjuice.fly.core.FlyMath.clampf;
import static com.maltjuice.fly.core.FlyMath.lag;

/** Converts sim population rates into body commands (SignalBuilder in main.swift). */
public final class SignalBuilder {
    private double dnaBaseline = 0;

    public BrainSignals make(LIFSim sim, double dt) {
        float diff = sim.rateDNaL - sim.rateDNaR;
        // Slow adaptation (tau ~8 s): steady-state walking is straight; only
        // transient DNa asymmetries (visual, stimulation) steer.
        dnaBaseline += (diff - dnaBaseline) * lag(1.0 / 8, dt);
        BrainSignals s = new BrainSignals();
        s.escape = sim.consumeGF();
        s.nervous = clampf(sim.rateLoom / 80.0, 0, 1);
        s.turnBias = clampf((diff - dnaBaseline) * 0.04, -1.0, 1.0);
        s.backward = sim.rateMDN > 8;
        s.walkDrive = clampf(sim.rateFwd / 10.0, 0, 1.3);
        s.groomDrive = sim.rateGroom / 8.0;
        s.wingDrive = clampf(sim.rateEscW / 10.0, 0, 1.3);
        s.arousal = clampf(sim.ratePop / 20.0, 0, 1);
        s.legCommands = sim.locomotor != null ? sim.locomotor.commands : null;
        return s;
    }
}
