package com.alekpeed.hearsay

import android.app.Application
import com.alekpeed.hearsay.feature.processing.AnalysisEngine
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HearsayApplication : Application() {

    @Inject lateinit var analysisEngine: AnalysisEngine

    override fun onCreate() {
        super.onCreate()

        // Nothing can be running in a process that has just started, so any job the database still
        // calls active is a leftover from one that died. Clearing it here rather than only on the
        // queue screen matters because the screen showing the stuck job is the project screen, and
        // a user with one analysis stuck has no reason to go looking in the queue for the cure.
        analysisEngine.recoverOrphanedJobs()
    }
}
