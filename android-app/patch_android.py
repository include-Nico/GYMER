#!/usr/bin/env python3
"""Applica a android/ (generato da `cap add android`) il codice nativo di GYMER."""
import os, re, shutil, sys
from PIL import Image

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

# 2) manifest: permessi, activity lockscreen, servizio, receiver
mf = os.path.join(APP, "AndroidManifest.xml")
x = open(mf, encoding="utf-8").read()
perms = [
    '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />',
    '<uses-permission android:name="android.permission.VIBRATE" />',
    '<uses-permission android:name="android.permission.WAKE_LOCK" />',
    '<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />',
    '<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />',
    '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />',
    '<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />',
    '<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />',
    '<uses-permission android:name="android.permission.USE_EXACT_ALARM" />',
    '<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />',
]
add = "\n    ".join(p for p in perms if p.split('"')[1] not in x)
if add:
    x = x.replace("</manifest>", "    " + add + "\n</manifest>")

comp = '''
        <activity
            android:name=".LockScreenActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:launchMode="singleTop"
            android:showOnLockScreen="true"
            android:turnScreenOn="true"
            android:taskAffinity="" />
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
open(os.path.join(dr, "ic_stat_timer.xml"), "w", encoding="utf-8").write('''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M9,1h6v2L9,3zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61zM13,14h-2L11,8h2z"/>
</vector>
''')

# 3b) layout della notifica del timer: riquadro con clessidra + tempo grande (stile timer Samsung)
lyd = os.path.join(APP, "res", "layout")
os.makedirs(lyd, exist_ok=True)
open(os.path.join(lyd, "gymer_notif.xml"), "w", encoding="utf-8").write('''<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:paddingTop="4dp"
    android:paddingBottom="4dp">
    <ImageView
        android:id="@+id/gn_icon"
        android:layout_width="46dp"
        android:layout_height="46dp"
        android:layout_marginEnd="12dp"
        android:src="@drawable/gn_tile" />
    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical">
        <Chronometer
            android:id="@+id/gn_time"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textAppearance="@android:style/TextAppearance.Material.Notification.Title"
            android:textSize="28sp" />
        <TextView
            android:id="@+id/gn_sub"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textAppearance="@android:style/TextAppearance.Material.Notification"
            android:textSize="13sp"
            android:singleLine="true" />
    </LinearLayout>
</LinearLayout>
''')

open(os.path.join(dr, "gn_tile.xml"), "w", encoding="utf-8").write('''<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item>
        <shape android:shape="rectangle">
            <solid android:color="#5B50D6" />
            <corners android:radius="14dp" />
        </shape>
    </item>
    <item android:left="11dp" android:top="9dp" android:right="11dp" android:bottom="9dp">
        <vector android:width="24dp" android:height="28dp" android:viewportWidth="24" android:viewportHeight="28">
            <path android:fillColor="#FFFFFF"
                android:pathData="M3,1h18v3h-1.5v3.2c0,1.7 -0.8,3.2 -2.1,4.3L14,14l3.4,2.5c1.3,1.1 2.1,2.6 2.1,4.3V24H21v3H3v-3h1.5v-3.2c0,-1.7 0.8,-3.2 2.1,-4.3L10,14l-3.4,-2.5C5.3,10.4 4.5,8.9 4.5,7.2V4H3zM8,4v3.2c0,0.9 0.4,1.7 1.1,2.3L12,11.7l2.9,-2.2c0.7,-0.6 1.1,-1.4 1.1,-2.3V4zM12,16.3l-2.9,2.2c-0.7,0.6 -1.1,1.4 -1.1,2.3V24h8v-3.2c0,-0.9 -0.4,-1.7 -1.1,-2.3z"/>
        </vector>
    </item>
</layer-list>
''')

# 4) splash scura (niente lampo bianco all'avvio)
for root, _, files in os.walk(os.path.join(APP, "res")):
    for f in files:
        if f in ("splash.png", "splash.jpg"):
            os.remove(os.path.join(root, f))

open(os.path.join(dr, "splash.xml"), "w", encoding="utf-8").write('''<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item><color android:color="#07070d"/></item>
</layer-list>
''')

# 5) icona dell'app (adattiva + legacy)
def conv(src, dst, size):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with Image.open(src) as im:
        im = im.convert("RGBA").resize((size, size), Image.Resampling.LANCZOS)
        im.save(dst, "PNG")

WWW = os.path.join(HERE, "www")
print("Contenuto di www/icons:", sorted(os.listdir(ICONS)) if os.path.isdir(ICONS) else "(cartella assente)")

def first(*paths):
    for q in paths:
        if os.path.exists(q):
            return q
    return None

flat = first(
    os.path.join(ICONS, "icon-512.png"),
    os.path.join(ICONS, "icon-192.png"),
    os.path.join(ICONS, "apple-touch-icon.png"),
    os.path.join(WWW, "icon-512.png")
)
fg = first(os.path.join(ICONS, "icon-maskable-512.png"), os.path.join(ICONS, "icon-maskable-192.png")) or flat
mono = first(os.path.join(ICONS, "icon-monochrome-432.png"))

if not flat:
    raise FileNotFoundError("Nessuna icona PNG trovata in www/icons")

print("Icona usata (legacy):", flat, "| (adattiva):", fg, "| (mono):", mono)

res = os.path.join(APP, "res")
for root, _, files in os.walk(res):
    for f in files:
        if f.startswith("ic_launcher"):
            os.remove(os.path.join(root, f))

dens = {"mdpi": (48, 108), "hdpi": (72, 162), "xhdpi": (96, 216), "xxhdpi": (144, 324), "xxxhdpi": (192, 432)}
for d, (legacy, adaptive) in dens.items():
    base = os.path.join(res, "mipmap-" + d)
    conv(flat, os.path.join(base, "ic_launcher.png"), legacy)
    conv(flat, os.path.join(base, "ic_launcher_round.png"), legacy)
    conv(fg, os.path.join(base, "ic_launcher_foreground.png"), adaptive)
    if mono:
        conv(mono, os.path.join(base, "ic_launcher_monochrome.png"), adaptive)

any26 = os.path.join(res, "mipmap-anydpi-v26")
os.makedirs(any26, exist_ok=True)
for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
    with open(os.path.join(any26, n), "w", encoding="utf-8") as fp:
        fp.write('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/gymer_icon_bg"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
''' + ('    <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>\n' if mono else '') + '''</adaptive-icon>
''')

os.makedirs(os.path.join(res, "values"), exist_ok=True)
with open(os.path.join(res, "values", "gymer_colors.xml"), "w", encoding="utf-8") as fp:
    fp.write('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="gymer_icon_bg">#07070D</color>
</resources>
''')

# 6) versione = numero della build (serve per aggiornare sopra la versione precedente)
vc = os.environ.get("GITHUB_RUN_NUMBER", "1")
bg = os.path.join(HERE, "android", "app", "build.gradle")
g = open(bg, encoding="utf-8").read()
g = re.sub(r"versionCode\s+\d+", f"versionCode {vc}", g)
g = re.sub(r'versionName\s+"[^"]*"', f'versionName "1.{vc}"', g)
open(bg, "w", encoding="utf-8").write(g)
print("patch ok, versionCode", vc)
