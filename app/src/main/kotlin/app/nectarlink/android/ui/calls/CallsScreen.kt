// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.ui.calls

import android.text.format.DateUtils
import android.widget.Toast
import app.nectarlink.android.contacts.ContactDefaultsPrefs
import app.nectarlink.android.contacts.PhoneContacts
import app.nectarlink.core.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nectarlink.android.R
import app.nectarlink.android.calls.ActiveCallManager
import app.nectarlink.android.core.Core
import app.nectarlink.android.core.CoreState
import app.nectarlink.core.CallLogEntry
import kotlinx.coroutines.launch

enum class CallFilter { All, Missed, Incoming, Outgoing }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsScreen(
    state: CoreState,
    core: Core,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val activeCall by ActiveCallManager.activeCall.collectAsState()
    val availableDevices = state.devices.filter { it.isAndroid }
    var selectedDevice by remember(availableDevices) {
        mutableStateOf(availableDevices.firstOrNull { it.online } ?: availableDevices.firstOrNull())
    }
    var callLogs by remember { mutableStateOf<List<CallLogEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var activeFilter by remember { mutableStateOf(CallFilter.All) }
    var searchQuery by remember { mutableStateOf("") }
    var showDialpadSheet by remember { mutableStateOf(false) }
    var dialpadNumber by remember { mutableStateOf("") }
    val context = LocalContext.current
    var matchingContacts by remember { mutableStateOf<List<Contact>>(emptyList()) }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank() || !PhoneContacts.canRead(context)) {
            matchingContacts = emptyList()
        } else {
            matchingContacts = withContext(Dispatchers.IO) {
                runCatching { PhoneContacts(context) {}.list(searchQuery, 0, 20) }.getOrDefault(emptyList())
            }
        }
    }

    fun refreshCalls() {
        val dev = selectedDevice ?: return
        loading = true
        scope.launch {
            callLogs = core.peerCallLog(dev.id)
            loading = false
        }
    }

    LaunchedEffect(selectedDevice?.id) {
        refreshCalls()
    }

    val filteredLogs = remember(callLogs, activeFilter, searchQuery) {
        callLogs.filter { entry ->
            val matchesFilter = when (activeFilter) {
                CallFilter.All -> true
                CallFilter.Missed -> entry.direction.equals("missed", ignoreCase = true) || entry.direction.equals("rejected", ignoreCase = true)
                CallFilter.Incoming -> entry.direction.equals("incoming", ignoreCase = true)
                CallFilter.Outgoing -> entry.direction.equals("outgoing", ignoreCase = true)
            }
            val matchesQuery = if (searchQuery.isBlank()) true else {
                (entry.name?.contains(searchQuery, ignoreCase = true) == true) || entry.number.contains(searchQuery)
            }
            matchesFilter && matchesQuery
        }
    }

    val groupedLogs = remember(filteredLogs) {
        val now = System.currentTimeMillis()
        val oneDayMs = 24 * 60 * 60 * 1000L
        filteredLogs.groupBy { entry ->
            val diff = now - entry.date
            when {
                diff < oneDayMs -> "Today"
                diff < 2 * oneDayMs -> "Yesterday"
                else -> "Older"
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Active In-Call Card with DTMF Keypad for IVR
            if (activeCall != null) {
                item(key = "active_call_card") {
                    ActiveCallCard(
                        call = activeCall!!,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }

            // Google Phone Search Bar
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.weight(1f),
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search contacts & calls",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                innerTextField()
                            }
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_backspace),
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        IconButton(
                            onClick = { refreshCalls() },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = "Refresh",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            // Peer Device Source Chip (clean & preselected)
            if (selectedDevice != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    color = if (selectedDevice!!.online) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                    modifier = Modifier.size(8.dp),
                                ) {}
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${selectedDevice!!.name} • ${if (selectedDevice!!.online) "Connected" else "Offline"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Filter Chips Bar (All, Missed, Incoming, Outgoing)
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(CallFilter.values()) { filter ->
                        val selected = activeFilter == filter
                        FilterChip(
                            selected = selected,
                            onClick = { activeFilter = filter },
                            label = {
                                Text(
                                    text = filter.name,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }

            if (searchQuery.isNotBlank() && matchingContacts.isNotEmpty()) {
                item(key = "section_contacts") {
                    Text(
                        text = "Contacts",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                items(matchingContacts, key = { "contact_${it.id}" }) { contact ->
                    ContactCallSearchItem(
                        contact = contact,
                        onDial = { number ->
                            val dev = selectedDevice ?: return@ContactCallSearchItem
                            scope.launch { core.peerDial(dev.id, number) }
                        },
                    )
                }
            }

            // Loading state
            if (loading && callLogs.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                    }
                }
            } else if (filteredLogs.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No calls matching \"$searchQuery\"" else "No recent calls",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // Grouped Call Logs (Today, Yesterday, Older)
                groupedLogs.forEach { (section, entries) ->
                    item(key = "section_$section") {
                        Text(
                            text = section,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }

                    items(entries, key = { "${it.id}_${it.date}" }) { entry ->
                        GoogleDialerCallItem(
                            entry = entry,
                            onDial = {
                                val dev = selectedDevice ?: return@GoogleDialerCallItem
                                scope.launch { core.peerDial(dev.id, entry.number) }
                            },
                        )
                    }
                }
            }

            // Bottom spacing for navigation dock & FAB
            item {
                Spacer(Modifier.height(88.dp))
            }
        }

        // Floating Keypad Button (bottom-right)
        FloatingActionButton(
            onClick = { showDialpadSheet = true },
            containerColor = Color(0xFF2E7D32),
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 84.dp)
                .size(58.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_dialpad),
                contentDescription = "Keypad",
                modifier = Modifier.size(26.dp),
            )
        }

        // Dialpad Bottom Sheet (matching Google Dialer)
        if (showDialpadSheet) {
            GoogleDialerKeypadSheet(
                number = dialpadNumber,
                onNumberChange = { dialpadNumber = it },
                onDismiss = { showDialpadSheet = false },
                onCall = {
                    val dev = selectedDevice
                    if (dev != null && dialpadNumber.isNotBlank()) {
                        showDialpadSheet = false
                        val toDial = dialpadNumber
                        dialpadNumber = ""
                        scope.launch { core.peerDial(dev.id, toDial) }
                    }
                },
            )
        }
    }
}

@Composable
private fun ContactCallSearchItem(
    contact: Contact,
    onDial: (String) -> Unit,
) {
    val context = LocalContext.current
    val numbers = contact.numbers
    val title = contact.name

    val defaultNum = ContactDefaultsPrefs.getDefaultNumber(context, contact.id)
    val primaryNumber = if (defaultNum != null && numbers.any { it.number == defaultNum }) {
        defaultNum
    } else {
        numbers.firstOrNull()?.number ?: return
    }
    val label = numbers.firstOrNull { it.number == primaryNumber }?.label

    var showNumberPicker by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (numbers.size > 1 && defaultNum == null) {
                    showNumberPicker = true
                } else {
                    onDial(primaryNumber)
                }
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = primaryNumber,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!label.isNullOrBlank()) {
                        Text(text = "•", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            IconButton(
                onClick = {
                    if (numbers.size > 1 && defaultNum == null) {
                        showNumberPicker = true
                    } else {
                        onDial(primaryNumber)
                    }
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_call),
                    contentDescription = "Call",
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }

    if (showNumberPicker) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showNumberPicker = false },
            title = { Text("Call $title") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select a number to call:", style = MaterialTheme.typography.bodyMedium)
                    numbers.forEach { numInfo ->
                        val isDefault = ContactDefaultsPrefs.getDefaultNumber(context, contact.id) == numInfo.number
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isDefault) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    ContactDefaultsPrefs.setDefaultNumber(context, contact.id, numInfo.number)
                                    onDial(numInfo.number)
                                    showNumberPicker = false
                                },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(numInfo.number, fontWeight = FontWeight.Bold)
                                    Text(numInfo.label ?: "Phone", style = MaterialTheme.typography.bodySmall)
                                }
                                if (isDefault) {
                                    Text("Default", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                } else {
                                    androidx.compose.material3.TextButton(onClick = {
                                        ContactDefaultsPrefs.setDefaultNumber(context, contact.id, numInfo.number)
                                        Toast.makeText(context, "Set as default for $title", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Text("Set as default", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showNumberPicker = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GoogleDialerCallItem(
    entry: CallLogEntry,
    onDial: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val hasName = !entry.name.isNullOrBlank()
    val title = if (hasName) entry.name!!.trim() else entry.number.ifEmpty { "Unknown Caller" }
    val isMissed = entry.direction.equals("missed", ignoreCase = true) || entry.direction.equals("rejected", ignoreCase = true)
    val isIncoming = entry.direction.equals("incoming", ignoreCase = true)
    val isOutgoing = entry.direction.equals("outgoing", ignoreCase = true)

    val directionColor = when {
        isMissed -> Color(0xFFD32F2F)
        isIncoming -> Color(0xFF2E7D32)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val arrowSymbol = when {
        isMissed -> "↙"
        isIncoming -> "↙"
        else -> "↗"
    }

    val timeSpan = remember(entry.date) {
        DateUtils.getRelativeTimeSpanString(entry.date).toString()
    }
    val durStr = remember(entry.duration) {
        val durSecs = entry.duration.toLong()
        if (durSecs > 0) {
            val mins = durSecs / 60
            val secs = durSecs % 60
            if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
        } else null
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onDial,
                onLongClick = {
                    if (entry.number.isNotBlank()) {
                        clipboardManager.setText(AnnotatedString(entry.number))
                        Toast.makeText(context, "Copied ${entry.number}", Toast.LENGTH_SHORT).show()
                    }
                },
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Circular Avatar (with initials or call icon)
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(
                        if (isMissed) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isMissed) Color(0xFFC62828) else MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.width(14.dp))

            // Details Column
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isMissed) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isMissed) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hasName && entry.number.isNotBlank()) {
                    Text(
                        text = entry.number,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = arrowSymbol,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = directionColor,
                    )
                    Text(
                        text = timeSpan,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (durStr != null) {
                        Text(text = "•", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text(
                            text = durStr,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Quick Call Button
            IconButton(
                onClick = onDial,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_call),
                    contentDescription = "Call",
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoogleDialerKeypadSheet(
    number: String,
    onNumberChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onCall: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Display Number, Paste, and Clear
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                val clipboardManager = LocalClipboardManager.current
                IconButton(
                    onClick = {
                        val clipText = clipboardManager.getText()?.text ?: ""
                        if (clipText.isNotBlank()) {
                            onNumberChange(number + clipText.filter { it.isDigit() || it == '+' || it == '*' || it == '#' })
                        }
                    },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_clipboard),
                        contentDescription = "Paste",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    text = number.ifEmpty { "" },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )

                if (number.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { onNumberChange("") },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_backspace),
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 3x4 Keypad Grid
            val keys = listOf(
                listOf("1" to "", "2" to "ABC", "3" to "DEF"),
                listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
                listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
                listOf("*" to "", "0" to "+", "#" to ""),
            )

            for (row in keys) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    for ((digit, letters) in row) {
                        DialpadButton(
                            digit = digit,
                            letters = letters,
                            onClick = { onNumberChange(number + digit) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Big Green Call Button
            Surface(
                onClick = onCall,
                shape = CircleShape,
                color = Color(0xFF2E7D32),
                modifier = Modifier.size(68.dp),
                enabled = number.isNotBlank(),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_call),
                        contentDescription = "Call",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun DialpadButton(
    digit: String,
    letters: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .size(72.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = digit,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (letters.isNotEmpty()) {
                Text(
                    text = letters,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
