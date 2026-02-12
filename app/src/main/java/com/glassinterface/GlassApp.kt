package com.glassinterface

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class for GlassInterface.
 * Annotated with @HiltAndroidApp to trigger Hilt code generation.
 */
@HiltAndroidApp
class GlassApp : Application()
