# 项目约定

## 项目关系

本项目 `ai-web-chat-android` 是 **WebView 套壳应用**，承载的远程页面来自后端项目：

- **后端项目根目录：** `D:\myproject\ai-web-chat`
- **前端单文件入口：** `D:\myproject\ai-web-chat\src\main\resources\static\index.html`
  - 前端无构建工具、无框架，原生 JS 单文件（132 KB+）
  - 修改前端后，**重启后端 Spring Boot 服务即生效**，无需重新打包 APK
- **APK 加载的远程地址：** `http://223.113.44.33:8080/`（写死在 `MainActivity.java` 的 `TARGET_URL`）

前后端联动的事项：
- 涉及 `window.AndroidBridge` 注入接口、`document.hidden` 判定、WebSocket 保活等，需要同时调整 `MainActivity.java` 与 `index.html`

## Gradle 构建

本项目没有 gradlew wrapper 脚本，统一使用本地 Gradle 8.7：

- **Gradle 可执行路径：** `D:\Java\gradle-8.7-bin\gradle-8.7\bin\gradle.bat`
- 以后所有构建/打包命令都直接调用这个路径，不要再尝试 `./gradlew` 或 `gradlew.bat`。

### 常用命令

```bash
# Debug APK
"D:/Java/gradle-8.7-bin/gradle-8.7/bin/gradle.bat" assembleDebug

# Release APK（需要配置 keystore.properties）
"D:/Java/gradle-8.7-bin/gradle-8.7/bin/gradle.bat" assembleRelease

# 清理
"D:/Java/gradle-8.7-bin/gradle-8.7/bin/gradle.bat" clean
```

执行时工作目录设为项目根：`D:\myproject\ai-web-chat-android`。

## 前端与原生的桥接

前端通过 `window.AndroidBridge` 调用原生能力（在 `MainActivity.java` 的 `WebAppInterface` 内部类中实现）：

| JS 方法 | 用途 | 触发位置 |
|---------|------|---------|
| `AndroidBridge.showNotification(title, body, tag)` | 弹系统通知 | `index.html` 的 `showNotification(msg)` |
| `AndroidBridge.setBadge(count)` | 更新启动器图标角标（0=清除） | `index.html` 的 `updateBadges()` |
| `AndroidBridge.clearBadge()` | 清除角标（等价于 `setBadge(0)`） | 按需调用 |
| `AndroidBridge.hasNotificationPermission()` | 检查通知权限（Android 13+） | 按需调用 |
| `AndroidBridge.requestNotificationPermission()` | 触发权限请求弹窗 | 按需调用 |
