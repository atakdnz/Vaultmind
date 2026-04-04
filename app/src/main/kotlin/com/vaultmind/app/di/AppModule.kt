package com.vaultmind.app.di

import android.content.Context
import com.vaultmind.app.ingestion.TextChunker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DI module for app-level singletons.
 *
 * Most components are @Singleton annotated directly and injected via constructor injection.
 * Only types that can't be annotated (third-party, primitives) go here.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * TextChunker is stateless and has configurable parameters.
     * Default parameters from the plan: 256 tokens, 40 overlap.
     */
    @Provides
    @Singleton
    fun provideTextChunker(): TextChunker = TextChunker(
        targetTokens = 256,
        overlapTokens = 40
    )
}
