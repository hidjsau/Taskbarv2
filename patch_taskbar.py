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
for f in ("ic_sync.xml", "ic_clipsync_launcher.xml", "clipsync_all_apps.xml",
          "clipsync_all_apps_pressed.xml", "tb_all_apps_button_icon.xml",
          "dex_back.xml", "dex_home.xml", "dex_recents.xml"):
    shutil.copy(os.path.join(HERE, f), os.path.join(ROOT, DRAW_DIR, f))
shutil.copy(os.path.join(HERE, "clipsync_values.xml"), os.path.join(ROOT, f"{MAIN}/res/values/clipsync_values.xml"))
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
            android:taskAffinity="com.clipsync.app.task"
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
            android:taskAffinity="com.clipsync.app.task"
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
    t = once(
        t,
        r"(if\(layout != null\) layout\.setAlpha\(isCollapsed && hide \? 0 : 1\);)",
        r"\1\n        com.clipsync.app.CollapseStyle.update(context, layout, button, isCollapsed);",
        "updateButton hook",
    )
    write(TC, t)
t = read(TC)
if "DexStyle" not in t:
    t = once(
        t,
        r"(SharedPreferences pref = U\.getSharedPreferences\(context\);\n\s*boolean altButtonConfig = pref\.getBoolean\(PREF_ALT_BUTTON_CONFIG, false\);)",
        r"com.clipsync.app.DexStyle.applyDefaults(context);\n        \1",
        "dex defaults",
    )
    t = once(
        t,
        r"(navbarButtonsEnabled = drawNavbarButtons\(context, layout, pref, accentColor\);)",
        r"\1\n        com.clipsync.app.DexStyle.arrangeNav(layout);",
        "dex nav order",
    )
    write(TC, t)
print("TaskbarController ok")

# 3b. entry on Taskbar's home (settings) page that opens ClipSync ------------
BASE = f"{MAIN}/res/xml/tb_pref_base.xml"
pb = read(BASE)
if "pref_screen_clipsync" not in pb:
    pb = once(
        pb,
        r"(\n[ \t]*</PreferenceScreen>)",
        lambda x: """
    <Preference
        android:key="pref_screen_clipsync"
        android:icon="@drawable/ic_clipsync_launcher"
        android:title="Text Sync (ClipSync)"
        android:summary="Send clipboard text to your other devices" />
""" + x.group(1),
        "tb_pref_base end",
    )
    write(BASE, pb)

# 3c. Appearance settings: collapsed position + widget button switch -------------
AP = f"{MAIN}/res/xml/tb_pref_appearance.xml"
ap = read(AP)
if "clipsync_collapse_pos" not in ap:
    ap = once(
        ap,
        r'(\n[ \t]*<PreferenceCategory android:key="dummy")',
        lambda x: """
    <ListPreference
        android:defaultValue="default"
        android:entries="@array/clipsync_collapse_entries"
        android:entryValues="@array/clipsync_collapse_values"
        android:key="clipsync_collapse_pos"
        android:negativeButtonText="@null"
        android:positiveButtonText="@null"
        android:title="Collapsed taskbar position" />

    <CheckBoxPreference
        android:defaultValue="@bool/tb_def_dashboard"
        android:key="dashboard"
        android:summary="Show a button in the Taskbar that opens your widgets"
        android:title="Widget button" />
""" + x.group(1),
        "appearance prefs",
    )
    write(AP, ap)

AFR = f"{TB_JAVA}/fragment/AppearanceFragment.java"
afr = read(AFR)
if "clipsync_collapse_pos" not in afr:
    afr = once(
        afr,
        r"(bindPreferenceSummaryToValue\(findPreference\(PREF_THEME\)\);)",
        r'''if(pref.getString("clipsync_collapse_pos", null) == null)
            pref.edit().putString("clipsync_collapse_pos", "default").apply();
        bindPreferenceSummaryToValue(findPreference("clipsync_collapse_pos"));
        bindPreferenceSummaryToValue(findPreference(PREF_DASHBOARD));
        \1''',
        "appearance bind",
    )
    write(AFR, afr)

AF = f"{TB_JAVA}/fragment/AboutFragment.java"
af = read(AF)
if "pref_screen_clipsync" not in af:
    af = once(
        af,
        r"(findPreference\(PREF_PREF_SCREEN_ADVANCED\)\.setOnPreferenceClickListener\(this\);)",
        r'\1\n        findPreference("pref_screen_clipsync").setOnPreferenceClickListener(this);',
        "about listener",
    )
    af = once(
        af,
        r"(switch\(p\.getKey\(\)\) \{\s*\n)(\s*)(case PREF_ABOUT:)",
        r'\1\2case "pref_screen_clipsync":\n\2    startActivity(new Intent(getActivity(), com.clipsync.app.MainActivity.class)\n\2            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));\n\2    break;\n\2\3',
        "about click",
    )
    write(AF, af)
# 3d. Taskbar opacity slider (Appearance settings) ---------------------------
ap = read(AP)
if "clipsync_bar_opacity" not in ap:
    ap = once(
        ap,
        r'(\n[ \t]*<PreferenceCategory android:key="dummy")',
        lambda x: """
    <Preference
        android:key="clipsync_bar_opacity"
        android:title="Taskbar opacity"
        android:summary="Make the taskbar more see-through" />
""" + x.group(1),
        "opacity pref",
    )
    write(AP, ap)

afr = read(AFR)
if "clipsync_bar_opacity" not in afr:
    afr = once(
        afr,
        r"(findPreference\(PREF_ACCENT_COLOR_PREF\)\.setOnPreferenceClickListener\(this\);)",
        r'''\1
        findPreference("clipsync_bar_opacity").setOnPreferenceClickListener(this);
        findPreference("clipsync_bar_opacity").setSummary(com.clipsync.app.BarOpacity.summary(getActivity()));''',
        "opacity listener",
    )
    afr = once(
        afr,
        r"(            case PREF_BACKGROUND_TINT_PREF:\n)",
        r'''            case "clipsync_bar_opacity":
                com.clipsync.app.BarOpacity.showDialog(getActivity(),
                        () -> p.setSummary(com.clipsync.app.BarOpacity.summary(getActivity())));
                break;
\1''',
        "opacity click",
    )
    write(AFR, afr)
print("opacity slider ok")

print("home page entry ok")

# 4. U.java: make room for the divider (start) and the sync button (end) -------
UJ = f"{TB_JAVA}/util/U.java"
u = read(UJ)
if "DexStyle.extraStartPx" not in u:
    u = once(
        u,
        r"(if\(navbarButtonsEnabled\)\s*\n\s*baseTaskbarSize \+= context\.getResources\(\)\.getDimension\(R\.dimen\.tb_navbar_buttons_margin\);)",
        r"\1\n\n        baseTaskbarSize += com.clipsync.app.DexStyle.extraStartPx(context);",
        "taskbar size start",
    )
    u = once(
        u,
        r"(if\(isSystemTrayEnabled\(context\)\) \{\s*\n\s*float sysTraySize)",
        r"if(com.clipsync.app.TaskbarSyncButton.enabled(context) && !TaskbarPosition.isVertical(context))\n            baseTaskbarSize += com.clipsync.app.TaskbarSyncButton.sizePx(context);\n\n        \1",
        "taskbar size end",
    )
    write(UJ, u)
print("U.java ok")

# 4b. slimmer, DeX-like sizes (bar 48dp, apps button glyph 24dp) ---------------
DM = f"{MAIN}/res/values/dimens.xml"
dm = read(DM)
if '<dimen name="tb_icon_size">48dp</dimen>' not in dm:
    dm = once(dm, r'(<dimen name="tb_icon_size">)60dp(</dimen>)', r"\g<1>48dp\2", "dimen icon size")
    dm = once(dm, r'(<dimen name="tb_base_size_start_plus_divider">)65dp(</dimen>)', r"\g<1>53dp\2", "dimen base start")
    dm = once(dm, r'(<dimen name="tb_app_drawer_icon_padding">)4dp(</dimen>)', r"\g<1>12dp\2", "dimen drawer padding")
    write(DM, dm)
print("dimens ok")

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
