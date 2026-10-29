package com.shima.analyzer.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small known-type dictionary plus host/project declarations, not a classpath resolver. */
final class SymbolTable {
    private static final String[] BUILT_INS = {
        "java.util.List", "java.util.ArrayList", "java.util.LinkedList", "java.util.Map", "java.util.HashMap",
        "java.util.Set", "java.util.HashSet", "java.util.Collections", "java.util.Collection", "java.util.Iterator",
        "java.util.Objects", "java.util.Optional", "java.util.Date", "java.util.UUID", "java.util.Arrays",
        "java.io.File", "java.io.IOException", "java.io.InputStream", "java.io.OutputStream",
        "android.app.Activity", "android.app.NotificationChannel", "android.app.NotificationManager", "android.app.ProgressDialog",
        "android.app.Person", "android.app.role.RoleManager", "android.content.Context", "android.content.Intent",
        "android.content.pm.ShortcutInfo", "android.os.Bundle", "android.os.Build", "android.os.LocaleList",
        "android.os.Trace", "android.os.VibrationEffect", "android.os.Vibrator", "android.util.Log",
        "android.view.View", "android.view.WindowInsetsController", "android.widget.Toast",
        "android.provider.Settings", "android.hardware.biometrics.BiometricPrompt"
    };
    private final Map<String, Set<String>> byName = new HashMap<>();
    private final Map<String, List<String>> ordered = new HashMap<>();
    SymbolTable(List<String> additional) {
        for (String name : BUILT_INS) add(name);
        for (String name : additional) add(name);
    }
    void add(String qualified) {
        int dot = qualified.lastIndexOf('.');
        String simple = dot < 0 ? qualified : qualified.substring(dot + 1);
        Set<String> names = byName.get(simple);
        if (names == null) { names = new LinkedHashSet<>(); byName.put(simple, names); }
        if (names.add(qualified)) ordered.remove(simple);
    }
    List<String> candidates(String simple) {
        List<String> cached = ordered.get(simple);
        if (cached != null) return cached;
        Set<String> names = byName.get(simple);
        if (names == null) return Collections.emptyList();
        ArrayList<String> result = new ArrayList<>(names);
        Collections.sort(result);
        cached = Collections.unmodifiableList(result);
        ordered.put(simple, cached);
        return cached;
    }
}
