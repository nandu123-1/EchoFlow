package com.echoflow.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.echoflow.app.domain.model.ActionType
import com.echoflow.app.domain.model.ParsedAction
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditActionBottomSheet(
    action: ParsedAction,
    onDismiss: () -> Unit,
    onSave: (ParsedAction) -> Unit
) {
    var title by remember { mutableStateOf(action.title) }
    var description by remember { mutableStateOf(action.description ?: "") }
    var recipient by remember { mutableStateOf(action.recipient ?: "") }
    var message by remember { mutableStateOf(action.message ?: "") }
    var durationMinutesStr by remember { mutableStateOf((action.durationMinutes ?: 60).toString()) }

    var dateStr by remember {
        mutableStateOf(
            action.dateTime?.format(DateTimeFormatter.ISO_LOCAL_DATE)
                ?: LocalDate.now().plusDays(1).toString()
        )
    }
    var timeStr by remember {
        mutableStateOf(
            action.dateTime?.format(DateTimeFormatter.ofPattern("HH:mm"))
                ?: "16:00"
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Edit ${action.type.name.lowercase().replaceFirstChar { it.uppercase() }} Action",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            if (action.type == ActionType.CALENDAR || action.type == ActionType.REMINDER) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = dateStr,
                        onValueChange = { dateStr = it },
                        label = { Text("Date (YYYY-MM-DD)") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = timeStr,
                        onValueChange = { timeStr = it },
                        label = { Text("Time (HH:mm)") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            if (action.type == ActionType.CALENDAR) {
                OutlinedTextField(
                    value = durationMinutesStr,
                    onValueChange = { durationMinutesStr = it },
                    label = { Text("Duration (minutes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            if (action.type == ActionType.MESSAGE || action.type == ActionType.REMINDER) {
                OutlinedTextField(
                    value = recipient,
                    onValueChange = { recipient = it },
                    label = { Text("Recipient (Name or Phone)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            if (action.type == ActionType.MESSAGE) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Message Body") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description / Notes") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = {
                        val parsedDateTime = try {
                            val d = LocalDate.parse(dateStr)
                            val t = LocalTime.parse(timeStr)
                            LocalDateTime.of(d, t)
                        } catch (e: Exception) {
                            action.dateTime ?: LocalDateTime.now().plusHours(1)
                        }

                        val isTemporal = action.type == ActionType.CALENDAR || action.type == ActionType.REMINDER
                        val newEpoch = if (isTemporal) {
                            parsedDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        } else null
                        val isPast = if (newEpoch != null) newEpoch <= System.currentTimeMillis() else false

                        val updated = action.copy(
                            title = title.ifBlank { action.title },
                            description = description.ifBlank { null },
                            recipient = recipient.ifBlank { null },
                            message = message.ifBlank { null },
                            durationMinutes = durationMinutesStr.toIntOrNull() ?: action.durationMinutes ?: 60,
                            dateTime = if (isTemporal) parsedDateTime else null,
                            resolvedEpochMillis = newEpoch,
                            requestedTime = if (isTemporal) parsedDateTime.format(DateTimeFormatter.ofPattern("HH:mm")) else null,
                            isPastTime = isPast,
                            requiresConfirmation = isPast,
                            ambiguityReason = if (isPast) "Updated time has already passed" else null
                        )
                        onSave(updated)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save Changes")
                }
            }
        }
    }
}
