package com.waz.zclient.roomdb.model


import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "KeyValues")
data class UserPreferenceEntity(

    @ColumnInfo(name = "key")
    @PrimaryKey
    val key: String,

    @ColumnInfo(name = "value")
    val value: String?
)
