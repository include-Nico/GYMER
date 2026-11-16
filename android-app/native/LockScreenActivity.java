package it.gymer.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Scheda nativa sopra la schermata di blocco: mostra anello countdown e comandi senza sbloccare il telefono. */
public class LockScreenActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private TimerService.RingView ring;
    private TextView subTxt;
    private TimerService.IconBtn bPlay;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
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

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(false);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
        getWindow().setStatusBarColor(Color.parseColor("#07070D"));
        getWindow().setNavigationBarColor(Color.parseColor("#07070D"));

        // Contenitore principale scuro
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#07070D"));
        root.setPadding(dp(24), dp(32), dp(24), dp(32));

        // Card stile One UI / GYMER
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(28), dp(24), dp(28));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF12121F);
        cardBg.setCornerRadius(dp(28));
        cardBg.setStroke(dp(1), 0x26FFFFFF);
        card.setBackground(cardBg);

        TextView title = new TextView(this);
        title.setText("GYMER · RECUPERO");
        title.setTextColor(0x99FFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setLetterSpacing(0.12f);
        card.addView(title);

        // Anello grande centrale (scalato per la lockscreen)
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

        // Fila pulsanti grandi: Stop | Pausa/Riprendi | +30s
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

        // Tasto discreto per nascondere fino al prossimo blocco schermo
        TextView dismiss = new TextView(this);
        dismiss.setText("Nascondi");
        dismiss.setTextColor(0x66FFFFFF);
        dismiss.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        dismiss.setPadding(dp(16), dp(12), dp(16), dp(4));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(14);
        card.addView(dismiss, dlp);

        root.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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
        dismiss.setOnClickListener(v -> finish());
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
}
