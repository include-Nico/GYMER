package it.gymer.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Esperienza nativa lockscreen GYMER:
 * - Modalita' "run": card glassmorphism con glow dinamico, anello neon con puntino luminoso e swipe-to-dismiss.
 * - Modalita' "done": risveglio schermo OLED minimale con controllo 'X' centrato al millimetro.
 */
public class LockScreenActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean doneMode = false;

    // UI Run
    private AmbientBgView ambientRun;
    private HeroRingView heroRing;
    private TextView statusPill;
    private TextView endPill;
    private ModernActionBtn bPlay;
    private LinearLayout card;

    // UI Done
    private Ringtone alarmRingtone;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (doneMode) return;
            TimerState.load(LockScreenActivity.this);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (!TimerState.active() || (km != null && !km.isKeyguardLocked())) {
                finish();
                return;
            }
            render();
            h.postDelayed(this, 50);
        }
    };

    private final Runnable autoStopDone = this::finishDone;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private int themeC1() {
        return TimerState.sp(this).getInt("c1", 0xFF8B7BFF);
    }

    private int themeC2() {
        return TimerState.sp(this).getInt("c2", 0xFF27D7FF);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        doneMode = "done".equals(getIntent() != null ? getIntent().getStringExtra("mode") : null);

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(doneMode);
        } else {
            int flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
            if (doneMode) flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
            getWindow().addFlags(flags);
        }
        if (doneMode) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            acquireWake();
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        if (doneMode) {
            buildDoneUi();
            startDoneAlert();
            h.postDelayed(autoStopDone, 30000);
        } else {
            buildRunningUi();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if ("done".equals(intent != null ? intent.getStringExtra("mode") : null) && !doneMode) {
            h.removeCallbacks(tick);
            doneMode = true;
            if (Build.VERSION.SDK_INT >= 27) setTurnScreenOn(true);
            else getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            acquireWake();
            buildDoneUi();
            startDoneAlert();
            h.removeCallbacks(autoStopDone);
            h.postDelayed(autoStopDone, 30000);
        }
    }

    private void acquireWake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                        "gymer:doneWake");
                wakeLock.acquire(32000L);
            }
        } catch (Exception ignored) {}
    }

    // =====================================================================
    // UI 1: Timer in corso (Glassmorphism + Glow + Anello con punta luminosa)
    // =====================================================================
    private void buildRunningUi() {
        int c1 = themeC1();
        int c2 = themeC2();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xF206060C);
        root.setOnClickListener(v -> finish());

        ambientRun = new AmbientBgView(this, c1, c2);
        root.addView(ambientRun, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(22), dp(24), dp(26));
        card.setElevation(dp(18));

        GradientDrawable cardBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE6161628, 0xF20D0D18}
        );
        cardBg.setCornerRadius(dp(34));
        cardBg.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(cardBg);
        card.setClickable(true);

        View handle = new View(this);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(0x38FFFFFF);
        handleBg.setCornerRadius(dp(4));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(dp(38), dp(4));
        hLp.bottomMargin = dp(16);
        card.addView(handle, hLp);

        statusPill = new TextView(this);
        statusPill.setText("●  RECUPERO ATTIVO");
        statusPill.setTextColor(c2);
        statusPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusPill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        statusPill.setLetterSpacing(0.11f);
        statusPill.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(withAlpha(c1, 36));
        pillBg.setCornerRadius(dp(20));
        pillBg.setStroke(dp(1), withAlpha(c2, 75));
        statusPill.setBackground(pillBg);
        card.addView(statusPill);

        heroRing = new HeroRingView(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(210), dp(210));
        rlp.topMargin = dp(20);
        rlp.bottomMargin = dp(14);
        card.addView(heroRing, rlp);

        endPill = new TextView(this);
        endPill.setTextColor(0xCCFFFFFF);
        endPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        endPill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        endPill.setPadding(dp(16), dp(7), dp(16), dp(7));
        GradientDrawable endBg = new GradientDrawable();
        endBg.setColor(0x14FFFFFF);
        endBg.setCornerRadius(dp(16));
        endPill.setBackground(endBg);
        card.addView(endPill);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(24);

        ModernActionBtn bStop = new ModernActionBtn(this, ModernActionBtn.STOP, 58, 0x1FFFFFFF, 0x26FFFFFF);
        bPlay = new ModernActionBtn(this, ModernActionBtn.PAUSE, 74, c1, c2);
        ModernActionBtn bAdd = new ModernActionBtn(this, ModernActionBtn.ADD, 58, 0x1FFFFFFF, 0x26FFFFFF);

        LinearLayout.LayoutParams pSide = new LinearLayout.LayoutParams(dp(58), dp(58));
        LinearLayout.LayoutParams pMid = new LinearLayout.LayoutParams(dp(74), dp(74));
        pMid.leftMargin = dp(20);
        pMid.rightMargin = dp(20);

        row.addView(bStop, pSide);
        row.addView(bPlay, pMid);
        row.addView(bAdd, pSide);
        card.addView(row, rowLp);

        card.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            boolean dragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (!dragging && (Math.abs(dx) > dp(10) || Math.abs(dy) > dp(10))) {
                            dragging = true;
                        }
                        if (dragging) {
                            card.setTranslationX(dx);
                            card.setTranslationY(dy);
                            card.setRotation(dx * 0.025f);
                            float dist = (float) Math.hypot(dx, dy);
                            float alpha = Math.max(0.2f, 1f - dist / dp(260));
                            card.setAlpha(alpha);
                            ambientRun.setAlpha(alpha);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            float dx2 = e.getRawX() - downX;
                            float dy2 = e.getRawY() - downY;
                            if (Math.hypot(dx2, dy2) > dp(85)) {
                                card.animate()
                                        .translationX(dx2 * 2.6f)
                                        .translationY(dy2 * 2.6f)
                                        .rotation(dx2 * 0.08f)
                                        .alpha(0f)
                                        .setDuration(170)
                                        .setListener(new AnimatorListenerAdapter() {
                                            @Override public void onAnimationEnd(Animator animation) { finish(); }
                                        }).start();
                            } else {
                                card.animate()
                                        .translationX(0f)
                                        .translationY(0f)
                                        .rotation(0f)
                                        .alpha(1f)
                                        .setInterpolator(new DecelerateInterpolator())
                                        .setDuration(200)
                                        .start();
                                ambientRun.animate().alpha(1f).setDuration(200).start();
                            }
                        }
                        return true;
                }
                return false;
            }
        });

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        clp.leftMargin = dp(20);
        clp.rightMargin = dp(20);
        root.addView(card, clp);
        setContentView(root);

        card.setScaleX(0.93f);
        card.setScaleY(0.93f);
        card.setAlpha(0f);
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();

        bStop.setOnClickListener(v -> {
            TimerState.act(this, "stop");
            TimerState.refreshService(this);
            finish();
        });
        bPlay.setOnClickListener(v -> {
            TimerState.act(this, "toggle");
            TimerState.refreshService(this);
            render();
        });
        bAdd.setOnClickListener(v -> {
            TimerState.act(this, "add");
            TimerState.refreshService(this);
            render();
        });
    }

    // =====================================================================
    // UI 2: Fine timer a schermo nero (Design OLED ultra-pulito e centrato)
    // =====================================================================
    private void buildDoneUi() {
        int c1 = themeC1();
        int c2 = themeC2();

        // Unica vista canvas a tutto schermo: testata elegante + anello e 'X' perfettamente coassiali
        DoneFullScreenView doneView = new DoneFullScreenView(this, c1, c2, this::finishDone);
        setContentView(doneView);
    }

    /**
     * Schermata intera di fine timer disegnata su un unico Canvas:
     * ogni bagliore, onda, anello e pulsante X condivide esattamente lo stesso centro matematico.
     */
    static class DoneFullScreenView extends View {
        final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint pillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint pillBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint brandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        final Paint targetTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint targetFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint progressArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint btnFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint btnBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint xPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        final RectF pillRect = new RectF();
        final RectF arcRect = new RectF();
        final float d;
        final int c1, c2;
        final Runnable onDismiss;

        float offX = 0f, offY = 0f;
        boolean dragging = false;
        float breathe = 0f;
        ValueAnimator breatheAnim;
        ValueAnimator snapAnim;

        DoneFullScreenView(Context c, int c1, int c2, Runnable onDismiss) {
            super(c);
            this.c1 = c1;
            this.c2 = c2;
            this.onDismiss = onDismiss;
            d = c.getResources().getDisplayMetrics().density;

            wavePaint.setStyle(Paint.Style.STROKE);
            wavePaint.setStrokeWidth(1.5f * d);

            pillBgPaint.setStyle(Paint.Style.FILL);
            pillBgPaint.setColor(withAlpha(c1, 34));

            pillBorderPaint.setStyle(Paint.Style.STROKE);
            pillBorderPaint.setStrokeWidth(1f * d);
            pillBorderPaint.setColor(withAlpha(c2, 90));

            brandPaint.setColor(c2);
            brandPaint.setTextAlign(Paint.Align.CENTER);
            brandPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            brandPaint.setTextSize(11.5f * d);
            brandPaint.setLetterSpacing(0.16f);

            timePaint.setColor(Color.WHITE);
            timePaint.setTextAlign(Paint.Align.CENTER);
            timePaint.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
            timePaint.setTextSize(78f * d);
            timePaint.setLetterSpacing(-0.02f);

            subPaint.setColor(0xB8FFFFFF);
            subPaint.setTextAlign(Paint.Align.CENTER);
            subPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            subPaint.setTextSize(16f * d);

            hintPaint.setColor(0x59FFFFFF);
            hintPaint.setTextAlign(Paint.Align.CENTER);
            hintPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            hintPaint.setTextSize(11f * d);
            hintPaint.setLetterSpacing(0.14f);

            targetTrackPaint.setStyle(Paint.Style.STROKE);
            targetTrackPaint.setStrokeWidth(1.5f * d);
            targetTrackPaint.setColor(0x26FFFFFF);

            targetFillPaint.setStyle(Paint.Style.FILL);
            targetFillPaint.setColor(0x0AFFFFFF);

            progressArcPaint.setStyle(Paint.Style.STROKE);
            progressArcPaint.setStrokeWidth(3.5f * d);
            progressArcPaint.setStrokeCap(Paint.Cap.ROUND);

            btnFillPaint.setStyle(Paint.Style.FILL);
            btnBorderPaint.setStyle(Paint.Style.STROKE);
            btnBorderPaint.setStrokeWidth(1.5f * d);

            xPaint.setStyle(Paint.Style.STROKE);
            xPaint.setStrokeWidth(3.2f * d);
            xPaint.setStrokeCap(Paint.Cap.ROUND);
            xPaint.setColor(Color.WHITE);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            breatheAnim = ValueAnimator.ofFloat(0f, 1f);
            breatheAnim.setDuration(2000);
            breatheAnim.setRepeatCount(ValueAnimator.INFINITE);
            breatheAnim.setRepeatMode(ValueAnimator.REVERSE);
            breatheAnim.setInterpolator(new DecelerateInterpolator());
            breatheAnim.addUpdateListener(a -> {
                breathe = (Float) a.getAnimatedValue();
                invalidate();
            });
            breatheAnim.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (breatheAnim != null) breatheAnim.cancel();
            if (snapAnim != null) snapAnim.cancel();
            super.onDetachedFromWindow();
        }

        private float ringCenterX() { return getWidth() / 2f; }
        private float ringCenterY() { return getHeight() * 0.72f; }
        private float targetRadius() { return 104f * d; }
        private float buttonRadius() { return 36f * d; }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            // 1. Sfondo nero OLED puro
            c.drawColor(0xFF05050A);

            float cx = ringCenterX();
            float cy = ringCenterY();
            float maxR = targetRadius();

            // 2. Bagliore radiale centrato ESATTAMENTE sul bersaglio della X (cx, cy)
            float glowR = maxR * (2.1f + 0.25f * breathe);
            glowPaint.setShader(new RadialGradient(cx, cy, glowR,
                    new int[]{withAlpha(c1, 62), withAlpha(c2, 22), Color.TRANSPARENT},
                    null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, glowR, glowPaint);

            // 3. Testata superiore minimale (pillola + 00:00 + sottotitolo)
            float topY = h * 0.19f;
            String badgeText = "GYMER  ·  FINE RECUPERO";
            float badgeW = brandPaint.measureText(badgeText) + 34 * d;
            float badgeH = 28 * d;
            pillRect.set(cx - badgeW / 2f, topY - badgeH / 2f, cx + badgeW / 2f, topY + badgeH / 2f);
            c.drawRoundRect(pillRect, badgeH / 2f, badgeH / 2f, pillBgPaint);
            c.drawRoundRect(pillRect, badgeH / 2f, badgeH / 2f, pillBorderPaint);
            c.drawText(badgeText, cx, topY - (brandPaint.ascent() + brandPaint.descent()) / 2f, brandPaint);

            float timeY = topY + 86 * d;
            c.drawText("00:00", cx, timeY, timePaint);
            c.drawText("Recupero terminato · Tocca a te", cx, timeY + 32 * d, subPaint);

            // 4. Cerchio bersaglio + onda respiro coassiale (stesso identico cx, cy)
            float dist = (float) Math.hypot(offX, offY);
            float progress = Math.min(1f, dist / (maxR * 0.78f));

            c.drawCircle(cx, cy, maxR, targetFillPaint);
            c.drawCircle(cx, cy, maxR, targetTrackPaint);

            if (!dragging) {
                float pulseR = maxR + (18f * d * breathe);
                wavePaint.setColor(withAlpha(c2, (int) ((1f - breathe) * 75)));
                c.drawCircle(cx, cy, pulseR, wavePaint);
            }

            // Arco progressivo che si riempie mentre trascini la X verso il bordo
            if (progress > 0.02f) {
                arcRect.set(cx - maxR, cy - maxR, cx + maxR, cy + maxR);
                progressArcPaint.setShader(new SweepGradient(cx, cy, new int[]{c1, c2, 0xFFFF4D6D, c1}, null));
                c.drawArc(arcRect, -90f, 360f * progress, false, progressArcPaint);
            }

            // 5. Pulsante centrale 'X'
            float bx = cx + offX;
            float by = cy + offY;
            float btnR = buttonRadius() + (dragging ? 3f * d : 0f);

            if (dragging) {
                btnFillPaint.setShader(new LinearGradient(bx - btnR, by - btnR, bx + btnR, by + btnR,
                        0xFFFF4D6D, 0xFFFF7A59, Shader.TileMode.CLAMP));
                btnBorderPaint.setColor(0x99FFFFFF);
            } else {
                btnFillPaint.setShader(new LinearGradient(bx - btnR, by - btnR, bx + btnR, by + btnR,
                        0xFF1E1E30, 0xFF131320, Shader.TileMode.CLAMP));
                btnBorderPaint.setColor(0x4DFFFFFF);
            }

            c.drawCircle(bx, by, btnR, btnFillPaint);
            c.drawCircle(bx, by, btnR, btnBorderPaint);

            float arm = 11f * d;
            c.drawLine(bx - arm, by - arm, bx + arm, by + arm, xPaint);
            c.drawLine(bx + arm, by - arm, bx - arm, by + arm, xPaint);

            // 6. Testo guida sotto il cerchio
            c.drawText("TRASCINA LA X PER CHIUDERE", cx, cy + maxR + 34 * d, hintPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float cx = ringCenterX();
            float cy = ringCenterY();
            float maxR = targetRadius();
            float hitR = buttonRadius() * 1.55f;

            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (Math.hypot(e.getX() - cx, e.getY() - cy) <= hitR) {
                        if (snapAnim != null) snapAnim.cancel();
                        dragging = true;
                        offX = 0f;
                        offY = 0f;
                        invalidate();
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return false;
                    offX = e.getX() - cx;
                    offY = e.getY() - cy;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging) return false;
                    dragging = false;
                    if (Math.hypot(offX, offY) >= maxR * 0.78f) {
                        onDismiss.run();
                    } else {
                        final float startX = offX;
                        final float startY = offY;
                        snapAnim = ValueAnimator.ofFloat(1f, 0f);
                        snapAnim.setDuration(220);
                        snapAnim.setInterpolator(new OvershootInterpolator(1.2f));
                        snapAnim.addUpdateListener(a -> {
                            float f = (Float) a.getAnimatedValue();
                            offX = startX * f;
                            offY = startY * f;
                            invalidate();
                        });
                        snapAnim.start();
                    }
                    return true;
            }
            return false;
        }
    }

    // =====================================================================
    // Componenti Grafici Custom (Run UI)
    // =====================================================================

    static class AmbientBgView extends View {
        final Paint p1 = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint p2 = new Paint(Paint.ANTI_ALIAS_FLAG);
        int c1, c2;

        AmbientBgView(Context c, int c1, int c2) {
            super(c);
            this.c1 = c1;
            this.c2 = c2;
        }

        void setColors(int a, int b) {
            if (a != c1 || b != c2) {
                c1 = a;
                c2 = b;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            p1.setShader(new RadialGradient(w * 0.25f, h * 0.30f, w * 0.75f,
                    new int[]{withAlpha(c1, 68), Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
            p2.setShader(new RadialGradient(w * 0.80f, h * 0.72f, w * 0.75f,
                    new int[]{withAlpha(c2, 52), Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p1);
            c.drawRect(0, 0, w, h, p2);
        }
    }

    static class HeroRingView extends View {
        final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint tipDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint subLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        final float d;
        float frac = 1f;
        String text = "0:00";
        String label = "RIMANENTI";
        int c1 = 0xFF8B7BFF, c2 = 0xFF27D7FF;

        HeroRingView(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            d = c.getResources().getDisplayMetrics().density;

            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(10 * d);
            track.setColor(0x1FFFFFFF);

            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeWidth(14 * d);
            glow.setStrokeCap(Paint.Cap.ROUND);
            glow.setMaskFilter(new BlurMaskFilter(10 * d, BlurMaskFilter.Blur.NORMAL));

            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(10 * d);
            arc.setStrokeCap(Paint.Cap.ROUND);

            tipDot.setStyle(Paint.Style.FILL);
            tipDot.setColor(Color.WHITE);

            txt.setColor(Color.WHITE);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            txt.setTextSize(46 * d);

            subLabel.setColor(0x73FFFFFF);
            subLabel.setTextAlign(Paint.Align.CENTER);
            subLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            subLabel.setTextSize(10.5f * d);
            subLabel.setLetterSpacing(0.14f);
        }

        void set(float f, String t, String lbl, int a, int b) {
            frac = Math.max(0f, Math.min(1f, f));
            text = t;
            label = lbl;
            c1 = a;
            c2 = b;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = getWidth();
            float cx = s / 2f, cy = s / 2f;
            float pad = 18 * d;
            r.set(pad, pad, s - pad, s - pad);
            float radius = (s - 2 * pad) / 2f;

            c.drawOval(r, track);

            if (frac > 0.003f) {
                SweepGradient sg = new SweepGradient(cx, cy, new int[]{c1, c2, c1}, null);
                glow.setShader(sg);
                arc.setShader(sg);

                c.save();
                c.rotate(-90f, cx, cy);
                c.drawArc(r, 0f, 360f * frac, false, glow);
                c.drawArc(r, 0f, 360f * frac, false, arc);
                c.restore();

                double angleRad = Math.toRadians(-90.0 + 360.0 * frac);
                float tx = (float) (cx + radius * Math.cos(angleRad));
                float ty = (float) (cy + radius * Math.sin(angleRad));
                c.drawCircle(tx, ty, 3.6f * d, tipDot);
            }

            float textY = cy - (txt.ascent() + txt.descent()) / 2f - 4 * d;
            c.drawText(text, cx, textY, txt);
            c.drawText(label, cx, textY + 22 * d, subLabel);
        }
    }

    static class ModernActionBtn extends View {
        static final int PLAY = 0, PAUSE = 1, ADD = 2, STOP = 3;
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final float d;
        final int size;
        int type;
        int colA, colB;
        boolean pressed = false;

        ModernActionBtn(Context c, int type, int sizeDp, int colA, int colB) {
            super(c);
            this.type = type;
            this.colA = colA;
            this.colB = colB;
            d = c.getResources().getDisplayMetrics().density;
            size = (int) (sizeDp * d);

            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(1.2f * d);
            border.setColor(0x38FFFFFF);

            fg.setColor(Color.WHITE);
            fg.setStyle(Paint.Style.FILL);
            fg.setTextAlign(Paint.Align.CENTER);
            fg.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            setClickable(true);
        }

        void setType(int t) {
            if (t != type) {
                type = t;
                invalidate();
            }
        }

        void setGradientColors(int a, int b) {
            if (a != colA || b != colB) {
                colA = a;
                colB = b;
                invalidate();
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true;
                    animate().scaleX(0.91f).scaleY(0.91f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (pressed) {
                        pressed = false;
                        animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    }
                    break;
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(size, size);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = getWidth(), m = s / 2f, u = s / 100f;
            bg.setShader(new LinearGradient(0, 0, s, s, colA, colB, Shader.TileMode.CLAMP));
            c.drawCircle(m, m, m - d, bg);
            c.drawCircle(m, m, m - d, border);

            switch (type) {
                case PLAY:
                    path.reset();
                    path.moveTo(41 * u, 31 * u);
                    path.lineTo(71 * u, 50 * u);
                    path.lineTo(41 * u, 69 * u);
                    path.close();
                    c.drawPath(path, fg);
                    break;
                case PAUSE:
                    c.drawRoundRect(new RectF(34 * u, 31 * u, 45 * u, 69 * u), 3.5f * u, 3.5f * u, fg);
                    c.drawRoundRect(new RectF(55 * u, 31 * u, 66 * u, 69 * u), 3.5f * u, 3.5f * u, fg);
                    break;
                case STOP:
                    c.drawRoundRect(new RectF(34 * u, 34 * u, 66 * u, 66 * u), 7 * u, 7 * u, fg);
                    break;
                case ADD:
                    fg.setTextSize(28 * u);
                    c.drawText("+30s", m, m - (fg.ascent() + fg.descent()) / 2f, fg);
                    break;
            }
        }
    }

    // =====================================================================
    // Audio / Vibrazione / Aggiornamento stato
    // =====================================================================
    private void startDoneAlert() {
        TimerState.load(this);
        try {
            if (TimerState.vib > 0) {
                vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator != null) {
                    long v = TimerState.vib;
                    long[] pat = new long[]{0, v, v / 2, v, 700};
                    if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pat, 0));
                    else vibrator.vibrate(pat, 0);
                }
            }
        } catch (Exception ignored) {}
        try {
            if (TimerState.snd) {
                Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                alarmRingtone = RingtoneManager.getRingtone(this, uri);
                if (alarmRingtone != null) alarmRingtone.play();
            }
        } catch (Exception ignored) {}
    }

    private void stopDoneAlert() {
        try { if (vibrator != null) vibrator.cancel(); } catch (Exception ignored) {}
        try { if (alarmRingtone != null && alarmRingtone.isPlaying()) alarmRingtone.stop(); } catch (Exception ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
    }

    private void finishDone() {
        h.removeCallbacks(autoStopDone);
        stopDoneAlert();
        finish();
    }

    private void render() {
        long rem = TimerState.remaining();
        float frac = TimerState.total > 0 ? (float) rem / (float) TimerState.total : 0f;
        int c1 = themeC1();
        int c2 = themeC2();
        int a, b;
        if (!TimerState.run) {
            a = 0xFF9AA0B4;
            b = 0xFF6F7690;
            statusPill.setText("❚❚  RECUPERO IN PAUSA");
            statusPill.setTextColor(0xFFB8BED1);
        } else if (rem <= 10000) {
            a = 0xFFFF5268;
            b = 0xFFFFB14A;
            statusPill.setText("🔥  ULTIMI SECONDI");
            statusPill.setTextColor(0xFFFFB14A);
        } else {
            a = c1;
            b = c2;
            statusPill.setText("●  RECUPERO ATTIVO");
            statusPill.setTextColor(c2);
        }

        ambientRun.setColors(a, b);
        heroRing.set(frac, TimerService.fmt(rem), TimerState.run ? "RIMANENTI" : "IN PAUSA", a, b);
        bPlay.setType(TimerState.run ? ModernActionBtn.PAUSE : ModernActionBtn.PLAY);
        bPlay.setGradientColors(a, b);
        endPill.setText(TimerState.run
                ? "Fine stimata · " + new SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(new Date(TimerState.end))
                : "Tocca play per riprendere");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (doneMode) return;
        TimerState.load(this);
        if (!TimerState.active()) { finish(); return; }
        render();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        stopDoneAlert();
        super.onDestroy();
    }
}
