package com.oneminutedocs.app.common

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneminutedocs.core.data.files.Workspace
import com.oneminutedocs.core.data.files.WorkspaceManager
import com.oneminutedocs.domain.DocError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Lifecycle of a processing operation as shown to the user. */
sealed interface OpState {
    data object Idle : OpState
    /** [progress] is null when progress cannot be measured (shown as an indeterminate indicator). */
    data class Running(@StringRes val title: Int, val progress: Float? = null, @StringRes val detail: Int? = null) : OpState
    /** Outputs were saved and validated. [notice] carries an optional explanatory message (e.g. layout limitations). */
    data class Done(val documentIds: List<String>, val notice: String? = null) : OpState
    data class Failed(val error: DocError) : OpState
}

/**
 * Base for tool screens: owns a private [Workspace] for the screen's lifetime and runs one operation at a time,
 * mapping failures to [DocError]s. Cancelling stops the coroutine (and therefore engines, which check for
 * cancellation); inputs are never modified.
 */
abstract class OperationViewModel(workspaces: WorkspaceManager) : ViewModel() {
    protected val workspace: Workspace = workspaces.create()

    private val _op = MutableStateFlow<OpState>(OpState.Idle)
    val op: StateFlow<OpState> = _op.asStateFlow()
    private var job: Job? = null

    protected fun runOperation(@StringRes title: Int, block: suspend OperationScope.() -> OpState.Done) {
        if (job?.isActive == true) return
        _op.value = OpState.Running(title)
        job = viewModelScope.launch {
            _op.value = try {
                block(OperationScope { p, detail -> _op.value = OpState.Running(title, p, detail) })
            } catch (t: Throwable) {
                OpState.Failed(t.toDocError())
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _op.value = OpState.Idle
    }

    fun dismissResult() {
        _op.value = OpState.Idle
    }

    override fun onCleared() {
        job?.cancel()
        workspace.close()
    }

    fun interface OperationScope {
        fun report(progress: Float?, @StringRes detail: Int?)
    }
}

fun OperationViewModel.OperationScope.progress(p: Float?) = report(p, null)
