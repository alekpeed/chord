package com.alekpeed.hearsay.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.alekpeed.hearsay.core.database.dao.ChartDao
import com.alekpeed.hearsay.core.database.dao.PracticeDao
import com.alekpeed.hearsay.core.database.dao.ProjectDao
import com.alekpeed.hearsay.core.database.dao.RevisionDao
import com.alekpeed.hearsay.core.database.entity.BeatEventEntity
import com.alekpeed.hearsay.core.database.entity.ChordEventEntity
import com.alekpeed.hearsay.core.database.entity.MediaAssetEntity
import com.alekpeed.hearsay.core.database.entity.ProjectEntity
import com.alekpeed.hearsay.core.database.entity.RevisionEntity
import com.alekpeed.hearsay.core.database.entity.SavedLoopEntity
import com.alekpeed.hearsay.core.database.entity.SectionEntity
import com.alekpeed.hearsay.core.database.entity.TempoSegmentEntity
import kotlinx.serialization.json.Json

/** Stores a list of strings as JSON. Tags are short and few; a join table would cost more than it pays. */
class StringListConverter {
    @TypeConverter
    fun fromJson(value: String?): List<String> =
        if (value.isNullOrEmpty()) emptyList() else Json.decodeFromString(value)

    @TypeConverter
    fun toJson(value: List<String>): String = Json.encodeToString(value)
}

@Database(
    entities = [
        ProjectEntity::class,
        MediaAssetEntity::class,
        RevisionEntity::class,
        ChordEventEntity::class,
        BeatEventEntity::class,
        TempoSegmentEntity::class,
        SectionEntity::class,
        SavedLoopEntity::class,
    ],
    version = HearsayDatabase.Version,
    exportSchema = true,
)
@TypeConverters(StringListConverter::class)
abstract class HearsayDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun chartDao(): ChartDao
    abstract fun revisionDao(): RevisionDao
    abstract fun practiceDao(): PracticeDao

    companion object {
        const val Version = 1
        const val Name = "hearsay.db"
    }
}
