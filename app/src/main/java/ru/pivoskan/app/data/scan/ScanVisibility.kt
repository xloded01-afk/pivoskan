package ru.pivoskan.app.data.scan

enum class VisibilityTransition { VISIBLE, HIDDEN }

/** Configuration gaps pause requests without pretending the user reopened the app. */
class ScanVisibility(private val transition: (VisibilityTransition) -> Unit,
    private val requestsChanged: (Boolean) -> Unit = {}) {
    private val started = mutableSetOf<String>()
    private var configurationGap = false
    private var interactive = true
    private var visible = false
    var canRequest = false
        private set
    fun activityStarted(id: String) { started += id; configurationGap = false; update() }
    fun activityStopped(id: String, changingConfiguration: Boolean) {
        started -= id
        if (started.isEmpty()) configurationGap = changingConfiguration
        update()
    }
    fun screenInteractive(value: Boolean) { interactive = value; update() }
    private fun update() {
        val allowed = interactive && started.isNotEmpty()
        if (allowed != canRequest) { canRequest = allowed; requestsChanged(allowed) }
        val next = interactive && (started.isNotEmpty() || configurationGap)
        if (next != visible) { visible = next; transition(if (next) VisibilityTransition.VISIBLE else VisibilityTransition.HIDDEN) }
    }
}
