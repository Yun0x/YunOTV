# Yuno TV 第二阶段验证

2026-10-07，当前工程 `/Users/yuno/java/Android/YunoTV`，应用 `com.yuno.tv`，版本 `0.2.0` / versionCode `2`。沿用第一阶段的 17 个模块，没有重新创建项目。

## A. BUILD

| 检查 | 结果 |
|---|---|
| `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test` | PASS；35 项通过，1 项远程集成按设计跳过，0 个失败 |
| `./gradlew lint` | PASS；app 汇总报告 0 错误、53 警告 |
| `./gradlew assembleDebug` | PASS |
| `./gradlew assembleRelease` | PASS；R8 与资源压缩 |
| `:app:verifyApi26Dependencies` | PASS；76 个运行时 AAR 的最低 API 均不超过 26 |
| APK Manifest | minSdk **26**、targetSdk **37**、compileSdk **37** |
| TV Manifest | LEANBACK_LAUNCHER、正式 Banner、横屏；touchscreen 与 leanback 均非安装必需特性 |
| Release 签名 | APK Signature Scheme v2，签名校验通过 |
| Release 安装/启动 | PASS；首次冷启动 Activity TotalTime 701 ms，远程内容仍后台加载 |
| Java 隔离 | 项目 Embedded JDK 25.0.3 ARM64；系统默认仍是 Java 8u341 |

APK 完整路径：

- Debug：`/Users/yuno/java/Android/YunoTV/app/build/outputs/apk/debug/app-debug.apk`，18,203,139 bytes / **17.36 MiB**。
- Release：`/Users/yuno/java/Android/YunoTV/app/build/outputs/apk/release/app-release.apk`，3,815,650 bytes / **3.64 MiB**。

最终文件哈希与体积见 [phase2-apk.json](phase2-apk.json)。Release 使用本工程已有的本地个人安装证书，未修改其他工程的 Java、签名或系统 shell 配置。

Lint 警告主要是固定依赖版本的升级建议，以及保留完整原图的旧式图标形状/重复资源提示；另有可选 monochrome 图标、电视横屏与少量资源/辅助 Modifier 提示。没有为清除警告升级依赖或提高 minSdk。API 26 的实际电视设备验收仍需进行。

## B. UI

| 页面/资源 | 第二阶段实现 |
|---|---|
| Logo | 从根 `logo.png` 生成五档 Launcher、圆形备用图标、安全区 Adaptive Icon、五档 TV Banner、内部 Logo 和启动资源；根原图保持不变 |
| Design System | core-ui 统一 YunoTheme、Colors、Typography、Shapes、Spacing、Motion、Elevation、FocusStyle、Dimensions；冷色深背景与克制的品牌紫色 |
| 首页 | Hero 背景/简介/播放/详情，继续观看、最近观看、最近更新及数据源动态分类；栏目按可见位置分页 |
| 搜索 | 左侧虚拟键盘与系统输入法，400 ms 防抖、逐源返回、聚合多来源与搜索历史 |
| 详情 | 海报/背景、元数据、简介、收藏/播放/从头播放、来源切换、线路和 Lazy 剧集列表；从播放器返回保留详情状态 |
| 播放器 | 电视控制栏、进度、播放暂停、上一/下一集、剧集、线路、倍速、比例、字幕和音轨；闲置自动隐藏；友好错误/重试/备用线路 |
| 历史 | 海报与续播信息，删除/清空与打开播放；进度每 7 秒及退出时保存 |
| 收藏 | Room 持久化添加/取消/列表/详情，记住列表位置 |
| 设置 | 播放、显示、缓存、历史、关于、配置管理及诊断日志导出；默认 Bundle 统计与能力诊断 |
| 直播 | 左侧频道分组、中间频道列表、右侧预览/全屏，频道收藏与最近观看 |

本地原创媒体的回归截图位于 [phase2-screenshots](phase2-screenshots)。测试目录未打包为应用内容；无配置的用户仍可自行添加 URL 或批量导入文件。

## C. TV REMOTE

统一焦点控件使用明确的 focusable、FocusRequester、stable key 与焦点记忆；卡片放大 1.06 倍，常规按钮约 1.04 倍，动画 160 ms。首页/搜索/历史/收藏保留列表和卡片焦点，详情保留选集/动作焦点，直播保留频道位置。

设备回归通过 Android D-Pad 按键选中首页第二张卡片、进入详情、Back 返回并断言焦点仍在原卡片；导航也通过方向键与确认键验证。播放器验证播放暂停专用键、左右 Seek、上下信息/剧集入口，以及先关闭控制栏再退出的 Back 行为。故意 404 的播放错误会聚焦重试，切换备用线路后恢复播放。修复了错误面板消失后 Dialog 原焦点目标被移除的问题，关闭菜单后明确回到当前控制栏或播放器。

部分测试使用点击来设置虚拟键盘和选择测试项；不能据此宣称所有设备厂商的遥控器均已验收。本次没有 Android TV 系统镜像或实体遥控器，设备为 API 37 ARM64 普通 Android Emulator，横屏 1920×1080 / 240 dpi。

## D. PERFORMANCE

- 图片：Coil 内存缓存为堆上限的 12%、磁盘 48 MiB；海报 264×396，背景最大 1280×720；remember 请求与失败占位。
- Compose：LazyRow/LazyColumn、稳定 key、生命周期感知 StateFlow；移除大影片 JSON SavedState，详情 ViewModel 在播放器期间保留。
- 网络：首页只访问最多三个排序后的可用来源；可见分类两路并发，最多十二行、每行 120 项。搜索四路并发、单源 12 秒、整体 30 秒，最多 128 个来源、3000 项结果。
- 配置：单文档 4 MiB、遍历最多 32 节点/5000 站点、JSON 深度 64；默认递归深度 4、最大 8，检测循环并复用同 URL 文档；直播清单只展开有界批次。
- 缓存：正文迁到私有文件，Room 仅保存索引和元数据；HTTP 条件请求 304、失败保留旧数据；首页/详情最多 80 个文件、每个最多 1 MiB。缓存 JSON 与历史详情解码、CMS 领域对象转换在 Default，文件读写在 IO，内容缓存写入串行化。
- 启动：本地设置/数据库/缓存就绪后显示 UI，远程配置后台刷新；不等待几百个源探测。默认 Bundle 的删除与禁用操作会保留。
- 并发修复：刷新仅更新状态和缓存时间等元数据，不再用刷新开始时的整条配置覆盖用户刚修改的启用、名称和优先级。
- Player：离开直播预览释放实例/监听器，进入全屏后避免同时占用两个解码器；退出全屏保存进度并释放。长期媒体请求与配置总超时分离。

API 37 / SwiftShader 的一轮真实 CMS 首页测量为 PSS 159,102 KiB（约 155 MiB），428 帧中 23 帧被系统标记为 jank（5.37%），中位 17 ms。该记录是模拟器样本，不能作为 2 GB 盒子、API 26 电视或长时间播放的性能结论；最终的缓存优化和解码器释放改动未进行同条件性能对比。

## E. SOURCE BUNDLE

读取的是根 `sources.txt.json`；Gradle 构建时生成 APK asset 副本，Kotlin/UI 没有硬编码其中的第三方列表。根 Bundle 的四个 enabled 配置进行了真实下载与递归解析，普通单元测试不依赖第三方服务。

以下是 2026-10-07 19:12（Asia/Shanghai）的应用真实集成快照，**包含已保存的配置缓存**，不是服务器永久状态：

| 统计项 | 数量 |
|---|---:|
| Bundle Source Count | 4 |
| Enabled Config Count | 4 |
| Downloaded | 7 |
| Download Failed | 5 |
| Resolved Config | 15 |
| Parsed Sites | 2015 |
| Deduplicated（去重后保留） | 1852 |
| Supported | 100 |
| Partial | 1 |
| Unsupported | 1751 |
| Broken | 0 |

Downloaded / Failed 包含递归子配置；Resolved Config 统计成功识别的唯一文档 URL，包含根 Bundle 与展开的清单，因此不等于 Downloaded。失败的外部子配置包含编码、下载与格式问题，已隔离并显示诊断，不影响其他配置。

应用快照见 [remote-cms-integration.json](remote-cms-integration.json)。另一次无缓存的主机远程测试见 [default-bundle-integration.json](default-bundle-integration.json)，去重后 1851 项 / 100 supported / 0 partial；两次相差一个直播引用，体现上游和缓存的差异。SUPPORTED 仅代表适配器具备能力，不表示所有服务器在线或内容均有播放权限。

根文件校验保持：

```text
logo.png         665915f30fc47452f33abef088ce54b8b5bfea70a1d0394b411bdee03eb9d5e9
sources.txt.json 199de5aaa48e3f2e9ca380893ade3babb01eb4093d88aa6f1086d3254d76469c
```

## F. CAPABILITY

| 类型 | 应用快照数量 |
|---|---:|
| CMS_API | 100 |
| DIRECT_MEDIA | 0 |
| LIVE_M3U | 1 |
| TVBOX_TYPE0 | 29 |
| TVBOX_TYPE1 | 0 |
| TVBOX_TYPE3 | 2 |
| DRPY_JS | 176 |
| SPIDER_JAR | 1517 |
| LOCAL | 0 |
| UNKNOWN | 27 |

可识别的 type=1 JSON CMS 归入 CMS_API，所以 TVBOX_TYPE1 为 0。LIVE_M3U 的引用站点标记 PARTIAL；可展开的频道仍由统一直播模型提供。复杂外部执行类型明确 UNSUPPORTED。

## G. REAL FUNCTION TEST

真实远程 CMS 元数据检查通过：从 `10-如意` 得到 20 条首页数据、1 条搜索匹配、20 个剧集，详情与 HTTP(S) 播放 URL 解析成功。未播放第三方影视；这些数据会随上游变化。

独立的本地合法媒体回归实际执行：JSON/TXT（远程/本地）合并、来源优先级与去重、禁用/启用、304、失败旧缓存、CMS 搜索/详情/选集、Media3 播放原创 H.264 MP4、暂停/恢复、退出保存、续播、Room 收藏、历史、自动下一集倒计时取消与切换、IPTV 预览/全屏/收藏、Back 卡片焦点恢复、404 备用线路恢复。

数据库迁移测试创建独立 v1 测试数据库，迁移后历史/收藏/etag/正文内容保留、正文迁出 Room；额外断言刷新完成不会覆盖禁用状态、编辑名称或优先级。用户正常数据库未被清空或 destructive migration。

最终端到端设备测试与数据库迁移 **2/2 PASS（60.847 s）**；远程 CMS 元数据为单独 opt-in 用例，**1/1 PASS**。原始结果见 [phase2-reports](phase2-reports)。

签名 Release 另行完成 UI 验证：实际输入测试源 URL，加载 CMS 首页/详情，Media3 播放原创 MP4、自动进入第二集、媒体键暂停，退出后历史显示第二集 76%，收藏列表保存成功，强制结束进程再启动后收藏仍存在。Release 截图位于同一截图目录。本次测试未播放第三方影视。

## H. UNSUPPORTED

当前不执行 Spider/JAR、DRPY/其他 JavaScript、type=3/未知外部插件、XML CMS、外部解析器、网页嗅探、DRM/付费/权限绕过。Provider 接口保留扩展位置；未知类型显示友好提示。

EPG 地址保留但未解析当前/下一节目。没有后台播放服务或应用退出后的定时刷新。直播清单递归展开有上限，并非无限抓取仓库。不是所有上游 JSON 历史变体均支持。

本机没有 Android TV 镜像/实体电视/API 26 设备；没有证明全部电视型号的遥控键映射、硬件解码器、2 GB 内存设备、弱网长时间播放表现。minSdk 26 已验证构建与最终 APK，而 API 26 实机运行仍待验收。

## I. FILES

主要新增：

- `core-model/.../SourceModels.kt`：能力、健康、诊断、播放解析模型。
- `core-config/.../BundleParser.kt`、`ConfigResolver.kt`、`CapabilityDetector.kt`：Bundle/格式/递归/相对 URL/能力。
- `core-network/.../PlaybackResolver.kt`、`SourceHealthChecker.kt`、`MediaContentCache.kt`：播放边界、健康排序、离线缓存。
- `core-ui/.../YunoDesignSystem.kt`、`SafeDiagnostics.kt`、`core/.../Digests.kt`：设计 Token、安全诊断与指纹性能。
- `feature-source/.../SourceDiagnosticsScreen.kt`：配置/站点能力与来源诊断。
- `SourcePipelineTest.kt`、`SourceBundleIntegrationTest.kt`、`RealSourceIntegrationTest.kt`、`DatabaseMigrationTest.kt`：单元/真实配置/远程 CMS/无损迁移。
- 正式品牌 PNG / Adaptive Icon 资源、Room v2 schema、本文、统计 JSON 与截图。

主要修改：ConfigRepository/Parsers/Merger、SettingsRepository、Room DAO/SourceStore/LibraryRepository、CMS/ProviderRegistry/SearchAggregator/Home UseCase、Media3 Factory/ViewModel、所有主要 TV 页面、YunoApp、Manifest/主题、版本/资产构建任务/ProGuard、品牌生成脚本、README 与架构文档。模块边界详见 [architecture.md](architecture.md)。

## J. GIT

最终 `git status` 见 [phase2-reports/git-status.txt](phase2-reports/git-status.txt)。当前 README 有未提交修改，Android 工程目录及本阶段新增文件仍未跟踪；未自动 add、commit、push 或 force push，保留用户原有工作区。

本次只使用专门启动的只读测试模拟器与本地原创媒体服务。验证完成后停止这两个测试进程，不影响用户已有的 AVD、Java 8、Android SDK 或其他项目。
