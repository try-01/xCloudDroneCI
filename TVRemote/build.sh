#!/bin/bash
# TV Remote - Build Script
# Builds a debug APK using Gradle wrapper

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== TV Remote Build Script ==="
echo ""

# Check for Java
if ! command -v java &>/dev/null; then
    echo "ERROR: Java not found. Install JDK 11+ and ensure 'java' is in PATH."
    echo "  Ubuntu/Debian: sudo apt install openjdk-17-jdk"
    echo "  macOS: brew install openjdk@17"
    echo "  Windows: Download from https://adoptium.net/"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
echo "Java version: $JAVA_VERSION"

if [ "$JAVA_VERSION" -lt 11 ]; then
    echo "ERROR: JDK 11 or higher required. Found JDK $JAVA_VERSION"
    exit 1
fi

# Check for ANDROID_HOME
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    echo ""
    echo "WARNING: ANDROID_HOME not set."
    echo "  Set it to your Android SDK path, e.g.:"
    echo "    export ANDROID_HOME=\$HOME/Android/Sdk"
    echo "    export ANDROID_SDK_ROOT=\$HOME/Android/Sdk"
    echo ""
    
    # Try common paths
    for path in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "/opt/android-sdk" "$ANDROID_SDK_ROOT"; do
        if [ -d "$path" ]; then
            export ANDROID_HOME="$path"
            echo "Found SDK at: $path"
            break
        fi
    done
    
    if [ -z "$ANDROID_HOME" ]; then
        echo "ERROR: Android SDK not found. Install via Android Studio or sdkmanager."
        exit 1
    fi
fi

echo "Android SDK: $ANDROID_HOME"
echo ""

# Generate Gradle wrapper if not present
if [ ! -f "gradlew" ]; then
    echo "Generating Gradle wrapper..."
    if command -v gradle &>/dev/null; then
        gradle wrapper --gradle-version 8.11.1
    else
        echo "Gradle not found. Downloading wrapper manually..."
        mkdir -p gradle/wrapper
        WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
        if [ ! -f "$WRAPPER_JAR" ]; then
            curl -sL "https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar" -o "$WRAPPER_JAR"
        fi
        
        cat > gradlew << 'WRAPPER'
#!/bin/sh
APP_BASE_NAME=$(basename "$0")
APP_HOME=$(cd "$(dirname "$0")" && pwd)
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
exec java $JAVA_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
WRAPPER
        chmod +x gradlew
    fi
fi

echo "Building debug APK..."
echo ""

# Build
./gradlew assembleDebug --no-daemon

echo ""
echo "=== Build Complete ==="
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
    echo "Debug APK: $APK_PATH"
    echo "Size: $(du -h "$APK_PATH" | cut -f1)"
    echo ""
    echo "To install on connected device:"
    echo "  adb install $APK_PATH"
else
    echo "APK not found at expected path. Check build output above."
fi
