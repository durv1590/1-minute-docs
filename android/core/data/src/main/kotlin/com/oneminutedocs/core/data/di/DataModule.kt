package com.oneminutedocs.core.data.di

import android.content.Context
import androidx.room.Room
import com.oneminutedocs.core.data.db.AppDatabase
import com.oneminutedocs.core.data.db.DocumentDao
import com.oneminutedocs.core.data.settings.ClientTokenStore
import com.oneminutedocs.core.data.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "omd.db").build()

    @Provides
    fun documentDao(db: AppDatabase): DocumentDao = db.documents()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindings {
    @Binds
    abstract fun tokenStore(impl: SettingsRepository): ClientTokenStore
}
