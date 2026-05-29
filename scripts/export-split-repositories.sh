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
init_repo "${web_dir}"

uploader_dir="$(prepare_repo_dir heartwith-android-uploader)"
copy_common_gradle "${uploader_dir}"
mkdir -p "${uploader_dir}/clients"
rsync -a "${ROOT_DIR}/clients/heartwith-android-uploader" "${uploader_dir}/clients/"
write_gradle_settings "${uploader_dir}" "heartwith-android-uploader" \
  "heartwith-android-uploader:clients/heartwith-android-uploader"
write_readme "${uploader_dir}" "Heartwith Android Uploader" \
"Small Android upload SDK shared by Heartwith collectors. It owns session creation, CBOR batch upload, retry/backoff, offline cache, and injectable HTTP transport for BLE and LSPosed/NPatch clients."
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
init_repo "${mihealth_dir}"

printf 'Split repositories exported to %s\n' "${OUT_DIR}"
