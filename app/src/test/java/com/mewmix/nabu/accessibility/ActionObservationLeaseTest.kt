package com.mewmix.nabu.accessibility

import org.junit.Assert.assertTrue
import org.junit.Test

class ActionObservationLeaseTest {
    private val observed = ActionObservationAuthority(
        observationId = "observation-1",
        packageName = "org.example",
        windowId = 7,
        stateFingerprint = "fingerprint-1",
        rotation = 0,
        displayWidth = 1080,
        displayHeight = 2400
    )

    @Test
    fun `exact observation is authorized once`() {
        val lease = ActionObservationLease().apply { bind(observed) }

        assertTrue(lease.consume(observed) is ActionLeaseValidation.Authorized)
        assertRejected(lease.consume(observed))
    }

    @Test
    fun `random observation id is rejected and consumes lease`() {
        val lease = ActionObservationLease().apply { bind(observed) }

        assertRejected(lease.consume(observed.copy(observationId = "random")))
        assertRejected(lease.consume(observed))
    }

    @Test
    fun `same package with different fingerprint is rejected`() {
        val lease = ActionObservationLease().apply { bind(observed) }

        assertRejected(lease.consume(observed.copy(stateFingerprint = "fingerprint-2")))
    }

    @Test
    fun `changed package is rejected`() {
        val lease = ActionObservationLease().apply { bind(observed) }

        assertRejected(lease.consume(observed.copy(packageName = "org.other")))
    }

    @Test
    fun `changed window is rejected`() {
        val lease = ActionObservationLease().apply { bind(observed) }

        assertRejected(lease.consume(observed.copy(windowId = 8)))
    }

    @Test
    fun `changed rotation is rejected`() {
        val lease = ActionObservationLease().apply { bind(observed) }

        assertRejected(lease.consume(observed.copy(rotation = 1)))
    }

    @Test
    fun `changed display geometry is rejected`() {
        val lease = ActionObservationLease().apply { bind(observed) }

        assertRejected(lease.consume(observed.copy(displayWidth = 2400, displayHeight = 1080)))
    }

    @Test
    fun `cleared lease is rejected`() {
        val lease = ActionObservationLease().apply {
            bind(observed)
            clear()
        }

        assertRejected(lease.consume(observed))
    }

    @Test
    fun `event drift invalidates lease without trusting event observation id`() {
        val lease = ActionObservationLease().apply { bind(observed) }

        lease.invalidateIfDrifted(
            observed.copy(observationId = "event-snapshot", stateFingerprint = "fingerprint-2")
        )

        assertRejected(lease.consume(observed))
    }

    @Test
    fun `duplicate event does not invalidate lease`() {
        val lease = ActionObservationLease().apply { bind(observed) }

        lease.invalidateIfDrifted(observed.copy(observationId = "event-snapshot"))

        assertTrue(lease.consume(observed) is ActionLeaseValidation.Authorized)
    }

    private fun assertRejected(result: ActionLeaseValidation) {
        assertTrue(result is ActionLeaseValidation.Rejected)
    }
}
