package com.alekpeed.hearsay.core.data.di

import com.alekpeed.hearsay.core.data.repository.AndroidMediaImportRepository
import com.alekpeed.hearsay.core.data.repository.RoomChartRepository
import com.alekpeed.hearsay.core.data.repository.RoomPracticeRepository
import com.alekpeed.hearsay.core.data.repository.RoomProjectRepository
import com.alekpeed.hearsay.core.model.repository.ChartRepository
import com.alekpeed.hearsay.core.model.repository.MediaImportRepository
import com.alekpeed.hearsay.core.model.repository.PracticeRepository
import com.alekpeed.hearsay.core.model.repository.ProjectRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the Room- and Android-backed implementations to the domain interfaces.
 *
 * This module is the only place a feature's dependency on storage is resolved; nothing above it
 * knows Room exists.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindsProjectRepository(impl: RoomProjectRepository): ProjectRepository

    @Binds
    @Singleton
    abstract fun bindsChartRepository(impl: RoomChartRepository): ChartRepository

    @Binds
    @Singleton
    abstract fun bindsPracticeRepository(impl: RoomPracticeRepository): PracticeRepository

    @Binds
    @Singleton
    abstract fun bindsMediaImportRepository(impl: AndroidMediaImportRepository): MediaImportRepository
}
