# 送信鸦 (ai-web-chat-android)

WebView 套壳 Android 应用，承载 AI WebChat 远程页面。

## 功能

- WebView 容器加载远程 WebChat 页面
- JS Bridge：系统通知、启动器角标、通知权限管理
- 文件选择（单选/多选）
- 启动闪屏 + 加载进度条
- SPA 返回键拦截（关闭浮层而非退出）
- 后台 WebSocket 保活

## 构建

本项目无 gradlew wrapper，使用本地 Gradle 8.7：

```bash
# Debug APK
"D:/Java/gradle-8.7-bin/gradle-8.7/bin/gradle.bat" assembleDebug

# Release APK（需配置 keystore.properties）
"D:/Java/gradle-8.7-bin/gradle-8.7/bin/gradle.bat" assembleRelease
```

## 项目结构

```
app/src/main/
├── java/com/aiwebchat/webchat/MainActivity.java   # 唯一 Activity
├── res/
│   ├── drawable*/         # 图标资源（logo、通知图标）
│   ├── mipmap-*/          # 各密度启动器图标
│   ├── layout/splash.xml  # 启动闪屏
│   └── values/            # 字符串、颜色、主题
└── AndroidManifest.xml
```

后端项目：`D:\myproject\ai-web-chat`（Spring Boot + 单文件前端）
