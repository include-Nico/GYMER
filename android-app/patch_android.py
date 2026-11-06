#!/usr/bin/env python3
"""Applica a android/ (generato da `cap add android`) il codice nativo di GYMER."""
import os, re, shutil, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(HERE, "android", "app", "src", "main")
PKG_DIR = os.path.join(APP, "java", "it", "gymer", "app")
ICONS = os.path.join(HERE, "www", "icons")

# 1) sorgenti Java
os.makedirs(PKG_DIR, exist_ok=True)
for f in os.listdir(os.path.join(HERE, "native")):
    if f.endswith(".java"):
        shutil.copy(os.path.join(HERE, "native", f), os.path.join(PKG_DIR, f))
# elimina un eventuale MainActivity creato in un altro package dal template
for root, _, files in os.walk(os.path.join(APP, "java")):
    for f in files:
        full = os.path.join(root, f)
        if f == "MainActivity.java" and os.path.abspath(root) != os.path.abspath(PKG_DIR):
            os.remove(full)

# 2) manifest: permessi, servizio, receiver
mf = os.path.join(APP, "AndroidManifest.xml")
x = open(mf, encoding="utf-8").read()
perms = [
    '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />',
    '<uses-permission android:name="android.permission.VIBRATE" />',
    '<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />',
    '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />',
    '<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />',
    '<uses-permission android:name="android.permission.USE_EXACT_ALARM" />',
    '<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />',
]
add = "\n    ".join(p for p in perms if p.split('"')[1] not in x)
if add:
    x = x.replace("</manifest>", "    " + add + "\n</manifest>")
comp = '''
        <service
            android:name=".TimerService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Timer di recupero con notifica e bolla flottante" />
        </service>
        <receiver android:name=".AlarmReceiver" android:exported="false" />
'''
if "TimerService" not in x:
    x = x.replace("</application>", comp + "    </application>")
open(mf, "w", encoding="utf-8").write(x)

# 3) icona piccola della notifica
dr = os.path.join(APP, "res", "drawable")
os.makedirs(dr, exist_ok=True)
open(os.path.join(dr, "ic_stat_timer.xml"), "w").write('''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M9,1h6v2L9,3zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61zM13,14h-2L11,8h2z"/>
</vector>
''')

# 3b) layout della notifica del timer (tempo grande che scorre)
lyd = os.path.join(APP, "res", "layout")
os.makedirs(lyd, exist_ok=True)
open(os.path.join(lyd, "gymer_notif.xml"), "w").write('''<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical">
    <TextView
        android:id="@+id/gn_title"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textAppearance="@android:style/TextAppearance.Material.Notification.Title"
        android:textSize="14sp"
        android:singleLine="true" />
    <Chronometer
        android:id="@+id/gn_time"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textAppearance="@android:style/TextAppearance.Material.Notification.Title"
        android:textSize="38sp"
        android:textStyle="bold" />
    <TextView
        android:id="@+id/gn_sub"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textAppearance="@android:style/TextAppearance.Material.Notification"
        android:textSize="12sp"
        android:singleLine="true" />
</LinearLayout>
''')

# 4) splash scura (niente lampo bianco all'avvio)
for root, _, files in os.walk(os.path.join(APP, "res")):
    for f in files:
        if f in ("splash.png", "splash.jpg"):
            os.remove(os.path.join(root, f))
open(os.path.join(dr, "splash.xml"), "w").write('''<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item><color android:color="#07070d"/></item>
</layer-list>
''')

def conv(src, dst, size):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    subprocess.run(["convert", src, "-resize", f"{size}x{size}", dst], check=True)

# 5) icona dell'app (adattiva, sfondo scuro). Cerca le icone di GYMER in piu' posti e scrive cosa usa.
WWW = os.path.join(HERE, "www")
print("Contenuto di www/icons:", sorted(os.listdir(ICONS)) if os.path.isdir(ICONS) else "(cartella assente)")
TMP = os.path.join(HERE, "icon-tmp")
os.makedirs(TMP, exist_ok=True)

def first(*paths):
    for q in paths:
        if os.path.exists(q):
            return q
    return None

def from_svg():
    for s in (os.path.join(ICONS, "icon.svg"), os.path.join(WWW, "icon.svg")):
        if os.path.exists(s):
            out = os.path.join(TMP, "from_svg.png")
            r = subprocess.run(["convert", "-background", "none", "-density", "600", s, "-resize", "512x512", out])
            if r.returncode == 0 and os.path.exists(out):
                return out
    return None

def fallback():
    out = os.path.join(TMP, "fallback.png")
    subprocess.run(["convert", "-size", "512x512", "gradient:#8b7bff-#27d7ff", "-fill", "white",
                    "-draw", "roundrectangle 96,236 416,276 20,20",
                    "-draw", "roundrectangle 120,160 168,352 14,14", "-draw", "roundrectangle 344,160 392,352 14,14",
                    "-draw", "roundrectangle 76,196 112,316 12,12", "-draw", "roundrectangle 400,196 436,316 12,12",
                    out], check=True)
    return out

flat = first(os.path.join(ICONS, "icon-512.png"), os.path.join(ICONS, "icon-192.png"),
             os.path.join(ICONS, "apple-touch-icon.png"), os.path.join(WWW, "icon-512.png")) or from_svg()
fg = first(os.path.join(ICONS, "icon-maskable-512.png")) or flat
if not flat:
    flat = fg = fallback()
    print("ATTENZIONE: icone di GYMER non trovate nel repo: uso un'icona provvisoria", file=sys.stderr)
print("Icona usata (legacy):", flat, "| (adattiva):", fg)

dens = {"mdpi": (48, 108), "hdpi": (72, 162), "xhdpi": (96, 216), "xxhdpi": (144, 324), "xxxhdpi": (192, 432)}
res = os.path.join(APP, "res")
for d, (legacy, adaptive) in dens.items():
    base = os.path.join(res, "mipmap-" + d)
    conv(flat, os.path.join(base, "ic_launcher.png"), legacy)
    conv(flat, os.path.join(base, "ic_launcher_round.png"), legacy)
    conv(fg, os.path.join(base, "ic_launcher_foreground.png"), adaptive)
any26 = os.path.join(res, "mipmap-anydpi-v26")
os.makedirs(any26, exist_ok=True)
for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
    open(os.path.join(any26, n), "w").write('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/gymer_icon_bg"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
''')
os.makedirs(os.path.join(res, "values"), exist_ok=True)
open(os.path.join(res, "values", "gymer_colors.xml"), "w").write('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="gymer_icon_bg">#07070D</color>
</resources>
''')
# il template Capacitor ha un foreground vettoriale: toglilo per non duplicare il nome
for root, _, files in os.walk(res):
    for f in files:
        if f.startswith("ic_launcher_foreground.") and root.endswith(("drawable-v24", "drawable")):
            os.remove(os.path.join(root, f))
# altre varianti dell'icona del template (es. monocromatica / background vettoriale) non devono sovrascrivere la nostra
for root, _, files in os.walk(res):
    for f in files:
        if f in ("ic_launcher_background.xml",) and root.endswith(("drawable", "drawable-v24")):
            os.remove(os.path.join(root, f))

# 6) versione = numero della build (serve per aggiornare sopra la versione precedente)
vc = os.environ.get("GITHUB_RUN_NUMBER", "1")
bg = os.path.join(HERE, "android", "app", "build.gradle")
g = open(bg, encoding="utf-8").read()
g = re.sub(r"versionCode\s+\d+", f"versionCode {vc}", g)
g = re.sub(r'versionName\s+"[^"]*"', f'versionName "1.{vc}"', g)
open(bg, "w", encoding="utf-8").write(g)
print("patch ok, versionCode", vc)
