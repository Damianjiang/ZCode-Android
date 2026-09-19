#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# IpcCodec 独立验证
#
# 把纯 JVM 的协议编解码器**脱离 Android** 单独编译运行：
# IpcCodec.kt 只依赖 kotlin-stdlib + gson，没有任何 Android API，
# 所以可以直接用 Gradle 缓存里已有的 kotlin-compiler-embeddable 编译成 class 再跑，
# 不需要模拟器、不需要给 app 模块引入测试依赖、不需要联网。
#
# 用法：  bash tools/verify-ipc-codec/run.sh
# 退出码：0 = 全部通过；1 = 有断言失败
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="$REPO/app/src/main/java/app/zemote/protocol/IpcCodec.kt"
CACHE="$HOME/.gradle/caches/modules-2/files-2.1"

# 每次跑用全新的临时目录：既避免清理旧产物，也避免依赖 TMPDIR 的路径风格
# （Windows 下 TMPDIR 可能是 "C:\..." 形式，拼进 bash 路径会变成相对路径）。
WORK="$(mktemp -d)"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *)                    SEP=':' ;;
esac

# Git Bash / MSYS 不会把 POSIX 路径（/c/Users/...）转换成 Windows 路径传给 java，
# 直接传过去会 ClassNotFoundException。有 cygpath 就统一转成 Windows 形式。
winpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

# 优先用项目声明的 Kotlin 版本，避免误取缓存里更新的编译器
KOTLIN_VER="$(grep -E '^kotlin[[:space:]]*=' "$REPO/gradle/libs.versions.toml" 2>/dev/null \
  | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"

# find_jar <group/artifact> <artifact-name> [preferred-version]
find_jar() {
  local dir="$CACHE/$1" name="$2" ver="${3:-}" hit=""
  [ -d "$dir" ] || return 0
  if [ -n "$ver" ]; then
    hit="$(find "$dir/$ver" -name "$name-*.jar" 2>/dev/null | head -1)"
  fi
  [ -n "$hit" ] || hit="$(find "$dir" -name "$name-*.jar" 2>/dev/null | sort -V | tail -1)"
  [ -n "$hit" ] && winpath "$hit"
}

KC="$(find_jar org.jetbrains.kotlin/kotlin-compiler-embeddable kotlin-compiler-embeddable "$KOTLIN_VER")"
STD="$(find_jar org.jetbrains.kotlin/kotlin-stdlib             kotlin-stdlib             "$KOTLIN_VER")"
CORO="$(find_jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm kotlinx-coroutines-core-jvm)"
GSON="$(find_jar com.google.code.gson/gson                      gson)"
ANNO="$(find_jar org.jetbrains/annotations                      annotations)"
TROVE="$(find_jar org.jetbrains.intellij.deps/trove4j           trove4j)"

missing=""
for pair in "kotlin-compiler-embeddable=$KC" "kotlin-stdlib=$STD" \
            "kotlinx-coroutines-core-jvm=$CORO" "gson=$GSON"; do
  [ -n "${pair#*=}" ] || missing="$missing ${pair%%=*}"
done
if [ -n "$missing" ]; then
  echo "缺少依赖 jar:$missing" >&2
  echo "先执行一次 ./gradlew :app:assembleDebug 把 Gradle 缓存填充起来。" >&2
  exit 1
fi

# 编译器自身的运行时 classpath（annotations / trove4j 存在就带上）
COMPILER_CP="$KC$SEP$STD$SEP$CORO"
[ -n "$ANNO" ]  && COMPILER_CP="$COMPILER_CP$SEP$ANNO"
[ -n "$TROVE" ] && COMPILER_CP="$COMPILER_CP$SEP$TROVE"

mkdir -p "$WORK"
cp "$SRC" "$HERE/CodecVerify.kt" "$WORK/"
cd "$WORK"

echo "== 编译 IpcCodec.kt + CodecVerify.kt（kotlin $KOTLIN_VER）=="
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -classpath "$STD$SEP$GSON" -d out IpcCodec.kt CodecVerify.kt

echo "== 运行验证 =="
java -cp "out$SEP$STD$SEP$GSON" CodecVerifyKt
