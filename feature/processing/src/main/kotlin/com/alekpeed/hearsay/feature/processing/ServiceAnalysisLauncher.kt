package com.alekpeed.hearsay.feature.processing

import android.content.Context
import com.alekpeed.hearsay.core.model.analysis.AnalysisLauncher
import com.alekpeed.hearsay.core.model.project.AnalysisProfile
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Starts the foreground service, so the run survives the screen it was started from. */
@Singleton
class ServiceAnalysisLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) : AnalysisLauncher {

    override fun start(projectId: String, profile: AnalysisProfile) =
        AnalysisService.start(context, projectId, profile)

    override fun cancel(projectId: String) = AnalysisService.cancel(context, projectId)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ProcessingModule {
    @Binds
    @Singleton
    abstract fun bindsAnalysisLauncher(impl: ServiceAnalysisLauncher): AnalysisLauncher
}
