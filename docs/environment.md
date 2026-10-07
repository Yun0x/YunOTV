# 本机环境与隔离

检查时间：2026-10-07，macOS 15.7.4 / Apple Silicon arm64。

- 系统默认 Java：Oracle Java 8u341，x86_64。没有卸载、修改或替换。
- 项目 JDK：Android Studio 自带 JetBrains Runtime 25.0.3，ARM64。
- Android SDK：`$HOME/Library/Android/sdk`，稳定平台 API 37.0、Build Tools 36.0.0、adb 37.0.1。
- Gradle 9.3.1 / AGP 9.1.1，Kotlin 使用 AGP 内置编译器。
- 所有模块 `minSdk = 26`；compileSdk/targetSdk = 37。JVM 字节码目标为 17，经 D8 转换为 Android DEX。

`gradlew` 在自己的子进程中指定 Embedded JDK；`gradle.properties` 固定 Daemon JDK。Android Studio 的项目 Gradle JVM 使用 `#GRADLE_LOCAL_JAVA_HOME`，对应项目 `.gradle/config.properties`。未修改 `.zshrc`、`.zprofile`、系统 JDK 注册或其他工程。

官方兼容依据：

- https://developer.android.com/build/releases/agp-9-1-0-release-notes
- https://docs.gradle.org/current/userguide/compatibility.html

依赖最低 API 的实际 AAR Manifest 检查记录见 `dependency-min-sdk.json`。非 AAR 库最低 API 依据：Retrofit 2.11 / OkHttp 4.12 支持 API 21，Gson 2.11 支持 API 21，Coroutines 支持 Android 早期版本；最终还需由 Manifest 合并与 lint 检查传递依赖和 API 调用。

本地 Release 签名由 `.signing/local-release.p12` 保存，只供当前个人工程安装。此目录不提交。需要分发时应设置独立安全的签名配置，并备份实际使用的证书；丢失签名后无法覆盖升级已经安装的相同包名。
