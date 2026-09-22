package com.echoflow.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// --- Entities ---

@Entity(tableName = "workflow_history")
data class WorkflowHistoryEntity(
    @PrimaryKey
    val id: String,
    val originalInput: String,
    val requestCreatedAt: Long = System.currentTimeMillis(),
    val deviceTimezone: String = java.time.ZoneId.systemDefault().id,
    val createdAt: Long,
    val aiEngine: String,
    val overallConfidence: Float,
    val executionStatus: String // ALL_SUCCESS, PARTIAL_SUCCESS, FAILED, PENDING
)

@Entity(
    tableName = "action_history",
    foreignKeys = [
        ForeignKey(
            entity = WorkflowHistoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["workflowId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["workflowId"])]
)
data class ActionHistoryEntity(
    @PrimaryKey
    val id: String,
    val workflowId: String,
    val actionType: String,
    val title: String,
    val description: String?,
    val recipient: String?,
    val recipientPhone: String? = null,
    val executionMode: String? = null,
    val timeExpression: String? = null,
    val requestedTime: String? = null,
    val resolvedTime: String? = null,
    val resolvedEpochMillis: Long? = null,
    val dateTime: String?, // ISO-8601 formatted string
    val durationMinutes: Int?,
    val message: String?,
    val confidence: Float,
    val executionState: String,
    val executionMessage: String?,
    val executionStartedAt: Long? = null,
    val executionFinishedAt: Long? = null,
    val driftMs: Long? = null,
    val createdAt: Long
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long
)

// --- Relations ---

data class WorkflowWithActions(
    @Embedded
    val workflow: WorkflowHistoryEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "workflowId"
    )
    val actions: List<ActionHistoryEntity>
)

// --- DAOs ---

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkflow(workflow: WorkflowHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActions(actions: List<ActionHistoryEntity>)

    @Transaction
    suspend fun insertWorkflowWithActions(workflow: WorkflowHistoryEntity, actions: List<ActionHistoryEntity>) {
        insertWorkflow(workflow)
        insertActions(actions)
    }

    @Transaction
    @Query("SELECT * FROM workflow_history ORDER BY createdAt DESC")
    fun getAllWorkflowsWithActions(): Flow<List<WorkflowWithActions>>

    @Transaction
    @Query("SELECT * FROM workflow_history WHERE id = :id LIMIT 1")
    suspend fun getWorkflowWithActionsById(id: String): WorkflowWithActions?

    @Query("SELECT * FROM action_history WHERE id = :actionId LIMIT 1")
    suspend fun getActionById(actionId: String): ActionHistoryEntity?

    @Query("SELECT * FROM action_history WHERE executionState IN ('PENDING', 'SCHEDULED', 'PREPARED', 'WAITING_FOR_USER', 'DETECTED', 'READY', 'EXECUTING', 'IN_PROGRESS') ORDER BY resolvedEpochMillis ASC, createdAt ASC")
    suspend fun getPendingActions(): List<ActionHistoryEntity>

    @Query("UPDATE action_history SET executionState = :state, executionMessage = :message, executionFinishedAt = :finishedAt WHERE id = :actionId")
    suspend fun updateActionExecutionState(actionId: String, state: String, message: String?, finishedAt: Long? = System.currentTimeMillis())

    @Query("UPDATE action_history SET resolvedEpochMillis = :newEpochMillis, requestedTime = :requestedTime, resolvedTime = :resolvedTime, executionState = 'SCHEDULED' WHERE id = :actionId")
    suspend fun updateActionSchedule(actionId: String, newEpochMillis: Long, requestedTime: String?, resolvedTime: String?)

    @Query("UPDATE action_history SET driftMs = :driftMs WHERE id = :actionId")
    suspend fun updateActionDrift(actionId: String, driftMs: Long)

    @Query("UPDATE action_history SET executionState = 'CANCELLED', executionMessage = 'Cancelled by user', executionFinishedAt = :finishedAt WHERE id = :actionId")
    suspend fun cancelAction(actionId: String, finishedAt: Long = System.currentTimeMillis())

    @Query("UPDATE action_history SET executionState = 'CANCELLED', executionMessage = 'Cancelled in workflow', executionFinishedAt = :finishedAt WHERE executionState IN ('PENDING', 'SCHEDULED', 'PREPARED', 'WAITING_FOR_USER', 'DETECTED', 'READY')")
    suspend fun cancelAllPendingActions(finishedAt: Long = System.currentTimeMillis())

    @Query("UPDATE workflow_history SET executionStatus = :status WHERE id = :workflowId")
    suspend fun updateWorkflowStatus(workflowId: String, status: String)

    @Query("DELETE FROM workflow_history WHERE id = :workflowId")
    suspend fun deleteWorkflow(workflowId: String)

    @Query("DELETE FROM workflow_history")
    suspend fun clearAll()
}

@Dao
interface NoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity)

    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun getAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getNoteById(id: String): NoteEntity?

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: String)
}

// --- Migrations ---

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE action_history ADD COLUMN driftMs INTEGER DEFAULT NULL")
    }
}

// --- Database ---

@Database(
    entities = [
        WorkflowHistoryEntity::class,
        ActionHistoryEntity::class,
        NoteEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class EchoFlowDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile
        private var INSTANCE: EchoFlowDatabase? = null

        fun getInstance(context: android.content.Context): EchoFlowDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EchoFlowDatabase::class.java,
                    "echoflow.db"
                ).addMigrations(MIGRATION_3_4).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
