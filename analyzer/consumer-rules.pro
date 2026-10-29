# JNI uses name-based registration. Keep this class/method names when R8 runs in a host app.
-keep class com.shima.analyzer.core.NativeBridge { native <methods>; }
