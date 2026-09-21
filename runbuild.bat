@echo off
cd /d C:/Colgram/Telegram-Src
set JAVA_HOME=C:/colgram-tools/jdk-17.0.20.1+1
set ANDROID_HOME=C:/android-sdk
"C:/colgram-tools/jdk-17.0.20.1+1/bin/java.exe" -cp "gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain --console=plain :TMessagesProj_AppStandalone:assembleAfatRelease > C:/Colgram/build5.log 2>&1
echo EXITCODE=%ERRORLEVEL% >> C:/Colgram/build5.log
