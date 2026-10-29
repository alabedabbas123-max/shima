package com.shima.analyzer.core;

/** JNI is deliberately hidden behind the platform-independent Java API. */
final class NativeBridge {
    static { System.loadLibrary("shima_analyzer"); }
    private NativeBridge() { }
    static native long create(int language);
    static native void setRevision(long handle, int revision);
    static native void destroy(long handle);
    static native int[] parse(long handle, String text, int revision, int timeoutMillis, int maxEvents,
            int start, int oldEnd, int newEnd, int startRow, int startColumn,
            int oldEndRow, int oldEndColumn, int newEndRow, int newEndColumn);
}
