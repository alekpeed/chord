package com.alekpeed.hearsay.core.database.di

import android.content.Context
import androidx.room.Room
import com.alekpeed.hearsay.core.database.HearsayDatabase
import com.alekpeed.hearsay.core.database.dao.AnalysisDao
import com.alekpeed.hearsay.core.database.dao.ChartDao
import com.alekpeed.hearsay.core.database.dao.EarTrainingDao
import com.alekpeed.hearsay.core.database.dao.PracticeDao
import com.alekpeed.hearsay.core.database.dao.ProjectDao
import com.alekpeed.hearsay.core.database.dao.RevisionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun providesDatabase(@ApplicationContext context: Context): HearsayDatabase =
        Room.databaseBuilder(context, HearsayDatabase::class.java, HearsayDatabase.Name)
            // No destructive fallback. A user's corrections are not disposable, so a missing
            // migration must fail loudly in development rather than silently wipe a library.
            .addMigrations(HearsayDatabase.Migration1To2, HearsayDatabase.Migration2To3)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides
    fun providesProjectDao(database: HearsayDatabase): ProjectDao = database.projectDao()

    @Provides
    fun providesChartDao(database: HearsayDatabase): ChartDao = database.chartDao()

    @Provides
    fun providesRevisionDao(database: HearsayDatabase): RevisionDao = database.revisionDao()

    @Provides
    fun providesPracticeDao(database: HearsayDatabase): PracticeDao = database.practiceDao()

    @Provides
    fun providesAnalysisDao(database: HearsayDatabase): AnalysisDao = database.analysisDao()

    @Provides
    fun providesEarTrainingDao(database: HearsayDatabase): EarTrainingDao = database.earTrainingDao()
}
