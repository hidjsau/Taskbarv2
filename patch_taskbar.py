#!/usr/bin/env python3
"""Adds ClipSync (text sync) to a Taskbar source tree. Run from the repo root.
Safe to run twice. Fails loudly if Taskbar's files differ from what it expects."""
import glob, os, re, shutil, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.getcwd()
MAIN = "app/src/main"
KT_DIR = f"{MAIN}/java/com/clipsync/app"
DRAW_DIR = f"{MAIN}/res/drawable"
TB_JAVA = f"{MAIN}/java/com/farmerbb/taskbar"


def read(p):
    with open(os.path.join(ROOT, p), encoding="utf-8") as f:
        return f.read()


def write(p, s):
    with open(os.path.join(ROOT, p), "w", encoding="utf-8", newline="") as f:
        f.write(s)


def once(text, pattern, repl, label, flags=0):
    new, n = re.subn(pattern, repl, text, count=0, flags=flags)
    if n != 1:
        sys.exit(f"PATCH FAILED: '{label}' matched {n} times (expected 1). Taskbar version differs.")
    return new


# 1. copy our files ---------------------------------------------------------
os.makedirs(os.path.join(ROOT, KT_DIR), exist_ok=True)
os.makedirs(os.path.join(ROOT, DRAW_DIR), exist_ok=True)
for f in glob.glob(os.path.join(HERE, "*.kt")):
    shutil.copy(f, os.path.join(ROOT, KT_DIR, os.path.basename(f)))
for f in ("ic_sync.xml", "ic_clipsync_launcher.xml"):
    shutil.copy(os.path.join(HERE, f), os.path.join(ROOT, DRAW_DIR, f))
print("copied sources and icons")

# 2. manifest ---------------------------------------------------------------
MF = "app/src/playstore/AndroidManifest.xml"
m = read(MF)
if "com.clipsync.app" not in m:
    perms = '''    <!-- ClipSync -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

'''
    comps = '''
        <!-- ClipSync -->
        <activity
            android:name="com.clipsync.app.MainActivity"
            android:exported="true"
            android:icon="@drawable/ic_clipsync_launcher"
            android:label="ClipSync"
            android:theme="@android:style/Theme.DeviceDefault.Light">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE_PREFERENCES" />
            </intent-filter>
        </activity>
        <activity
            android:name="com.clipsync.app.HistoryActivity"
            android:exported="false"
            android:label="ClipSync messages"
            android:theme="@android:style/Theme.DeviceDefault.Light" />
        <activity
            android:name="com.clipsync.app.SendActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:noHistory="true"
            android:taskAffinity=""
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
        <service
            android:name="com.clipsync.app.SyncService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
        <service
            android:name="com.clipsync.app.SendTileService"
            android:exported="true"
            android:icon="@drawable/ic_sync"
            android:label="Send clipboard"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>
        </service>

'''
    m = once(m, r"(\n[ \t]*<application\b)", lambda x: "\n" + perms.rstrip("\n") + "\n" + x.group(1), "manifest <application>")
    m = once(m, r"(\n[ \t]*</application>)", lambda x: comps + x.group(1).lstrip("\n"), "manifest </application>")
    write(MF, m)
print("manifest ok")

# 3. TaskbarController: add the button, keep it in sync with show/hide ------
TC = f"{TB_JAVA}/ui/TaskbarController.java"
t = read(TC)
if "TaskbarSyncButton" not in t:
    t = once(
        t,
        r"(dashboardEnabled = drawDashboardButton\(context, layout, dashboardButton, accentColor\);)",
        r"\1\n        com.clipsync.app.TaskbarSyncButton.attach(context, dashboardButton, accentColor);",
        "attach sync button",
    )
    t = once(
        t,
        r"(if\(dashboardEnabled\)\s*\n\s*dashboardButton\.setVisibility\(View\.VISIBLE\);)",
        r"\1\n            com.clipsync.app.TaskbarSyncButton.setShown(true);",
        "show sync button",
    )
    t = once(
        t,
        r"(if\(dashboardEnabled\)\s*\n\s*dashboardButton\.setVisibility\(View\.GONE\);)",
        r"\1\n            com.clipsync.app.TaskbarSyncButton.setShown(false);",
        "hide sync button",
    )
    write(TC, t)
print("TaskbarController ok")

# 4. U.java: make room for the button when sizing the taskbar ---------------
UJ = f"{TB_JAVA}/util/U.java"
u = read(UJ)
if "TaskbarSyncButton" not in u:
    u = once(
        u,
        r"(if\(getBooleanPrefWithDefault\(context, PREF_DASHBOARD\)\)\s*\n\s*baseTaskbarSize \+= context\.getResources\(\)\.getDimension\(R\.dimen\.tb_dashboard_button_size\);)",
        r"\1\n\n        if(com.clipsync.app.TaskbarSyncButton.enabled(context))\n            baseTaskbarSize += context.getResources().getDimension(R.dimen.tb_dashboard_button_size);",
        "taskbar size",
    )
    write(UJ, u)
print("U.java ok")

# 5. fixed debug signing so updates install over each other ------------------
BG = "app/build.gradle"
b = read(BG)
if "clipsyncDebug" not in b:
    b = once(
        b,
        r"(signingConfigs\s*\{)",
        r"""\1
        clipsyncDebug {
            storeFile rootProject.file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }""",
        "signingConfigs",
    )
    b = once(
        b,
        r'(debug\s*\{\s*\n\s*applicationIdSuffix "\.debug")',
        r"\1\n            signingConfig signingConfigs.clipsyncDebug",
        "debug build type",
    )
    write(BG, b)
print("build.gradle ok")

# 6. remove Taskbar's own CI so only our workflow runs -----------------------
old = os.path.join(ROOT, ".github/workflows/gradle.yml")
if os.path.exists(old):
    os.remove(old)
    print("removed gradle.yml")

print("ALL PATCHES APPLIED")
