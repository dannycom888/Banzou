package com.dannycom888.banzou

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.dannycom888.banzou.audio.Separator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

enum class EntryStatus { WAITING, RUNNING, DONE, FAILED, CANCELLED }

data class QueueEntry(
    val id: Long,
    val item: SongItem,
    val status: EntryStatus = EntryStatus.WAITING,
    val progress: Float = 0f,
    val stage: String = "",
    val error: String? = null,
)

data class QueueState(
    val entries: List<QueueEntry> = emptyList(),
    val running: Boolean = false,
) {
    val doneCount: Int get() = entries.count { it.status == EntryStatus.DONE }
    val failedEntries: List<QueueEntry> get() = entries.filter { it.status == EntryStatus.FAILED }
    val overallProgress: Float
        get() = if (entries.isEmpty()) 0f
        else entries.sumOf {
            when (it.status) {
                EntryStatus.DONE -> 1.0
                EntryStatus.RUNNING -> it.progress.toDouble()
                else -> 0.0
            }
        }.toFloat() / entries.size
}

/**
 * Sequential processing queue: one song at a time (the phone cannot take
 * more), with per-song and overall progress, cancel, and retry of the
 * failed ones. The heavy work runs under [QueueService] (foreground).
 */
object QueueManager {
    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state

    private val cancelRequested = AtomicBoolean(false)
    private var job: Job? = null
    private var nextId = 1L

    fun enqueue(context: Context, items: List<SongItem>) {
        val fresh = items.filter { !it.hasAccompaniment }.map { QueueEntry(nextId++, it) }
        if (fresh.isEmpty()) return
        _state.update { old ->
            val kept = old.entries.filter {
                it.status == EntryStatus.RUNNING || it.status == EntryStatus.WAITING
            }
            old.copy(entries = kept + fresh)
        }
        startServiceIfNeeded(context)
    }

    fun retryFailed(context: Context) {
        _state.update { old ->
            old.copy(entries = old.entries.map {
                if (it.status == EntryStatus.FAILED) {
                    it.copy(status = EntryStatus.WAITING, progress = 0f, stage = "", error = null)
                } else {
                    it
                }
            })
        }
        startServiceIfNeeded(context)
    }

    fun cancelAll() {
        cancelRequested.set(true)
    }

    private fun startServiceIfNeeded(context: Context) {
        if (job?.isActive == true) return
        val intent = Intent(context, QueueService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    /** Called by QueueService. Runs until the queue drains or is cancelled. */
    fun runLoop(context: Context, scope: CoroutineScope): Job {
        job?.let { if (it.isActive) return it }
        cancelRequested.set(false)
        val newJob = scope.launch(Dispatchers.Default) {
            _state.update { it.copy(running = true) }
            var separator: Separator? = null
            try {
                separator = Separator(SongProcessor.modelFile(context))
                while (true) {
                    if (cancelRequested.get()) {
                        _state.update { old ->
                            old.copy(entries = old.entries.map {
                                if (it.status == EntryStatus.WAITING) {
                                    it.copy(status = EntryStatus.CANCELLED)
                                } else {
                                    it
                                }
                            })
                        }
                        break
                    }
                    val next = _state.value.entries.firstOrNull { it.status == EntryStatus.WAITING }
                        ?: break
                    processOne(context, separator, next)
                }
            } catch (e: Exception) {
                _state.update { old ->
                    old.copy(entries = old.entries.map {
                        if (it.status == EntryStatus.RUNNING) {
                            it.copy(status = EntryStatus.FAILED, error = e.message ?: "未知错误")
                        } else {
                            it
                        }
                    })
                }
            } finally {
                separator?.close()
                _state.update { it.copy(running = false) }
            }
        }
        job = newJob
        return newJob
    }

    private fun processOne(context: Context, separator: Separator, entry: QueueEntry) {
        _state.update { old ->
            old.copy(entries = old.entries.map {
                if (it.id == entry.id) {
                    it.copy(status = EntryStatus.RUNNING, progress = 0f, stage = "准备中")
                } else {
                    it
                }
            })
        }
        try {
            SongProcessor.process(
                context = context,
                separator = separator,
                item = entry.item,
                isCancelled = { cancelRequested.get() },
                onProgress = { frac, stage ->
                    _state.update { old ->
                        old.copy(entries = old.entries.map {
                            if (it.id == entry.id) it.copy(progress = frac, stage = stage) else it
                        })
                    }
                },
            )
            _state.update { old ->
                old.copy(entries = old.entries.map {
                    if (it.id == entry.id) {
                        it.copy(status = EntryStatus.DONE, progress = 1f, stage = "完成")
                    } else {
                        it
                    }
                })
            }
        } catch (e: InterruptedException) {
            _state.update { old ->
                old.copy(entries = old.entries.map {
                    if (it.id == entry.id) it.copy(status = EntryStatus.CANCELLED, stage = "已取消") else it
                })
            }
        } catch (e: Exception) {
            if (cancelRequested.get()) {
                _state.update { old ->
                    old.copy(entries = old.entries.map {
                        if (it.id == entry.id) it.copy(status = EntryStatus.CANCELLED, stage = "已取消") else it
                    })
                }
            } else {
                _state.update { old ->
                    old.copy(entries = old.entries.map {
                        if (it.id == entry.id) {
                            it.copy(status = EntryStatus.FAILED, error = e.message ?: e.javaClass.simpleName)
                        } else {
                            it
                        }
                    })
                }
            }
        }
    }
}
