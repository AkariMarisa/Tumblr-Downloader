#!/usr/bin/env bash
set -euo pipefail

# 固定本项目常用构建环境（如需改路径请同步修改这里）
JAVA_HOME_FIXED="/home/akari/.sdkman/candidates/java/21.0.1-tem"
ANDROID_HOME_FIXED="/home/akari/Android/Sdk"

export JAVA_HOME="$JAVA_HOME_FIXED"
export ANDROID_HOME="$ANDROID_HOME_FIXED"
export ANDROID_SDK_ROOT="$ANDROID_HOME_FIXED"

# 保证可执行工具路径在 PATH
export PATH="$JAVA_HOME/bin:$PATH"
[ -d "$ANDROID_HOME/platform-tools" ] && export PATH="$ANDROID_HOME/platform-tools:$PATH"
[ -d "$ANDROID_HOME/cmdline-tools/latest/bin" ] && export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
[ -d "$ANDROID_HOME/emulator" ] && export PATH="$ANDROID_HOME/emulator:$PATH"

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

# 本地 SDK 路径配置（Gradle 会优先读取）
if [ ! -d "$JAVA_HOME_FIXED" ]; then
  echo "[error] JAVA_HOME not found: $JAVA_HOME_FIXED"
  exit 1
fi
if [ ! -d "$ANDROID_HOME_FIXED" ]; then
  echo "[error] ANDROID_HOME not found: $ANDROID_HOME_FIXED"
  exit 1
fi

# 本地 SDK 路径配置（Gradle 会优先读取）
cat > local.properties <<EOF
sdk.dir=$ANDROID_HOME_FIXED
EOF

gradle_cmd="gradle"
if [ -x "./gradlew" ]; then
  gradle_cmd="./gradlew"
fi

task="${1:-:app:assembleDebug}"
shift || true

if [ ! -d "$JAVA_HOME" ]; then
  echo "[error] JAVA_HOME not found: $JAVA_HOME"
  exit 1
fi
if [ ! -d "$ANDROID_HOME" ]; then
  echo "[error] ANDROID_HOME not found: $ANDROID_HOME"
  exit 1
fi

printf '[info] Java      : %s\n' "$JAVA_HOME"
printf '[info] AndroidSDK: %s\n' "$ANDROID_HOME"
printf '[info] GradleCmd : %s\n' "$gradle_cmd"

"$gradle_cmd" "$task" "$@"

if [ -f app/build/outputs/apk/debug/app-debug.apk ]; then
  echo "[ok] APK: app/build/outputs/apk/debug/app-debug.apk"
else
  apk_file="$(find app/build/outputs/apk -type f -name '*.apk' 2>/dev/null | head -n 1 || true)"
  if [ -n "$apk_file" ]; then
    echo "[ok] APK: $apk_file"
  else
    echo "[warn] 未发现 APK 输出文件（当前 task 可能未打包 APK）"
  fi
fi
