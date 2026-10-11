package com.oneminutedocs.app.common

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter

fun formatSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

fun formatDate(context: Context, millis: Long): String =
    DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
