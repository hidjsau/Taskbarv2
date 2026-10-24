/* Copyright 2016 Braden Farmer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.farmerbb.taskbar.service;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

import com.farmerbb.taskbar.R;
import com.farmerbb.taskbar.util.U;

import static com.farmerbb.taskbar.util.Constants.*;

public class PowerMenuService extends AccessibilityService {

    private static final long WATCHDOG_INTERVAL_MS = 30_000L;
    private static final String PREF_BATTERY_PROMPTED = "battery_exemption_prompted";

    private Handler watchdogHandler;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            checkTaskbarAlive();
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    private final BroadcastReceiver powerMenuReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if(!performGlobalAction(intent.getIntExtra(EXTRA_ACTION, -1))) {
                U.showToast(PowerMenuService.this, R.string.tb_lock_device_not_supported);
            }
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onCreate() {
        super.onCreate();

        U.registerReceiver(this, powerMenuReceiver, ACTION_ACCESSIBILITY_ACTION);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        askBatteryExemptionOnce();

        // The system keeps this service (and this process) alive, so use it as a watchdog:
        // if HyperOS froze or killed the taskbar services, bring them back.
        watchdogHandler = U.newHandler();
        watchdogHandler.removeCallbacks(watchdog);
        watchdogHandler.postDelayed(watchdog, 2000);
    }

    private void checkTaskbarAlive() {
        try {
            SharedPreferences pref = U.getSharedPreferences(this);

            boolean shouldBeRunning = pref.getBoolean(PREF_TASKBAR_ACTIVE, false)
                    && !pref.getBoolean(PREF_IS_HIDDEN, false)
                    && U.canDrawOverlays(this);

            if(!shouldBeRunning) return;

            boolean alive = U.isServiceRunning(this, NotificationService.class)
                    && U.isServiceRunning(this, TaskbarService.class)
                    && U.isServiceRunning(this, StartMenuService.class);

            if(!alive) U.restartTaskbar(this);
        } catch (Exception ignored) {}
    }

    private void askBatteryExemptionOnce() {
        try {
            if(android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return;

            SharedPreferences pref = U.getSharedPreferences(this);
            if(pref.getBoolean(PREF_BATTERY_PROMPTED, false)) return;

            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if(pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;

            pref.edit().putBoolean(PREF_BATTERY_PROMPTED, true).apply();

            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if(watchdogHandler != null) watchdogHandler.removeCallbacks(watchdog);
        U.unregisterReceiver(this, powerMenuReceiver);
    }
}
