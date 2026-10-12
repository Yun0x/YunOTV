# 默认配置本地化验收

日期：2026-10-08。项目版本 0.3.0 / versionCode 3；minSdk 26，compileSdk / targetSdk 37。使用 Android Studio Embedded JDK，保留原 Release 签名和系统 Java 8。

## 构建与同步

- `syncYunoSources` 已执行成功。工具下载到 staging，Kotlin 编译器复用现有 ConfigResolver / Parser / Unified Model / ConfigMerger / SourceFingerprint / CapabilityDetector。源码中的根 Manifest 不再打包成启动入口。
- `test`、`lint`、`assembleDebug`、`assembleRelease` PASS；最终整套构建使用 Gradle `--offline`。
- 普通 assemble 不依赖 download/sync 任务。仅在本地验证 Bundle 与 index 的 SHA-256 一致，缺少 Assets 时明确要求开发者主动同步。
- Python 安全测试 5/5 PASS：相对路径、原子目录交换、验证失败保留旧版本、根配置下载失败保留旧版本、私有地址拒绝。
- 本地资源单元测试覆盖 HTTP 完全禁止时优先读取 Assets、损坏提取缓存修复、清缓存后重新读 Assets、路径穿越拒绝、压缩清单与解压后哈希一致。
- 签名 APK：`app/build/outputs/apk/release/app-release.apk`，57,213,966 bytes（54.56 MiB）。新增体积来自用户要求的本地插件和清单，不新增原生 JS 引擎依赖。

## 当前上游同步统计

| 指标 | 数量 |
|---|---:|
| Root Config | 4 |
| Downloaded Config / Playlist documents | 253 |
| Failed Config / Playlist documents | 314 |
| Parsed Sites | 1,897 |
| Deduplicated Sites | 1,744 |
| CMS Sites | 92 |
| DRPY Sites | 177 |
| JS URLs（含静态 helper） | 220 |
| JS Downloaded | 176 |
| Unique JS | 157 |
| JS Failed | 44 |
| Spider Sites | 1,419 |
| Jar URLs | 70 |
| Jar Downloaded / Unique Jar | 30 / 30 |
| Jar Failed | 40 |
| Playlist URLs | 555 |
| Playlist Downloaded | 231 |
| Unique packaged playlists | 200 |
| Playlist Failed | 324 |
| Assets Size | 58,411,040 bytes（55.71 MiB） |

统计对应本次上游内容；与之前 Phase 2/3 快照不同，不能据数量差直接认定运行时回归。下载成功仅表示资源通过格式/大小/哈希验证，不表示 Home/Search/Detail/Play 全部可用。

失败中包含 HTTP 404/403/超时/无效编码/本机地址/校验失败。根配置失败或验证失败会中止发布并保留现有 Assets；可选子配置、JS/JAR/清单失败会记录诊断，不生成虚假的本地映射。已有资源更新失败可保留同 URL 且满足期望 MD5/SHA-256 的上一版。

## GitHub 阻断特殊验收

使用独立 QA 代理阻断 `raw.githubusercontent.com`、`github.com` 及其子域的 CONNECT/HTTP 请求，其余网络请求正常转发。代理不解密 TLS、不替换证书，只记录请求计数；验收中实际收到 7 次 GitHub 请求并全部拒绝。每次先清除专用模拟器上的应用数据，再安装真实签名、R8 优化的 Release APK。

| 验收 | Android TV API 36 ARM64 / 2 GiB | Android API 26 ARM64 / 2 GiB |
|---|---|---|
| GitHub Blocked Boot | PASS | PASS |
| Local Bundle | PASS | PASS |
| Local JS | PASS | PASS |
| Local Jar | PASS | PASS |
| Packaged playlist hash/read | PASS | PASS |
| D-Pad → 设置 → 源管理 → 显示本地源 | PASS | PASS |

Release QA 使用 SDK 类型直接验证 Assets、数据库中 `COMPILED` 本地源记录及隔离进程 Binder，不依赖 Debug 类名或未压缩代码。真实本地 JS 引擎使用原创规则初始化；真实第三方 JAR 完成类解析/加载。该测试不播放任何第三方影视。

额外的 Android TV Provider 测试：通过默认本地 Bundle 进入现有 CMS Provider；对 CompatibilityRuntime 注入禁止全部远程请求的 OkHttp，进入 DRPY / Spider Provider，真实本地 JS 初始化与 JAR 类加载 PASS；本地直播清单解析 PASS。此测试是 Debug 下对生产资源解析入口的直接验证，Release 下的上述表格另有独立测试。

## 启动、缓存及直播

默认启动：APK Unified Bundle → 私有缓存/Room 索引 → 首页。默认 Bundle 不参加远程自动刷新；用户自行添加的远程源保持原有缓存与刷新行为。升级时将旧 `default-bundle` Manifest 记录替换为编译 Bundle，保留禁用和优先级；已删除的默认源不会被重新创建。

RemoteResourceResolver 先按原始远程 URL 查询 resource-index，再验证并提取 Assets。JS、JAR 以 SHA-256 命名；JAR 继续验证 ZIP、DEX 签名、校验和和资源上限。索引不存在的资源才允许远程请求。提取后的 JS/JAR/清单缓存分别限 32/96/16 MiB；清缓存不会删除 APK 中的资源。

完整直播清单压缩在 `live/{sha256}.playlist`，索引记录解压后 SHA-256。避免 `.gz` 扩展名，因为 AAPT 会自动解压并改名，破坏资源索引。直播目录按选择读取，每个活动清单最多显示 5,000 个频道；首页不展开 22 万个频道。相对媒体/Logo 地址仍按原始清单 URL 解析。

## 限制

OFFLINE CONFIG BOOT 已通过；这不提供视频离线播放，也不承诺所有真实插件可用。44 个 JS URL、40 个 JAR URL 未能下载，相关来源仍可能不可用；未收录资源的远程 fallback 在 GitHub 不可达时会友好失败。部分脚本可能还依赖动态下载的非静态配置或当前不支持的 helper；第三方 CMS/API、海报、视频与 EPG 仍需网络。原定 Phase 3 全部真实来源四步兼容及日常播放验收尚未达成。

## 复现

```sh
./gradlew syncYunoSources
./gradlew test lint assembleDebug assembleRelease --offline
python3 -m unittest discover -s tools/source-compiler/tests -v
python3 scripts/github-blocking-proxy.py --port 18888
# 以下仅用于专用 Android Emulator；会清除测试应用数据。
adb -s emulator-5580 shell settings put global http_proxy 10.0.2.2:18888
adb -s emulator-5580 install -r app/build/outputs/apk/release/app-release.apk
adb -s emulator-5580 shell pm clear com.yuno.tv
./gradlew :qa-release:assembleRelease
adb -s emulator-5580 install -r qa-release/build/outputs/apk/release/qa-release-release.apk
adb -s emulator-5580 shell am instrument -w -e class com.yuno.tv.OfflineReleaseBootTest com.yuno.tv.releaseqa/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5580 shell settings put global http_proxy :0
```

构建与设备日志在忽略的 `build/` 中。所有内置第三方 URL 均由根 Manifest 及其下载内容派生，未复制进生产 Kotlin 源码。
