#!/usr/bin/env bash
# Multi-platform build script for MQuickJS native images

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="$PROJECT_DIR/dist"

VERSION="1.0.0"
APP_NAME="mqjs"

echo "=== MQuickJS Native Image Build Script ==="
echo "Project directory: $PROJECT_DIR"
echo "Output directory: $OUTPUT_DIR"

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Function to build for current platform
build_current_platform() {
    echo ""
    echo "Building for current platform..."
    
    cd "$PROJECT_DIR/kotlin"
    
    # Clean and build
    ./gradlew clean jar
    
    # Build native image
    ./gradlew nativeCompile
    
    # Copy the native executable to output directory
    if [ -f "build/native/nativeCompile/$APP_NAME" ]; then
        cp "build/native/nativeCompile/$APP_NAME" "$OUTPUT_DIR/$APP_NAME-$(uname -s)-$(uname -m)"
        echo "Native image created: $OUTPUT_DIR/$APP_NAME-$(uname -s)-$(uname -m)"
    elif [ -f "build/native/nativeCompile/$APP_NAME.exe" ]; then
        cp "build/native/nativeCompile/$APP_NAME.exe" "$OUTPUT_DIR/$APP_NAME-windows-$(uname -m).exe"
        echo "Native image created: $OUTPUT_DIR/$APP_NAME-windows-$(uname -m).exe"
    else
        echo "ERROR: Native image not found!"
        exit 1
    fi
}

# Function to build JAR (platform independent)
build_jar() {
    echo ""
    echo "Building JAR..."
    
    cd "$PROJECT_DIR/kotlin"
    ./gradlew jar
    
    # Copy JAR to output directory
    cp build/libs/*.jar "$OUTPUT_DIR/$APP_NAME-$VERSION.jar"
    echo "JAR created: $OUTPUT_DIR/$APP_NAME-$VERSION.jar"
}

# Parse arguments
BUILD_JAR=false
BUILD_NATIVE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --jar)
            BUILD_JAR=true
            shift
            ;;
        --native)
            BUILD_NATIVE=true
            shift
            ;;
        --all)
            BUILD_JAR=true
            BUILD_NATIVE=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [--jar] [--native] [--all]"
            exit 1
            ;;
    esac
done

# Default to building both if no options specified
if [ "$BUILD_JAR" = false ] && [ "$BUILD_NATIVE" = false ]; then
    BUILD_JAR=true
    BUILD_NATIVE=true
fi

if [ "$BUILD_JAR" = true ]; then
    build_jar
fi

if [ "$BUILD_NATIVE" = true ]; then
    build_current_platform
fi

echo ""
echo "=== Build Complete ==="
echo "Output files in: $OUTPUT_DIR"
ls -la "$OUTPUT_DIR"
