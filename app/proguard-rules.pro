# Crash reports send the root cause's class name as exceptionType (telemetry/README.md).
-keepnames class * extends java.lang.Throwable
