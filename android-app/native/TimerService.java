package it.gymer.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Servizio in primo piano: notifica con cronometro + bolla flottante. */
public class TimerService extends Service {
    static final String CH = "gymer_run";
    static final String ACT = "it.gymer.app.act.";

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout bubbleView;
    private TextView tvTime, btnPlay;
    private WindowManager.LayoutParams lp;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!TimerState.active()) return;
            if (TimerState.run && System.currentTimeMillis() >= TimerState.end) {
                TimerState.finish(TimerService.this);
                return;
            }
            updateBubbleText();
            h.postDelayed(this, 250);
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        TimerState.load(this);
        String a = intent != null ? intent.getAction() : null;
        if (a != null && a.startsWith(ACT)) {
            TimerState.act(this, a.substring(ACT.length()));
        }
        startFg(); // sempre: chi ci avvia con startForegroundService si aspetta la notifica subito
        if (!TimerState.active()) {
            removeBubble();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        refreshBubble();
        h.removeCallbacks(ticker);
        h.post(ticker);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        removeBubble();
        super.onDestroy();
    }

    // ---------- notifica ----------
    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Timer in corso", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Mostra il recupero in corso");
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private PendingIntent actionPi(String act, int req) {
        Intent i = new Intent(this, TimerService.class).setAction(ACT + act);
        int fl = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 26) return PendingIntent.getForegroundService(this, req, i, fl);
        return PendingIntent.getService(this, req, i, fl);
    }

    private Notification buildNotif() {
        ensureChannel();
        boolean run = TimerState.run;
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent open = PendingIntent.getActivity(this, 4, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int icon = getResources().getIdentifier("ic_stat_timer", "drawable", getPackageName());
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
                .setSmallIcon(icon != 0 ? icon : android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(run ? "Recupero in corso" : "Recupero in pausa")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory("stopwatch")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(open);
        if (run) {
            b.setWhen(TimerState.end).setShowWhen(true).setUsesChronometer(true);
            if (Build.VERSION.SDK_INT >= 24) b.setChronometerCountDown(true);
            b.setContentText("Finisce alle " + new SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(new Date(TimerState.end)));
        } else {
            b.setShowWhen(false).setContentText(fmt(TimerState.left) + " rimanenti");
        }
        b.addAction(0, run ? "Pausa" : "Riprendi", actionPi(run ? "pause" : "resume", 11));
        b.addAction(0, "+30s", actionPi("add", 12));
        b.addAction(0, "Stop", actionPi("stop", 13));
        return b.build();
    }

    private void startFg() {
        Notification n = buildNotif();
        if (Build.VERSION.SDK_INT >= 34) startForeground(TimerState.ID_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(TimerState.ID_RUN, n);
    }

    static String fmt(long ms) {
        long c = (ms + 999) / 1000;
        return (c / 60) + ":" + String.format(Locale.ITALY, "%02d", c % 60);
    }

    // ---------- bolla flottante ----------
    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private boolean canShowBubble() {
        return TimerState.bubble && !TimerState.bubbleHidden && !TimerState.foreground && TimerState.active()
                && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this));
    }

    private void refreshBubble() {
        if (canShowBubble()) {
            if (bubbleView == null) addBubble();
            updateBubbleText();
        } else removeBubble();
    }

    private TextView btn(String label, int sizeSp) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x26FFFFFF);
        t.setBackground(g);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(32), dp(32));
        p.leftMargin = dp(4);
        t.setLayoutParams(p);
        return t;
    }

    private void addBubble() {
        bubbleView = new LinearLayout(this);
        bubbleView.setOrientation(LinearLayout.HORIZONTAL);
        bubbleView.setGravity(Gravity.CENTER_VERTICAL);
        bubbleView.setPadding(dp(14), dp(5), dp(7), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEB0B0B16);
        bg.setCornerRadius(dp(26));
        bg.setStroke(dp(1), 0x44FFFFFF);
        bubbleView.setBackground(bg);
        bubbleView.setElevation(dp(6));

        tvTime = new TextView(this);
        tvTime.setTextColor(0xFFFFFFFF);
        tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tvTime.setTypeface(Typeface.DEFAULT_BOLD);
        tvTime.setMinWidth(dp(48));
        bubbleView.addView(tvTime);

        btnPlay = btn("❚❚", 11);
        btnPlay.setOnClickListener(v -> { TimerState.act(this, "toggle"); refreshAfterAction(); });
        TextView btnStop = btn("■", 12);
        btnStop.setOnClickListener(v -> { TimerState.act(this, "stop"); refreshAfterAction(); });
        TextView btnHide = btn("✕", 12);
        btnHide.setOnClickListener(v -> { TimerState.hideBubble(this); removeBubble(); });
        bubbleView.addView(btnPlay);
        bubbleView.addView(btnStop);
        bubbleView.addView(btnHide);

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = TimerState.sp(this).getInt("bx", dp(16));
        lp.y = TimerState.sp(this).getInt("by", dp(140));

        // trascinamento dal tempo
        tvTime.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy;
            float tx, ty;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN: sx = lp.x; sy = lp.y; tx = e.getRawX(); ty = e.getRawY(); return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = sx + (int) (e.getRawX() - tx);
                        lp.y = sy + (int) (e.getRawY() - ty);
                        try { wm.updateViewLayout(bubbleView, lp); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        TimerState.sp(TimerService.this).edit().putInt("bx", lp.x).putInt("by", lp.y).apply();
                        return true;
                }
                return false;
            }
        });
        try { wm.addView(bubbleView, lp); } catch (Exception e) { bubbleView = null; }
    }

    private void updateBubbleText() {
        if (bubbleView == null) return;
        tvTime.setText(fmt(TimerState.remaining()));
        btnPlay.setText(TimerState.run ? "❚❚" : "▶");
    }

    private void removeBubble() {
        if (bubbleView != null) {
            try { wm.removeView(bubbleView); } catch (Exception ignored) {}
            bubbleView = null;
        }
    }

    private void refreshAfterAction() {
        if (!TimerState.active()) {
            removeBubble();
            stopForeground(true);
            stopSelf();
            return;
        }
        startFg();
        refreshBubble();
        h.removeCallbacks(ticker);
        h.post(ticker);
    }
}
