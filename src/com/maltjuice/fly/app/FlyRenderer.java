package com.maltjuice.fly.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import com.maltjuice.fly.core.Fly;
import com.maltjuice.fly.core.FlyMath;

/**
 * Top-down 2D canvas painter for the fly: body parts, six FK legs, wings with
 * flight blur, ground shadow and a small HUD. Scene units are the same
 * "points" the Swift body uses; the canvas is scaled by screen density.
 */
final class FlyRenderer {
    // palette from the customized FlyModel.swift (peach body, cream head,
    // plum eyes, warm stripes)
    private static final int HONEY = 0xFFF1A96E;
    private static final int CREAM = 0xFFFFD6A6;
    private static final int PLUM = 0xFF47213B;
    private static final int LEG_COLOR = 0xFFA86357;
    private static final int ABDOMEN = 0xFFDE946E;
    private static final int STRIPE = 0xFFA8575C;

    // abdomen sprite: stripes clipped to the ellipse at 8x supersampling, so
    // no band can ever poke outside the body silhouette (drawn once)
    private static final Bitmap ABDOMEN_BITMAP = buildAbdomenSprite();
    private static final RectF ABDOMEN_DST = new RectF(-14f, -4.5f, 1f, 4.5f);

    private static Bitmap buildAbdomenSprite() {
        final float ss = 8;   // px per model unit
        Bitmap bmp = Bitmap.createBitmap((int) (15 * ss), (int) (9 * ss), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(ABDOMEN);
        c.drawOval(0, 0, 15 * ss, 9 * ss, p);
        Path oval = new Path();
        oval.addOval(0, 0, 15 * ss, 9 * ss, Path.Direction.CW);
        p.setColor(STRIPE);
        c.save();
        c.clipPath(oval);
        // model (mx, my) -> sprite ((mx+7.5)*ss, (my+4.5)*ss)
        c.drawOval(0, 0.1f * ss, 2.6f * ss, 8.9f * ss, p);          // posterior tip
        c.drawRect(3.3f * ss, 0.2f * ss, 4.3f * ss, 8.8f * ss, p);  // stripes
        c.drawRect(4.9f * ss, 0.3f * ss, 5.7f * ss, 8.7f * ss, p);
        c.drawRect(6.3f * ss, 0.4f * ss, 7.0f * ss, 8.6f * ss, p);
        c.restore();
        return bmp;
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    FlyRenderer() {
        paint.setStyle(Paint.Style.FILL);
        hudPaint.setColor(0xCCFFFFFF);
        hudPaint.setTextSize(12);
        hudPaint.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * Draw one fly. Scene coords: origin at screen center, +y up, units =
     * points (dp); converted to screen pixels here. Model local frame is
     * +Y forward, drawn with nose toward +x, hence rotate(-heading).
     */
    void drawFly(Canvas canvas, Fly fly, float cx, float cy, float density) {
        double scale = Fly.FLY_SCALE * (1 + 0.8 * fly.alt);
        float sx = cx + (float) fly.posx * density;
        float sy = cy - (float) fly.posy * density;

        // ---- ground shadow: slides away with altitude ----
        double shadowOff = fly.alt * 55 * density;
        paint.setColor(0x4D000000);
        paint.setAlpha((int) (0x4D * Math.max(0.15, 1 - fly.alt * 0.7)));
        canvas.save();
        canvas.translate(sx - (float) shadowOff * 0.5f, sy - (float) shadowOff);
        canvas.drawOval(-16 * density, -9 * density, 16 * density, 9 * density, paint);
        canvas.restore();

        canvas.save();
        canvas.translate(sx, sy);
        canvas.scale((float) (scale * density), (float) (scale * density));
        canvas.rotate((float) Math.toDegrees(-fly.heading));
        // now: nose = +x, left side of body = +y (screen down = model +x side)
        drawFlyBody(canvas, fly);
        canvas.restore();
    }

    private void drawFlyBody(Canvas canvas, Fly fly) {
        boolean flying = fly.state == Fly.State.FLYING;
        double wingFlight = fly.getWingFlightAmount();
        double stroke = Math.sin(fly.flapPhase * 2 * Math.PI);
        double beat = FlyMath.smoothstep((wingFlight - 0.8) / 0.2);

        // ---- legs (under the body) ----
        for (int i = 0; i < fly.legs.length; i++) {
            Fly.Leg leg = fly.legs[i];
            double hip = leg.angle, elev = leg.lift, knee = leg.kneeAngle;
            // physics direction is (cos yaw, sin yaw) in (side, forward);
            // screen x = forward, screen y = side, hence sin->x, cos->y.
            // In top view femur/tibia/tarsus all project onto the same yaw
            // line, so attach->knee->foot is straight (bends are vertical).
            double yaw = leg.baseYaw + leg.swingSign * hip;
            double kx = leg.geometry.attachY + Math.sin(yaw) * leg.geometry.femur * Math.cos(elev);
            double ky = leg.geometry.attachX + Math.cos(yaw) * leg.geometry.femur * Math.cos(elev);
            double[] foot = Fly.fkFoot(leg, hip, elev, knee);
            // model (x=side, y=fwd) -> screen (x=fwd, y=side)
            float ax = (float) leg.geometry.attachY, ay = (float) leg.geometry.attachX;
            float bx = (float) kx, by = (float) ky;
            float cx = (float) foot[1], cy = (float) foot[0];
            // raised feet: thinner stroke, slight inward offset
            boolean lifted = foot[2] > 1.2;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(lifted ? 1.0f : 1.5f);
            paint.setColor(LEG_COLOR);
            path.reset();
            path.moveTo(ax, ay);
            path.lineTo(bx, by);
            path.lineTo(cx, cy);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF7A4A42);
            canvas.drawCircle(cx, cy, lifted ? 0.7f : 1.0f, paint);
        }

        // ---- body: abdomen sprite (stripes pre-clipped), thorax, head ----
        paint.setFilterBitmap(true);
        canvas.drawBitmap(ABDOMEN_BITMAP, null, ABDOMEN_DST, paint);
        paint.setFilterBitmap(false);

        // thorax
        paint.setColor(HONEY);
        canvas.drawOval(2.5f - 4.6f, -4.4f, 2.5f + 4.6f, 4.4f, paint);

        // head
        paint.setColor(CREAM);
        canvas.drawOval(9.0f - 3.0f, -3.0f, 9.0f + 3.0f, 3.0f, paint);
        // eyes (plum, both sides)
        paint.setColor(PLUM);
        canvas.drawOval(9.7f - 1.8f, -2.1f - 1.6f, 9.7f + 1.8f, -2.1f + 1.6f, paint);
        canvas.drawOval(9.7f - 1.8f, 2.1f - 1.6f, 9.7f + 1.8f, 2.1f + 1.6f, paint);
        // antennae
        paint.setStrokeWidth(0.5f);
        canvas.drawLine(11.6f, -0.9f, 13.6f, -2.2f, paint);
        canvas.drawLine(11.6f, 0.9f, 13.6f, 2.2f, paint);

        // ---- folded / beating wings, drawn OVER the body ----
        // The SceneKit original has wings above the abdomen (z=10.4), and a
        // translucent wing sweeping over the tail never pops against an opaque
        // silhouette; drawing them underneath made the tail rim flicker at the
        // flap rate (membrane sliding in/out of the abdomen's shadow).
        for (int s = 0; s < 2; s++) {
            double side = s == 0 ? -1 : 1;
            float hingeX = 0.5f, hingeY = (float) (side * 1.6);
            double groundedSpread = 0.13 + 0.3 * fly.wingRaise;
            double spread = groundedSpread + (1.1 - groundedSpread) * wingFlight;
            double ang = spread + 0.175 * stroke * beat;   // rad from -fwd axis, outward
            // direction in screen coords (nose +x): backward swing toward -x,
            // outward toward +side*+y
            double dx = -Math.cos(ang);
            double dy = side * Math.sin(ang);
            canvas.save();
            canvas.translate(hingeX, hingeY);
            float wingAlpha = (float) (0.30 + 0.10 * wingFlight);
            paint.setColor(Color.argb((int) (wingAlpha * 255), 0xEB, 0xEB, 0xF2));
            canvas.rotate((float) Math.toDegrees(Math.atan2(dy, dx)));
            // membrane extends along the rotated +x axis = direction (dx, dy)
            // = backward and outward, matching the Swift model's -Y membrane
            canvas.drawOval(0, -2.6f, 16.5f, 2.6f, paint);
            canvas.restore();
        }
        if (wingFlight > 0.02) {
            // motion-blur discs at the wingtips; steady alpha and angle —
            // pulsing these at flap rate reads as flicker, not smear
            float flick = (float) (0.18 * wingFlight);
            paint.setColor(Color.argb((int) (flick * 255), 0xD9, 0xD9, 0xE0));
            for (int s = 0; s < 2; s++) {
                double side = s == 0 ? -1 : 1;
                canvas.save();
                canvas.translate(-2.8f, (float) (side * 8.4));
                // tilt the smear outward-BACKWARD, aligned with the swept
                // membranes (SceneKit z-rotation sign is opposite to canvas)
                canvas.rotate((float) Math.toDegrees(side * 0.45));
                canvas.drawOval(-2.4f, -5.5f, 2.4f, 5.5f, paint);
                canvas.restore();
            }
        }
    }

    /** Dark room background with a soft light pool in the middle. */
    void drawBackground(Canvas canvas, float w, float h) {
        canvas.drawColor(0xFF101014);
        RadialGradient g = new RadialGradient(w / 2, h / 2, Math.max(w, h) * 0.75f,
                new int[]{0xFF232630, 0xFF14151B, 0xFF0C0C10},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        paint.setShader(g);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }

    void drawLedges(Canvas canvas, java.util.List<Fly.Ledge> ledges,
                    float cx, float cy, float density) {
        if (ledges == null) return;
        paint.setColor(0x26FFFFFF);
        for (Fly.Ledge L : ledges) {
            float x0 = cx + (float) L.x0 * density;
            float x1 = cx + (float) L.x1 * density;
            float y = cy - (float) L.y * density;
            canvas.drawRect(x0, y - 2.5f * density, x1, y + 2.5f * density, paint);
        }
    }

    void drawHud(Canvas canvas, String[] lines, float x, float y, float textSize) {
        hudPaint.setTextSize(textSize);
        float maxW = 0;
        for (String line : lines) maxW = Math.max(maxW, hudPaint.measureText(line));
        float avail = canvas.getWidth() - x - 12f;   // keep a right margin
        if (maxW > avail) {                          // shrink to fit, never overflow
            textSize *= avail / maxW;
            hudPaint.setTextSize(textSize);
            maxW = avail;
        }
        paint.setColor(0x66000000);
        canvas.drawRoundRect(x - 8, y - textSize, x + maxW + 8,
                y + lines.length * (textSize * 1.45f) - textSize * 0.5f, 8, 8, paint);
        float ty = y + textSize * 0.2f;
        for (String line : lines) {
            canvas.drawText(line, x, ty, hudPaint);
            ty += textSize * 1.45f;
        }
    }

    void drawHint(Canvas canvas, String text, float cx, float y, float textSize, float alpha) {
        hudPaint.setTextSize(textSize);
        hudPaint.setTextAlign(Paint.Align.CENTER);
        hudPaint.setAlpha((int) (0xCC * alpha));
        canvas.drawText(text, cx, y, hudPaint);
        hudPaint.setAlpha(0xCC);
        hudPaint.setTextAlign(Paint.Align.LEFT);
    }
}
