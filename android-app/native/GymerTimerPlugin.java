package it.gymer.app;

import android.Manifest;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.os.VibrationEffect;
import android.os.Vibrator;
import androidx.core.app.NotificationManagerCompat;
import org.json.JSONArray;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.getcapacitor.PermissionState;

@CapacitorPlugin(
        name = "GymerTimer",
        permissions = { @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notif") }
)
public class GymerTimerPlugin extends Plugin {

    @Override
    public void load() {
        TimerState.load(getContext());
        TimerState.foreground = true;
        TimerState.listener = () -> notifyListeners("state", toJs());
    }

    private JSObject toJs() {
        JSObject o = new JSObject();
        o.put("run", TimerState.run);
        o.put("end", TimerState.end);
        o.put("left", TimerState.left / 1000.0);
        o.put("total", TimerState.total / 1000.0);
        o.put("u", TimerState.u);
        return o;
    }

    @PluginMethod
    public void sync(PluginCall call) {
        try {
            boolean run = call.getBoolean("run", false);
            long end = call.getData().optLong("end", 0);
            double left = call.getData().optDouble("left", 0);
            double total = call.getData().optDouble("total", 0);
            long u = call.getData().optLong("u", System.currentTimeMillis());
            boolean snd = call.getData().optInt("snd", 1) == 1;
            int vib = call.getData().optInt("vib", 400);
            TimerState.setFromJs(getContext(), run, end, left, total, u, snd, vib);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void getState(PluginCall call) {
        TimerState.load(getContext());
        call.resolve(toJs());
    }

    @PluginMethod
    public void overlayStatus(PluginCall call) {
        JSObject o = new JSObject();
        o.put("granted", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(getContext()));
        o.put("bubble", TimerState.bubble);
        call.resolve(o);
    }

    @PluginMethod
    public void requestOverlay(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void setBubble(PluginCall call) {
        TimerState.bubble = call.getBoolean("on", true);
        TimerState.save(getContext());
        if (TimerState.active()) TimerState.refreshService(getContext());
        call.resolve();
    }

    @PluginMethod
    public void diagnose(PluginCall call) {
        Context c = getContext();
        JSObject o = new JSObject();
        o.put("notif", NotificationManagerCompat.from(c).areNotificationsEnabled());
        o.put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c));
        boolean exact = true;
        try { if (Build.VERSION.SDK_INT >= 31) exact = ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).canScheduleExactAlarms(); } catch (Exception ignored) {}
        o.put("exact", exact);
        o.put("sdk", Build.VERSION.SDK_INT);
        o.put("active", TimerState.active());
        o.put("bubble", TimerState.bubble);
        call.resolve(o);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        try {
            String what = call.getString("what", "notif");
            Intent i;
            if ("overlay".equals(what)) {
                i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
            } else {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void test(PluginCall call) {
        TimerState.snd = call.getData().optInt("snd", 1) == 1;
        TimerState.vib = call.getData().optInt("vib", 400);
        TimerState.postDone(getContext());
        call.resolve();
    }

    @PluginMethod
    public void vibrate(PluginCall call) {
        try {
            JSONArray a = call.getData().optJSONArray("p");
            if (a == null || a.length() == 0) { call.resolve(); return; }
            long[] pat = new long[a.length() + 1];
            pat[0] = 0;
            for (int k = 0; k < a.length(); k++) pat[k + 1] = Math.max(1, a.optLong(k, 10));
            Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pat, -1));
                else v.vibrate(pat, -1);
            }
        } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void requestNotif(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notif") != PermissionState.GRANTED) {
            requestPermissionForAlias("notif", call, "notifCb");
        } else {
            JSObject o = new JSObject();
            o.put("granted", true);
            call.resolve(o);
        }
    }

    @PermissionCallback
    private void notifCb(PluginCall call) {
        JSObject o = new JSObject();
        o.put("granted", getPermissionState("notif") == PermissionState.GRANTED);
        call.resolve(o);
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        TimerState.load(getContext());
        TimerState.setForeground(getContext(), true);
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        TimerState.setForeground(getContext(), false);
    }
}
