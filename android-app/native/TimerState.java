package it.gymer.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/** Stato condiviso del timer di recupero (pagina web, servizio, allarme, bolla). */
public final class TimerState {
    private TimerState() {}

    public interface Listener { void onChange(); }

    public static boolean run;
    public static long end;     // ms epoch
    public static long left;    // ms
    public static long total;   // ms
    public static long u;       // ultimo aggiornamento (ms epoch)
    public static boolean snd = true;
    public static int vib = 400;
    public static boolean bubble = true;
    public static boolean bubbleHidden = false;
    public static boolean foreground = false;
    public static Listener listener;

    static final int ID_RUN = 7, ID_DONE = 8;

    static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences("gymer_timer", 0); }

    public static synchronized void load(Context c) {
        SharedPreferences p = sp(c);
        run = p.getBoolean("run", false);
        end = p.getLong("end", 0);
        left = p.getLong("left", 0);
        total = p.getLong("total", 0);
        u = p.getLong("u", 0);
        snd = p.getBoolean("snd", true);
        vib = p.getInt("vib", 400);
        bubble = p.getBoolean("bubble", true);
        bubbleHidden = p.getBoolean("hidden", false);
    }

    public static synchronized void save(Context c) {
        sp(c).edit().putBoolean("run", run).putLong("end", end).putLong("left", left).putLong("total", total)
                .putLong("u", u).putBoolean("snd", snd).putInt("vib", vib).putBoolean("bubble", bubble)
                .putBoolean("hidden", bubbleHidden).apply();
    }

    public static long remaining() { return run ? Math.max(0, end - System.currentTimeMillis()) : left; }
    public static boolean paused() { return !run && left > 0 && left < total - 100; }
    public static boolean active() { return run || paused(); }

    static void changed() { Listener l = listener; if (l != null) { try { l.onChange(); } catch (Exception ignored) {} } }

    /** Aggiornamento che arriva dalla pagina. */
    public static synchronized void setFromJs(Context c, boolean r, long e, double leftSec, double totalSec, long upd, boolean s, int v) {
        boolean was = active();
        run = r; end = e; left = (long) (leftSec * 1000); total = (long) (totalSec * 1000); u = upd; snd = s; vib = v;
        if (!was && active()) bubbleHidden = false;
        save(c);
        schedule(c);
        refreshService(c);
    }

    /** Azione che arriva da notifica o bolla: pause, resume, toggle, add, stop. */
    public static synchronized void act(Context c, String a) {
        long now = System.currentTimeMillis();
        if ("toggle".equals(a)) a = run ? "pause" : "resume";
        switch (a) {
            case "pause": if (run) { left = Math.max(0, end - now); run = false; } break;
            case "resume": if (!run && left > 0) { end = now + left; run = true; } break;
            case "add": if (run) end += 30000; else left += 30000; total += 30000; break;
            case "stop": run = false; left = total; break;
            default: return;
        }
        u = now;
        save(c);
        schedule(c);
        changed();
    }

    public static void hideBubble(Context c) { bubbleHidden = true; save(c); changed(); }

    // ---------- allarme di fine ----------
    static PendingIntent alarmPi(Context c) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction("it.gymer.app.DONE");
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static void schedule(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = alarmPi(c);
            am.cancel(pi);
            if (!run) return;
            boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exact) {
                if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pi);
                else am.setExact(AlarmManager.RTC_WAKEUP, end, pi);
            } else {
                Intent launch = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
                PendingIntent show = PendingIntent.getActivity(c, 2, launch, PendingIntent.FLAG_IMMUTABLE);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(end, show), pi);
            }
        } catch (Exception ignored) {}
    }

    // ---------- servizio ----------
    public static void refreshService(Context c) {
        try {
            if (active()) {
                Intent i = new Intent(c, TimerService.class);
                try { ContextCompat.startForegroundService(c, i); } catch (Exception e) { /* app in background: il servizio e' gia' attivo */ }
            } else {
                c.stopService(new Intent(c, TimerService.class));
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                nm.cancel(ID_RUN);
            }
        } catch (Exception ignored) {}
    }

    public static void setForeground(Context c, boolean f) {
        foreground = f;
        if (f) {
            try { ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(ID_DONE); } catch (Exception ignored) {}
        }
        if (active()) refreshService(c);
    }

    // ---------- fine recupero ----------
    public static synchronized void finish(Context c) {
        if (!run) return;
        if (System.currentTimeMillis() < end - 1500) return; // allarme vecchio
        run = false; left = 0; u = System.currentTimeMillis();
        save(c);
        try { ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(alarmPi(c)); } catch (Exception ignored) {}
        try { c.stopService(new Intent(c, TimerService.class)); } catch (Exception ignored) {}
        try { ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(ID_RUN); } catch (Exception ignored) {}
        if (!foreground) postDone(c);
        changed();
    }

    static void postDone(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        long[] pattern = vib > 0 ? new long[]{0, vib, vib / 3, vib, vib / 3, vib} : null;
        String chId = "gymer_done_" + (snd ? 1 : 0) + "_" + vib;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(chId, "Recupero finito", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Avviso quando il recupero e' finito");
            if (snd) {
                Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                ch.setSound(uri, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            } else ch.setSound(null, null);
            if (pattern != null) { ch.enableVibration(true); ch.setVibrationPattern(pattern); } else ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
        Intent launch = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        PendingIntent open = PendingIntent.getActivity(c, 3, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int icon = c.getResources().getIdentifier("ic_stat_timer", "drawable", c.getPackageName());
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, chId)
                .setSmallIcon(icon != 0 ? icon : android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("GYMER")
                .setContentText("Recupero finito: tocca a te!")
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(open);
        if (Build.VERSION.SDK_INT < 26) {
            if (pattern != null) b.setVibrate(pattern);
            if (snd) b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
        }
        try { nm.notify(ID_DONE, b.build()); } catch (Exception ignored) {}
    }
}
