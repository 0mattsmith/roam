package app.roam.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        SourceEntity::class, ArtistEntity::class, AlbumEntity::class,
        TrackEntity::class, ArtworkEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(RoamConverters::class)
abstract class RoamDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun albums(): AlbumDao
    abstract fun artists(): ArtistDao
    abstract fun sources(): SourceDao
    abstract fun artwork(): ArtworkDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): RoamDatabase =
        Room.databaseBuilder(ctx, RoamDatabase::class.java, "roam.db")
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides fun tracks(db: RoamDatabase) = db.tracks()
    @Provides fun albums(db: RoamDatabase) = db.albums()
    @Provides fun artists(db: RoamDatabase) = db.artists()
    @Provides fun sources(db: RoamDatabase) = db.sources()
    @Provides fun artwork(db: RoamDatabase) = db.artwork()
}
