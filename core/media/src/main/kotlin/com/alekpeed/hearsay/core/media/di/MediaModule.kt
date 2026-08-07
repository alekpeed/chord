package com.alekpeed.hearsay.core.media.di

import com.alekpeed.hearsay.core.media.playback.Media3PlaybackController
import com.alekpeed.hearsay.core.model.playback.PlaybackController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {

    /**
     * Features ask for the interface. Swapping Media3 for something else, or for a fake in a test,
     * is a change to this binding and nothing else.
     */
    @Binds
    @Singleton
    abstract fun bindsPlaybackController(impl: Media3PlaybackController): PlaybackController
}
