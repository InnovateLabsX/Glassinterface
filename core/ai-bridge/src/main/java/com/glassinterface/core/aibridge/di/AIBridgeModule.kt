package com.glassinterface.core.aibridge.di

import com.glassinterface.core.aibridge.AIEngine
import com.glassinterface.core.aibridge.NetworkAIEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AIBridgeModule {

    /**
     * Bind NetworkAIEngine as the AIEngine implementation.
     * Connects to the Python FastAPI server via WebSocket.
     *
     * To switch back to the fake stub for UI-only development,
     * change this to bind FakeAIEngine instead.
     */
    @Binds
    @Singleton
    abstract fun bindAIEngine(impl: NetworkAIEngine): AIEngine
}
