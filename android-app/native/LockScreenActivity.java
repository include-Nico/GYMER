package it.gymer.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Scheda nativa sopra la schermata di blocco:
 * - Modalita' "run": mostra anello countdown; si chiude toccando fuori dal riquadro o trascinando il riquadro.
 * - Modalita' "done": accende lo schermo nero a fine timer e mostra la 'X' da toccare e trascinare per chiudere.
 */
public class LockScreenActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean doneMode = false;
    private TimerService.RingView ring;
    private TextView subTxt;
    private TimerService.IconBtn bPlay;
    private LinearLayout card;
    private Ringtone alarmRingtone;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (doneMode) return;
            TimerState.load(LockScreenActivity.this);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            // Chiudi se il timer e' finito/fermato oppure se l'utente ha sbloccato il telefono
            if (!TimerState.active() || (km != null && !km.isKeyguardLocked())) {
                finish();
                return;
            }
            render();
            h.postDelayed(this, 100);
        }
    };

    private final Runnable autoStopDone = this::finishDone;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
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
    // UI 1: Timer in corso (tocco fuori o trascinamento card per chiudere)
    // =====================================================================
    private void buildRunningUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xD907070D);
        // Tocco fuori dal riquadro -> chiude subito
        root.setOnClickListener(v -> finish());

        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(28), dp(24), dp(28));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF12121F);
        cardBg.setCornerRadius(dp(28));
        cardBg.setStroke(dp(1), 0x26FFFFFF);
        card.setBackground(cardBg);
        card.setClickable(true);

        TextView title = new TextView(this);
        title.setText("GYMER · RECUPERO");
        title.setTextColor(0x99FFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setLetterSpacing(0.12f);
        card.addView(title);

        ring = new TimerService.RingView(this) {
            @Override protected void onMeasure(int w, int h) { int s = dp(170); setMeasuredDimension(s, s); }
        };
        ring.track.setStrokeWidth(dp(8));
        ring.arc.setStrokeWidth(dp(8));
        ring.txt.setTextSize(dp(38));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(20);
        rlp.bottomMargin = dp(12);
        card.addView(ring, rlp);

        subTxt = new TextView(this);
        subTxt.setTextColor(0xB3FFFFFF);
        subTxt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        card.addView(subTxt);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(26);

        TimerService.IconBtn bStop = new TimerService.IconBtn(this, TimerService.IconBtn.STOP, 56);
        bPlay = new TimerService.IconBtn(this, TimerService.IconBtn.PAUSE, 68);
        TimerService.IconBtn bAdd = new TimerService.IconBtn(this, TimerService.IconBtn.ADD, 56);

        LinearLayout.LayoutParams pSide = new LinearLayout.LayoutParams(dp(56), dp(56));
        LinearLayout.LayoutParams pMid = new LinearLayout.LayoutParams(dp(68), dp(68));
        pMid.leftMargin = dp(18);
        pMid.rightMargin = dp(18);

        row.addView(bStop, pSide);
        row.addView(bPlay, pMid);
        row.addView(bAdd, pSide);
        card.addView(row, rowLp);

        // Trascinamento (swipe) della card verso qualsiasi direzione per chiudere
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
                        if (!dragging && (Math.abs(dx) > dp(12) || Math.abs(dy) > dp(12))) {
                            dragging = true;
                        }
                        if (dragging) {
                            card.setTranslationX(dx);
                            card.setTranslationY(dy);
                            float dist = (float) Math.hypot(dx, dy);
                            card.setAlpha(Math.max(0.25f, 1f - dist / dp(260)));
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            float dx2 = e.getRawX() - downX;
                            float dy2 = e.getRawY() - downY;
                            if (Math.hypot(dx2, dy2) > dp(90)) {
                                card.animate()
                                        .translationX(dx2 * 2.5f)
                                        .translationY(dy2 * 2.5f)
                                        .alpha(0f)
                                        .setDuration(160)
                                        .setListener(new AnimatorListenerAdapter() {
                                            @Override public void onAnimationEnd(Animator animation) { finish(); }
                                        }).start();
                            } else {
                                card.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(180).start();
                            }
                        }
                        return true;
                }
                return false;
            }
        });

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        clp.leftMargin = dp(24);
        clp.rightMargin = dp(24);
        root.addView(card, clp);
        setContentView(root);

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
    // UI 2: Fine timer a schermo spento/bloccato (notifica + X trascinabile)
    // =====================================================================
    private void buildDoneUi() {
        int c1 = TimerState.sp(this).getInt("c1", 0xFF8B7BFF);
        int c2 = TimerState.sp(this).getInt("c2", 0xFF27D7FF);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.parseColor("#07070D"));
        root.setPadding(dp(24), dp(56), dp(24), dp(48));

        // Riquadro notifica di fine timer in alto
        LinearLayout notifBanner = new LinearLayout(this);
        notifBanner.setOrientation(LinearLayout.VERTICAL);
        notifBanner.setGravity(Gravity.CENTER);
        notifBanner.setPadding(dp(24), dp(22), dp(24), dp(22));
        GradientDrawable bannerBg = new GradientDrawable();
        bannerBg.setColor(0xFF141424);
        bannerBg.setCornerRadius(dp(26));
        bannerBg.setStroke(dp(1), 0x33FFFFFF);
        notifBanner.setBackground(bannerBg);

        TextView badge = new TextView(this);
        badge.setText("GYMER · RECUPERO FINITO");
        badge.setTextColor(c2);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        badge.setLetterSpacing(0.12f);
        notifBanner.addView(badge);

        TextView bigZero = new TextView(this);
        bigZero.setText("0:00");
        bigZero.setTextColor(Color.WHITE);
        bigZero.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52);
        bigZero.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams zlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        zlp.topMargin = dp(8);
        zlp.bottomMargin = dp(4);
        notifBanner.addView(bigZero, zlp);

        TextView msg = new TextView(this);
        msg.setText("Recupero finito: tocca a te!");
        msg.setTextColor(0xCCFFFFFF);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        notifBanner.addView(msg);

        root.addView(notifBanner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Spazio centrale flessibile
        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        // Controllo 'X' da toccare e trascinare fuori dall'anello per chiudere
        DragDismissX dragX = new DragDismissX(this, c1, c2, this::finishDone);
        root.addView(dragX, new LinearLayout.LayoutParams(dp(220), dp(220)));

        TextView hint = new TextView(this);
        hint.setText("Tocca e trascina la X per chiudere");
        hint.setTextColor(0x80FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(8);
        root.addView(hint, hlp);

        setContentView(root);
    }

    /** Cerchio centrale con 'X' che segue il dito: se trascinato oltre il bordo chiude l'avviso. */
    static class DragDismissX extends View {
        final Paint outerRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint innerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint xStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float d;
        final Runnable onDismiss;
        float offX = 0f, offY = 0f;
        boolean activeDrag = false;

        DragDismissX(Context c, int c1, int c2, Runnable onDismiss) {
            super(c);
            this.onDismiss = onDismiss;
            d = c.getResources().getDisplayMetrics().density;

            outerRing.setStyle(Paint.Style.STROKE);
            outerRing.setStrokeWidth(2 * d);
            outerRing.setColor(0x38FFFFFF);

            innerFill.setStyle(Paint.Style.FILL);
            innerFill.setColor(0xFF1E1E32);

            xStroke.setStyle(Paint.Style.STROKE);
            xStroke.setStrokeWidth(4 * d);
            xStroke.setStrokeCap(Paint.Cap.ROUND);
            xStroke.setColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float maxR = Math.min(cx, cy) - 8 * d;
            float btnR = 36 * d;

            // Cerchio soglia esterno
            float dist = (float) Math.hypot(offX, offY);
            float progress = Math.min(1f, dist / (maxR - btnR * 0.4f));
            outerRing.setColor(Color.argb((int) (55 + 160 * progress), 255, 255, 255));
            c.drawCircle(cx, cy, maxR, outerRing);

            // Pulsante X mobile
            float bx = cx + offX;
            float by = cy + offY;
            innerFill.setColor(activeDrag ? 0xFFFF5268 : 0xFF23233A);
            c.drawCircle(bx, by, btnR, innerFill);

            float arm = 12 * d;
            c.drawLine(bx - arm, by - arm, bx + arm, by + arm, xStroke);
            c.drawLine(bx + arm, by - arm, bx - arm, by + arm, xStroke);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float maxR = Math.min(cx, cy) - 8 * d;
            float btnR = 44 * d;

            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (Math.hypot(e.getX() - cx, e.getY() - cy) <= btnR * 1.4f) {
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
        int c1 = TimerState.sp(this).getInt("c1", 0xFF8B7BFF);
        int c2 = TimerState.sp(this).getInt("c2", 0xFF27D7FF);
        int a, b;
        if (!TimerState.run) { a = 0xFF9AA0B4; b = 0xFF6F7690; }
        else if (rem <= 10000) { a = 0xFFFF6B6B; b = 0xFFFFB14A; }
        else { a = c1; b = c2; }

        ring.set(frac, TimerService.fmt(rem), a, b);
        bPlay.setType(TimerState.run ? TimerService.IconBtn.PAUSE : TimerService.IconBtn.PLAY);
        subTxt.setText(TimerState.run
                ? "Fine alle " + new SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(new Date(TimerState.end))
                : "Recupero in pausa");
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
