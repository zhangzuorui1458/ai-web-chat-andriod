package com.aiwebchat.webchat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * WebView 套壳入口：加载远程 WebChat 页面。
 * 所有业务在前端，App 仅负责容器与文件选择。
 */
public class MainActivity extends AppCompatActivity {

    /** 远程后端地址。IP 变化时改这里即可。 */
    private static final String TARGET_URL = "http://223.113.44.33:8080/";

    /** 通知渠道 ID（Android 8+ 必需） */
    private static final String CHANNEL_ID = "messages";
    /** 暴露给前端的 JS Bridge 名称：window.AndroidBridge */
    private static final String JS_BRIDGE_NAME = "AndroidBridge";
    /** 通知 ID（同 tag 的通知会被覆盖，避免堆叠） */
    private static final int NOTIFICATION_ID = 1001;
    /** 通知权限请求码 */
    private static final int REQ_POST_NOTIFICATIONS = 1001;

    private WebView webView;
    private ProgressBar progressBar;
    /** 启动闪屏（白底 + logo + "送信鸦"），WebView 首页加载完成后移除 */
    private View splashView;
    /** 状态栏占位条，颜色跟随前端主题动态变化 */
    private View statusBarSpacer;
    /** 当前未读消息数（用于设置启动器图标角标） */
    private int badgeCount = 0;

    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // API 35（Android 15）默认强制 edge-to-edge，setDecorFitsSystemWindows 在部分
        // 机型上无效，WebView 会延伸到状态栏下方遮挡顶部。这里用物理占位方案：
        // root 用垂直 LinearLayout，第一个子 View 为状态栏高度的占位条（颜色跟随主题），
        // 第二个子 View 才是真正的 FrameLayout 内容容器，整体内容随之下移。
        statusBarSpacer = new View(this);
        statusBarSpacer.setBackgroundColor(Color.parseColor("#1E1E2E"));
        statusBarSpacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, getStatusBarHeight()));

        FrameLayout content = new FrameLayout(this);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(statusBarSpacer);
        root.addView(content);

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        content.addView(webView);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(3));
        pbLp.gravity = Gravity.TOP;
        progressBar.setLayoutParams(pbLp);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
        content.addView(progressBar);

        // 启动闪屏：盖在 WebView 之上，等首个页面加载完成（onPageFinished）再移除。
        // 解决 Android 12+ 自适应图标深色 background 在 SplashScreen 上露出"黑框"的问题。
        splashView = getLayoutInflater().inflate(R.layout.splash, content, false);
        content.addView(splashView);

        setContentView(root);

        // Android 15 强制 edge-to-edge，windowSoftInputMode=adjustResize 不会自动压缩
        // 内容区域，键盘会盖住输入框。这里手动监听 IME insets，把键盘高度作为 root
        // 底部 padding，让 WebView 整体上移，从而保证输入框可见。
        applyIMEPadding(root);

        // 文件选择回调注册（必须在 WebView 调起选择器之前注册）
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (filePathCallback == null) return;
                    Uri[] results = null;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        if (data.getDataString() != null) {
                            results = new Uri[]{Uri.parse(data.getDataString())};
                        } else if (data.getClipData() != null) {
                            int count = data.getClipData().getItemCount();
                            results = new Uri[count];
                            for (int i = 0; i < count; i++) {
                                results[i] = data.getClipData().getItemAt(i).getUri();
                            }
                        }
                    }
                    filePathCallback.onReceiveValue(results);
                    filePathCallback = null;
                });

        configureWebView();
        configureBackPress();

        // 注册系统通知与图标角标能力（前端通过 window.AndroidBridge 调用）
        createNotificationChannel();
        webView.addJavascriptInterface(new WebAppInterface(), JS_BRIDGE_NAME);
        // Android 13+ 需要运行时申请通知权限
        ensureNotificationPermission();

        if (savedInstanceState == null) {
            webView.loadUrl(TARGET_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        // 前端用 localStorage 存 token / theme / recentEmojis，必须开
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // 缩放与视口
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        // 缓存
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Cookie
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);
        // Mixed Content 兼容（HTTP 页面里的 HTTPS CDN 资源）
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 站内链接一律在 WebView 内打开
                return false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                // 仅主框架报错时提示，避免子资源（如 CDN）失败就弹 toast
                if (request.isForMainFrame()) {
                    Toast.makeText(MainActivity.this,
                            "加载失败：" + error.getDescription(), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 首页加载完成，移除启动闪屏，露出真实页面
                hideSplash();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                // 上一次未消费的回调必须先取消，否则会回调一次 null 导致 <input> 卡死
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent intent = params.createIntent();
                // 允许多选（前端 input 可能有 multiple）
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    fileChooserLauncher.launch(intent);
                } catch (Exception e) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this,
                            "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                // 透传前端 alert，保证原逻辑可跑
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                result.confirm();
                return true;
            }
        });

        // 下载交给系统
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "无法处理下载链接", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void configureBackPress() {
        // 适配 Android 13+ 的返回键新 API
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView == null) return;
                // 1. WebView 有真实浏览历史（多页跳转）→ 后退
                if (webView.canGoBack()) {
                    webView.goBack();
                    return;
                }
                // 2. 前端是 SPA，URL 不变，canGoBack() 永远 false。
                //    调用前端协议 window.handleNativeBack()，让其关闭当前最上层浮层
                //    （详情面板/表情/搜索/modal/灯箱/移动端聊天抽屉）。
                //    返回 true 表示前端处理了，不退出；返回 false 才退出 app。
                //    注意：不要用 setEnabled(false) + onBackPressed() 来退出，否则
                //    callback 会在 Activity 生命周期内永久 disabled，从桌面返回 app
                //    后（Activity 未销毁）滑动返回手势将彻底失效。
                webView.evaluateJavascript(
                        "(window.handleNativeBack && window.handleNativeBack()) === true",
                        value -> {
                            if (!"true".equals(value)) {
                                finish();
                            }
                        });
            }
        });
    }

    /** 监听 IME（软键盘）insets，把键盘高度作为根容器底部 padding，
     *  使 WebView 内容随键盘弹出整体上移，避免输入框被遮挡。 */
    private void applyIMEPadding(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int imeHeight;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                imeHeight = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                // API 20-29：systemWindowInsetBottom 在键盘弹出时即为其高度
                imeHeight = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, 0, 0, imeHeight);
            return insets;
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 首页加载完成后调用，从内容容器移除启动闪屏。 */
    private void hideSplash() {
        if (splashView != null && splashView.getParent() instanceof ViewGroup) {
            ((ViewGroup) splashView.getParent()).removeView(splashView);
        }
        splashView = null;
    }

    /** 获取系统状态栏高度（像素）。取不到时兜底 24dp。 */
    private int getStatusBarHeight() {
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            return getResources().getDimensionPixelSize(resId);
        }
        return dp(24);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        // 注意：不调用 webView.onPause()。一旦调用，WebView 会暂停 JS 执行，
        // 前端的 WebSocket 连接和定时器全部停止，导致后台收不到消息。
        // 让 WebView 在后台继续运行，配合前端的 WebSocket 保活，
        // 才能在用户短暂切出应用时继续接收消息推送。
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    // ======================== 通知 & 角标 ========================

    /** 创建通知渠道（Android 8+ 必需）。 */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "消息通知", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("接收新消息时提醒");
            channel.enableLights(true);
            channel.enableVibration(true);
            channel.setShowBadge(true); // 允许启动器显示角标
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    /** Android 13+ 需要运行时申请 POST_NOTIFICATIONS 权限。 */
    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_POST_NOTIFICATIONS);
            }
        }
    }

    /**
     * 弹出一条系统通知。前端收到 WebSocket 消息且应用在后台时调用。
     * @param title   通知标题（如发送者昵称）
     * @param content 通知正文（如消息预览）
     * @param tag     通知 tag，相同 tag 会被新通知覆盖（一般传会话 ID）
     */
    private void showNotification(String title, String content, String tag) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return; // 无权限，静默丢弃
        }
        if (title == null) title = "";
        if (content == null) content = "";
        if (tag == null || tag.isEmpty()) tag = "default";

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, tag.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true) // 点击后自动消失
                .setContentIntent(pi)
                .setNumber(badgeCount > 0 ? badgeCount : 1); // 部分启动器据此显示角标

        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(tag.hashCode(), b.build());
        } catch (SecurityException ignore) {
            // 个别 ROM 上通知权限被冻结，忽略
        }
    }

    /** 更新启动器图标角标（支持原生 Android、Sony、小米等常见启动器）。 */
    private void setBadgeCount(int count) {
        badgeCount = Math.max(0, count);
        try {
            Intent i = new Intent("android.intent.action.BADGE_COUNT_UPDATE");
            i.putExtra("badge_count", badgeCount);
            i.putExtra("badge_count_package_name", getPackageName());
            i.putExtra("badge_count_class_name", MainActivity.class.getName());
            sendBroadcast(i);
        } catch (Exception ignore) {
        }
    }

    /**
     * 暴露给前端的 JS Bridge：window.AndroidBridge
     * 所有方法都会被 WebView 在 JavaBridge 线程调用，UI 操作已自行处理线程切换。
     */
    public class WebAppInterface {

        /** 弹出系统通知。tag 相同会覆盖（建议传会话 ID）。 */
        @JavascriptInterface
        public void showNotification(String title, String content, String tag) {
            MainActivity.this.showNotification(title, content, tag);
        }

        /** 设置启动器图标未读数角标。传 0 等于清除。 */
        @JavascriptInterface
        public void setBadge(int count) {
            MainActivity.this.setBadgeCount(count);
        }

        /** 清除图标角标。等价于 setBadge(0)。 */
        @JavascriptInterface
        public void clearBadge() {
            MainActivity.this.setBadgeCount(0);
        }

        /** 当前是否拥有通知权限（Android 13+ 有效）。 */
        @JavascriptInterface
        public boolean hasNotificationPermission() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
            return ContextCompat.checkSelfPermission(MainActivity.this,
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }

        /** 触发系统权限弹窗（仅 Android 13+，已授权则无操作）。 */
        @JavascriptInterface
        public void requestNotificationPermission() {
            runOnUiThread(MainActivity.this::ensureNotificationPermission);
        }

        /** 设置状态栏背景色（跟随前端主题）。
         *  @param color 十六进制颜色字符串，如 "#000000" 或 "#F5F5F7" */
        @JavascriptInterface
        public void setStatusBarColor(String color) {
            runOnUiThread(() -> MainActivity.this.applyStatusBarColor(color));
        }
    }

    /** 根据前端传入的颜色值同时更新状态栏颜色和占位条颜色，
     *  使状态栏区域与前端主题完全一致。
     *  同时根据背景亮度自动切换状态栏文字颜色（浅色背景→深色文字，深色背景→浅色文字）。 */
    private void applyStatusBarColor(String colorStr) {
        if (colorStr == null || colorStr.isEmpty()) return;
        try {
            int color = Color.parseColor(colorStr);
            // 设置系统状态栏颜色
            getWindow().setStatusBarColor(color);
            // 同步更新占位条背景色
            if (statusBarSpacer != null) {
                statusBarSpacer.setBackgroundColor(color);
            }
            // 根据背景亮度自动切换状态栏文字颜色：
            // 浅色背景（如白天模式 #F5F5F7）→ 深色文字，深色背景（如黑夜模式 #000000）→ 浅色文字
            boolean isLightBg = isLightColor(color);
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (isLightBg) {
                // API 23+：设置状态栏文字为深色
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                // 移除深色文字标志，恢复默认浅色文字
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        } catch (IllegalArgumentException ignore) {
        }
    }

    /** 判断颜色是否为浅色（用于决定状态栏文字深浅）。
     *  使用 W3C 标准亮度公式：L = 0.299*R + 0.587*G + 0.114*B，>160 视为浅色。 */
    private boolean isLightColor(int color) {
        double luminance = 0.299 * Color.red(color)
                         + 0.587 * Color.green(color)
                         + 0.114 * Color.blue(color);
        return luminance > 160;
    }

    @Override
    protected void onDestroy() {
        // 按 removeView → stopLoading → removeAllViews → destroy 顺序释放，防泄漏
        if (webView != null) {
            ((ViewGroup) webView.getParent()).removeView(webView);
            webView.stopLoading();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
