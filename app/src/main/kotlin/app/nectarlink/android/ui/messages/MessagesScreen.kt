// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.ui.messages

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nectarlink.android.R
import app.nectarlink.android.core.Core
import app.nectarlink.android.core.CoreState
import app.nectarlink.android.sms.MessagesCache
import app.nectarlink.core.SmsMessage
import app.nectarlink.core.SmsThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val AVATAR_COLORS = listOf(
    Color(0xFFE65100), // Orange
    Color(0xFF1565C0), // Blue
    Color(0xFF2E7D32), // Green
    Color(0xFF6A1B9A), // Purple
    Color(0xFFC2185B), // Pink
    Color(0xFF00838F), // Cyan
    Color(0xFF455A64), // Blue Grey
)

private fun getAvatarColor(name: String): Color {
    val hash = kotlin.math.abs(name.hashCode())
    return AVATAR_COLORS[hash % AVATAR_COLORS.size]
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    state: CoreState,
    core: Core,
    onInsideThreadChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cache = remember(context) { MessagesCache.get(context) }
    val availableDevices = state.devices.filter { it.isAndroid }
    var selectedDevice by remember(availableDevices) {
        mutableStateOf(availableDevices.firstOrNull { it.online } ?: availableDevices.firstOrNull())
    }
    var threads by remember { mutableStateOf<List<SmsThread>>(emptyList()) }
    var loadingThreads by remember { mutableStateOf(false) }
    var activeThread by remember { mutableStateOf<SmsThread?>(null) }
    var threadMessages by remember { mutableStateOf<List<SmsMessage>>(emptyList()) }
    var loadingMessages by remember { mutableStateOf(false) }

    LaunchedEffect(activeThread) {
        onInsideThreadChanged(activeThread != null)
    }

    var searchQuery by remember { mutableStateOf("") }
    var showNewMessageDialog by remember { mutableStateOf(false) }
    var newMsgRecipient by remember { mutableStateOf("") }
    var newMsgText by remember { mutableStateOf("") }
    var replyText by remember { mutableStateOf("") }

    fun refreshThreads() {
        val dev = selectedDevice ?: return
        val cached = cache.getCachedThreads(dev.id)
        if (cached.isNotEmpty()) {
            threads = cached
        } else {
            loadingThreads = true
        }
        scope.launch {
            val remote = core.peerSmsThreads(dev.id)
            if (remote.isNotEmpty()) {
                threads = remote
                cache.putThreads(dev.id, remote)
                cache.preloadTopThreads(scope, core, dev.id, remote)
            }
            loadingThreads = false
        }
    }

    fun loadMessages(thread: SmsThread) {
        val dev = selectedDevice ?: return
        activeThread = thread
        // Instantly clear unread count in local state & cache
        threads = threads.map { if (it.id == thread.id) it.copy(unread = 0u) else it }
        cache.markThreadRead(dev.id, thread.id)

        // Also update local Android SMS provider if thread id matches local database
        thread.id.toLongOrNull()?.let { tid ->
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.Telephony.Sms.READ, 1)
                        put(android.provider.Telephony.Sms.SEEN, 1)
                    }
                    context.contentResolver.update(
                        android.provider.Telephony.Sms.CONTENT_URI,
                        values,
                        "${android.provider.Telephony.Sms.THREAD_ID} = ? AND ${android.provider.Telephony.Sms.READ} = 0",
                        arrayOf(tid.toString()),
                    )
                    context.contentResolver.update(
                        android.net.Uri.parse("content://sms/conversations/$tid"),
                        values,
                        "${android.provider.Telephony.Sms.READ} = 0",
                        null,
                    )
                }
            }
        }

        val cached = cache.getCachedMessages(dev.id, thread.id)
        if (cached.isNotEmpty()) {
            threadMessages = cached
            loadingMessages = false
        } else {
            loadingMessages = true
        }
        scope.launch {
            val msgs = core.peerSmsMessages(dev.id, thread.id).reversed()
            if (msgs.isNotEmpty()) {
                threadMessages = msgs
                cache.putMessages(dev.id, thread.id, msgs)
            }
            loadingMessages = false
        }
    }

    LaunchedEffect(selectedDevice?.id) {
        refreshThreads()
    }

    val filteredThreads = remember(threads, searchQuery) {
        if (searchQuery.isBlank()) threads else {
            threads.filter { th ->
                th.names.any { it.contains(searchQuery, ignoreCase = true) } ||
                    th.addresses.any { it.contains(searchQuery) } ||
                    th.snippet.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // Detail Chat Screen (matching Google Messages chat)
    if (activeThread != null) {
        val currentThread = activeThread!!
        BackHandler {
            activeThread = null
            refreshThreads()
        }

        Column(modifier = modifier.fillMaxSize()) {
            // Chat header
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        activeThread = null
                        refreshThreads()
                    }) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                    }
                    val title = currentThread.names.firstOrNull()?.takeIf { it.isNotBlank() }
                        ?: currentThread.addresses.firstOrNull() ?: "Chat"
                    val avatarColor = remember(title) { getAvatarColor(title) }

                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(avatarColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = title.take(1).uppercase(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (currentThread.addresses.isNotEmpty()) {
                            Text(
                                text = currentThread.addresses.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Direct Call Button inside Chat
                    val callTarget = currentThread.addresses.firstOrNull()
                    if (callTarget != null && selectedDevice != null) {
                        IconButton(onClick = {
                            scope.launch { core.peerDial(selectedDevice!!.id, callTarget) }
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_call),
                                contentDescription = "Call",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    IconButton(onClick = { loadMessages(currentThread) }) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Refresh")
                    }
                }
            }

            // Message bubbles (Google Messages style)
            val listState = rememberLazyListState()
            LaunchedEffect(threadMessages.size) {
                if (threadMessages.isNotEmpty()) {
                    listState.animateScrollToItem(threadMessages.size - 1)
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (loadingMessages && threadMessages.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text(
                                        text = "SMS conversation with ${currentThread.names.firstOrNull() ?: currentThread.addresses.firstOrNull() ?: "contact"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }

                        items(threadMessages, key = { "${it.id}_${it.date}" }) { msg ->
                            GoogleMessagesBubble(msg = msg)
                        }
                    }
                }
            }

            // Chat input bar (Google Messages pill)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = replyText,
                        onValueChange = { replyText = it },
                        placeholder = { Text("Text message") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(26.dp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(onSend = {
                            val dev = selectedDevice
                            val target = currentThread.addresses.firstOrNull()
                            if (dev != null && target != null && replyText.isNotBlank()) {
                                val body = replyText.trim()
                                replyText = ""
                                val optimistic = cache.addOptimisticOutgoing(dev.id, currentThread.id, target, body)
                                threadMessages = threadMessages + optimistic
                                scope.launch {
                                    core.peerSmsSend(dev.id, listOf(target), body)
                                    val updated = core.peerSmsMessages(dev.id, currentThread.id).reversed()
                                    if (updated.isNotEmpty()) {
                                        threadMessages = updated
                                        cache.putMessages(dev.id, currentThread.id, updated)
                                    }
                                    refreshThreads()
                                }
                            }
                        }),
                        maxLines = 4,
                    )
                    Surface(
                        onClick = {
                            val dev = selectedDevice
                            val target = currentThread.addresses.firstOrNull()
                            if (dev != null && target != null && replyText.isNotBlank()) {
                                val body = replyText.trim()
                                replyText = ""
                                val optimistic = cache.addOptimisticOutgoing(dev.id, currentThread.id, target, body)
                                threadMessages = threadMessages + optimistic
                                scope.launch {
                                    core.peerSmsSend(dev.id, listOf(target), body)
                                    val updated = core.peerSmsMessages(dev.id, currentThread.id).reversed()
                                    if (updated.isNotEmpty()) {
                                        threadMessages = updated
                                        cache.putMessages(dev.id, currentThread.id, updated)
                                    }
                                    refreshThreads()
                                }
                            }
                        },
                        shape = CircleShape,
                        color = Color(0xFF2E7D32),
                        enabled = replyText.isNotBlank() && selectedDevice?.online == true,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_send),
                                contentDescription = "Send",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
        return
    }

    // Main Google Messages Conversation List Screen
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header: "Messages" + Search Bar
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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
                                        text = "Search messages",
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
                            onClick = { refreshThreads() },
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

            // Preselected Peer Phone Source Chip
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

            // Conversations
            if (loadingThreads && threads.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                    }
                }
            } else if (filteredThreads.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No messages matching \"$searchQuery\"" else "No messages found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(filteredThreads, key = { it.id }) { thread ->
                    GoogleMessagesThreadItem(
                        thread = thread,
                        onClick = { loadMessages(thread) },
                    )
                }
            }

            item {
                Spacer(Modifier.height(88.dp))
            }
        }

        // Google Messages "Start chat" Icon-Only FAB
        FloatingActionButton(
            onClick = { showNewMessageDialog = true },
            containerColor = Color(0xFF2E7D32),
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 84.dp)
                .size(58.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_message),
                contentDescription = "Start chat",
                modifier = Modifier.size(26.dp),
            )
        }
    }

    if (showNewMessageDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showNewMessageDialog = false },
            title = { Text("New Message", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newMsgRecipient,
                        onValueChange = { newMsgRecipient = it },
                        label = { Text("To") },
                        placeholder = { Text("Type name or phone number") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newMsgText,
                        onValueChange = { newMsgText = it },
                        label = { Text("Message") },
                        placeholder = { Text("Text message") },
                        maxLines = 4,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val dev = selectedDevice
                        if (dev != null && newMsgRecipient.isNotBlank() && newMsgText.isNotBlank()) {
                            val to = newMsgRecipient.trim()
                            val body = newMsgText.trim()
                            showNewMessageDialog = false
                            newMsgRecipient = ""
                            newMsgText = ""
                            cache.addOptimisticOutgoing(dev.id, "", to, body)
                            scope.launch {
                                core.peerSmsSend(dev.id, listOf(to), body)
                                refreshThreads()
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    enabled = newMsgRecipient.isNotBlank() && newMsgText.isNotBlank() && selectedDevice?.online == true,
                ) {
                    Text("Send", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewMessageDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun GoogleMessagesThreadItem(
    thread: SmsThread,
    onClick: () -> Unit,
) {
    val title = thread.names.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: thread.addresses.firstOrNull() ?: "Conversation"
    val avatarColor = remember(title) { getAvatarColor(title) }
    val timeSpan = remember(thread.date) {
        DateUtils.getRelativeTimeSpanString(thread.date).toString()
    }

    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Google Messages Colorful 50dp Avatar
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(avatarColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (thread.unread > 0u) FontWeight.Bold else FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = timeSpan,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (thread.unread > 0u) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (thread.unread > 0u) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = thread.snippet.ifEmpty { "No messages" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (thread.unread > 0u) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (thread.unread > 0u) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (thread.unread > 0u) {
                        Surface(
                            color = Color(0xFF2E7D32),
                            shape = CircleShape,
                            modifier = Modifier.size(20.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = thread.unread.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GoogleMessagesBubble(msg: SmsMessage) {
    val isOutgoing = msg.outgoing
    val timeSpan = remember(msg.date) {
        DateUtils.getRelativeTimeSpanString(msg.date).toString()
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isOutgoing) 20.dp else 4.dp,
                bottomEnd = if (isOutgoing) 4.dp else 20.dp,
            ),
            color = if (isOutgoing) Color(0xFF1B5E20) else MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(0.78f),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = msg.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isOutgoing) Color.White else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = timeSpan,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOutgoing) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}
