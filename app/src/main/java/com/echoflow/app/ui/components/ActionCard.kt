package com.echoflow.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echoflow.app.domain.model.ActionType
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.domain.model.ParsedAction
import com.echoflow.app.ui.theme.*
import java.time.format.DateTimeFormatter

@Composable
fun ActionCard(
    action: ParsedAction,
    executionState: ExecutionState,
    executionMessage: String? = null,
    calendarEventId: Long? = null,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onConfirm: () -> Unit = {},
    onRescheduleTomorrow: () -> Unit = {},
    onOpenCalendar: (Long) -> Unit = {},
    onCancelAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val typeColor = getActionColor(action.type)
    val cardShape = RoundedCornerShape(18.dp)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = typeColor.copy(alpha = 0.25f),
                shape = cardShape
            ),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = EchoDarkSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Type Badge + Confidence Pill + Edit/Delete Icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(typeColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = getActionIcon(action.type),
                            contentDescription = action.type.name,
                            tint = typeColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Text(
                        text = action.type.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = typeColor
                    )

                    // Confidence badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = "${(action.confidence * 100).toInt()}% conf",
                            style = MaterialTheme.typography.labelSmall,
                            color = EchoTextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Edit & Delete actions
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "Edit Action",
                            tint = EchoTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Delete Action",
                            tint = EchoTextTertiary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Title
            Text(
                text = action.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = EchoTextPrimary
            )

            // Dynamic action fields
            when (action.type) {
                ActionType.CALENDAR -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        action.dateTime?.let { dt ->
                            DetailRow(
                                icon = Icons.Outlined.AccessTime,
                                text = "${dt.format(DateTimeFormatter.ofPattern("EEE, MMM d • h:mm a"))} (${action.durationMinutes ?: 60}m)"
                            )
                        }

                        val calendarDest = action.calendarAccount
                        if (!calendarDest.isNullOrBlank()) {
                            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                Text(
                                    text = "Calendar",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = EchoTextSecondary
                                )
                                Text(
                                    text = calendarDest,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = EchoAccent
                                )
                            }
                        }

                        action.description?.let { desc ->
                            if (desc.isNotBlank()) {
                                DetailRow(icon = Icons.Outlined.Description, text = desc)
                            }
                        }

                        if (executionState == ExecutionState.SUCCESS) {
                            val effectiveEventId = calendarEventId ?: action.calendarEventId ?: -1L
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = { onOpenCalendar(effectiveEventId) },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EchoCalendar),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CalendarToday,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Open in Calendar", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                ActionType.REMINDER -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        action.dateTime?.let { dt ->
                            DetailRow(
                                icon = Icons.Outlined.Notifications,
                                text = "Scheduled for: ${dt.format(DateTimeFormatter.ofPattern("h:mm a (EEE, MMM d)"))}"
                            )
                        }
                        if (!action.requestedTime.isNullOrBlank() && action.requestedTime != action.dateTime?.format(DateTimeFormatter.ofPattern("HH:mm"))) {
                            DetailRow(
                                icon = Icons.Outlined.Schedule,
                                text = "Requested: ${action.requestedTime}"
                            )
                        }
                        action.recipient?.let { rec ->
                            DetailRow(icon = Icons.Outlined.Person, text = "Target: $rec")
                        }
                    }
                }
                ActionType.MESSAGE -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        action.recipient?.let { rec ->
                            DetailRow(icon = Icons.Outlined.Person, text = "To: $rec")
                        }
                        action.message?.let { msg ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = OceanBackground,
                                border = BorderStroke(1.dp, OceanCardBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "MESSAGE CONTENT",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = OceanSecondary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "\"$msg\"",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = EchoTextPrimary
                                    )
                                }
                            }
                        }
                        action.dateTime?.let { dt ->
                            DetailRow(
                                icon = Icons.Outlined.Schedule,
                                text = "Scheduled: ${dt.format(DateTimeFormatter.ofPattern("h:mm a (EEE, MMM d)"))}"
                            )
                        } ?: action.timeExpression?.let { expr ->
                            DetailRow(icon = Icons.Outlined.Schedule, text = "When: $expr")
                        }
                    }
                }
                ActionType.CALL -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        action.recipient?.let { rec ->
                            DetailRow(icon = Icons.Outlined.Person, text = "Call: $rec")
                        }
                        action.recipientPhone?.let { phone ->
                            DetailRow(icon = Icons.Outlined.Phone, text = "Phone: $phone")
                        }
                        action.requestedTime?.let { time ->
                            DetailRow(icon = Icons.Outlined.AccessTime, text = "At: $time")
                        }
                    }
                }
                ActionType.NOTE -> {
                    action.description?.let { desc ->
                        DetailRow(icon = Icons.AutoMirrored.Outlined.StickyNote2, text = desc)
                    }
                }
                ActionType.UNKNOWN -> {}
            }

            // Past-time alert and recovery banner
            if (action.isPastTime) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = EchoWarning.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = EchoWarning,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${action.dateTime?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "Requested time"} has passed.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = EchoWarning
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onRescheduleTomorrow,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "Schedule Tomorrow ${action.dateTime?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EchoWarning
                                )
                            }
                            OutlinedButton(
                                onClick = onEdit,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Edit Time", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // Confirmation banner if needed (when not past-time which is already handled above)
            if (action.requiresConfirmation && !action.isPastTime) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = EchoWarning.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = action.ambiguityReason ?: "Needs review before execution",
                            style = MaterialTheme.typography.bodySmall,
                            color = EchoWarning,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = onConfirm,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("Confirm", color = EchoWarning, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Call confirmation required badge
            if (action.type == ActionType.CALL) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFA855F7).copy(alpha = 0.15f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(imageVector = Icons.Outlined.Phone, contentDescription = null, tint = Color(0xFFA855F7), modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Confirmation required", style = MaterialTheme.typography.labelSmall, color = Color(0xFFA855F7), fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Status Bar & Result message
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (action.type == ActionType.CALENDAR && (executionState == ExecutionState.SUCCESS || executionState == ExecutionState.COMPLETED)) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = EchoSuccess.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "✓ Event added to calendar",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = EchoSuccess,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    } else {
                        ExecutionStatePill(state = executionState)
                    }

                    if (executionState.isPendingOrScheduled && executionState != ExecutionState.EXECUTING) {
                        Spacer(modifier = Modifier.width(6.dp))
                        TextButton(
                            onClick = onCancelAction,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text("Cancel", style = MaterialTheme.typography.labelSmall, color = EchoError.copy(alpha = 0.8f))
                        }
                    }
                }

                if (!executionMessage.isNullOrBlank() && !(action.type == ActionType.CALENDAR && (executionState == ExecutionState.SUCCESS || executionState == ExecutionState.COMPLETED))) {
                    Text(
                        text = executionMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (executionState) {
                            ExecutionState.SUCCESS, ExecutionState.COMPLETED -> EchoSuccess
                            ExecutionState.FAILED -> EchoError
                            ExecutionState.CANCELLED -> EchoTextTertiary
                            else -> EchoTextSecondary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = EchoTextSecondary,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = EchoTextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ExecutionStatePill(state: ExecutionState) {
    val (color, text) = when (state) {
        ExecutionState.DETECTED, ExecutionState.PENDING -> EchoTextTertiary to "Pending"
        ExecutionState.READY -> EchoAccent to "Ready"
        ExecutionState.NEEDS_CONFIRMATION -> EchoWarning to "Review"
        ExecutionState.PREPARED -> EchoSecondary to "Prepared"
        ExecutionState.SCHEDULED -> EchoAccent to "Scheduled"
        ExecutionState.EXECUTING -> EchoAccent to "Executing..."
        ExecutionState.IN_PROGRESS -> EchoSecondary to "In Progress"
        ExecutionState.WAITING_FOR_USER -> EchoWarning to "Waiting for You"
        ExecutionState.COMPLETED, ExecutionState.SUCCESS -> EchoSuccess to "Completed"
        ExecutionState.CANCELLED -> EchoTextTertiary to "Cancelled"
        ExecutionState.FAILED -> EchoError to "Failed"
        ExecutionState.SKIPPED -> EchoTextTertiary to "Skipped"
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

fun getActionColor(type: ActionType): Color = when (type) {
    ActionType.CALENDAR -> EchoCalendar
    ActionType.REMINDER -> EchoReminder
    ActionType.MESSAGE -> EchoMessage
    ActionType.CALL -> Color(0xFFA855F7) // Purple for Call actions
    ActionType.NOTE -> EchoNote
    ActionType.UNKNOWN -> EchoTextTertiary
}

fun getActionIcon(type: ActionType): ImageVector = when (type) {
    ActionType.CALENDAR -> Icons.Outlined.CalendarToday
    ActionType.REMINDER -> Icons.Outlined.Notifications
    ActionType.MESSAGE -> Icons.AutoMirrored.Outlined.Send
    ActionType.CALL -> Icons.Outlined.Phone
    ActionType.NOTE -> Icons.AutoMirrored.Outlined.StickyNote2
    ActionType.UNKNOWN -> Icons.AutoMirrored.Outlined.HelpOutline
}
