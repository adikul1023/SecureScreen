# Keep app classes that are started from AndroidManifest.
-keep class com.securescreen.app.service.ForegroundService { *; }
-keep class com.securescreen.app.receiver.BootReceiver { *; }
-keep class com.securescreen.app.SecureActivity { *; }
