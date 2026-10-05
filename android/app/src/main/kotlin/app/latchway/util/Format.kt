package app.latchway.util

import java.util.Locale

object Format {
    fun bytes(n: Long): String {
        if (n < 0) return "unknown size"
        if (n < 1024) return "$n B"
        var v = n.toDouble()
        var unit = 0
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        while (v >= 1024 && unit < units.size - 1) {
            v /= 1024
            unit++
        }
        return String.format(Locale.getDefault(), if (v >= 100) "%.0f %s" else "%.1f %s", v, units[unit])
    }

    fun rate(bytes: Long, millis: Long): String {
        if (millis <= 0) return ""
        return bytes(bytes * 1000 / millis) + "/s"
    }

    fun eta(bytes: Long, total: Long, millis: Long): String? {
        if (total <= 0 || bytes <= 0 || millis <= 0) return null
        val remainingMs = (total - bytes) * millis / bytes
        val s = remainingMs / 1000
        return when {
            s < 60 -> "${s}s left"
            s < 3600 -> "${s / 60}m ${s % 60}s left"
            else -> "${s / 3600}h ${(s % 3600) / 60}m left"
        }
    }

    fun duration(millis: Long): String {
        val s = millis / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }
}
