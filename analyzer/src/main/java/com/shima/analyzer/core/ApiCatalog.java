package com.shima.analyzer.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** A deliberately small, versioned catalogue. Unknown Android APIs produce no compatibility claim. */
final class ApiCatalog {
    static final class Entry {
        final int since, deprecated;
        Entry(int since, int deprecated) { this.since=since; this.deprecated=deprecated; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Entry)) return false;
            Entry value=(Entry) other;
            return since==value.since && deprecated==value.deprecated;
        }
        @Override public int hashCode() { return 31*since+deprecated; }
    }
    private static final Map<String, Entry> CLASSES, STATIC_METHODS;
    static {
        Map<String, Entry> classes = new HashMap<>();
        add(classes, "android.app.NotificationChannel", 26, 0);
        add(classes, "android.os.LocaleList", 24, 0);
        add(classes, "android.os.VibrationEffect", 26, 0);
        add(classes, "android.hardware.biometrics.BiometricPrompt", 28, 0);
        add(classes, "android.app.Person", 28, 0);
        add(classes, "android.content.pm.ShortcutInfo", 25, 0);
        add(classes, "android.app.role.RoleManager", 29, 0);
        add(classes, "android.view.WindowInsetsController", 30, 0);
        add(classes, "android.app.ProgressDialog", 1, 26);
        CLASSES=Collections.unmodifiableMap(classes);
        Map<String, Entry> methods = new HashMap<>();
        add(methods, "android.os.Trace#beginAsyncSection", 29, 0);
        add(methods, "android.os.Trace#endAsyncSection", 29, 0);
        add(methods, "android.os.Trace#setCounter", 29, 0);
        add(methods, "android.provider.Settings#canDrawOverlays", 23, 0);
        add(methods, "android.os.Build#getSerial", 26, 0);
        STATIC_METHODS=Collections.unmodifiableMap(methods);
    }
    private static void add(Map<String, Entry> target, String name, int since, int deprecated) {
        target.put(name, new Entry(since, deprecated));
    }
    static Entry type(AnalysisConfig config, String name) {
        Entry supplied=config.androidTypes().get(name);
        return supplied != null ? supplied : CLASSES.get(name);
    }
    static Entry method(AnalysisConfig config, String owner, String method) {
        String key=owner + "#" + method;
        Entry supplied=config.androidStaticMethods().get(key);
        return supplied != null ? supplied : STATIC_METHODS.get(key);
    }
}
