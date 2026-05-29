# Repository Split Plan

当前仓库已经发展成服务端、网页端、BLE Android 客户端、LSPosed/NPatch 模块混合仓库。继续放在一起会让 CI、版本、发布节奏和权限模型互相影响。建议拆成下面 6 个仓库。

## Recommended Repositories

### 1. `heartwith-protocol`

职责：
- API 文档和省电上传协议。
- 数据库/保留策略文档。
- 后续可放 OpenAPI、CBOR schema、客户端兼容性说明。

内容：
- `docs/API.md`
- `docs/PROTOCOL.md`
- `docs/DATABASE.md`

理由：
- 服务端、BLE 客户端、Hook 客户端、网页端都依赖同一套协议。
- 协议版本应独立于任意一个实现发布。

### 2. `heartwith-server`

职责：
- Rust + Axum 后端。
- 数据库迁移、TimescaleDB/PostgreSQL/SQLite 开发支持。
- Web 静态资源托管入口和轻量 fallback 页面。

内容：
- `Cargo.toml`
- `Cargo.lock`
- `server/`
- `web-fallback/`
- `scripts/build-linux-amd64-server.sh`
- 协议文档副本或 Git submodule 引入的 `heartwith-protocol`。

发布节奏：
- 服务端独立发版，例如 `server-v1.1.0`。
- 不要求和 Android/Web/LSP 客户端版本同步。

### 3. `heartwith-web`

职责：
- Kotlin/Wasm Compose 网页大厅。
- Web 端 Miuix UI、图表、SSE 大厅实时更新。

内容：
- `clients/heartwith-web/`
- `clients/heartwith-shared/`
- Gradle wrapper 和 Gradle 版本目录。

说明：
- 当前 `heartwith-shared` 同时包含 UI、API client 和协议模型。为了避免过早抽成远程依赖，首轮拆分建议 Web 仓库保留一份 `clients/heartwith-shared/`，并通过协议文档约束兼容性。
- 后续如果 Android 与 Web 共享代码变化频繁，再把 `heartwith-shared` 单独抽成可发布的 KMP library。

发布节奏：
- Web 可按服务端部署节奏发布，但不阻塞 Android/LSP。

### 4. `heartwith-android-uploader`

职责：
- Android 上传 SDK。
- 会话创建、CBOR 批量上传、离线缓存、重试、省电上传窗口。

内容：
- `clients/heartwith-android-uploader/`
- Gradle wrapper 和 Gradle 版本目录。

设计边界：
- SDK 暴露 `HeartwithUploader`、`HeartwithUploadConfig`、`HeartwithHttpClient`。
- 默认 HTTP 实现使用 `HttpURLConnection`，不引入 OkHttp/Ktor/Compose。
- LSP/NPatch 需要 cleartext hook 时，通过 `HeartwithCleartextScope` 或自定义 `HeartwithHttpClient` 注入，不污染普通 BLE 客户端。

### 5. `heartwith-ble-collector`

职责：
- 原生 BLE 广播/标准心率特征采集 Android 客户端。
- 后台采集、通知、省电策略、CBOR 上传。

内容：
- `clients/heartwith-compose/`
- `clients/heartwith-shared/`
- Gradle wrapper 和 Gradle 版本目录。

说明：
- 这里保留 `heartwith-shared` 是为了让 Android UI 与协议模型继续复用当前实现。
- 上传逻辑迁移完成后依赖 `heartwith-android-uploader`，不再维护自己的上传实现。
- BLE 客户端的发版、签名、权限声明与 Web/Server 解耦。

发布节奏：
- Android app 独立发版，例如 `android-v1.0.1`。

### 6. `heartwith-mihealth-module`

职责：
- LSPosed/NPatch 小米运动健康 hook 模块。
- 设置页、通知、hook 源选择、省电策略。

内容：
- `clients/heartwith-mihealth-lsp/`
- `clients/xposed-api-stub/`
- `docs/MIHEALTH_LSPOSED.md`
- Gradle wrapper 和 Gradle 版本目录。

理由：
- 这个模块涉及 hook、作用域、NPatch、LSPosed 和反射保留策略，风险面和发布渠道与普通 BLE 客户端完全不同。
- 单独仓库能减少误改普通 Android 客户端和 Web 的概率。
- MiHealth 里 cleartext/OkHttp hook 属于宿主环境适配，应留在此仓库；共享 uploader 只提供可注入 HTTP transport。

发布节奏：
- 模块独立发版，例如 `mihealth-v1.0.1`。

## Why Not Keep One Client Repo?

Android BLE、Web 大厅和 MiHealth hook 的业务确实都属于“客户端”，但它们的维护问题完全不同：
- Android BLE 关注蓝牙权限、后台保活、省电和通知。
- Web 关注首屏速度、图表性能、SSE 和浏览器兼容。
- MiHealth hook 关注 LSPosed/NPatch、目标应用版本、反射和进程生命周期。

把三者放在一个客户端仓库会继续让 CI 和发布互相牵制。更合理的是拆开实现仓库，用 `heartwith-protocol` 管住协议兼容性。

## Migration Order

1. 先冻结当前 monorepo 的 `dev` 分支，确保 Android/Web/Server 构建都通过。
2. 使用 `scripts/export-split-repositories.sh` 生成本地拆分快照。
3. 分别在 GitHub 创建目标仓库。
4. 将每个快照仓库初始化为独立 Git 仓库并推送。
5. 配置每个仓库自己的 CI：
   - server: `cargo test`, `cargo build --release`
   - web: `./gradlew :heartwith-web:wasmJsBrowserDistribution`
   - uploader: `./gradlew :heartwith-android-uploader:assembleRelease`
   - android: `./gradlew :heartwith-compose:assembleRelease`
   - mihealth-module: `./gradlew :heartwith-mihealth-lsp:assembleRelease`
6. monorepo 进入归档或只保留迁移说明。

## History Preservation

首轮建议用快照拆分，原因是当前根 Gradle/Cargo 配置需要按目标仓库重写，直接 `git subtree split` 得到的仓库不能开箱构建。

如果必须保留历史，可以在快照仓库稳定后，再用 `git filter-repo` 做历史迁移：
- 对每个目标仓库保留对应目录和根构建文件。
- 使用 `--path-rename` 把模块目录提升到目标仓库期望位置。
- 最后手工套用快照仓库里的独立构建文件。

## Versioning

推荐版本线：
- Protocol: `protocol-v1`
- Server: `server-vX.Y.Z`
- Web: `web-vX.Y.Z`
- Android uploader: `uploader-vX.Y.Z`
- Android BLE: `android-vX.Y.Z`
- MiHealth module: `mihealth-vX.Y.Z`

服务端和客户端不要求版本号同步，只通过协议版本兼容。
