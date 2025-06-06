package com.o7solutions.task.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase


@Database(entities = [ImageEntity::class], version = 1)
abstract class DatabaseDB: RoomDatabase() {

    abstract fun databaseDao(): DatabaseDao


    companion object {


        @Volatile
        private var db: DatabaseDB? = null

        fun getInstance(context: Context): DatabaseDB {

            if (db == null) {
                db = Room.databaseBuilder(
                    context,
                    DatabaseDB::class.java,
                    "image_database"
                )
                    .allowMainThreadQueries()
                    .build()
            }

            return db!!
        }


    }
}