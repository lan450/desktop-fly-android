package com.maltjuice.fly.core;

import com.maltjuice.fly.core.LegDynamics.LegMotorCommand;

/** What the brain tells the body each frame (BrainSignals in Sim.swift). */
public final class BrainSignals {
    public boolean escape;        // giant fiber spiked -> takeoff NOW
    public double nervous;        // looming-detector population rate, 0..1
    public double turnBias;       // rad/s steering from DNa left-right difference
    public boolean backward;      // MDN burst -> backward walking
    public double walkDrive;      // DNp09 forward-walking command rate
    public double groomDrive;     // DNg11 grooming command rate
    public double wingDrive;      // DNp02/04/11 escape-maneuver DN rate
    public double arousal;        // whole-population activity, 0..1
    public double tempo = 1;      // thermal scaling of locomotion
    public boolean sleep;         // circadian + idle -> sleep-like state
    public LegMotorCommand[] legCommands;   // MaleCNS motor output, RF LF RM LM RH LH
}
