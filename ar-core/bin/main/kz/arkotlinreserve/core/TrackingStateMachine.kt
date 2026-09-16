package kz.arkotlinreserve.core

class TrackingStateMachine(private var graceMillis: Long) {
    private var activeMarkerId: Int? = null
    private var lastSeenMillis: Long = 0
    private var everTracked = false

    init { require(graceMillis in 0..5_000) }

    fun updateGracePeriod(value: Long) {
        require(value in 0..5_000)
        graceMillis = value
    }

    fun observed(markerId: Int, nowMillis: Long): TrackingState {
        activeMarkerId = markerId
        lastSeenMillis = nowMillis
        everTracked = true
        return TrackingState.Tracking(markerId)
    }

    fun missed(nowMillis: Long): TrackingState {
        val id = activeMarkerId ?: return if (everTracked) TrackingState.Lost else TrackingState.Searching
        val elapsed = (nowMillis - lastSeenMillis).coerceAtLeast(0)
        return if (elapsed <= graceMillis) TrackingState.TemporarilyLost(id, elapsed) else {
            activeMarkerId = null
            TrackingState.Lost
        }
    }

    fun reset() {
        activeMarkerId = null
        lastSeenMillis = 0
        everTracked = false
    }
}
