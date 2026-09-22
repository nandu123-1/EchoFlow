package com.echoflow.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echoflow.app.data.db.ActionHistoryEntity
import com.echoflow.app.data.db.WorkflowWithActions
import com.echoflow.app.ui.components.getActionColor
import com.echoflow.app.ui.components.getActionIcon
import com.echoflow.app.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * EchoFlow V5.1 History Screen — Ocean Breeze Ambient Execution Log.
 *
 * Displays chronological execution logs grouped by TODAY, YESTERDAY, EARLIER.
 * Uses truthful outcome badges:
 * - ✓ SENT
 * - ✓ COMPLETED
 * - ↗ HANDED OFF
 * - ◉ WAITING
 * - ✕ FAILED
 * - ↶ CANCELLED
 */
@Composable
fun HistoryScreen(
    workflows: List<WorkflowWithActions>,
    onDeleteWorkflow: (String) -> Unit,
    onRunAgain: (WorkflowWithActions) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedWorkflow by remember { mutableStateOf<WorkflowWithActions?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = OceanBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            Column {
                Text(
                    text = "Execution History",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = OceanPrimary
                )
                Text(
                    text = "Ambient Action Engine Log",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = OceanSecondary
                )
            }

            if (workflows.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = null,
                            modifier = Modifier.size(54.dp),
                            tint = OceanSecondary.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "No workflows executed yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = OceanPrimary
                        )
                        Text(
                            text = "Actions delegated and scheduled from voice or text will be recorded here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = EchoTextSecondary,
                            modifier = Modifier.padding(horizontal = 32.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                // Group by Today, Yesterday, Earlier
                val today = LocalDate.now()
                val yesterday = today.minusDays(1)

                val grouped = remember(workflows) {
                    workflows.groupBy { item ->
                        val date = Instant.ofEpochMilli(item.workflow.createdAt)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                        when {
                            date.isEqual(today) -> "TODAY"
                            date.isEqual(yesterday) -> "YESTERDAY"
                            else -> "EARLIER"
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    grouped.forEach { (header, items) ->
                        item {
                            Text(
                                text = header,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = OceanPrimary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                            )
                        }

                        items(items, key = { it.workflow.id }) { item ->
                            HistoryItemCard(
                                item = item,
                                onClick = { selectedWorkflow = item }
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(40.dp))
                    }
                }
            }
        }
    }

    // Detail Dialog
    selectedWorkflow?.let { wf ->
        HistoryDetailDialog(
            workflowWithActions = wf,
            onDismiss = { selectedWorkflow = null },
            onDelete = {
                onDeleteWorkflow(wf.workflow.id)
                selectedWorkflow = null
            },
            onRunAgain = {
                onRunAgain(wf)
                selectedWorkflow = null
            }
        )
    }
}

@Composable
fun HistoryItemCard(
    item: WorkflowWithActions,
    onClick: () -> Unit
) {
    val timeFormatted = remember(item.workflow.createdAt) {
        val dt = Instant.ofEpochMilli(item.workflow.createdAt).atZone(ZoneId.systemDefault())
        dt.format(DateTimeFormatter.ofPattern("h:mm a"))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = OceanSurface),
        border = BorderStroke(1.dp, OceanCardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = OceanSecondary.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = item.workflow.aiEngine,
                        style = MaterialTheme.typography.labelSmall,
                        color = OceanPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Text(
                    text = timeFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = EchoTextTertiary
                )
            }

            Text(
                text = "\"${item.workflow.originalInput}\"",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = EchoTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Truthful Summary Row (No false FAILED)
            val actions = item.actions
            val completedCount = actions.count { it.executionState in listOf("SUCCESS", "COMPLETED", "PREPARED") }
            val scheduledCount = actions.count { it.executionState == "SCHEDULED" }
            val waitingCount = actions.count { it.executionState in listOf("WAITING_FOR_USER", "NEEDS_CONFIRMATION") }
            val failedCount = actions.count { it.executionState == "FAILED" }

            val (statusText, statusColor) = when {
                completedCount == actions.size -> "All completed" to EchoSuccess
                scheduledCount == actions.size -> "Scheduled" to OceanSecondary
                waitingCount > 0 -> "Needs attention" to EchoWarning
                completedCount > 0 && failedCount == 0 -> "$completedCount completed" to EchoSuccess
                failedCount == actions.size -> "Failed" to EchoError
                failedCount > 0 -> "$failedCount failed" to EchoError
                else -> "${actions.size} action(s)" to EchoTextSecondary
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${actions.size} action${if (actions.size > 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = EchoTextSecondary
                )

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryDetailDialog(
    workflowWithActions: WorkflowWithActions,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onRunAgain: () -> Unit
) {
    val wf = workflowWithActions.workflow
    val actions = workflowWithActions.actions

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "Workflow Execution Details",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = OceanPrimary
                )
                Text(
                    text = "Created ${Instant.ofEpochMilli(wf.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, MMM d • h:mm a"))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = EchoTextSecondary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = OceanBackground,
                    border = BorderStroke(1.dp, OceanCardBorder)
                ) {
                    Text(
                        text = "\"${wf.originalInput}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = EchoTextPrimary,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Text(
                    text = "ACTION RESULTS (${actions.size})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = OceanSecondary
                )

                LazyColumn(
                    modifier = Modifier.heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(actions, key = { it.id }) { action ->
                        HistoryActionRow(action)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRunAgain,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OceanPrimary)
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Run Flow Again", color = Color.White)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onDelete) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = EchoError)
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", color = OceanPrimary)
                }
            }
        },
        containerColor = OceanSurface,
        shape = RoundedCornerShape(22.dp)
    )
}

@Composable
fun HistoryActionRow(action: ActionHistoryEntity) {
    val parsedType = try {
        com.echoflow.app.domain.model.ActionType.valueOf(action.actionType)
    } catch (_: Exception) {
        com.echoflow.app.domain.model.ActionType.UNKNOWN
    }
    val typeColor = getActionColor(parsedType)

    val (outcomeLabel, outcomeColor) = when (action.executionState) {
        "SUCCESS" -> {
            if (action.actionType == "MESSAGE") "✓ SENT" to EchoSuccess
            else "✓ COMPLETED" to EchoSuccess
        }
        "COMPLETED" -> "✓ COMPLETED" to EchoSuccess
        "PREPARED" -> "↗ HANDED OFF" to OceanSecondary
        "WAITING_FOR_USER", "NEEDS_CONFIRMATION" -> "◉ WAITING" to EchoWarning
        "SCHEDULED" -> "● SCHEDULED" to OceanSecondary
        "CANCELLED" -> "↶ CANCELLED" to EchoTextTertiary
        "FAILED" -> "✕ FAILED" to EchoError
        else -> action.executionState to EchoTextSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(OceanBackground)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(typeColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = getActionIcon(parsedType),
                contentDescription = null,
                tint = typeColor,
                modifier = Modifier.size(16.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = action.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = EchoTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            action.executionMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = EchoTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Truthful Outcome Badge
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = outcomeColor.copy(alpha = 0.15f)
        ) {
            Text(
                text = outcomeLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = outcomeColor,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                fontSize = 11.sp
            )
        }
    }
}
