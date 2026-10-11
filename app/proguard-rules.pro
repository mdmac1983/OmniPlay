# Release builds only remove unused code; names are kept so stack traces stay readable and nothing is renamed.
-dontobfuscate
# Workers and services are created by name (manifest / WorkManager).
-keep class app.orionmd.omniplay.cloud.SyncWorker { *; }
-keep class ai.onnxruntime.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-keep class app.orionmd.ffmpeg.** { *; }
