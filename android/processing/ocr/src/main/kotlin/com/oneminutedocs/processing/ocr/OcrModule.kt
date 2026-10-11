package com.oneminutedocs.processing.ocr

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class OcrModule {
    @Binds
    abstract fun ocrEngine(impl: MlKitOcrEngine): OcrEngine
}
