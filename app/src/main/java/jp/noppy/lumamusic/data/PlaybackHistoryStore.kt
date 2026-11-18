package jp.noppy.lumamusic.data

import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** SAF document IDs are independent of the tree grant used to access them. */
internal fun stableTrackId(trackUri: String): String {
    val uri = Uri.parse(trackUri)
    if (uri.scheme != "content") return trackUri
    val authority = uri.authority ?: return trackUri
    val documentId = try {
        DocumentsContract.getDocumentId(uri)
    } catch (_: IllegalArgumentException) {
        return trackUri
    }
    return "document:${Uri.encode(authority)}:${Uri.encode(documentId)}"
}

/** Persist transport events in order, even if the playback service releases its player immediately after. */
internal class PlaybackHistoryStore(private val dao: LibraryDao) {
    private sealed interface Command {
        data class Record(val update: PlaybackUpdate) : Command
        data class Clear(val finished: CompletableDeferred<Unit>) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutableGeneration = MutableStateFlow(0L)
    val generation = mutableGeneration.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (command in commands) {
                when (command) {
                    is Command.Record -> {
                        val update = command.update
                        try {
                            dao.recordPlayback(stableTrackId(update.trackUri), update)
                        } catch (error: Exception) {
                            Log.e("LumaHistory", "Playback statistics could not be saved", error)
                        }
                    }
                    is Command.Clear -> {
                        try {
                            dao.clearHistory()
                            command.finished.complete(Unit)
                        } catch (error: Exception) {
                            command.finished.completeExceptionally(error)
                        }
                    }
                }
            }
        }
    }

    fun record(update: PlaybackUpdate?) {
        if (update != null) commands.trySend(Command.Record(update))
    }

    // Requests originate on the main thread, as do service callbacks.
    suspend fun clear() {
        val finished = CompletableDeferred<Unit>()
        commands.send(Command.Clear(finished))
        mutableGeneration.value++
        finished.await()
    }
}
