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
    version = 13,
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

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE artists ADD COLUMN groupArtistId INTEGER")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE artists ADD COLUMN bannerArtworkId TEXT")
    }
}

// Starts null for every existing row, which is the point: it makes artists
// whose logo was already looked up eligible for a banner search for the first
// time. Before this they were excluded forever.
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE artists ADD COLUMN bannerAttemptedAt INTEGER")
    }
}

// "Remove from library" without deleting anything. NOT NULL DEFAULT 0, so every
// existing track stays visible.
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
    }
}

// Playback trim points. Nullable, so "not set" is distinct from "starts at 0".
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN startMs INTEGER")
        db.execSQL("ALTER TABLE tracks ADD COLUMN endMs INTEGER")
    }
}

/**
 * Lyrics, cached per track rather than fetched per play.
 *
 * Three columns because they answer three different questions. `lyrics` is the
 * plain text; `syncedLyrics` is the LRC form WHEN the source had one, which is
 * a different thing from having no lyrics at all; `lyricsAttemptedAt` is what
 * stops a track LRCLIB has never heard of being asked about on every single
 * play. It must be stamped on failure too -- the artist photo pass learned that
 * one the hard way.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN lyrics TEXT")
        db.execSQL("ALTER TABLE tracks ADD COLUMN syncedLyrics TEXT")
        db.execSQL("ALTER TABLE tracks ADD COLUMN lyricsAttemptedAt INTEGER")
    }
}

/**
 * Where the file actually lives, which the crawl always knew and always threw
 * away.
 *
 * Needed to find the .lrc sitting beside a track: the folder cannot be derived
 * from the artist and album TAGS, because a tag and a folder name are allowed
 * to disagree -- that mismatch is exactly why the artist photo pass resolves
 * with create = false. Both are file facts, so sync owns them and refreshes
 * them when a file moves.
 *
 * Null until the next crawl fills them in, so every read has to cope with that.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN fileName TEXT")
        db.execSQL("ALTER TABLE tracks ADD COLUMN folderPath TEXT")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): RoamDatabase =
        Room.databaseBuilder(ctx, RoamDatabase::class.java, "roam.db")
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                MIGRATION_12_13,
            )
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides fun tracks(db: RoamDatabase) = db.tracks()
    @Provides fun albums(db: RoamDatabase) = db.albums()
    @Provides fun artists(db: RoamDatabase) = db.artists()
    @Provides fun sources(db: RoamDatabase) = db.sources()
    @Provides fun artwork(db: RoamDatabase) = db.artwork()
}
