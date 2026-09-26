package io.ucc.applogic

/**
 * Formats epoch milliseconds as `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` (UTC, proleptic
 * Gregorian) without java.time/SimpleDateFormat so it runs on every target.
 */
internal fun isoUtc(epochMs: Long): String {
    val days = epochMs.floorDiv(86_400_000L)
    val msOfDay = epochMs.mod(86_400_000L)
    // civil-from-days (Howard Hinnant), valid for the whole Long range we care about.
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val year = if (m <= 2) y + 1 else y
    val h = msOfDay / 3_600_000; val min = msOfDay / 60_000 % 60; val sec = msOfDay / 1000 % 60; val milli = msOfDay % 1000
    return "${pad(year, 4)}-${pad(m, 2)}-${pad(d, 2)}T${pad(h, 2)}:${pad(min, 2)}:${pad(sec, 2)}.${pad(milli, 3)}Z"
}

private fun pad(v: Long, width: Int): String = v.toString().padStart(width, '0')
