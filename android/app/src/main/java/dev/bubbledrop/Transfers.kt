package dev.bubbledrop

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class Transfer(
    val id: String,
    val incoming: Boolean,
    val peerName: String,
    /** `macos` or `android`; picks the device glyph in the transfer card. */
    val peerPlatform: String = "",
    val summary: String,
    val totalBytes: Long,
    val doneBytes: Long = 0,
    val status: Status,
    val savedUris: List<Uri> = emptyList(),
    /** Set when this transfer is a web link rather than files. */
    val link: String? = null,
    /** For links: whether the receiver opened it right away (otherwise it showed a notification). */
    val linkOpened: Boolean = false,
) {
    sealed interface Status {
        data object Waiting : Status
        data object Active : Status
        data object Done : Status
        data class Failed(val message: String) : Status
    }

    val fraction: Float get() = if (totalBytes > 0) doneBytes.toFloat() / totalBytes else 0f
}

class TransferRepository {
    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers

    fun upsert(transfer: Transfer) = _transfers.update { list ->
        if (list.any { it.id == transfer.id }) list.map { if (it.id == transfer.id) transfer else it }
        else (listOf(transfer) + list).take(20)
    }

    fun update(id: String, change: (Transfer) -> Transfer) = _transfers.update { list ->
        list.map { if (it.id == id) change(it) else it }
    }

    fun clearFinished() = _transfers.update { list ->
        list.filter { it.status == Transfer.Status.Active || it.status == Transfer.Status.Waiting }
    }
}

fun describeNames(names: List<String>): String = if (names.size == 1) names[0] else "${names.size} files"
