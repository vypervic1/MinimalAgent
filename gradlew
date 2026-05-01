#!/bin/sh
exec java -cp "$(cd "$(dirname "$0")" && pwd)/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
