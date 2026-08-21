package com.mewmix.nabu.accessibility

/**
 * The exact Android state that authorized one physical UI operation.
 *
 * This contains only immutable values. Live AccessibilityNodeInfo instances must never cross the
 * observation boundary.
 */
data class ActionObservationAuthority(
    val observationId: String,
    val packageName: String,
    val windowId: Int,
    val stateFingerprint: String,
    val rotation: Int,
    val displayWidth: Int,
    val displayHeight: Int
)

sealed interface ActionLeaseValidation {
    data object Authorized : ActionLeaseValidation
    data class Rejected(val reason: String) : ActionLeaseValidation
}

/** Single-use, fail-closed custody for the latest planner observation. */
class ActionObservationLease {
    private var authority: ActionObservationAuthority? = null

    @Synchronized
    fun bind(value: ActionObservationAuthority) {
        require(value.observationId.isNotBlank()) { "observationId must not be blank" }
        authority = value
    }

    @Synchronized
    fun clear() {
        authority = null
    }

    @Synchronized
    fun invalidateIfDrifted(current: ActionObservationAuthority) {
        val expected = authority ?: return
        if (!expected.sameStateAs(current, compareObservationId = false)) {
            authority = null
        }
    }

    /**
     * Validates and consumes the authority atomically. A rejection also clears the authority so a
     * caller cannot retry a suspicious request against the same observation.
     */
    @Synchronized
    fun consume(current: ActionObservationAuthority): ActionLeaseValidation {
        val expected = authority
            ?: return ActionLeaseValidation.Rejected("No action observation lease is active.")
        authority = null

        val mismatch = when {
            current.observationId != expected.observationId ->
                "Action observation lease is stale or invalid."
            current.packageName != expected.packageName ->
                "Active package changed since observation (${expected.packageName} -> ${current.packageName})."
            current.windowId != expected.windowId ->
                "Active window changed since observation (${expected.windowId} -> ${current.windowId})."
            current.stateFingerprint != expected.stateFingerprint ->
                "Screen state changed since observation."
            current.rotation != expected.rotation ->
                "Display rotation changed since observation (${expected.rotation} -> ${current.rotation})."
            current.displayWidth != expected.displayWidth || current.displayHeight != expected.displayHeight ->
                "Display geometry changed since observation."
            else -> null
        }
        return mismatch?.let(ActionLeaseValidation::Rejected) ?: ActionLeaseValidation.Authorized
    }

    private fun ActionObservationAuthority.sameStateAs(
        other: ActionObservationAuthority,
        compareObservationId: Boolean
    ): Boolean =
        (!compareObservationId || observationId == other.observationId) &&
            packageName == other.packageName &&
            windowId == other.windowId &&
            stateFingerprint == other.stateFingerprint &&
            rotation == other.rotation &&
            displayWidth == other.displayWidth &&
            displayHeight == other.displayHeight
}
