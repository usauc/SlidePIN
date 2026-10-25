#!/usr/bin/env bash
# SlidePIN 构建脚本（LSPosed 模块）
#
# 依赖：JDK 17+、Android build-tools（d8 / aapt2 / zipalign / apksigner）、android.jar
# 用法：
#   export JDK_HOME=/path/to/jdk-17
#   export BUILD_TOOLS=/path/to/android-sdk/build-tools/34.0.0
#   export ANDROID_JAR=/path/to/android-sdk/platforms/android-33/android.jar
#   ./build.sh
#
# 未设置环境变量时，会尝试仓库同级的 toolchain/ 目录与常见 SDK 路径。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$ROOT/build"
DEX="$ROOT/dex"

find_first() {
  for p in "$@"; do
    [ -e "$p" ] && { echo "$p"; return 0; }
  done
  return 1
}

# ---- JDK ----
if [ -z "${JDK_HOME:-}" ]; then
  JDK_HOME="$(find_first "$ROOT/../toolchain"/jdk-* "${JAVA_HOME:-/nonexistent}" || true)"
fi
[ -n "${JDK_HOME:-}" ] || { echo "错误：未找到 JDK，请设置 JDK_HOME"; exit 1; }

if [ -x "$JDK_HOME/bin/javac" ]; then
  JAVAC="$JDK_HOME/bin/javac"; KEYTOOL="$JDK_HOME/bin/keytool"; JAVA="$JDK_HOME/bin/java"
else
  JAVAC="$JDK_HOME/bin/javac.exe"; KEYTOOL="$JDK_HOME/bin/keytool.exe"; JAVA="$JDK_HOME/bin/java.exe"
fi
[ -x "$JAVAC" ] || { echo "错误：$JAVAC 不存在"; exit 1; }

# ---- build-tools ----
if [ -z "${BUILD_TOOLS:-}" ]; then
  BUILD_TOOLS="$(find_first "$ROOT/../toolchain"/android-* "${ANDROID_HOME:-/nonexistent}/build-tools/34.0.0" "$HOME/AppData/Local/Android/Sdk/build-tools/34.0.0" || true)"
fi
[ -n "${BUILD_TOOLS:-}" ] || { echo "错误：未找到 build-tools，请设置 BUILD_TOOLS"; exit 1; }

AAPT2="$BUILD_TOOLS/aapt2.exe"; [ -x "$AAPT2" ] || AAPT2="$BUILD_TOOLS/aapt2"
ZIPALIGN="$BUILD_TOOLS/zipalign.exe"; [ -x "$ZIPALIGN" ] || ZIPALIGN="$BUILD_TOOLS/zipalign"
D8_JAR="$BUILD_TOOLS/lib/d8.jar"
APKSIGNER_JAR="$BUILD_TOOLS/lib/apksigner.jar"
[ -f "$D8_JAR" ] || { echo "错误：$D8_JAR 不存在"; exit 1; }

# ---- android.jar ----
if [ -z "${ANDROID_JAR:-}" ]; then
  ANDROID_JAR="$(find_first "$ROOT/../toolchain"/android-*/android.jar "${ANDROID_HOME:-/nonexistent}/platforms/android-33/android.jar" "$HOME/AppData/Local/Android/Sdk/platforms/android-33/android.jar" || true)"
fi
[ -f "${ANDROID_JAR:-}" ] || { echo "错误：未找到 android.jar，请设置 ANDROID_JAR"; exit 1; }

mkdir -p "$OUT" "$DEX"

echo "==> 1/6 编译 Java"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$ANDROID_JAR" \
  -d "$OUT" \
  $(find "$ROOT/src" "$ROOT/stubs" -name '*.java')

echo "==> 2/6 生成 classes.dex"
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release --min-api 29 --lib "$ANDROID_JAR" \
  --output "$DEX" $(find "$OUT/com" -name '*.class')

echo "==> 3/6 链接清单"
"$AAPT2" link -o "$ROOT/base.apk" \
  --manifest "$ROOT/AndroidManifest.xml" \
  -I "$ANDROID_JAR" \
  --min-sdk-version 29 --target-sdk-version 33

echo "==> 4/6 注入 dex 与 xposed_init"
python - "$ROOT/base.apk" "$DEX/classes.dex" "$ROOT/assets/xposed_init" <<'PY'
import sys, zipfile
apk, dex, init = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
    z.write(init, "assets/xposed_init")
print("注入完成")
PY

echo "==> 5/6 zipalign"
"$ZIPALIGN" -f 4 "$ROOT/base.apk" "$ROOT/aligned.apk"

echo "==> 6/6 签名"
# 发布签名：设置 SLIDEPIN_KEYSTORE / SLIDEPIN_STOREPASS / SLIDEPIN_KEYPASS / SLIDEPIN_ALIAS
# 未设置时回退到仓库内的 debug keystore（仅供本地测试）
if [ -n "${SLIDEPIN_KEYSTORE:-}" ]; then
  KS="$SLIDEPIN_KEYSTORE"
  KS_PASS="${SLIDEPIN_STOREPASS:-}"
  KEY_PASS="${SLIDEPIN_KEYPASS:-$KS_PASS}"
  ALIAS="${SLIDEPIN_ALIAS:-slidepin}"
  [ -f "$KS" ] || { echo "错误：keystore 不存在：$KS"; exit 1; }
  [ -n "$KS_PASS" ] || { echo "错误：未设置 SLIDEPIN_STOREPASS"; exit 1; }
  echo "    使用发布密钥：$KS（alias=$ALIAS）"
  SIGN_ARGS=(--ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS")
  [ -n "$ALIAS" ] && SIGN_ARGS+=(--ks-key-alias "$ALIAS")
else
  echo "    未提供 SLIDEPIN_KEYSTORE，使用 debug 密钥（请勿用于公开分发）"
  if [ ! -f "$ROOT/debug.keystore" ]; then
    "$KEYTOOL" -genkeypair -v -keystore "$ROOT/debug.keystore" \
      -storepass android -keypass android -alias androiddebugkey \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  fi
  SIGN_ARGS=(--ks "$ROOT/debug.keystore" --ks-pass pass:android --key-pass pass:android)
fi

"$JAVA" -jar "$APKSIGNER_JAR" sign "${SIGN_ARGS[@]}" \
  --out "$ROOT/SlidePIN.apk" "$ROOT/aligned.apk"

"$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$ROOT/SlidePIN.apk" | head -4
ls -lh "$ROOT/SlidePIN.apk"
echo "构建完成"
