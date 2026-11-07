package it.gymer.app;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Servizio in primo piano: notifica (stile lettore multimediale o classica) + bolla flottante moderna. */
public class TimerService extends Service {
    static final String CH = "gymer_run2"; // v2: importanza normale (le notifiche "silenziose" spesso sono nascoste dalla schermata di blocco)
    static final String ACT = "it.gymer.app.act.";

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private MediaSession ms;

    // bolla
    private LinearLayout bubbleView;
    private RingView ring;
    private IconBtn bPlay, bAdd, bStop, bHide;
    private WindowManager.LayoutParams lp;
    private boolean expanded = false;
    private final Runnable collapse = () -> setExpanded(false);

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!TimerState.active()) return;
            if (TimerState.run && System.currentTimeMillis() >= TimerState.end) {
                TimerState.finish(TimerService.this);
                return;
            }
            updateBubble();
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
        if (a != null && a.startsWith(ACT)) TimerState.act(this, a.substring(ACT.length()));
        startFg(); // sempre: chi ci avvia con startForegroundService si aspetta la notifica subito
        if (!TimerState.active()) {
            removeBubble();
            releaseSession();
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
        releaseSession();
        super.onDestroy();
    }

    // =====================================================================
    // Notifica
    // =====================================================================
    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            try { nm.deleteNotificationChannel("gymer_run"); } catch (Exception ignored) {}
            NotificationChannel ch = new NotificationChannel(CH, "Timer in corso", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Mostra il recupero in corso, anche nella schermata di blocco (senza suono)");
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
    }

    private PendingIntent actionPi(String act, int req) {
        Intent i = new Intent(this, TimerService.class).setAction(ACT + act);
        int fl = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 26) return PendingIntent.getForegroundService(this, req, i, fl);
        return PendingIntent.getService(this, req, i, fl);
    }

    private PendingIntent openPi() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        return PendingIntent.getActivity(this, 4, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private int smallIcon() {
        int icon = getResources().getIdentifier("ic_stat_timer", "drawable", getPackageName());
        return icon != 0 ? icon : android.R.drawable.ic_lock_idle_alarm;
    }

    private String endText() {
        return "Finisce alle " + new SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(new Date(TimerState.end));
    }

    /** Notifica classica con tre pulsanti di testo. */
    private Notification buildClassic() {
        boolean run = TimerState.run;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
                .setSmallIcon(smallIcon())
                .setContentTitle(run ? "Recupero in corso" : "Recupero in pausa")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory("stopwatch")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openPi());
        if (run) {
            b.setWhen(TimerState.end).setShowWhen(true).setUsesChronometer(true);
            if (Build.VERSION.SDK_INT >= 24) b.setChronometerCountDown(true);
            b.setContentText(endText());
        } else {
            b.setShowWhen(false).setContentText(fmt(TimerState.left) + " rimanenti");
        }
        b.addAction(0, run ? "Pausa" : "Riprendi", actionPi(run ? "pause" : "resume", 11));
        b.addAction(0, "+30s", actionPi("add", 12));
        b.addAction(0, "Stop", actionPi("stop", 13));
        return b.build();
    }

    private Notification.Action mAct(int ic, String title, String a, int req) {
        return new Notification.Action.Builder(Icon.createWithResource(this, ic), title, actionPi(a, req)).build();
    }

    /** Notifica stile lettore multimediale (come Spotify): barra di avanzamento e comandi nella schermata di blocco. */
    private Notification buildMedia() {
        boolean run = TimerState.run;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(smallIcon())
                .setContentTitle(run ? "Recupero in corso" : "Recupero in pausa")
                .setContentText(run ? endText() : fmt(TimerState.left) + " rimanenti")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(c1())
                .setContentIntent(openPi());
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        if (run) {
            b.setWhen(TimerState.end).setShowWhen(true).setUsesChronometer(true);
            if (Build.VERSION.SDK_INT >= 24) b.setChronometerCountDown(true);
        } else {
            b.setShowWhen(false);
        }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        b.addAction(mAct(android.R.drawable.ic_media_previous, "Azzera", "stop", 13));
        b.addAction(mAct(run ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, run ? "Pausa" : "Riprendi", run ? "pause" : "resume", 11));
        b.addAction(mAct(android.R.drawable.ic_media_next, "+30s", "add", 12));
        Notification.MediaStyle st = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (ms != null) st.setMediaSession(ms.getSessionToken());
        b.setStyle(st);
        return b.build();
    }

    /** 0 = riquadro (stile timer Samsung), 1 = live Android 16 / Now Bar, 2 = lettore musicale */
    private int nstyle() {
        android.content.SharedPreferences p = TimerState.sp(this);
        if (p.contains("nstyle")) return p.getInt("nstyle", 0);
        return p.getBoolean("mstyle2", false) ? 2 : 0;
    }
    private boolean media() { return nstyle() == 2; }

    /** Colori del tema scelto nell'app (inviati dalla pagina con setColors). */
    private int c1() { return TimerState.sp(this).getInt("c1", 0xFF8B7BFF); }
    private int c2() { return TimerState.sp(this).getInt("c2", 0xFF27D7FF); }

    private int rid(String name, String type) { return getResources().getIdentifier(name, type, getPackageName()); }

    private Notification.Builder baseBuilder() {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
    }

    private void addTimerActions(Notification.Builder b) {
        boolean run = TimerState.run;
        b.addAction(new Notification.Action.Builder(0, "Annulla", actionPi("stop", 13)).build());
        b.addAction(new Notification.Action.Builder(0, run ? "Pausa" : "Riprendi", actionPi(run ? "pause" : "resume", 11)).build());
        b.addAction(new Notification.Action.Builder(0, "+30s", actionPi("add", 12)).build());
    }

    /** Riquadro: icona clessidra + tempo grande che scorre da solo + Annulla / Pausa / +30s. */
    private Notification buildCustom() {
        int lay = rid("gymer_notif", "layout"), idT = rid("gn_time", "id"), idSub = rid("gn_sub", "id");
        if (Build.VERSION.SDK_INT < 24 || lay == 0 || idT == 0 || idSub == 0) return buildClassic();
        boolean run = TimerState.run;
        long rem = TimerState.remaining();
        RemoteViews rv = new RemoteViews(getPackageName(), lay);
        rv.setChronometerCountDown(idT, true);
        rv.setChronometer(idT, SystemClock.elapsedRealtime() + rem, null, run);
        rv.setTextViewText(idSub, run ? "Recupero / " + new SimpleDateFormat("HH:mm", Locale.ITALY).format(new Date(TimerState.end)) : "Recupero in pausa");
        Notification.Builder b = baseBuilder();
        b.setSmallIcon(smallIcon())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(c1())
                .setCustomContentView(rv)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setContentIntent(openPi());
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        addTimerActions(b);
        return b.build();
    }

    /** Live (Android 16 / One UI 8): notifica standard "promossa" -> pillola nella barra di stato e Now Bar. */
    private Notification buildLive() {
        boolean run = TimerState.run;
        Notification.Builder b = baseBuilder();
        b.setSmallIcon(smallIcon())
                .setContentTitle(run ? "Recupero in corso" : "Recupero in pausa")
                .setContentText(run ? endText() : fmt(TimerState.left) + " rimanenti")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(c1())
                .setContentIntent(openPi());
        if (run) {
            b.setWhen(TimerState.end).setShowWhen(true).setUsesChronometer(true);
            if (Build.VERSION.SDK_INT >= 24) b.setChronometerCountDown(true);
        } else {
            b.setShowWhen(false);
        }
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        addTimerActions(b);
        try { // API 36: richiesta di promozione a "live update" (si chiama per riflessione, cosi' compila anche con SDK 35)
            Notification.Builder.class.getMethod("setRequestPromotedOngoing", boolean.class).invoke(b, true);
        } catch (Throwable ignored) {}
        return b.build();
    }

    private Notification buildMinimal() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(smallIcon()).setContentTitle("GYMER").setContentText("Timer in corso").setOngoing(true).setContentIntent(openPi());
        return b.build();
    }

    private void startFg() {
        ensureChannel(); // senza canale valido Android chiude l'app (notifica del servizio non valida)
        Notification n = null;
        try {
            if (media() && TimerState.active()) {
                try {
                    ensureSession();
                    updateSession();
                    n = buildMedia();
                } catch (Throwable e) {
                    releaseSession();
                    n = null;
                }
            } else {
                releaseSession();
            }
            if (n == null && nstyle() == 1) {
                try { n = buildLive(); } catch (Throwable e) { n = null; }
            }
            if (n == null) {
                try { n = buildCustom(); } catch (Throwable e) { n = null; }
            }
            if (n == null) n = buildClassic();
        } catch (Throwable e) {
            n = buildMinimal();
        }
        try {
            post(n);
        } catch (Throwable e) {
            post(buildMinimal());
        }
    }

    private void post(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(TimerState.ID_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(TimerState.ID_RUN, n);
    }

    static String fmt(long ms) {
        long c = (ms + 999) / 1000;
        return (c / 60) + ":" + String.format(Locale.ITALY, "%02d", c % 60);
    }

    // =====================================================================
    // Sessione multimediale
    // =====================================================================
    private void cmd(String a) {
        TimerState.act(this, a);
        refreshAfterAction();
    }

    private void ensureSession() {
        if (ms != null) return;
        ms = new MediaSession(this, "GYMER");
        ms.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        ms.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { cmd("resume"); }
            @Override public void onPause() { cmd("pause"); }
            @Override public void onSkipToNext() { cmd("add"); }
            @Override public void onSkipToPrevious() { cmd("stop"); }
            @Override public void onStop() { cmd("stop"); }
        });
        ms.setActive(true);
    }

    private void updateSession() {
        if (ms == null) return;
        long total = Math.max(1000, TimerState.total);
        long pos = Math.max(0, Math.min(total, total - TimerState.remaining()));
        MediaMetadata.Builder m = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Recupero")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "GYMER")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, total);
        try {
            int id = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
            if (id != 0) {
                Bitmap bm = BitmapFactory.decodeResource(getResources(), id);
                if (bm != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bm);
            }
        } catch (Exception ignored) {}
        ms.setMetadata(m.build());
        PlaybackState.Builder p = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                .setState(TimerState.run ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos,
                        TimerState.run ? 1f : 0f, SystemClock.elapsedRealtime());
        ms.setPlaybackState(p.build());
    }

    private void releaseSession() {
        if (ms != null) {
            try { ms.setActive(false); ms.release(); } catch (Exception ignored) {}
            ms = null;
        }
    }

    // =====================================================================
    // Bolla flottante moderna: anello con il tempo, si espande al tocco
    // =====================================================================
    private float dpf(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
    private int dp(float v) { return (int) dpf(v); }

    /** Anello di avanzamento con il tempo al centro. */
    static class RingView extends View {
        final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        float frac = 1f;
        String text = "0:00";
        int c1 = 0xFF8B7BFF, c2 = 0xFF27D7FF;
        final float d;

        RingView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            track.setStyle(Paint.Style.STROKE); track.setStrokeWidth(4 * d); track.setColor(0x33FFFFFF);
            arc.setStyle(Paint.Style.STROKE); arc.setStrokeWidth(4 * d); arc.setStrokeCap(Paint.Cap.ROUND);
            txt.setColor(Color.WHITE); txt.setTextAlign(Paint.Align.CENTER);
            txt.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            txt.setTextSize(14.5f * d);
        }

        void set(float f, String t, int a, int b) {
            frac = Math.max(0f, Math.min(1f, f)); text = t; c1 = a; c2 = b;
            invalidate();
        }

        @Override protected void onMeasure(int w, int h) { int s = (int) (54 * d); setMeasuredDimension(s, s); }

        @Override
        protected void onDraw(Canvas c) {
            float s = getWidth(), pad = arc.getStrokeWidth() / 2f + 2 * d;
            r.set(pad, pad, s - pad, s - pad);
            c.drawOval(r, track);
            if (frac > 0.002f) {
                arc.setShader(new SweepGradient(s / 2f, s / 2f, new int[]{c1, c2, c1}, null));
                c.save();
                c.rotate(-90, s / 2f, s / 2f);
                c.drawArc(r, 0, 360f * frac, false, arc);
                c.restore();
            }
            c.drawText(text, s / 2f, s / 2f - (txt.ascent() + txt.descent()) / 2f, txt);
        }
    }

    /** Pulsante rotondo con icona disegnata (niente caratteri speciali). */
    static class IconBtn extends View {
        static final int PLAY = 0, PAUSE = 1, ADD = 2, STOP = 3, CLOSE = 4;
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Path path = new android.graphics.Path();
        int type;
        final float d;
        final int size;

        IconBtn(Context c, int type, int sizeDp) {
            super(c);
            this.type = type;
            d = c.getResources().getDisplayMetrics().density;
            size = (int) (sizeDp * d);
            bg.setColor(0x1FFFFFFF);
            fg.setColor(Color.WHITE); fg.setStyle(Paint.Style.FILL);
            fg.setTextAlign(Paint.Align.CENTER); fg.setTypeface(Typeface.DEFAULT_BOLD);
            setClickable(true);
        }

        void setType(int t) { if (t != type) { type = t; invalidate(); } }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(size, size); }

        @Override
        protected void onDraw(Canvas c) {
            float s = getWidth(), m = s / 2f, u = s / 100f;
            c.drawCircle(m, m, m, bg);
            switch (type) {
                case PLAY:
                    path.reset(); path.moveTo(40 * u, 30 * u); path.lineTo(72 * u, 50 * u); path.lineTo(40 * u, 70 * u); path.close();
                    c.drawPath(path, fg); break;
                case PAUSE:
                    c.drawRoundRect(new RectF(33 * u, 30 * u, 45 * u, 70 * u), 3 * u, 3 * u, fg);
                    c.drawRoundRect(new RectF(55 * u, 30 * u, 67 * u, 70 * u), 3 * u, 3 * u, fg); break;
                case STOP:
                    c.drawRoundRect(new RectF(33 * u, 33 * u, 67 * u, 67 * u), 6 * u, 6 * u, fg); break;
                case ADD:
                    fg.setTextSize(30 * u);
                    c.drawText("+30", m, m - (fg.ascent() + fg.descent()) / 2f, fg); break;
                case CLOSE:
                    fg.setStyle(Paint.Style.STROKE); fg.setStrokeWidth(7 * u); fg.setStrokeCap(Paint.Cap.ROUND);
                    c.drawLine(36 * u, 36 * u, 64 * u, 64 * u, fg);
                    c.drawLine(64 * u, 36 * u, 36 * u, 64 * u, fg);
                    fg.setStyle(Paint.Style.FILL); break;
            }
        }
    }

    private boolean canShowBubble() {
        return TimerState.bubble && !TimerState.bubbleHidden && !TimerState.foreground && TimerState.active()
                && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this));
    }

    private void refreshBubble() {
        if (canShowBubble()) {
            if (bubbleView == null) addBubble();
            updateBubble();
        } else removeBubble();
    }

    private LinearLayout.LayoutParams gap(int w, int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.leftMargin = dp(6);
        p.gravity = Gravity.CENTER_VERTICAL;
        return p;
    }

    private int screenW() { return getResources().getDisplayMetrics().widthPixels; }
    private int screenH() { return getResources().getDisplayMetrics().heightPixels; }

    private void addBubble() {
        bubbleView = new LinearLayout(this);
        bubbleView.setOrientation(LinearLayout.HORIZONTAL);
        bubbleView.setGravity(Gravity.CENTER_VERTICAL);
        bubbleView.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0101020);
        bg.setCornerRadius(dp(40));
        bg.setStroke(dp(1), 0x33FFFFFF);
        bubbleView.setBackground(bg);
        bubbleView.setElevation(dp(8));

        ring = new RingView(this);
        bubbleView.addView(ring, new LinearLayout.LayoutParams(ViewGroup_WRAP(), ViewGroup_WRAP()));

        bPlay = new IconBtn(this, IconBtn.PAUSE, 38);
        bAdd = new IconBtn(this, IconBtn.ADD, 38);
        bStop = new IconBtn(this, IconBtn.STOP, 38);
        bHide = new IconBtn(this, IconBtn.CLOSE, 30);
        bubbleView.addView(bPlay, gap(dp(38), dp(38)));
        bubbleView.addView(bAdd, gap(dp(38), dp(38)));
        bubbleView.addView(bStop, gap(dp(38), dp(38)));
        LinearLayout.LayoutParams lh = gap(dp(30), dp(30));
        lh.rightMargin = dp(6);
        bubbleView.addView(bHide, lh);
        bPlay.setOnClickListener(v -> { rearm(); TimerState.act(this, "toggle"); refreshAfterAction(); });
        bAdd.setOnClickListener(v -> { rearm(); TimerState.act(this, "add"); refreshAfterAction(); });
        bStop.setOnClickListener(v -> { TimerState.act(this, "stop"); refreshAfterAction(); });
        bHide.setOnClickListener(v -> { TimerState.hideBubble(this); removeBubble(); });
        setControlsVisible(false);
        expanded = false;

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = TimerState.sp(this).getInt("bx", screenW() - dp(70));
        lp.y = TimerState.sp(this).getInt("by", dp(160));

        // trascinamento + tocco (tocco = apre/chiude i comandi)
        ring.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy;
            float tx, ty;
            boolean moved;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = lp.x; sy = lp.y; tx = e.getRawX(); ty = e.getRawY(); moved = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - tx) > dp(8) || Math.abs(e.getRawY() - ty) > dp(8)) moved = true;
                        if (moved) {
                            lp.x = sx + (int) (e.getRawX() - tx);
                            lp.y = sy + (int) (e.getRawY() - ty);
                            clampPos();
                            try { wm.updateViewLayout(bubbleView, lp); } catch (Exception ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) setExpanded(!expanded);
                        else snapAndSave();
                        return true;
                }
                return false;
            }
        });
        try { wm.addView(bubbleView, lp); } catch (Exception e) { bubbleView = null; }
    }

    private static int ViewGroup_WRAP() { return android.view.ViewGroup.LayoutParams.WRAP_CONTENT; }

    private void setControlsVisible(boolean v) {
        int vis = v ? View.VISIBLE : View.GONE;
        bPlay.setVisibility(vis); bAdd.setVisibility(vis); bStop.setVisibility(vis); bHide.setVisibility(vis);
    }

    private void rearm() {
        h.removeCallbacks(collapse);
        if (expanded) h.postDelayed(collapse, 5000);
    }

    private void setExpanded(boolean on) {
        if (bubbleView == null) return;
        expanded = on;
        h.removeCallbacks(collapse);
        setControlsVisible(on);
        if (on) h.postDelayed(collapse, 5000);
        bubbleView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        clampPos();
        try { wm.updateViewLayout(bubbleView, lp); } catch (Exception ignored) {}
        if (!on) snapAndSave();
    }

    private void clampPos() {
        int w = bubbleView.getMeasuredWidth() > 0 ? bubbleView.getMeasuredWidth() : dp(62);
        int hgt = bubbleView.getMeasuredHeight() > 0 ? bubbleView.getMeasuredHeight() : dp(62);
        lp.x = Math.max(0, Math.min(lp.x, screenW() - w));
        lp.y = Math.max(0, Math.min(lp.y, screenH() - hgt - dp(40)));
    }

    /** A riposo la bolla si aggancia al bordo piu' vicino. */
    private void snapAndSave() {
        if (bubbleView == null) return;
        bubbleView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = bubbleView.getMeasuredWidth();
        final int from = lp.x;
        final int to = (lp.x + w / 2 < screenW() / 2) ? dp(6) : screenW() - w - dp(6);
        TimerState.sp(this).edit().putInt("bx", to).putInt("by", lp.y).apply();
        ValueAnimator va = ValueAnimator.ofInt(from, to);
        va.setDuration(180);
        va.addUpdateListener(a -> {
            if (bubbleView == null) return;
            lp.x = (Integer) a.getAnimatedValue();
            try { wm.updateViewLayout(bubbleView, lp); } catch (Exception ignored) {}
        });
        va.start();
    }

    private void updateBubble() {
        if (bubbleView == null) return;
        long rem = TimerState.remaining();
        float frac = TimerState.total > 0 ? (float) rem / (float) TimerState.total : 0f;
        int a, b;
        if (!TimerState.run) { a = 0xFF9AA0B4; b = 0xFF6F7690; }
        else if (rem <= 10000) { a = 0xFFFF6B6B; b = 0xFFFFB14A; }
        else { a = c1(); b = c2(); }
        ring.set(frac, fmt(rem), a, b);
        bPlay.setType(TimerState.run ? IconBtn.PAUSE : IconBtn.PLAY);
    }

    private void removeBubble() {
        h.removeCallbacks(collapse);
        if (bubbleView != null) {
            try { wm.removeView(bubbleView); } catch (Exception ignored) {}
            bubbleView = null;
        }
    }

    private void refreshAfterAction() {
        if (!TimerState.active()) {
            removeBubble();
            releaseSession();
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
