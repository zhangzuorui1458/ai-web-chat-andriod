# =============================================================
# ProGuard / R8 规则 — 送信鸦 WebView 套壳应用
# =============================================================

# ---- 通用 Android ----
# 保留 View 相关方法（XML 引用的 onClick 等）
-keep class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# ---- WebView / JS Bridge ----
# 保留 JS Bridge 内部类，防止 @JavascriptInterface 注解的方法被移除
-keepclassmembers @android.webkit.JavascriptInterface class * {
    public *;
}
-keep @android.webkit.JavascriptInterface class * { *; }

# 保留 WebAppInterface 类（被 WebView.addJavascriptInterface 反射使用）
-keep class com.aiwebchat.webchat.MainActivity$WebAppInterface { *; }

# ---- R 文件 ----
# 资源 ID 类保留字段名（部分库需要）
-keepclassmembers class **.R$* {
    public static <fields>;
}

# ---- 调试日志移除 ----
-assumenosideeffects class android.util.Log {
    public static int v(*);
    public static int d(*);
    public static int i(*);
}
