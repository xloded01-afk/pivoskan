package ru.pivoskan.app.data.scan

/** A task survives process reclamation but a newly opened task has a new identity. */
class AutoScanSession(initialKey: String?, private val save: (String?) -> Unit) {
    private var key = initialKey
    private var previous: String? = null
    fun request(taskId: Int, cityId: String, force: Boolean = false): Boolean {
        require(taskId >= 0 && cityId.isNotBlank())
        val next = "$taskId:$cityId"
        if (!force && key == next) return false
        previous = key
        save(next)
        key = next
        return true
    }
    fun launchFailed() { save(previous); key = previous }
}
