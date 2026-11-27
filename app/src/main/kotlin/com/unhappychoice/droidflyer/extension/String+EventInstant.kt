package com.unhappychoice.droidflyer.extension

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private val eventTimestamp = Regex("""(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(?:Z|\+00:00)?""")

// bitFlyer's event_date has un-padded, variable-length fractional seconds, so raw strings
// cannot be compared lexicographically to determine which event happened later.
fun String.toEventInstantNanos(): Long {
    val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
    val parts = requireNotNull(eventTimestamp.matchEntire(this))
    val millis = requireNotNull(utc.parse(parts.groupValues[1])).time
    val nanos = parts.groupValues[2].padEnd(9, '0').toLong()
    return millis * 1_000_000 + nanos
}
