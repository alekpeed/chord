package com.alekpeed.hearsay.core.common.dispatchers

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

enum class HearsayDispatcher { Default, IO, Decode }

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val dispatcher: HearsayDispatcher)

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @Provides
    @Dispatcher(HearsayDispatcher.Default)
    fun providesDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Dispatcher(HearsayDispatcher.IO)
    fun providesIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * Audio decoding and, later, inference run here on a bounded pool.
     *
     * The bound matters: analysis must never be able to starve playback, so it is capped below the
     * core count rather than being allowed to take the whole default pool.
     */
    @Provides
    @Singleton
    @Dispatcher(HearsayDispatcher.Decode)
    fun providesDecodeDispatcher(): CoroutineDispatcher {
        val parallelism = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
        return Dispatchers.Default.limitedParallelism(parallelism, name = "decode")
    }

    @Provides
    @Singleton
    @ApplicationScope
    fun providesApplicationScope(
        @Dispatcher(HearsayDispatcher.Default) dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}
