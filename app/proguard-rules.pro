# GlassInterface ProGuard Rules

# Keep AI Engine interface for Hilt binding
-keep interface com.glassinterface.core.aibridge.AIEngine { *; }

# Keep data models (used in JSON serialization potentially)
-keep class com.glassinterface.core.common.** { *; }

# CameraX
-keep class androidx.camera.** { *; }

# Standard Android rules
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
