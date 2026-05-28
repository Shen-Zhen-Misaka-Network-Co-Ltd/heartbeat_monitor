# Heartwith

小米手环心率共享大厅，全端实现：

- `server/`: Rust + Axum 服务端，提供会话、CBOR 批量摄取、大厅快照、SSE 推送、最近曲线。
- `clients/heartwith-compose/`: Kotlin Compose Multiplatform 客户端。
  - Android: 原生 BLE 订阅小米手环标准心率特征 `0x2A37`，低功耗批量上传。
  - Wasm/JS: 使用 Miuix 的网页大厅。

Miuix 采用 `compose-miuix-ui/miuix`，当前依赖版本锁定为 `0.9.1`。

## Run Server

服务器部署推荐使用 PostgreSQL + TimescaleDB：

```bash
export HEARTWITH_DATABASE_URL='postgres://heartwith:heartwith@127.0.0.1:5432/heartwith'
```

本地开发不设置 `HEARTWITH_DATABASE_URL` 时会使用 `sqlite://heartwith.db`。

```bash
cargo run -p heartwith-server
```

服务默认监听 `http://127.0.0.1:8000`。如果已经构建了 Web 客户端，Rust 服务端会优先托管：

```text
clients/heartwith-compose/build/kotlin-webpack/wasmJs/productionExecutable
```

否则使用 `web-fallback/` 的轻量回退页面。

## Build Clients

本机需要 Android SDK。仓库包含 Gradle Wrapper，Android 构建需要安装 `platforms;android-37.0` 和常规 build-tools。

Android:

```bash
ANDROID_HOME=/path/to/android-sdk ./gradlew :heartwith-compose:assembleDebug
```

Web:

```bash
./gradlew :heartwith-compose:wasmJsBrowserProductionWebpack
```

Tests:

```bash
cargo test -p heartwith-server
ANDROID_HOME=/path/to/android-sdk ./gradlew :heartwith-compose:allTests
```

## API

接口文档见 [docs/API.md](docs/API.md)。
协议选择和省电策略见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。
数据库选择见 [docs/DATABASE.md](docs/DATABASE.md)。
