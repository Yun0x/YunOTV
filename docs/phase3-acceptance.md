# Phase 3 范围

Phase 3 已构建签名 0.3.0 APK。隔离的 Rhino / Spider Runtime、外部 JSON 解析器、分层搜索及播放线路回退已实现。

默认真实源的完整 Home/Search/Detail/Play 兼容性仍未达到原定的日常使用验收标准。现有 `phase3-reports/runtime-results.json` 对应此前的抽样与上游快照；不能把候选 PARTIAL 或资源下载成功算成 Fully Supported。

后续架构调整已把默认配置、可公开下载的 JS/JAR，以及直播清单编译入 APK。该调整的构建、特殊离线启动验收和限制见 [本地配置验收](offline-config-acceptance.md)。这项验收验证配置启动、JS 初始化、JAR 类加载，不代表所有第三方来源均能播放。
