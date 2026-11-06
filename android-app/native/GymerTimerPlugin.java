package it.gymer.app;

import android.Manifest;
import android.content.Intent;
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
