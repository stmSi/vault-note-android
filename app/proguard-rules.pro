# Debug-only logging calls are removed after BuildConfig.DEBUG is folded to false.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Preserve useful crash line information without retaining source file names.
-keepattributes LineNumberTable
-renamesourcefileattribute SourceFile
