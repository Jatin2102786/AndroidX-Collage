package com.o7solutions.task.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface DatabaseDao {

    @Insert
    fun insertImage(imageEntity: ImageEntity)

    @Query("SELECT * FROM image_table")
    fun getAllImages(): List<ImageEntity>

}