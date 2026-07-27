package app.roam.core.common

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class IoDispatcher
@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class DefaultDispatcher
@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides @IoDispatcher
    fun io(): CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

    @Provides @DefaultDispatcher
    fun default(): CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default

    @Provides @Singleton @ApplicationScope
    fun appScope(@IoDispatcher d: CoroutineDispatcher): CoroutineScope = CoroutineScope(SupervisorJob() + d)
}
