package com.glasstv.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Puts the TV to sleep for the Settings menu's Sleep row. Apps can't (PowerManager.goToSleep needs DEVICE_POWER),
 * but an accessibility service's lock-screen action does: on Android 9+ the system turns the screen off for it.
 * It filters no keys and reads no windows. Enable over adb:
 *   settings put secure enabled_accessibility_services com.glasstv.tvhome/.SleepService
 *   settings put secure accessibility_enabled 1
 */
public class SleepService extends AccessibilityService {
    private static SleepService running;

    /** False when the service is not enabled, or the TV refused. */
    static boolean sleep() {
        SleepService s = running;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = this;
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
