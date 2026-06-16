#!/bin/bash
# android向けビルドスクリプト。debug/release/cancelを選択し、オプションでインストールも行う。

while true; do
    read -p "debug?release?cancel?(d/r/c) " choice
    case "$choice" in
        d|r ) break ;;
        c ) echo "Cancelled."; exit 0 ;;
        * ) echo "Invalid input. Please enter d, r, or c." ;;
    esac
done

if [ "$choice" = "d" ]; then
    echo "Building Debug APK..."
    GRADLE_TASK="assembleDebug"
    BUILD_TYPE="debug"
else
    echo "Building Release APK..."
    GRADLE_TASK="assembleRelease"
    BUILD_TYPE="release"
fi

if ! ./gradlew "$GRADLE_TASK"; then
    echo "Error: Build failed."
    exit 1
fi
echo "Build successful!"

echo -n "Install to device?(y/n) "
read install
if [ "$install" = "y" ]; then
    INSTALL_TASK="install${BUILD_TYPE^}"
    echo "Installing..."
    if ./gradlew "$INSTALL_TASK"; then
        echo "Install successful!"
    else
        echo "Error: Install failed."
        exit 1
    fi
fi
