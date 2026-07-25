package com.mewmix.nabu.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

object UiSnapshotStore {
    private val _currentSnapshot = MutableStateFlow<UiSnapshot?>(null)
    private val sequence = AtomicLong(0L)
    
    /**
     * A continuous flow representing the most recent state of the screen.
     */
    val currentSnapshot = _currentSnapshot.asStateFlow()

    /**
     * Pushes a new snapshot into the store.
     */
    fun updateSnapshot(snapshot: UiSnapshot): UiSnapshot {
        val published = snapshot.copy(sequence = sequence.incrementAndGet())
        _currentSnapshot.value = published
        return published
    }

    suspend fun awaitAfter(sequence: Long, timeoutMs: Long): UiSnapshot? =
        withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            currentSnapshot.filterNotNull().first { it.sequence > sequence }
        }
    
    /**
     * Convenience method to manually clear the snapshot (e.g. on service disconnect)
     */
    fun clear() {
        _currentSnapshot.value = null
    }
}
