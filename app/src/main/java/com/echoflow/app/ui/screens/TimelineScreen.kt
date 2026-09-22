package com.echoflow.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
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
import com.echoflow.app.data.db.ActionHistoryEntity
import com.echoflow.app.data.db.WorkflowWithActions
import com.echoflow.app.domain.model.ActionType
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.domain.model.ParsedAction
import com.echoflow.app.ui.components.getActionColor
import com.echoflow.app.ui.components.getActionIcon
import com.echoflow.app.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

enum class TimelineFilter {
    ALL, TODAY, TOMORROW, UPCOMING
}

data class TimelineItem(
    val id: String,
    val type: ActionType,
    val title: String,
    val description: String?,
    val recipient: String?,
    val recipientPhone: String?,
    val message: String?,
    val timeDisplay: String,
    val epochMillis: Long,
    val durationMinutes: Int?,
    val calendarEventId: Long?,
    val executionState: ExecutionState,
    val executionMessage: String?,
    val driftMs: Long?,
    val isPending: Boolean
)

@Composable
fun TimelineScreen(
    pendingActions: List<ParsedAction>,
    historyWorkflows: List<WorkflowWithActions>,
    onSnoozeAction: (String, Int) -> Unit,
    onCancelAction: (String) -> Unit,
    onExecuteActionNow: (String) -> Unit,
    onOpenCalendar: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf(TimelineFilter.ALL) }

    // Aggregate pending scheduled actions and historical actions into a unified timeline
    val allItems = remember(pendingActions, historyWorkflows) {
        val itemsMap = LinkedHashMap<String, TimelineItem>()

        // 1. In-memory actively scheduled alarms
        for (action in pendingActions) {
            val epoch = action.resolvedEpochMillis
                ?: action.dateTime?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                ?: (System.currentTimeMillis() + 3600_000L)

            val timeDisplay = action.requestedTime
                ?: action.timeExpression
                ?: formatEpochTime(epoch)

            itemsMap[action.id] = TimelineItem(
                id = action.id,
                type = action.type,
                title = action.title,
                description = action.description,
                recipient = action.recipient,
                recipientPhone = action.recipientPhone,
                message = action.message,
                timeDisplay = timeDisplay,
                epochMillis = epoch,
                durationMinutes = action.durationMinutes,
                calendarEventId = action.calendarEventId,
                executionState = action.executionState,
                executionMessage = action.executionMessage,
                driftMs = null,
                isPending = true
            )
        }

        // 2. Historical & executed actions from Room
        for (wf in historyWorkflows) {
            for (act in wf.actions) {
                if (!itemsMap.containsKey(act.id)) {
                    val epoch = act.resolvedEpochMillis
                        ?: act.dateTime?.let { runCatching { java.time.LocalDateTime.parse(it).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() }
                        ?: act.createdAt

                    val timeDisplay = act.requestedTime
                        ?: act.resolvedTime
                        ?: act.timeExpression
                        ?: formatEpochTime(epoch)

                    val type = try { ActionType.valueOf(act.actionType) } catch (_: Exception) { ActionType.REMINDER }
                    val state = try { ExecutionState.valueOf(act.executionState) } catch (_: Exception) { ExecutionState.COMPLETED }

                    itemsMap[act.id] = TimelineItem(
                        id = act.id,
                        type = type,
                        title = act.title,
                        description = act.description,
                        recipient = act.recipient,
                        recipientPhone = act.recipientPhone,
                        message = act.message,
                        timeDisplay = timeDisplay,
                        epochMillis = epoch,
                        durationMinutes = act.durationMinutes,
                        calendarEventId = null,
                        executionState = state,
                        executionMessage = act.executionMessage,
                        driftMs = act.driftMs,
                        isPending = state.isPendingOrScheduled
                    )
                }
            }
        }

        itemsMap.values.sortedBy { it.epochMillis }
    }

    val today = remember { LocalDate.now() }
    val tomorrow = remember { today.plusDays(1) }

    // Filter items based on selected filter
    val filteredItems = remember(allItems, selectedFilter) {
        when (selectedFilter) {
            TimelineFilter.ALL -> allItems
            TimelineFilter.TODAY -> allItems.filter {
                Instant.ofEpochMilli(it.epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().isEqual(today)
            }
            TimelineFilter.TOMORROW -> allItems.filter {
                Instant.ofEpochMilli(it.epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().isEqual(tomorrow)
            }
            TimelineFilter.UPCOMING -> allItems.filter {
                val itemDate = Instant.ofEpochMilli(it.epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                itemDate.isAfter(tomorrow) || (it.isPending && !itemDate.isBefore(today))
            }
        }
    }

    // Group filtered items by date
    val groupedItems = remember(filteredItems) {
        filteredItems.groupBy { item ->
            val date = Instant.ofEpochMilli(item.epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            when {
                date.isEqual(today) -> "Today"
                date.isEqual(tomorrow) -> "Tomorrow"
                date.isAfter(tomorrow) -> date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
                else -> "Past Actions"
            }
        }
    }

    val pendingCount = allItems.count { it.isPending }
    val completedCount = allItems.count { it.executionState == ExecutionState.SUCCESS || it.executionState == ExecutionState.COMPLETED }

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

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Timeline",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = OceanPrimary
                    )
                    Text(
                        text = "Ambient Action Engine Schedule",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = OceanSecondary
                    )
                }

                // Stats pill
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = OceanSurface,
                    border = BorderStroke(1.dp, OceanCardBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$pendingCount pending",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = OceanPrimary
                        )
                        Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(OceanSecondary))
                        Text(
                            text = "$completedCount done",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = EchoSuccess
                        )
                    }
                }
            }

            // Filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TimelineFilter.entries.forEach { filter ->
                    val isSelected = selectedFilter == filter
                    val count = when (filter) {
                        TimelineFilter.ALL -> allItems.size
                        TimelineFilter.TODAY -> allItems.count {
                            Instant.ofEpochMilli(it.epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().isEqual(today)
                        }
                        TimelineFilter.TOMORROW -> allItems.count {
                            Instant.ofEpochMilli(it.epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().isEqual(tomorrow)
                        }
                        TimelineFilter.UPCOMING -> allItems.count {
                            val d = Instant.ofEpochMilli(it.epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                            d.isAfter(tomorrow) || (it.isPending && !d.isBefore(today))
                        }
                    }

                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedFilter = filter },
                        label = { Text("${filter.name.lowercase().replaceFirstChar { it.uppercase() }} ($count)", fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OceanPrimary,
                            selectedLabelColor = Color.White,
                            containerColor = OceanSurface,
                            labelColor = EchoTextPrimary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = if (isSelected) OceanPrimary else OceanCardBorder,
                            selectedBorderColor = OceanPrimary,
                            borderWidth = 1.dp,
                            selectedBorderWidth = 1.dp,
                            enabled = true,
                            selected = isSelected
                        )
                    )
                }
            }

            if (filteredItems.isEmpty()) {
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
                            imageVector = Icons.Filled.CalendarMonth,
                            contentDescription = null,
                            modifier = Modifier.size(54.dp),
                            tint = EchoTextTertiary.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "No actions scheduled",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = EchoTextSecondary
                        )
                        Text(
                            text = "Actions parsed from your voice or text workflows will appear chronologically here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = EchoTextTertiary,
                            modifier = Modifier.padding(horizontal = 32.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    groupedItems.forEach { (dateHeader, items) ->
                        item {
                            Text(
                                text = dateHeader,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = EchoAccent,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }

                        items(items, key = { it.id }) { item ->
                            TimelineItemCard(
                                item = item,
                                onSnooze = { onSnoozeAction(item.id, 5) },
                                onCancel = { onCancelAction(item.id) },
                                onExecuteNow = { onExecuteActionNow(item.id) },
                                onOpenCalendar = onOpenCalendar
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
}

@Composable
fun TimelineItemCard(
    item: TimelineItem,
    onSnooze: () -> Unit,
    onCancel: () -> Unit,
    onExecuteNow: () -> Unit,
    onOpenCalendar: (Long) -> Unit
) {
    val typeColor = getActionColor(item.type)
    val typeIcon = getActionIcon(item.type)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Time & track indicator column
        Column(
            modifier = Modifier.width(68.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = formatItemTime(item.epochMillis),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = EchoTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(typeColor)
            )
        }

        // Action Card
        Card(
            modifier = Modifier
                .weight(1f)
                .border(1.dp, OceanCardBorder, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = OceanSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header row: Type badge + Status badge + Drift badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = null,
                            tint = typeColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = item.type.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = typeColor
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Drift badge if available
                        if (item.driftMs != null) {
                            val sign = if (item.driftMs >= 0) "+" else ""
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = OceanSecondary.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "⚡ Drift: $sign${item.driftMs}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = OceanPrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // State pill
                        TimelineStatePill(state = item.executionState)
                    }
                }

                // Title
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = EchoTextPrimary
                )

                // Recipient or message details
                if (!item.recipient.isNullOrBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "To: ${item.recipient}",
                            style = MaterialTheme.typography.bodySmall,
                            color = EchoTextSecondary
                        )
                        if (!item.recipientPhone.isNullOrBlank()) {
                            Text(
                                text = "(${item.recipientPhone})",
                                style = MaterialTheme.typography.bodySmall,
                                color = EchoTextTertiary
                            )
                        }
                    }
                }

                if (!item.message.isNullOrBlank()) {
                    Text(
                        text = "\"${item.message}\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = EchoTextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!item.description.isNullOrBlank() && item.description != item.title) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = EchoTextTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Execution message / status detail
                if (!item.executionMessage.isNullOrBlank()) {
                    Text(
                        text = item.executionMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.executionState == ExecutionState.FAILED) EchoError else EchoTextTertiary
                    )
                }

                // Action buttons for pending items
                if (item.isPending) {
                    Divider(color = OceanCardBorder.copy(alpha = 0.6f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onSnooze,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = EchoTextPrimary),
                            border = BorderStroke(1.dp, OceanCardBorder)
                        ) {
                            Icon(imageVector = Icons.Outlined.Snooze, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Snooze 5m", style = MaterialTheme.typography.labelSmall)
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        OutlinedButton(
                            onClick = onCancel,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = EchoError),
                            border = BorderStroke(1.dp, EchoError.copy(alpha = 0.4f))
                        ) {
                            Icon(imageVector = Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancel", style = MaterialTheme.typography.labelSmall)
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Button(
                            onClick = onExecuteNow,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = OceanPrimary)
                        ) {
                            Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Run Now", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                } else if (item.calendarEventId != null) {
                    Divider(color = OceanCardBorder.copy(alpha = 0.6f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = { onOpenCalendar(item.calendarEventId) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = OceanPrimary),
                            border = BorderStroke(1.dp, OceanCardBorder)
                        ) {
                            Icon(imageVector = Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open Calendar", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TimelineStatePill(state: ExecutionState) {
    val (color, text) = when (state) {
        ExecutionState.DETECTED -> EchoTextTertiary to "Detected"
        ExecutionState.PENDING -> EchoTextTertiary to "Pending"
        ExecutionState.READY -> OceanPrimary to "Ready"
        ExecutionState.NEEDS_CONFIRMATION -> EchoWarning to "Needs Confirm"
        ExecutionState.SCHEDULED -> OceanSecondary to "Scheduled"
        ExecutionState.PREPARED -> OceanSecondary to "Handed Off"
        ExecutionState.EXECUTING -> OceanPrimary to "Executing..."
        ExecutionState.IN_PROGRESS -> OceanSecondary to "In Progress"
        ExecutionState.WAITING_FOR_USER -> EchoWarning to "Waiting"
        ExecutionState.COMPLETED, ExecutionState.SUCCESS -> EchoSuccess to "Completed"
        ExecutionState.CANCELLED -> EchoTextTertiary to "Cancelled"
        ExecutionState.FAILED -> EchoError to "Failed"
        ExecutionState.SKIPPED -> EchoTextTertiary to "Skipped"
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun formatEpochTime(epoch: Long): String {
    return Instant.ofEpochMilli(epoch)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a"))
}

private fun formatItemTime(epoch: Long): String {
    return Instant.ofEpochMilli(epoch)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a"))
}
