# 校园浏览器 · APP 端（Campus Browser APP）

Android 原生 Kotlin，基于系统 WebView 的轻量受管浏览器：服务端统一下发配置，平板端按白/黑名单管控访问，适用于校园信息门户、电子班牌、教室平板统一上网管控。

当前版本：**v0.7.0**（versionCode 8）

## 功能

- 首次启动必须填写服务器地址并成功拉取配置，否则无法进入
- 按地址前缀自动使用 `http://` 或 `https://`（已全局放行明文流量）
- 启动直达配置首页，WebView 层强制白/黑名单拦截，禁长按菜单、禁下载
- **规则匹配加固**：匹配只看域名/端口/路径、不区分 http/https；支持 `host:8080` 端口精确匹配；修复书签带尾斜杠不生效问题
- **启动权限自检与前置拍照**：启动先拉最新配置，服务端开启「要求启动拍照」时未授权相机权限无法进入；授权后 CameraX 前置静默拍一张上传，无前置摄像头则上报跳过
- **拍照稳定性加固**：全局拍照互斥（避免相机被并发占用）、超时 15 秒、拍照或上传失败自动缓存，下次启动补传
- **PIN 服务端校验**：管理密码不再本地比对，改为把 MD5 哈希发到服务端 `/api/v1/verify-pin` 校验；离线/网络失败统一提示「密码错误」
- **输错密码拍照告警**：管理员密码输错一次即静默拍一张前置照片上传服务端，后台设备列表标红提醒
- 顶部工具栏（优化图标间距）：后退 / 前进 / 地址栏（受管只读）/ **扫码** / 刷新 / 快捷书签 / **应用** / 回首页，带加载进度条
- **扫码**：主界面工具栏与设置页均可扫码，支持网址二维码与 `app://包名` 应用二维码
- **唤醒其他应用**：WebView 与扫码中的自定义 scheme 按服务端白名单拉起，工具栏「应用」按钮快速启动
- **截屏限制**：服务端开启后全局禁止截屏/录屏（FLAG_SECURE），最近任务缩略图遮蔽
- **隐藏入口远程开关**：服务端可远程彻底关闭 Logo 连点入口，关闭后点击静默无效、无提示，仅服务器可重开
- **UA 标识**：访问服务端统一使用 `CampusBrowser/版本 OkHttp/4.12`，WebView 访问互联网保持浏览器 UA；服务端可开启校验
- 定时轮询新配置，RSA 验签；网络失败或配置异常时继续使用本地缓存
- 隐藏管理员入口：工具栏毕业帽 Logo 3 秒内连点 5 次，输入密码后可修改服务器地址、开启临时无管控模式（重启自动恢复）；页面跳转带淡入淡出动画
- PIN 连续输错 5 次锁定 5 分钟
- 设备注册/心跳自动上报当前 IPv4 与 APP 版本

## 界面与图标

- Material Design 3：教育蓝青品牌色、胶囊按钮、圆角卡片、顶部进度条
- 首启页横屏左右分栏：左侧品牌渐变面板，右侧连接表单卡片
- 启动图标为自适应图标（含 Android 13+ 单色主题图标）：蓝青渐变底 + 白色毕业帽与地球

## 构建

用 Android Studio（Hedgehog 或更新）打开本目录，等待 Gradle 同步后运行到平板；命令行：

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

- minSdk 24（Android 7.0）/ targetSdk 34 / Kotlin 1.9 / Gradle 8.9 / AGP 8.5（需 JDK 17）

## 部署前必做：配置签名公钥

服务端初始化后，在 Web 后台复制签名公钥 PEM，粘贴到：

```
app/src/main/java/edu/campus/browser/SecurityConfig.kt
```

将 `SERVER_PUBLIC_KEY_PEM` 填为公钥内容再打包。留空时 APP 不验签（仅可用于调试）。
同文件的 `PIN_SALT` 必须与服务端 `Services/Security.cs` 中的盐值一致。

## 主要源码

```
app/src/main/java/edu/campus/browser/
├── App.kt                      # MMKV 初始化
├── SecurityConfig.kt           # 公钥、PIN 盐、锁定策略
├── crypto/Crypto.kt            # RSA 验签、PIN 哈希
├── capture/FrontCameraCapture.kt # CameraX 前置静默拍照
├── config/AppConfig.kt         # 配置模型（含 hiddenEntry/blockScreenshot/allowedApps/requireStartupPhoto）
├── config/ConfigRepository.kt  # 注册/拉取/验签/缓存/轮询/IP/照片上传/verifyPin/UA拦截器
├── net/UrlRuleMatcher.kt       # 白/黑名单匹配（域名/端口/路径，不区分协议）
├── scan/QrScanner.kt           # ZXing 扫码封装
├── applaunch/AppLauncher.kt    # 白名单应用拉起
├── ui/PermissionManager.kt     # 启动权限自检与批量申请
├── ui/setup/SetupActivity.kt   # 首启强制连接服务器
└── ui/main/
    ├── MainActivity.kt         # WebView 主界面、启动门控、轮询、隐藏入口、扫码/应用按钮
    ├── ManagedWebViewClient.kt # 跳转拦截与 scheme 处理
    └── AdminUnlockDialog.kt    # 密码服务端校验解锁（输错触发拍照上传）
```

## 开源依赖

| 库 | 用途 |
| --- | --- |
| Material Components for Android 1.12 | Material 3 主题与控件 |
| SwipeRefreshLayout 1.1.0 | 下拉刷新 |
| ZXing Android Embedded 4.3.0 | 二维码扫码 |
| CameraX 1.3.4 | 前置摄像头静默拍照 |
| OkHttp 4.12 | 网络请求 |
| MMKV 1.3.9 | 配置本地缓存（腾讯，基于 mmap） |
| AndroidX appcompat / constraintlayout / lifecycle | 基础组件 |

## 开源协议

GPL-3.0
