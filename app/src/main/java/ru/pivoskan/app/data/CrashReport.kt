package ru.pivoskan.app.data

object CrashReport {
    /** Exception messages can contain URLs, tokens or addresses: store types and frames only. */
    fun format(error: Throwable): String = buildString {
        var current: Throwable? = error
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        repeat(6) {
            val cause = current ?: return@buildString
            if (!seen.add(cause)) return@buildString
            appendLine(cause.javaClass.name.take(200))
            cause.stackTrace.take(30).forEach { appendLine(it.toString().take(300)) }
            current = cause.cause
        }
    }.take(15000)
}
