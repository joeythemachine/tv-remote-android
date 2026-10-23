package com.joeythemachine.tvremote;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The remote, drawn entirely in code: chrome bezel, mustard body and raised
 * keys that sink into a recessed well when pressed.
 *
 * Everything is laid out in a 314 x 514 "design" space and scaled to fit.
 */
public class RemoteView extends View {

    public interface Actions {
        void onKey(String key);
        void onPower();
        void onExit();
        void onStatusHold();
        void onPlex();
        void onKeyboard();
    }

    private static final float DW = 314f, DH = 588f;

    private static final int KEY_BROWN = 0xFF4B4637;
    private static final int KEY_POWER = 0xFFC2522B;
    private static final int KEY_OK    = 0xFF1F6650;
    private static final int SIDE      = 0xFF2B2619;
    private static final int CREAM     = 0xFFF3E3B6;

    private enum Glyph { TEXT, UP, DOWN, LEFT, RIGHT, MINUS, PLUS, PLEX, KEYBOARD }

    private static class Key {
        final String action;   // "POWER" or a KEY_ code
        final String label;
        final Glyph glyph;
        final int color;
        final RectF cell;      // includes the space under the key's side
        float press;           // 0 = up, 1 = fully pressed
        ValueAnimator anim;
        boolean repeats;

        Key(String action, String label, Glyph glyph, int color, RectF cell) {
            this.action = action; this.label = label; this.glyph = glyph; this.color = color; this.cell = cell;
        }
    }

    private final List<Key> keys = new ArrayList<>();
    private final Actions actions;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF tmp = new RectF();

    private Shader bezelShader, ringShader, bodyShader;
    private float scale = 1f, offX = 0f, offY = 0f;
    private String status = "";
    private Key active;
    private boolean exitPressed;
    private final RectF exitHit = new RectF(262, 536, 292, 568);
    private final RectF statusHit = new RectF(30, 534, 256, 572);
    private boolean statusPressed;
    private final Runnable statusHold = new Runnable() {
        @Override public void run() {
            if (statusPressed) {
                statusPressed = false;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                actions.onStatusHold();
            }
        }
    };

    private final Runnable repeater = new Runnable() {
        @Override public void run() {
            if (active != null && active.repeats) {
                actions.onKey(active.action);
                handler.postDelayed(this, 220);
            }
        }
    };

    public RemoteView(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        setHapticFeedbackEnabled(true);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);
        buildKeys();
        buildShaders();
    }

    public void setStatus(String s) {
        status = s == null ? "" : s;
        invalidate();
    }

    // ---------------------------------------------------------------- layout

    private void buildKeys() {
        float left = 22f, width = 270f;

        // Power / Source
        float y = 26f, h = 62f, half = width / 2f;
        addKey("POWER", "Power", Glyph.TEXT, KEY_POWER, new RectF(left + 4, y + 4, left + half - 8, y + h - 4));
        addKey("KEY_SOURCE", "Source", Glyph.TEXT, KEY_BROWN, new RectF(left + half + 8, y + 4, left + width - 4, y + h - 4));

        // Plex / Keyboard
        y = 100f;
        addKey("PLEX", "Plex", Glyph.PLEX, KEY_BROWN, new RectF(left + 4, y + 4, left + half - 8, y + h - 4));
        addKey("KEYBOARD", "", Glyph.KEYBOARD, KEY_BROWN, new RectF(left + half + 8, y + 4, left + width - 4, y + h - 4));

        // D-pad: columns 0.88 : 1 : 0.88
        float unit = width / 2.76f, c0 = unit * 0.88f, c1 = unit;
        float x0 = left, x1 = left + c0, x2 = left + c0 + c1, x3 = left + width;
        float up = 174f, mid = 232f, down = 298f, end = 356f;
        addKey("KEY_UP", "", Glyph.UP, KEY_BROWN, inset(x1, up, x2, mid));
        addKey("KEY_LEFT", "", Glyph.LEFT, KEY_BROWN, inset(x0, mid, x1, down));
        addKey("KEY_ENTER", "OK", Glyph.TEXT, KEY_OK, inset(x1, mid, x2, down));
        addKey("KEY_RIGHT", "", Glyph.RIGHT, KEY_BROWN, inset(x2, mid, x3, down));
        addKey("KEY_DOWN", "", Glyph.DOWN, KEY_BROWN, inset(x1, down, x2, end));

        // Back / Home / Menu
        float third = width / 3f;
        addKey("KEY_RETURN", "Back", Glyph.TEXT, KEY_BROWN, inset(left, 370, left + third, 430));
        addKey("KEY_HOME", "Home", Glyph.TEXT, KEY_BROWN, inset(left + third, 370, left + 2 * third, 430));
        addKey("KEY_MENU", "Menu", Glyph.TEXT, KEY_BROWN, inset(left + 2 * third, 370, left + width, 430));

        // Volume
        Key down1 = addKey("KEY_VOLDOWN", "", Glyph.MINUS, KEY_BROWN, inset(left, 460, left + third, 526));
        addKey("KEY_MUTE", "Mute", Glyph.TEXT, KEY_BROWN, inset(left + third, 460, left + 2 * third, 526));
        Key up1 = addKey("KEY_VOLUP", "", Glyph.PLUS, KEY_BROWN, inset(left + 2 * third, 460, left + width, 526));
        down1.repeats = true;
        up1.repeats = true;
    }

    private static RectF inset(float l, float t, float r, float b) {
        return new RectF(l + 5, t + 5, r - 5, b - 5);
    }

    private Key addKey(String action, String label, Glyph g, int color, RectF cell) {
        Key k = new Key(action, label, g, color, cell);
        keys.add(k);
        return k;
    }

    private void buildShaders() {
        bezelShader = new LinearGradient(0, 0, DW, DH,
                new int[]{0xFFFAFAFA, 0xFF8E8E8E, 0xFFEDEDED, 0xFF6A6A6A, 0xFFDCDCDC},
                new float[]{0f, 0.22f, 0.45f, 0.7f, 1f}, Shader.TileMode.CLAMP);
        ringShader = new LinearGradient(DW, DH, 0, 0,
                new int[]{0xFFF4F4F4, 0xFF7C7C7C, 0xFFE2E2E2}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        bodyShader = new RadialGradient(DW * 0.45f, DH * 0.35f, DH * 0.75f,
                0xFFDBA634, 0xFFCC9524, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float margin = Math.min(w, h) * 0.04f;
        scale = Math.min((w - 2 * margin) / DW, (h - 2 * margin) / DH);
        offX = (w - DW * scale) / 2f;
        offY = (h - DH * scale) / 2f;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.translate(offX, offY);
        canvas.scale(scale, scale);

        fill.setStyle(Paint.Style.FILL);

        // chrome bezel and mustard body
        fill.setShader(bezelShader);
        canvas.drawRoundRect(0, 0, DW, DH, 38, 38, fill);
        fill.setShader(null);
        fill.setColor(0xFF3A3A3A);
        canvas.drawRoundRect(2.5f, 2.5f, DW - 2.5f, DH - 2.5f, 36, 36, fill);
        fill.setShader(ringShader);
        canvas.drawRoundRect(6.5f, 6.5f, DW - 6.5f, DH - 6.5f, 33, 33, fill);
        fill.setShader(bodyShader);
        canvas.drawRoundRect(8, 8, DW - 8, DH - 8, 31.5f, 31.5f, fill);
        fill.setShader(null);

        for (Key k : keys) drawKey(canvas, k);

        // VOLUME label
        text.setColor(0xFFF6E7BE);
        text.setTextSize(11);
        text.setLetterSpacing(0.35f);
        canvas.drawText("VOLUME", DW / 2f, 449, text);
        text.setLetterSpacing(0f);

        // status
        text.setColor(0xFF6E5216);
        text.setTextSize(11);
        canvas.drawText(status, DW / 2f, 556, text);

        // tiny exit X
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.6f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setColor(exitPressed ? SIDE : 0xFF7A5C1A);
        float cx = 277, cy = 552;
        canvas.drawLine(cx - 4, cy - 4, cx + 4, cy + 4, stroke);
        canvas.drawLine(cx + 4, cy - 4, cx - 4, cy + 4, stroke);

        canvas.restore();
    }

    private void drawKey(Canvas canvas, Key k) {
        RectF c = k.cell;
        float p = k.press;

        // recessed well (fades in while pressed)
        if (p > 0f) {
            int a = (int) (255 * p);
            tmp.set(c.left - 4, c.top - 4, c.right + 4, c.bottom);
            fill.setShader(new LinearGradient(0, tmp.top, 0, tmp.bottom,
                    Color.argb(a, 0x9C, 0x73, 0x18), Color.argb(a, 0xB9, 0x8A, 0x20), Shader.TileMode.CLAMP));
            canvas.drawRoundRect(tmp, 11, 11, fill);
            fill.setShader(null);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.5f);
            stroke.setColor(Color.argb(a, 0x7A, 0x5A, 0x12));
            canvas.drawRoundRect(tmp, 11, 11, stroke);
        }

        // dark underside
        fill.setColor(SIDE);
        canvas.drawRoundRect(c.left, c.top + 5, c.right, c.bottom, 8, 8, fill);

        // face, shifted down when pressed
        float shift = 4f * p;
        tmp.set(c.left, c.top + shift, c.right, c.bottom - 5 + shift);
        fill.setColor(k.color);
        canvas.drawRoundRect(tmp, 8, 8, fill);

        // soft top highlight
        fill.setShader(new LinearGradient(0, tmp.top, 0, tmp.bottom,
                new int[]{0x1FFFFFFF, 0x00FFFFFF, 0x14000000}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(tmp, 8, 8, fill);
        fill.setShader(null);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.2f);
        stroke.setColor(SIDE);
        canvas.drawRoundRect(tmp, 8, 8, stroke);

        // label or symbol
        float cx = tmp.centerX(), cy = tmp.centerY();
        fill.setColor(CREAM);
        path.reset();
        switch (k.glyph) {
            case TEXT:
                text.setColor(CREAM);
                text.setTextSize("OK".equals(k.label) ? 22 : 18);
                Paint.FontMetrics fm = text.getFontMetrics();
                canvas.drawText(k.label, cx, cy - (fm.ascent + fm.descent) / 2f, text);
                break;
            case UP:
                path.moveTo(cx, cy - 6.5f); path.lineTo(cx + 8, cy + 6.5f); path.lineTo(cx - 8, cy + 6.5f); path.close();
                canvas.drawPath(path, fill);
                break;
            case DOWN:
                path.moveTo(cx, cy + 6.5f); path.lineTo(cx + 8, cy - 6.5f); path.lineTo(cx - 8, cy - 6.5f); path.close();
                canvas.drawPath(path, fill);
                break;
            case LEFT:
                path.moveTo(cx - 6.5f, cy); path.lineTo(cx + 6.5f, cy - 8); path.lineTo(cx + 6.5f, cy + 8); path.close();
                canvas.drawPath(path, fill);
                break;
            case RIGHT:
                path.moveTo(cx + 6.5f, cy); path.lineTo(cx - 6.5f, cy - 8); path.lineTo(cx - 6.5f, cy + 8); path.close();
                canvas.drawPath(path, fill);
                break;
            case PLEX: {
                // "Plex" with its gold chevron
                text.setColor(CREAM);
                text.setTextSize(18);
                Paint.FontMetrics pm = text.getFontMetrics();
                float tw = text.measureText("Plex");
                float base = cy - (pm.ascent + pm.descent) / 2f;
                canvas.drawText("Plex", cx - 7, base, text);
                float chx = cx - 7 + tw / 2f + 6;
                fill.setColor(0xFFE5A00D);
                path.moveTo(chx, cy - 8); path.lineTo(chx + 5, cy - 8); path.lineTo(chx + 11, cy);
                path.lineTo(chx + 5, cy + 8); path.lineTo(chx, cy + 8); path.lineTo(chx + 6, cy); path.close();
                canvas.drawPath(path, fill);
                fill.setColor(CREAM);
                break;
            }
            case KEYBOARD: {
                // a little keyboard drawing
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(1.6f);
                stroke.setColor(CREAM);
                canvas.drawRoundRect(cx - 17, cy - 11, cx + 17, cy + 11, 3, 3, stroke);
                for (int row = 0; row < 2; row++) {
                    for (int col = 0; col < 6; col++) {
                        float kx = cx - 13 + col * 5.2f, ky = cy - 7 + row * 5;
                        canvas.drawRect(kx, ky, kx + 3, ky + 3, fill);
                    }
                }
                canvas.drawRect(cx - 9, cy + 4, cx + 9, cy + 7, fill);
                break;
            }
            case MINUS:
                canvas.drawRect(cx - 11, cy - 2, cx + 11, cy + 2, fill);
                break;
            case PLUS:
                canvas.drawRect(cx - 11, cy - 2, cx + 11, cy + 2, fill);
                canvas.drawRect(cx - 2, cy - 11, cx + 2, cy + 11, fill);
                break;
        }
    }

    // ---------------------------------------------------------------- touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = (e.getX() - offX) / scale, y = (e.getY() - offY) / scale;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (exitHit.contains(x, y)) { exitPressed = true; invalidate(); return true; }
                if (statusHit.contains(x, y)) { statusPressed = true; handler.postDelayed(statusHold, 600); return true; }
                Key k = keyAt(x, y);
                if (k != null) {
                    active = k;
                    animate(k, 1f, 60);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (k.repeats) handler.postDelayed(repeater, 450);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (active != null && !grow(active.cell).contains(x, y)) release(false);
                if (exitPressed && !exitHit.contains(x, y)) { exitPressed = false; invalidate(); }
                if (statusPressed && !statusHit.contains(x, y)) { statusPressed = false; handler.removeCallbacks(statusHold); }
                return true;
            case MotionEvent.ACTION_UP:
                if (statusPressed) { statusPressed = false; handler.removeCallbacks(statusHold); return true; }
                if (exitPressed) {
                    exitPressed = false; invalidate();
                    if (exitHit.contains(x, y)) actions.onExit();
                    return true;
                }
                if (active != null) release(true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                exitPressed = false;
                statusPressed = false;
                handler.removeCallbacks(statusHold);
                if (active != null) release(false);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void release(boolean fire) {
        Key k = active;
        active = null;
        handler.removeCallbacks(repeater);
        animate(k, 0f, 140);
        if (!fire) return;
        if ("POWER".equals(k.action)) actions.onPower();
        else if ("PLEX".equals(k.action)) actions.onPlex();
        else if ("KEYBOARD".equals(k.action)) actions.onKeyboard();
        else actions.onKey(k.action);
    }

    private Key keyAt(float x, float y) {
        for (Key k : keys) if (grow(k.cell).contains(x, y)) return k;
        return null;
    }

    private RectF grow(RectF r) {
        return new RectF(r.left - 4, r.top - 4, r.right + 4, r.bottom + 4);
    }

    private void animate(Key k, float to, long ms) {
        if (k.anim != null) k.anim.cancel();
        k.anim = ValueAnimator.ofFloat(k.press, to);
        k.anim.setDuration(ms);
        k.anim.addUpdateListener(a -> { k.press = (float) a.getAnimatedValue(); invalidate(); });
        k.anim.start();
    }
}
