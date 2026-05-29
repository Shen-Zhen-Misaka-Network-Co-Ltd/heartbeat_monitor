#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-"${ROOT_DIR}/../heartwith-split"}"

copy_common_gradle() {
  local repo_dir="$1"
  rsync -a \
    "${ROOT_DIR}/gradle" \
    "${ROOT_DIR}/gradlew" \
    "${ROOT_DIR}/gradlew.bat" \
    "${ROOT_DIR}/gradle.properties" \
    "${ROOT_DIR}/build.gradle.kts" \
    "${ROOT_DIR}/.gitignore" \
    "${repo_dir}/"
}

write_gradle_settings() {
  local repo_dir="$1"
  local root_name="$2"
  shift 2

  {
    printf '%s\n' 'pluginManagement {'
    printf '%s\n' '    repositories {'
    printf '%s\n' '        google()'
    printf '%s\n' '        mavenCentral()'
    printf '%s\n' '        gradlePluginPortal()'
    printf '%s\n' '    }'
    printf '%s\n' '}'
    printf '%s\n' ''
    printf '%s\n' 'dependencyResolutionManagement {'
    printf '%s\n' '    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)'
    printf '%s\n' '    repositories {'
    printf '%s\n' '        google()'
    printf '%s\n' '        mavenCentral()'
    printf '%s\n' '    }'
    printf '%s\n' '}'
    printf '%s\n' ''
    printf 'rootProject.name = "%s"\n' "${root_name}"
    for module in "$@"; do
      local name="${module%%:*}"
      local path="${module#*:}"
      printf 'include(":%s")\n' "${name}"
      printf 'project(":%s").projectDir = file("%s")\n' "${name}" "${path}"
    done
  } > "${repo_dir}/settings.gradle.kts"
}

write_readme() {
  local repo_dir="$1"
  local title="$2"
  local body="$3"
  {
    printf '# %s\n\n' "${title}"
    printf '%s\n' "${body}"
  } > "${repo_dir}/README.md"
}

write_workflow() {
  local repo_dir="$1"
  local name="$2"
  mkdir -p "${repo_dir}/.github/workflows"
  cat > "${repo_dir}/.github/workflows/${name}.yml"
}

write_android_debug_keystore_step() {
  cat <<'YAML'
      - name: Prepare debug signing key
        run: |
          mkdir -p "$HOME/.android"
          keytool -genkeypair \
            -keystore "$HOME/.android/debug.keystore" \
            -storepass android \
            -keypass android \
            -alias androiddebugkey \
            -keyalg RSA \
            -keysize 2048 \
            -validity 10000 \
            -dname "CN=Android Debug,O=Android,C=US"
YAML
}

write_android_setup_steps() {
  cat <<'YAML'
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21

      - uses: android-actions/setup-android@v3

      - name: Install Android SDK platform
        run: sdkmanager "platforms;android-37.0" "build-tools;37.0.0"

      - uses: gradle/actions/setup-gradle@v4
YAML
}

write_protocol_ci() {
  local repo_dir="$1"
  write_workflow "${repo_dir}" ci <<'YAML'
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  docs:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Check protocol docs
        run: |
          test -s docs/API.md
          test -s docs/PROTOCOL.md
          test -s docs/DATABASE.md
YAML
}

write_server_ci() {
  local repo_dir="$1"
  write_workflow "${repo_dir}" ci <<'YAML'
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  server:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: dtolnay/rust-toolchain@stable

      - uses: Swatinem/rust-cache@v2

      - name: Check
        run: cargo check --package heartwith-server

      - name: Test
        run: cargo test --package heartwith-server
YAML
}

write_web_ci() {
  local repo_dir="$1"
  write_workflow "${repo_dir}" ci <<'YAML'
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  web:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21

      - uses: gradle/actions/setup-gradle@v4

      - name: Test
        run: ./gradlew :heartwith-web:allTests --warning-mode=fail

      - name: Build
        run: ./gradlew :heartwith-web:wasmJsBrowserDistribution --warning-mode=fail
YAML
}

write_android_uploader_ci() {
  local repo_dir="$1"
  {
    cat <<'YAML'
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  android-uploader:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

YAML
    write_android_setup_steps
    cat <<'YAML'

      - name: Build
        run: ./gradlew :heartwith-android-uploader:assembleRelease --warning-mode=fail
YAML
  } | write_workflow "${repo_dir}" ci
}

write_ble_ci() {
  local repo_dir="$1"
  {
    cat <<'YAML'
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  android:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

YAML
    write_android_setup_steps
    write_android_debug_keystore_step
    cat <<'YAML'

      - name: Build
        run: ./gradlew :heartwith-compose:assembleRelease --warning-mode=fail
YAML
  } | write_workflow "${repo_dir}" ci
}

write_ble_release() {
  local repo_dir="$1"
  write_workflow "${repo_dir}" release <<'YAML'
name: Release

on:
  push:
    tags:
      - 'android-v*'

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21

      - uses: android-actions/setup-android@v3

      - name: Install Android SDK platform
        run: sdkmanager "platforms;android-37.0" "build-tools;37.0.0"

      - name: Restore release signing key
        run: |
          mkdir -p "$HOME/.android"
          printf '%s' "$HEARTWITH_RELEASE_KEYSTORE_BASE64" | base64 --decode > "$HOME/.android/debug.keystore"
          chmod 600 "$HOME/.android/debug.keystore"
          keytool -list -keystore "$HOME/.android/debug.keystore" -storepass android -alias androiddebugkey >/dev/null
        env:
          HEARTWITH_RELEASE_KEYSTORE_BASE64: ${{ secrets.HEARTWITH_RELEASE_KEYSTORE_BASE64 }}

      - uses: gradle/actions/setup-gradle@v4

      - name: Build APK
        run: ./gradlew :heartwith-compose:assembleRelease --warning-mode=fail

      - uses: softprops/action-gh-release@v2
        with:
          files: clients/heartwith-compose/build/outputs/apk/release/Heartwith-*-release.apk
          generate_release_notes: true
YAML
}

write_mihealth_ci() {
  local repo_dir="$1"
  {
    cat <<'YAML'
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  mihealth-module:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

YAML
    write_android_setup_steps
    write_android_debug_keystore_step
    cat <<'YAML'

      - name: Build
        run: ./gradlew :heartwith-mihealth-lsp:assembleRelease --warning-mode=fail
YAML
  } | write_workflow "${repo_dir}" ci
}

write_mihealth_release() {
  local repo_dir="$1"
  write_workflow "${repo_dir}" release <<'YAML'
name: Release

on:
  push:
    tags:
      - 'mihealth-v*'

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21

      - uses: android-actions/setup-android@v3

      - name: Install Android SDK platform
        run: sdkmanager "platforms;android-37.0" "build-tools;37.0.0"

      - name: Restore release signing key
        run: |
          mkdir -p "$HOME/.android"
          printf '%s' "$HEARTWITH_RELEASE_KEYSTORE_BASE64" | base64 --decode > "$HOME/.android/debug.keystore"
          chmod 600 "$HOME/.android/debug.keystore"
          keytool -list -keystore "$HOME/.android/debug.keystore" -storepass android -alias androiddebugkey >/dev/null
        env:
          HEARTWITH_RELEASE_KEYSTORE_BASE64: ${{ secrets.HEARTWITH_RELEASE_KEYSTORE_BASE64 }}

      - uses: gradle/actions/setup-gradle@v4

      - name: Build APK
        run: ./gradlew :heartwith-mihealth-lsp:assembleRelease --warning-mode=fail

      - uses: softprops/action-gh-release@v2
        with:
          files: clients/heartwith-mihealth-lsp/build/outputs/apk/release/Heartwith-*-release.apk
          generate_release_notes: true
YAML
}

init_repo() {
  local repo_dir="$1"
  (
    cd "${repo_dir}"
    git init -q
    git add .
    git commit -qm "Initial split from heartwith monorepo"
  )
}

copy_protocol_docs() {
  local repo_dir="$1"
  mkdir -p "${repo_dir}/docs"
  rsync -a \
    "${ROOT_DIR}/docs/API.md" \
    "${ROOT_DIR}/docs/PROTOCOL.md" \
    "${ROOT_DIR}/docs/DATABASE.md" \
    "${repo_dir}/docs/"
}

prepare_repo_dir() {
  local name="$1"
  local repo_dir="${OUT_DIR}/${name}"
  rm -rf "${repo_dir}"
  mkdir -p "${repo_dir}"
  printf '%s\n' "${repo_dir}"
}

mkdir -p "${OUT_DIR}"

protocol_dir="$(prepare_repo_dir heartwith-protocol)"
copy_protocol_docs "${protocol_dir}"
write_readme "${protocol_dir}" "Heartwith Protocol" \
"API, CBOR ingest protocol, database retention policy, and compatibility documents shared by all Heartwith implementations."
write_protocol_ci "${protocol_dir}"
init_repo "${protocol_dir}"

server_dir="$(prepare_repo_dir heartwith-server)"
rsync -a \
  "${ROOT_DIR}/Cargo.toml" \
  "${ROOT_DIR}/Cargo.lock" \
  "${ROOT_DIR}/server" \
  "${ROOT_DIR}/web-fallback" \
  "${ROOT_DIR}/scripts/build-linux-amd64-server.sh" \
  "${ROOT_DIR}/.gitignore" \
  "${server_dir}/"
copy_protocol_docs "${server_dir}"
write_readme "${server_dir}" "Heartwith Server" \
"Rust + Axum backend for Heartwith. Run with: cargo run -p heartwith-server"
write_server_ci "${server_dir}"
init_repo "${server_dir}"

web_dir="$(prepare_repo_dir heartwith-web)"
copy_common_gradle "${web_dir}"
mkdir -p "${web_dir}/clients"
rsync -a "${ROOT_DIR}/clients/heartwith-web" "${web_dir}/clients/"
rsync -a "${ROOT_DIR}/clients/heartwith-shared" "${web_dir}/clients/"
write_gradle_settings "${web_dir}" "heartwith-web" \
  "heartwith-web:clients/heartwith-web"
write_readme "${web_dir}" "Heartwith Web" \
"Kotlin/Wasm Compose web lobby. Build with: ./gradlew :heartwith-web:wasmJsBrowserDistribution"
write_web_ci "${web_dir}"
init_repo "${web_dir}"

uploader_dir="$(prepare_repo_dir heartwith-android-uploader)"
copy_common_gradle "${uploader_dir}"
mkdir -p "${uploader_dir}/clients"
rsync -a "${ROOT_DIR}/clients/heartwith-android-uploader" "${uploader_dir}/clients/"
write_gradle_settings "${uploader_dir}" "heartwith-android-uploader" \
  "heartwith-android-uploader:clients/heartwith-android-uploader"
write_readme "${uploader_dir}" "Heartwith Android Uploader" \
"Small Android upload SDK shared by Heartwith collectors. It owns session creation, CBOR batch upload, retry/backoff, offline cache, and injectable HTTP transport for BLE and LSPosed/NPatch clients."
write_android_uploader_ci "${uploader_dir}"
init_repo "${uploader_dir}"

android_dir="$(prepare_repo_dir heartwith-ble-collector)"
copy_common_gradle "${android_dir}"
mkdir -p "${android_dir}/clients"
rsync -a "${ROOT_DIR}/clients/heartwith-compose" "${android_dir}/clients/"
rsync -a "${ROOT_DIR}/clients/heartwith-shared" "${android_dir}/clients/"
write_gradle_settings "${android_dir}" "heartwith-ble-collector" \
  "heartwith-compose:clients/heartwith-compose"
write_readme "${android_dir}" "Heartwith BLE Collector" \
"Native BLE collector Android app. Build with: ./gradlew :heartwith-compose:assembleRelease. This repository will depend on heartwith-android-uploader for shared upload logic."
write_ble_ci "${android_dir}"
write_ble_release "${android_dir}"
init_repo "${android_dir}"

mihealth_dir="$(prepare_repo_dir heartwith-mihealth-module)"
copy_common_gradle "${mihealth_dir}"
mkdir -p "${mihealth_dir}/clients" "${mihealth_dir}/docs"
rsync -a "${ROOT_DIR}/clients/heartwith-mihealth-lsp" "${mihealth_dir}/clients/"
rsync -a "${ROOT_DIR}/clients/xposed-api-stub" "${mihealth_dir}/clients/"
rsync -a "${ROOT_DIR}/docs/MIHEALTH_LSPOSED.md" "${mihealth_dir}/docs/"
write_gradle_settings "${mihealth_dir}" "heartwith-mihealth-module" \
  "heartwith-mihealth-lsp:clients/heartwith-mihealth-lsp" \
  "xposed-api-stub:clients/xposed-api-stub"
write_readme "${mihealth_dir}" "Heartwith MiHealth Module" \
"LSPosed/NPatch module for Xiaomi Health heart-rate collection. Build with: ./gradlew :heartwith-mihealth-lsp:assembleRelease. This repository keeps MiHealth-specific hook and cleartext adaptation code while sharing upload protocol through heartwith-android-uploader."
write_mihealth_ci "${mihealth_dir}"
write_mihealth_release "${mihealth_dir}"
init_repo "${mihealth_dir}"

printf 'Split repositories exported to %s\n' "${OUT_DIR}"
