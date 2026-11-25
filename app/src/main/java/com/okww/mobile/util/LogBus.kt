package com.okww.mobile.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogBus {

    data class Line(val time: String, val text: String)

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    fun i(tag: String, msg: String) = append(tag, msg, Log.INFO)

    fun w(tag: String, msg: String) = append(tag, msg, Log.WARN)

    fun e(tag: String, msg: String) = append(tag, msg, Log.ERROR)

    private fun append(tag: String, msg: String, priority: Int) {
        Log.println(priority, "OKWW/$tag", msg)
        val line = Line(fmt.format(Date()), "[$tag] $msg")
        _lines.update { (it + line).takeLast(200) }
    }
}
