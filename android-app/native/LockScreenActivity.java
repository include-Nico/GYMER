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
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Esperienza nativa lockscreen GYMER:
 * - Modalita' "run": card glassmorphism con glow dinamico, anello neon con puntino luminoso, pillola orario e swipe-to-dismiss.
 * - Modalita' "done": risveglio schermo con onde sonar pulsanti, card hero "0:00" e slider magnetico 'X' per spegnere.
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
    private PulseHaloView pulseHalo;
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

    private float dpf(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private int dp(float v) {
        return (int) dpf(v);
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

        // Sfondo con alone ambientale morbido
        ambientRun = new AmbientBgView(this, c1, c2);
        root.addView(ambientRun, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Card principale glassmorphism
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

        // Maniglia superiore stile sheet iOS/One UI
        View handle = new View(this);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(0x38FFFFFF);
        handleBg.setCornerRadius(dp(4));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(dp(38), dp(4));
        hLp.bottomMargin = dp(16);
        card.addView(handle, hLp);

        // Header pillola brand + stato
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER);

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
        topRow.addView(statusPill);
        card.addView(topRow);

        // Anello Hero centrale
        heroRing = new HeroRingView(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(210), dp(210));
        rlp.topMargin = dp(20);
        rlp.bottomMargin = dp(14);
        card.addView(heroRing, rlp);

        // Pillola orario di fine stimato
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

        // Pulsanti azione moderni (Stop | Pausa/Play | +30s)
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

        // Swipe omnidirezionale per chiudere la card
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

        // Animazione ingresso fluida
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
    // UI 2: Fine timer a schermo nero/bloccato (Sonar + Card Hero + X Magnetica)
    // =====================================================================
    private void buildDoneUi() {
        int c1 = themeC1();
        int c2 = themeC2();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF05050A);

        // Sfondo sonar pulsante
        pulseHalo = new PulseHaloView(this, c1, c2);
        root.addView(pulseHalo, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(22), dp(54), dp(22), dp(42));

        // Card superiore effetto glass
        LinearLayout notifBanner = new LinearLayout(this);
        notifBanner.setOrientation(LinearLayout.VERTICAL);
        notifBanner.setGravity(Gravity.CENTER);
        notifBanner.setPadding(dp(24), dp(24), dp(24), dp(26));
        notifBanner.setElevation(dp(16));

        GradientDrawable bannerBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xEB18182C, 0xF50E0E1A}
        );
        bannerBg.setCornerRadius(dp(32));
        bannerBg.setStroke(dp(1), withAlpha(c2, 95));
        notifBanner.setBackground(bannerBg);

        TextView badge = new TextView(this);
        badge.setText("⚡  GYMER · RECUPERO COMPLETATO");
        badge.setTextColor(c2);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        badge.setLetterSpacing(0.11f);
        badge.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(withAlpha(c1, 42));
        badgeBg.setCornerRadius(dp(20));
        badge.setBackground(badgeBg);
        notifBanner.addView(badge);

        TextView bigZero = new TextView(this);
        bigZero.setText("0:00");
        bigZero.setTextColor(Color.WHITE);
        bigZero.setTextSize(TypedValue.COMPLEX_UNIT_SP, 62);
        bigZero.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
        bigZero.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams zlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        zlp.topMargin = dp(10);
        zlp.bottomMargin = dp(2);
        notifBanner.addView(bigZero, zlp);

        TextView msg = new TextView(this);
        msg.setText("Pronto per la prossima serie!");
        msg.setTextColor(0xD9FFFFFF);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        msg.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        notifBanner.addView(msg);

        content.addView(notifBanner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Spazio flessibile centrale
        content.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        // Controllo 'X' magnetico con scia luminosa e tacche direzionali
        DragDismissX dragX = new DragDismissX(this, c1, c2, this::finishDone);
        content.addView(dragX, new LinearLayout.LayoutParams(dp(240), dp(240)));

        TextView hint = new TextView(this);
        hint.setText("TRASCINA LA X FUORI DAL CERCHIO");
        hint.setTextColor(0x73FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        hint.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(6);
        content.addView(hint, hlp);

        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    // =====================================================================
    // Componenti Grafici Custom (Canvas)
    // =====================================================================

    /** Sfondo ambientale con doppia luce radiale sfumata ai colori del tema. */
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

    /** Onde sonar concentriche animate a fine timer. */
    static class PulseHaloView extends View {
        final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final int c1, c2;
        float phase = 0f;
        ValueAnimator anim;

        PulseHaloView(Context c, int c1, int c2) {
            super(c);
            this.c1 = c1;
            this.c2 = c2;
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(2.5f * c.getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(2400);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                phase = (Float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (anim != null) anim.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f;
            float cy = getHeight() * 0.70f;
            float maxR = Math.max(getWidth(), getHeight()) * 0.55f;

            glowPaint.setShader(new RadialGradient(cx, cy, maxR * 0.85f,
                    new int[]{withAlpha(c1, 75), withAlpha(c2, 24), Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, maxR * 0.85f, glowPaint);

            for (int i = 0; i < 3; i++) {
                float p = (phase + i / 3f) % 1f;
                float r = maxR * (0.15f + 0.85f * p);
                int alpha = (int) ((1f - p) * 110);
                ringPaint.setColor(withAlpha(i % 2 == 0 ? c2 : c1, alpha));
                c.drawCircle(cx, cy, r, ringPaint);
            }
        }
    }

    /** Anello countdown ad alta risoluzione con glow, tacche e puntino luminoso in testa. */
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

                // Puntino bianco luminoso sulla punta dell'arco
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

    /** Pulsante circolare con gradiente, bordo luminoso e feedback tattile alla pressione. */
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

    /** Controllo 'X' magnetico: si illumina e ingrandisce man mano che lo trascini verso il bordo. */
    static class DragDismissX extends View {
        final Paint outerTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint outerProgress = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint innerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint innerBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint xStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float d;
        final int c1, c2;
        final Runnable onDismiss;
        float offX = 0f, offY = 0f;
        boolean activeDrag = false;

        DragDismissX(Context c, int c1, int c2, Runnable onDismiss) {
            super(c);
            this.c1 = c1;
            this.c2 = c2;
            this.onDismiss = onDismiss;
            d = c.getResources().getDisplayMetrics().density;

            outerTrack.setStyle(Paint.Style.STROKE);
            outerTrack.setStrokeWidth(2 * d);
            outerTrack.setColor(0x2EFFFFFF);

            outerProgress.setStyle(Paint.Style.STROKE);
            outerProgress.setStrokeWidth(3.5f * d);
            outerProgress.setStrokeCap(Paint.Cap.ROUND);

            innerFill.setStyle(Paint.Style.FILL);
            innerBorder.setStyle(Paint.Style.STROKE);
            innerBorder.setStrokeWidth(1.5f * d);

            xStroke.setStyle(Paint.Style.STROKE);
            xStroke.setStrokeWidth(3.8f * d);
            xStroke.setStrokeCap(Paint.Cap.ROUND);
            xStroke.setColor(Color.WHITE);

            chevronPaint.setStyle(Paint.Style.FILL);
            chevronPaint.setColor(0x55FFFFFF);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float maxR = Math.min(cx, cy) - 10 * d;
            float baseBtnR = 38 * d;

            float dist = (float) Math.hypot(offX, offY);
            float progress = Math.min(1f, dist / (maxR * 0.72f));

            // Cerchio bersaglio esterno
            c.drawCircle(cx, cy, maxR, outerTrack);
            if (progress > 0.02f) {
                outerProgress.setShader(new SweepGradient(cx, cy, new int[]{c1, c2, 0xFFFF4D6D, c1}, null));
                c.drawArc(new RectF(cx - maxR, cy - maxR, cx + maxR, cy + maxR), -90f, 360f * progress, false, outerProgress);
            }

            // 4 piccoli punti guida cardinali
            if (!activeDrag) {
                float guideR = maxR - 14 * d;
                c.drawCircle(cx, cy - guideR, 2.2f * d, chevronPaint);
                c.drawCircle(cx, cy + guideR, 2.2f * d, chevronPaint);
                c.drawCircle(cx - guideR, cy, 2.2f * d, chevronPaint);
                c.drawCircle(cx + guideR, cy, 2.2f * d, chevronPaint);
            }

            // Pulsante X centrale trascinabile
            float bx = cx + offX;
            float by = cy + offY;
            float btnR = baseBtnR + (activeDrag ? 4 * d : 0f);

            if (activeDrag) {
                innerFill.setShader(new LinearGradient(bx - btnR, by - btnR, bx + btnR, by + btnR,
                        0xFFFF4D6D, 0xFFFF7A59, Shader.TileMode.CLAMP));
                innerBorder.setColor(0x88FFFFFF);
            } else {
                innerFill.setShader(new LinearGradient(bx - btnR, by - btnR, bx + btnR, by + btnR,
                        0xFF222238, 0xFF161626, Shader.TileMode.CLAMP));
                innerBorder.setColor(0x44FFFFFF);
            }

            c.drawCircle(bx, by, btnR, innerFill);
            c.drawCircle(bx, by, btnR, innerBorder);

            float arm = 12 * d;
            c.drawLine(bx - arm, by - arm, bx + arm, by + arm, xStroke);
            c.drawLine(bx + arm, by - arm, bx - arm, by + arm, xStroke);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float maxR = Math.min(cx, cy) - 10 * d;
            float hitR = 54 * d;

            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (Math.hypot(e.getX() - cx, e.getY() - cy) <= hitR) {
                        activeDrag = true;
                        offX = 0f;
                        offY = 0f;
                        invalidate();
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (!activeDrag) return false;
                    offX = e.getX() - cx;
                    offY = e.getY() - cy;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!activeDrag) return false;
                    activeDrag = false;
                    if (Math.hypot(offX, offY) >= maxR * 0.72f) {
                        onDismiss.run();
                    } else {
                        offX = 0f;
                        offY = 0f;
                        invalidate();
                    }
                    return true;
            }
            return false;
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
