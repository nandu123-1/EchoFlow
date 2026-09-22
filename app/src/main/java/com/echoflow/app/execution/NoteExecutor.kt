package com.echoflow.app.execution

import android.content.Context
import android.util.Log
import com.echoflow.app.data.db.EchoFlowDatabase
import com.echoflow.app.data.db.NoteEntity
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.domain.model.ParsedAction
import kotlinx.coroutines.flow.Flow

/**
 * Stores notes locally in the Room database (`EchoFlowDatabase`),
 * ensuring true persistence across application and device restarts.
 */
class NoteExecutor(private val context: Context) {

    companion object {
        private const val TAG = "EchoFlow-DB"
    }

    private val noteDao = EchoFlowDatabase.getInstance(context).noteDao()

    suspend fun execute(action: ParsedAction): ExecutionResult {
        val title = action.title.ifBlank { "Untitled Note" }
        val content = action.description ?: action.title
        Log.d(TAG, "[NOTE] Persisting note to Room database: '$title'")

        return try {
            val now = System.currentTimeMillis()
            val note = NoteEntity(
                id = action.id,
                title = title,
                content = content,
                createdAt = now,
                updatedAt = now
            )

            noteDao.insertNote(note)
            Log.d(TAG, "[NOTE] Note successfully written to SQLite/Room. ID=${note.id}")

            ExecutionResult(
                state = ExecutionState.SUCCESS,
                message = "Note saved to Room ('$title')"
            )
        } catch (e: Exception) {
            Log.e(TAG, "[NOTE] Error saving note to Room", e)
            ExecutionResult(
                state = ExecutionState.FAILED,
                message = "Note save failed: ${e.message ?: "Database error"}"
            )
        }
    }

    fun getAllNotes(): Flow<List<NoteEntity>> = noteDao.getAllNotes()

    suspend fun deleteNote(id: String) = noteDao.deleteNote(id)
}
