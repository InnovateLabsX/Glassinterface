package com.glassinterface.core.aibridge.di

import android.content.Context
import com.glassinterface.core.aibridge.AIEngine
import com.glassinterface.core.aibridge.engine.LocalAIEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AIBridgeModule {

    @Provides
    @Singleton
    fun provideAIEngine(@ApplicationContext context: Context): AIEngine {
        return LocalAIEngine(context)
    }
}
