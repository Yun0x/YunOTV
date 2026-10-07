# 验证记录

2026-10-07，Yuno TV 0.1.0 / com.yuno.tv。

## 构建与兼容性

| 检查 | 结果 |
| --- | --- |
| `./gradlew assembleDebug` | 成功，生成 app-debug.apk |
| `./gradlew assembleRelease` | 成功，R8 压缩，生成已签名 app-release.apk |
| Debug / Release lint | 均为 0 个错误；30 个警告主要为依赖版本更新提示，另有电视横屏方向提示 |
| 领域单元测试 | 17 项全部通过：配置解析/指纹 11 项、并发搜索/编码 3 项、续播策略 3 项 |
| 依赖 AAR Manifest | 76 个运行时 AAR 均要求 API 26 或更低 |
| 最终 Release Manifest | minSdk 26、targetSdk 37、LEANBACK_LAUNCHER |
| APK 签名校验 | apksigner 校验成功，APK Signature Scheme v2，RSA 3072 |
| 系统默认 Java | 仍为 Oracle Java 8u341 |
| 项目 Gradle JVM | Launcher/Daemon 均为 Android Studio Embedded JDK 25.0.3 ARM64 |

## 设备端测试

在 API 37 ARM64 Android Emulator 上，以 1920×1080、240 dpi 显示运行。该镜像是普通 Android 镜像；遥控测试通过真实 Android 按键事件完成。

端到端仪器测试通过以下流程：

- 远程 JSON/TXT 与本地 JSON/TXT 导入的 Repository 流程、优先级覆盖和多来源指纹去重。
- 条件请求 304、禁用/启用、刷新失败后使用旧缓存。
- 标准 CMS 首页、分类、全局搜索、电视虚拟键盘、详情、线路与选集。
- Media3 实际播放原创合成 H.264 视频、播放暂停键、退出保存进度、Room 历史和续播计算。
- 收藏、历史、继续观看、IPTV 预览、频道收藏及全屏。
- 自动下一集倒计时取消、重播、5 秒后切换到第二集、保存当前集数。
- 使用方向键选择第二张卡片，进入详情后返回，焦点恢复到第二张卡片。
- 故意返回 HTTP 404 的媒体地址，显示友好错误并聚焦重试按钮；遥控选择备用线路后恢复实际播放。

签名 Release APK 另行安装验证：实际界面添加 URL、配置解析、CMS 首页/详情、触摸显示控制栏、触摸暂停、实际播放、退出后历史显示均通过。

测试视频由 `scripts/generate-test-video.swift` 原创合成，测试 CMS 仅绑定 127.0.0.1，未使用第三方影视内容。该模拟器的硬件 H.264 解码器发生系统错误时，应用的一次软件解码回退也已验证。

截图保存在 `docs/screenshots`。Gradle 的测试与 lint 原始报告保存在各模块 `build` 目录；重新执行检查时会更新这些报告。

## 未验证的设备范围

本次没有连接实体电视、盒子或 API 26 设备，没有进行 API 26 实机测试。API 26 兼容性已检查最终 APK Manifest、依赖最低 API、工程代码及 lint；真实设备解码能力、电视厂商遥控器差异、长期播放和弱网行为仍需设备验收。EPG 解析和复杂 Spider/JAR/JS 不属于第一版实现范围。

## APK SHA-256

```text
Debug   c51178a4018009292650451942bf9a2c0bd2d633db05f73eb250f6e0f2bfa518
Release d5fcb2abc84d60a275fea07c77fa5aba3e23286f7eccbdf8fe8b11948efbe897
```
