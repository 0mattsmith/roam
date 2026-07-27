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
    fun ioDispatcher(): CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

    // NOT `fun default()`. Dagger emits a Java factory whose method name
    // mirrors this one, and `default` is a Java reserved word -- JavaPoet
    // fails with "not a valid name: default". Same trap for native, package,
    // switch, new, final, static.
    @Provides @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default

    @Provides @Singleton @ApplicationScope
    fun appScope(@IoDispatcher d: CoroutineDispatcher): CoroutineScope = CoroutineScope(SupervisorJob() + d)
}
