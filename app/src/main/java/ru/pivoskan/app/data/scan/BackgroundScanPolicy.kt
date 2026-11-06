package ru.pivoskan.app.data.scan

object BackgroundScanPolicy {
    fun canRequest(visible: Boolean, interactive: Boolean, serviceRunning: Boolean) = serviceRunning || (visible && interactive)
    fun finished(sources: List<SourceProgress>) = sources.isNotEmpty() &&
        sources.none { it.phase == SourcePhase.WAITING || it.phase == SourcePhase.RUNNING }
}
