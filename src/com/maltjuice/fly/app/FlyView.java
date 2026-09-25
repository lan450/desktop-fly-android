package com.maltjuice.fly.app;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import com.maltjuice.fly.core.BrainSignals;
import com.maltjuice.fly.core.CircuitData;
import com.maltjuice.fly.core.Environment;
import com.maltjuice.fly.core.Fly;
import com.maltjuice.fly.core.FlyMath;
import com.maltjuice.fly.core.LIFSim;
import com.maltjuice.fly.core.LocomotorSim;
import com.maltjuice.fly.core.SignalBuilder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * The closed loop: touch senses -> LIF brain (FlyWire circuit) + MaleCNS cord
 * -> SignalBuilder -> fly body -> canvas. One Choreographer-driven thread,
 * 120 Hz sensory sub-ticks like the macOS SimulationClock, ms-accumulator for
 * the 1 kHz neural step. Circadian clock and night sleep included.
 */
public final class FlyView extends View {

    private final FlyRenderer renderer = new FlyRenderer();
    private final SignalBuilder signalBuilder = new SignalBuilder();

    private LIFSim sim;
    private Fly fly;
    private volatile boolean dataReady = false;
    private volatile String dataError = null;
    private String dataInfo = "loading connectome…";
    private String dataLine2 = null;        // second provenance line, set with data

    // scene size in points (dp)
    private float sceneW = 1080, sceneH = 1920;
    private float density = 1;

    // loop state
    private long lastNanos = 0;
    private final Choreographer choreographer = Choreographer.getInstance();
    private double msAccumulator = 0;
    private static final double SENSORY_TICK = 1.0 / 120;

    // touch = "cursor": position in scene coords + kinematics for looming
    private Double mouseX = null, mouseY = null;
    private Double prevMouseX = null, prevMouseY = null;
    private double mouseVelX = 0, mouseVelY = 0;
    private double mouseVelRawX = 0, mouseVelRawY = 0;
    private double mouseSampleDt = 0;
    private long lastTouchMs = 0;

    // environment
    private float activity = 1;
    private boolean sleepy = false;
    private float deviceTempo = 1;          // ectotherm: hot device = faster fly
    private long lastTempoPollMs = 0;
    private double scaleRef = 400;          // min(sceneW, sceneH), set on layout
    private final List<Fly.Ledge> ledges = new ArrayList<>();

    // HUD
    private double fps = 0;
    private long fpsWindowStart = 0;
    private int fpsFrames = 0;
    private double startupElapsed = 0;

    public FlyView(Context context) {
        super(context);
        setKeepScreenOn(true);
        lastTouchMs = System.currentTimeMillis();   // opening the app counts as interaction
        Thread loader = new Thread(this::loadData, "connectome-loader");
        loader.setDaemon(true);
        loader.start();
        choreographer.postFrameCallback(this::frame);
    }

    private void loadData() {
        try {
            String circuit = readAsset("data/circuit.json");
            String cord = readAsset("data/locomotor_circuit.json");
            CircuitData.CircuitFile brain = CircuitData.parseCircuit(circuit);
            CircuitData.LocomotorCircuitFile cordFile = CircuitData.parseLocomotor(cord);
            LocomotorSim locomotor = new LocomotorSim(cordFile);
            sim = new LIFSim(brain, locomotor);
            fly = new Fly(0, 0);
            dataInfo = "FlyWire v783 · brain " + brain.neurons.length + "n/" + brain.edges.length + "e";
            dataLine2 = "MaleCNS v1.0 · cord " + cordFile.neurons.length + "n/" + cordFile.edges.length + "e";
            dataReady = true;
        } catch (Exception e) {
            dataError = "data load failed: " + e;
        }
    }

    private String readAsset(String name) throws Exception {
        InputStream in = getContext().getAssets().open(name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        density = getResources().getDisplayMetrics().density;
        sceneW = w / density;
        sceneH = h / density;
        scaleRef = Math.min(sceneW, sceneH);
        ledges.clear();
        double inset = 60;
        ledges.add(new Fly.Ledge(sceneH / 2 - inset, -sceneW / 2 + inset, sceneW / 2 - inset, 1));
        ledges.add(new Fly.Ledge(-(sceneH / 2 - inset), -sceneW / 2 + inset, sceneW / 2 - inset, 2));
    }

    // ---- main loop ----

    private void frame(long timeNanos) {
        choreographer.postFrameCallback(this::frame);
        if (lastNanos == 0) { lastNanos = timeNanos; return; }
        double dt = Math.min(0.05, Math.max(0, (timeNanos - lastNanos) / 1e9));
        lastNanos = timeNanos;
        fpsFrames++;
        if (fpsWindowStart == 0) fpsWindowStart = timeNanos;
        if (timeNanos - fpsWindowStart >= 1_000_000_000L) {
            fps = fpsFrames * 1e9 / (timeNanos - fpsWindowStart);
            fpsFrames = 0;
            fpsWindowStart = timeNanos;
        }
        updateEnvironment();
        if (dataReady) {
            double remaining = dt;
            while (remaining > 1e-6) {
                double slice = Math.min(SENSORY_TICK, remaining);
                remaining -= slice;
                advanceSimulation(slice);
            }
        }
        startupElapsed += dt;
        postInvalidateOnAnimation();
    }

    private void updateEnvironment() {
        Calendar now = Calendar.getInstance();
        double hour = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60.0;
        activity = Environment.circadianActivity(hour);
        boolean night = hour >= 22 || hour < 6;
        long idleMs = System.currentTimeMillis() - lastTouchMs;
        // same rule as the reference: 10 idle minutes at night, or 30 anywhere
        sleepy = (idleMs > 600_000 && night) || idleMs > 1_800_000;
        pollTempo();
    }

    /** Ectotherm coupling (Environment.swift thermalTempo): map device
     *  thermal state — or battery temperature below API 29 — to the tempo
     *  that scales the leg-physics clock. Polled every 2 s; changes slowly. */
    private void pollTempo() {
        long now = System.currentTimeMillis();
        if (now - lastTempoPollMs < 2000) return;
        lastTempoPollMs = now;
        float t = 1;
        Context ctx = getContext();
        if (Build.VERSION.SDK_INT >= 29) {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                switch (pm.getCurrentThermalStatus()) {
                    case PowerManager.THERMAL_STATUS_LIGHT: t = 1.15f; break;
                    case PowerManager.THERMAL_STATUS_MODERATE: t = 1.35f; break;
                    case PowerManager.THERMAL_STATUS_SEVERE:
                    case PowerManager.THERMAL_STATUS_CRITICAL: t = 1.5f; break;
                    default: t = 1; break;
                }
            }
        } else {
            Intent battery = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                float c = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) / 10f;
                t = c >= 44 ? 1.5f : c >= 40 ? 1.35f : c >= 36 ? 1.15f : 1;
            }
        }
        deviceTempo = t;
    }

    private void advanceSimulation(double dt) {
        if (sim == null || fly == null) return;
        // senses: finger as looming threat + wind. Distance constants are the
        // desktop-tuned values (800/130/500/520 pt on a 1512x982 pt screen)
        // expressed as fractions of the screen's short side, so the falloff
        // fields keep the same spatial meaning on a phone.
        double loomRange = 0.815 * scaleRef;
        double hoverDist = 0.132 * scaleRef;
        double puffRange = 0.509 * scaleRef;
        double loomL = 0, loomR = 0, puff = 0;
        if (mouseX != null) {
            updateMouseVelocity(dt);
            double relX = mouseX - fly.posx, relY = mouseY - fly.posy;
            double dist = Math.max(20, Math.hypot(relX, relY));
            double approach = -(relX * mouseVelX + relY * mouseVelY) / dist;
            double loom = FlyMath.clampf(approach / dist * 6, 0, 1)
                    * FlyMath.clampf(1 - dist / loomRange, 0, 1);
            loom += FlyMath.clampf((hoverDist - dist) / hoverDist, 0, 1) * 0.5;  // finger hovering close
            loom = FlyMath.clampf(loom, 0, 1);
            double fx = Math.cos(fly.heading), fy = Math.sin(fly.heading);
            double rdX = relX / dist, rdY = relY / dist;
            double crossZ = fx * rdY - fy * rdX;   // >0: threat on the fly's left
            double lw = FlyMath.clampf(0.5 + 0.5 * crossZ, 0.12, 1);
            double rw = FlyMath.clampf(0.5 - 0.5 * crossZ, 0.12, 1);
            puff = FlyMath.clampf(Math.hypot(mouseVelX, mouseVelY) / 1500, 0, 1)
                    * FlyMath.clampf(1 - dist / puffRange, 0, 1);
            loomL = loom * lw;
            loomR = loom * rw;
        } else {
            prevMouseX = null;
            mouseVelX = 0; mouseVelY = 0;
        }
        sim.loomL = (float) loomL;
        sim.loomR = (float) loomR;
        sim.airPuff = (float) puff;
        sim.gaitDrive = (float) fly.walkingIntensity();
        sim.gaitPhase = (float) fly.getGaitPhase();
        sim.legFeedback = fly.legFeedback();
        sim.activityScale = (float) ((1 - (1 - activity) * 0.35) * (sleepy ? 0.75 : 1));
        sim.sensoryGate = sleepy ? 0.55f : 1f;

        msAccumulator += dt * 1000;
        int steps = (int) Math.min(50, msAccumulator + 1e-6);
        msAccumulator -= steps;
        sim.step(steps);

        BrainSignals s = signalBuilder.make(sim, dt);
        s.tempo = deviceTempo;
        s.sleep = sleepy;
        fly.terrain = ledges;
        fly.update(dt, sceneW, sceneH, mouseX, mouseY, s);
    }

    private void updateMouseVelocity(double dt) {
        if (prevMouseX != null && dt > 0) {
            mouseSampleDt += dt;
            if ((double) mouseX != prevMouseX || (double) mouseY != prevMouseY || mouseSampleDt >= 1.0 / 30) {
                mouseVelRawX = (mouseX - prevMouseX) / mouseSampleDt;
                mouseVelRawY = (mouseY - prevMouseY) / mouseSampleDt;
                prevMouseX = mouseX;
                prevMouseY = mouseY;
                mouseSampleDt = 0;
            }
            double k = FlyMath.lag(24, dt);
            mouseVelX += (mouseVelRawX - mouseVelX) * k;
            mouseVelY += (mouseVelRawY - mouseVelY) * k;
        } else {
            prevMouseX = mouseX;
            prevMouseY = mouseY;
            mouseSampleDt = 0;
        }
    }

    // ---- touch ----

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        double x = (event.getX() - cx) / density;
        double y = (cy - event.getY()) / density;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                mouseX = x; mouseY = y;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    lastTouchMs = System.currentTimeMillis();
                    injectTap(x, y);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mouseX = null; mouseY = null;
                lastTouchMs = System.currentTimeMillis();
                break;
        }
        return true;
    }

    /** A tap on the substrate: distance-attenuated stimulus into the real
     *  sensory pathway (what a global click does on the Mac). */
    private void injectTap(double x, double y) {
        if (sim == null || fly == null) return;
        double d = Math.hypot(x - fly.posx, y - fly.posy);
        double strength = FlyMath.clampf(1 - d / (0.529 * scaleRef), 0, 1);
        if (strength > 0.05) {
            sim.stimulate(sim.sens, (float) (0.15 + strength * 0.35), 130);
        }
    }

    // ---- draw ----

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        renderer.drawBackground(canvas, w, h);
        if (!dataReady) {
            String msg = dataError != null ? dataError : dataInfo;
            renderer.drawHint(canvas, msg, w / 2, h / 2, 16 * density, 1);
            return;
        }
        renderer.drawLedges(canvas, ledges, w / 2, h / 2, density);
        renderer.drawFly(canvas, fly, w / 2, h / 2, density);

        String stateCn;
        switch (fly.state) {
            case WALKING: stateCn = fly.backwardTimer > 0 ? "倒退走" : "行走"; break;
            case FLYING: stateCn = "飞行"; break;
            case GROOMING: stateCn = "理毛"; break;
            case SLEEPING: stateCn = "睡觉 💤"; break;
            default: stateCn = "停下"; break;
        }
        Calendar now = Calendar.getInstance();
        List<String> hud = new ArrayList<>();
        hud.add("🪰 " + stateCn + " · 速度 " + (int) fly.speed + " pt/s"
                + (fly.alt > 0.01 ? " · 高度 " + String.format("%.0f", fly.alt * 100) + "%" : ""));
        hud.add("脑: pop " + String.format("%.1f", sim.ratePop) + " Hz · 尖峰 "
                + sim.totalSpikes + (sim.locomotor != null ? " · 索 " + sim.locomotor.totalSpikes : ""));
        hud.add("昼夜活动 " + String.format("%.0f%%", activity * 100)
                + " · 温度 ×" + String.format("%.2f", deviceTempo)
                + " · " + String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)));
        hud.add(dataInfo);
        if (dataLine2 != null) hud.add(dataLine2);
        hud.add("fps " + String.format("%.0f", fps));
        renderer.drawHud(canvas, hud.toArray(new String[0]), 16 * density, 40 * density, 13 * density);

        if (startupElapsed < 10) {
            double alpha = startupElapsed < 7 ? 1 : 10 - startupElapsed;
            renderer.drawHint(canvas, "按住手指靠近它=威胁 · 快速滑动=风 · 点一下=戳",
                    w / 2, h - 60 * density, 15 * density, (float) alpha);
        }
    }
}
