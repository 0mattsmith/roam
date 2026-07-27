package app.roam.core.database

import androidx.room.TypeConverter
import app.roam.core.model.ArtworkSource
import app.roam.core.model.SourceType
import app.roam.core.model.TagState

class RoamConverters {
    @TypeConverter fun sourceType(v: SourceType): String = v.name
    @TypeConverter fun toSourceType(v: String): SourceType = SourceType.valueOf(v)

    @TypeConverter fun tagState(v: TagState): String = v.name
    @TypeConverter fun toTagState(v: String): TagState = TagState.valueOf(v)

    @TypeConverter fun artSource(v: ArtworkSource): String = v.name
    @TypeConverter fun toArtSource(v: String): ArtworkSource = ArtworkSource.valueOf(v)
}
