package com.mewmix.nabu.uiagent

/** Monotonic ownership token used to reject callbacks from cancelled or superseded runners. */
internal class ActionRequestOwnership {
    private var epoch = 0L
    private var sessionId: String? = null

    fun begin(newSessionId: String): Long {
        require(newSessionId.isNotBlank())
        epoch += 1
        sessionId = newSessionId
        return epoch
    }

    fun invalidate(expectedSessionId: String) {
        if (sessionId == expectedSessionId) {
            epoch += 1
            sessionId = null
        }
    }

    fun owns(expectedSessionId: String, expectedEpoch: Long): Boolean =
        sessionId == expectedSessionId && epoch == expectedEpoch
}
