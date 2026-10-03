#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

echo "=================================================="
echo "  Maintenance Mode - Build Script"
echo "=================================================="
echo

CHECK_ONLY=0
if [ "$1" = "--check" ]; then
    CHECK_ONLY=1
fi

find_jdk() {
    local bases=(
        "$JDK_HOME"
        "$JAVA_HOME"
        "/c/Program Files/Android/Android Studio/jbr"
        "/c/Program Files/Eclipse Adoptium"
        "/c/Program Files/Java"
        "/c/Program Files/Microsoft"
        "/c/Program Files/Zulu"
        "/c/Program Files/BellSoft"
        "/c/Program Files/Amazon Corretto"
        "$HOME/AppData/Local/Programs/Eclipse Adoptium"
    )

    local expanded=()
    local base
    for base in "${bases[@]}"; do
        [ -n "$base" ] || continue
        [ -d "$base" ] || continue
        expanded+=("$base")
        local d
        for d in "$base"/jdk-21* "$base"/jdk-17* "$base"/jdk21* "$base"/jdk17* \
                 "$base"/zulu-21* "$base"/zulu-17* \
                 "$base"/LibericaJDK-21* "$base"/LibericaJDK-17* \
                 "$base"/jbr* "$base"/*21* "$base"/*17*; do
            [ -d "$d" ] && expanded+=("$d")
        done
    done

    local c
    for c in "${expanded[@]}"; do
        if [ ! -f "$c/bin/javac" ] && [ ! -f "$c/bin/javac.exe" ]; then
            continue
        fi

        local major=""
        if [ -f "$c/release" ]; then
            major=$(sed -n 's/^JAVA_VERSION="\([0-9][0-9]*\).*/\1/p' "$c/release" | head -n 1)
        fi

        case "$major" in
            17|18|19|20|21)
                echo "$c"
                return 0
                ;;
        esac
    done

    return 1
}

JDK=$(find_jdk) || {
    echo "[ERROR] Could not find a compatible Java 17 - 21 installation."
    echo
    echo "  This project cannot be built with Java 8, 23 or 25."
    echo "  Please install \"Eclipse Temurin 21 - JDK\" from:"
    echo "  https://adoptium.net/temurin/releases/?version=21"
    echo
    exit 1
}

echo "[INFO] Using Java:"
"$JDK/bin/java" -version 2>&1
echo

if [ "$CHECK_ONLY" = "1" ]; then
    echo "[INFO] Check passed, Java detection works."
    exit 0
fi

BUILD_DIR="${HOME}/.mmode-build"
if command -v cygpath >/dev/null 2>&1 && [ -n "$LOCALAPPDATA" ]; then
    BUILD_DIR="$(cygpath -u "$LOCALAPPDATA")/mmode-build"
fi
GRADLE_DIR="${BUILD_DIR}/gradle-8.5"
GRADLE_ZIP="${BUILD_DIR}/gradle-8.5-bin.zip"

if [ ! -f "${GRADLE_DIR}/bin/gradle" ]; then
    mkdir -p "${BUILD_DIR}"
    if [ ! -f "${GRADLE_ZIP}" ]; then
        echo "[INFO] Downloading Gradle 8.5, about 130 MB, one time only ..."
        curl -L --fail -o "${GRADLE_ZIP}" "https://mirrors.cloud.tencent.com/gradle/gradle-8.5-bin.zip"
    fi
    echo "[INFO] Extracting Gradle ..."
    unzip -q -o "${GRADLE_ZIP}" -d "${BUILD_DIR}"
fi

export JAVA_HOME="$JDK"

echo "[INFO] Starting the build. The first run downloads everything,"
echo "       so it can take a while. Please be patient ..."
echo

"${GRADLE_DIR}/bin/gradle" --init-script "${PWD}/build-mirrors.init.gradle" clean build -Prelease=true --console=plain

echo
echo "=================================================="
echo "  Build finished! The mod jar is:"
echo "=================================================="
ls -1 build/libs/*.jar 2>/dev/null || true
echo
echo "Full path: $(pwd)/build/libs"
