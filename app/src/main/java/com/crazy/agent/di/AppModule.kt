package com.crazy.agent.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt module for providing dependencies */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    // Classes with @Inject constructor are automatically provided by Hilt.
    // No explicit @Provides methods needed for CommandParser, AgentPlanBuilder, ActionExecutor, or
    // GeminiClient.
}
