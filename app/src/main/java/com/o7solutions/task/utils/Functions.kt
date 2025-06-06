package com.o7solutions.task.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Functions {

    fun convertMillisToDateTime(millis: Long): String {
        val date = Date(millis)
        val format = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault())
        return format.format(date)
    }
}