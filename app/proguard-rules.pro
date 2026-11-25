# OKWW Mobile ProGuard / R8 规则
#
# 当前 release 未开启 minify（isMinifyEnabled = false），此文件用于后续开启混淆时保留必要类。
# 若开启 minify，请保留 ML Kit OCR 相关类（模型与反射调用）。

-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# ViewBinding 由 AGP 生成，无需额外规则
