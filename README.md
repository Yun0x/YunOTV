# Yuno TV

Android TV / Kotlin / Compose for TV 的个人播放器与媒体源管理工程。包名 `com.yuno.tv`，版本 `0.2.0`。APK 最低支持 Android 8.0 / API 26。

## macOS 构建

直接用 Android Studio 打开当前目录并同步。项目已配置 Gradle JVM 为 Android Studio Embedded JDK，命令行 Wrapper 使用同一个 JDK。系统 Java 8、shell 配置和其他 Java 工程没有修改。

```sh
./gradlew assembleDebug
./gradlew assembleRelease
```

输出：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

本机工具链：AGP 9.1.1 / Gradle 9.3.1 / Android Studio Embedded JDK 25 / compileSdk、targetSdk 37。所有模块 `minSdk = 26`。兼容依据和检查记录见 `docs/environment.md`、`docs/dependency-min-sdk.json`。

Release 使用本项目 `.signing/local-release.p12` 的个人安装证书。首次在新目录构建 Release 前运行 `./scripts/create-local-signing.sh`。请备份自己的证书；Debug 与 Release 签名不同，互相覆盖安装时需要先卸载旧签名版本，卸载会删除应用数据。

正式分发签名可通过环境变量 `YUNO_KEYSTORE`、`YUNO_STORE_PASSWORD`、`YUNO_KEY_ALIAS`、`YUNO_KEY_PASSWORD` 指定，工程不会保存这些凭据。

安装到已连接电视或盒子：

```sh
"$HOME/Library/Android/sdk/platform-tools/adb" install -r app/build/outputs/apk/release/app-release.apk
```

## 使用

首次启动自动导入根目录的默认 Source Bundle。也可进入“设置 → 配置源管理”自行添加远程 JSON/TXT/M3U URL，以及通过系统文件选择器批量导入文件；无配置时显示添加引导。部分电视未预装文件选择器时，需要先安装兼容的文件管理器。

外部 TVBox JSON 仅在解析边界使用。`sites` 中 `type = 1`、HTTP(S) JSON CMS API 可用；`type = 0` 的 XML CMS、Spider/JAR/JS、解析器执行和 WebView 嗅探不在第一版范围。不支持类型会显示友好提示，不执行外部代码。

TXT 的每行支持 `名称,URL`、`名称|URL`、单独 URL、UTF-8/BOM、CRLF/LF、`#`/`//` 注释和未标记的说明文字。HTTP(S) CMS 地址成为影视源；`.mp4/.m3u8/.ts` 地址成为直播/直链频道。对没有扩展名的 IPTV 地址，请选择 `IPTV` 格式。分组 TXT 支持 `分类,#genre#`，M3U 支持 EXTINF 名称、group-title、tvg-logo 和 EPG 地址。

多个源按 API/type/ext 的 SHA-256 指纹去重，优先级数值较大的源覆盖，并保留所有输入来源。首页栏目跟随 CMS 返回的分类和内容，不内置影视目录。全局搜索同时查询启用源，独立超时，并将相同名称、年份、类型、地区的结果聚合，保留可选的来源站点。

远程配置先读私有文件缓存与 Room 索引，后台条件刷新；下载或解析失败继续使用旧缓存。自动刷新在应用启动以及进程运行期间每 6 小时执行。没有注册常驻服务或应用退出后的后台调度。

Android 17 访问家庭局域网地址需要在配置源管理中允许局域网权限；较早系统保持原有网络行为。公网 URL 不需要此额外权限。

## 遥控器

- 方向键：导航、选卡片和选集；全屏隐藏控制栏时左右快进/快退，默认 10 秒。
- 确认键：选择；播放器背景上显示/隐藏控制栏，控制栏获得焦点时选择当前控件。
- 上键：打开播放信息与控制栏；下键：打开剧集。
- 播放/暂停、快进/快退专用按键：直接控制播放。
- 返回：先关闭菜单或控制栏，再退出播放器/详情，保留观看进度。

历史每 7 秒及离开播放器时保存。完成度达到 95% 的剧集在续播时选择下一集；自动下一集提供 5 秒倒计时、立即播放和取消。最后一集不自动跳转。媒体格式和解码能力仍取决于设备与 Media3 支持情况。

## 验证与维护

```sh
./gradlew :core-config:testDebugUnitTest :core-network:testDebugUnitTest :core-database:testDebugUnitTest
./gradlew :app:verifyApi26Dependencies :app:lintDebug
```

设备端端到端测试使用原创合成视频和本地模拟 CMS，不包含第三方影视：

```sh
swift scripts/generate-test-video.swift /tmp/yuno-test.mp4
python3 scripts/test-fixture-server.py --video /tmp/yuno-test.mp4
# 另一个终端，连接 Android Emulator（10.0.2.2 指向宿主机）后执行：
./gradlew :app:connectedDebugAndroidTest
```

该测试仅应在专门的测试模拟器运行，会添加 `QA` 配置与测试观看数据。自建测试服务仅绑定宿主机 127.0.0.1。

模块边界见 `docs/architecture.md`。Room v1 / v2 schema 已导出到 `core-database/schemas`，后续修改表结构必须增加迁移，禁止直接破坏用户数据库。诊断日志只记录事件和异常类别，不记录 URL、输入关键词、Token、Cookie 或响应正文。

第一阶段构建、签名、测试和设备范围的记录见 [第一阶段验证](docs/verification.md)，当前版本见下方第二阶段验证。截图中的测试目录和原创视频只用于验证，不会内置到 APK。

## 第一版扩展边界

可选 EPG 地址已保留，但未解析节目单；复杂 Spider/JAR/JS 插件只保留 `SourceProvider` 扩展边界。当前版本仅播放用户有权访问的 HTTP(S) 媒体，不内置内容、不绕过 DRM、不破解付费平台。


## 第二阶段

正式品牌来自根目录 `logo.png`，原图保持不变；默认配置只来自根目录 `sources.txt.json`，Gradle 自动生成 APK 内资源副本。首次导入后，删除或禁用操作会保留。

新增递归 Bundle / MultiRepo、Generic JSON、相对 URL、逐站点能力诊断、健康记录、文件缓存迁移和离线首页/详情。电视界面使用统一颜色、圆角、尺寸与 160 ms 焦点动画，新增 Hero 播放入口、按可见栏目分页、400 ms 搜索防抖和逐源结果更新。

```sh
./gradlew test
./gradlew lint
./gradlew assembleDebug
./gradlew assembleRelease
./scripts/verify-remote-sources.sh
```

远程统计、功能验证、设备范围、APK 路径及限制见 [第二阶段验证](docs/phase2-verification.md)。搜索、配置数量和直播清单展开均设置上限，以控制弱电视的内存与网络开销。JAR/Spider/JS、XML CMS 和 EPG 节目单仍不执行。
