package app.roam.data.source

import app.roam.core.model.SourceType
import dagger.MapKey

/**
 * Lets :data:catalog consume providers without depending on the modules that
 * implement them. Each source module contributes @IntoMap; the sync engine
 * injects Map<SourceType, Provider<SourceProvider>> and never imports Drive,
 * SMB or WebDAV directly.
 */
@MapKey
@Retention(AnnotationRetention.RUNTIME)
annotation class SourceTypeKey(val value: SourceType)
