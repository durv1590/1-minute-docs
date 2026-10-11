package com.oneminutedocs.app.di

import com.oneminutedocs.app.BuildConfig
import com.oneminutedocs.core.network.ApiBaseUrl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Process-wide scope for short work that must outlive a screen (e.g. saving a draft when the editor closes). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** Backend base URL from the build (-Pomd.apiBaseUrl.release=...). Empty means cloud features are unavailable. */
    @Provides
    @ApiBaseUrl
    fun apiBaseUrl(): String = BuildConfig.API_BASE_URL

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
