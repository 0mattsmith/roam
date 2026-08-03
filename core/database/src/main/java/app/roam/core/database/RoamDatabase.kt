package app.roam.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 6,
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

/**
 * Additive only. A destructive migration here would take loved flags, play
 * counts and last-played times with it -- see invariant 3.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE artists ADD COLUMN artworkAttemptedAt INTEGER")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN userEdited INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE artists ADD COLUMN logoArtworkId TEXT")
        db.execSQL("ALTER TABLE artists ADD COLUMN logoAttemptedAt INTEGER")
        db.execSQL("ALTER TABLE artists ADD COLUMN preferLogo INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE albums ADD COLUMN compilation INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE artists ADD COLUMN sortAs TEXT")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): RoamDatabase =
        Room.databaseBuilder(ctx, RoamDatabase::class.java, "roam.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides fun tracks(db: RoamDatabase) = db.tracks()
    @Provides fun albums(db: RoamDatabase) = db.albums()
    @Provides fun artists(db: RoamDatabase) = db.artists()
    @Provides fun sources(db: RoamDatabase) = db.sources()
    @Provides fun artwork(db: RoamDatabase) = db.artwork()
}
