package dev.starlinkguard.data

import dev.starlinkguard.core.detect.Baseline
import dev.starlinkguard.core.detect.MonitorState
import dev.starlinkguard.core.detect.SuppressionReason
import dev.starlinkguard.core.detect.Trigger
import dev.starlinkguard.core.model.DishLocation
import dev.starlinkguard.core.model.DishStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Everything the dashboard shows. */
data class MonitorUiState(
    val state: MonitorState = MonitorState.DISARMED,
    val serviceRunning: Boolean = false,
    val lastStatus: DishStatus? = null,
    val lastLocation: DishLocation? = null,
    val lastPollAtMs: Long = 0L,
    val lastSuccessfulPollAtMs: Long = 0L,
    val lastError: String? = null,
    val baseline: Baseline? = null,
    val suppression: SuppressionReason = SuppressionReason.NONE,
    val activeTriggers: List<Trigger> = emptyList(),
    val alarmSounding: Boolean = false,
    /** False once the dish has refused a location request, so the UI can explain why. */
    val locationAvailable: Boolean = true,
)

/**
 * The single place the service publishes state and the UI reads it.
 *
 * A process-wide object rather than a bound service: the UI only ever observes, and this keeps
 * the activity working the same whether the service is running, restarting, or has just been
 * killed by the system.
 */
object MonitorRepository {

    private val _uiState = MutableStateFlow(MonitorUiState())
    val uiState: StateFlow<MonitorUiState> = _uiState.asStateFlow()

    fun update(transform: (MonitorUiState) -> MonitorUiState) = _uiState.update(transform)

    fun reset() {
        _uiState.value = MonitorUiState()
    }
}
