// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.ui.contacts

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.RawContacts
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nectarlink.android.R
import app.nectarlink.android.calls.ActiveCallManager
import app.nectarlink.android.core.Core
import app.nectarlink.android.core.CoreState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ContactNumberInfo(
    val number: String,
    val label: String?,
)

data class DeviceContact(
    val id: String,
    val name: String,
    val starred: Boolean,
    val photoUri: String?,
    val numbers: List<ContactNumberInfo>,
    val accountType: String?,
    val isGoogle: Boolean,
)

enum class ContactSourceFilter {
    All,
    Favorites,
    Google,
    Device,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    state: CoreState,
    core: Core,
    onNavigateToCalls: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
    }

    var allContacts by remember { mutableStateOf<List<DeviceContact>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var activeFilter by rememberSaveable { mutableStateOf(ContactSourceFilter.All) }
    var selectedContactForDetails by remember { mutableStateOf<DeviceContact?>(null) }

    // Peer device selection for Android-to-Android remote calling
    val availableAndroidDevices = remember(state.devices) { state.devices.filter { it.isAndroid } }
    var selectedDevice by remember(availableAndroidDevices) {
        mutableStateOf(availableAndroidDevices.firstOrNull { it.online } ?: availableAndroidDevices.firstOrNull())
    }
    var callViaPeer by remember(selectedDevice) {
        mutableStateOf(selectedDevice != null && selectedDevice!!.online)
    }

    fun initiateCall(number: String, contactName: String? = null) {
        val clean = number.trim()
        if (clean.isEmpty()) return
        selectedContactForDetails = null
        val dev = selectedDevice
        if (callViaPeer && dev != null) {
            if (dev.online) {
                ActiveCallManager.startOutgoing(dev.id, dev.name, clean, contactName)
                scope.launch {
                    core.peerDial(dev.id, clean)
                }
                val label = if (!contactName.isNullOrBlank()) "$contactName ($clean)" else clean
                Toast.makeText(context, "Calling $label via ${dev.name}…", Toast.LENGTH_SHORT).show()
                onNavigateToCalls()
            } else {
                Toast.makeText(context, "${dev.name} is offline. Please switch to this phone or reconnect.", Toast.LENGTH_SHORT).show()
            }
        } else {
            ActiveCallManager.startOutgoing(null, null, clean, contactName)
            callPhoneNumber(context, clean)
            onNavigateToCalls()
        }
    }

    fun refreshContacts() {
        if (!hasPermission) return
        loading = true
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                loadDeviceContacts(context)
            }
            allContacts = list
            loading = false
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            refreshContacts()
        }
    }

    // Filter contacts based on search query and active filter
    val filteredContacts = remember(allContacts, searchQuery, activeFilter) {
        val q = searchQuery.trim()
        val qDigits = q.filter(Char::isDigit)

        allContacts.asSequence()
            .filter { contact ->
                when (activeFilter) {
                    ContactSourceFilter.All -> true
                    ContactSourceFilter.Favorites -> contact.starred
                    ContactSourceFilter.Google -> contact.isGoogle
                    ContactSourceFilter.Device -> !contact.isGoogle
                }
            }
            .filter { contact ->
                if (q.isEmpty()) return@filter true
                contact.name.contains(q, ignoreCase = true) ||
                    contact.numbers.any { n ->
                        n.number.contains(q, ignoreCase = true) ||
                            (qDigits.isNotEmpty() && n.number.filter(Char::isDigit).contains(qDigits))
                    }
            }
            .toList()
    }

    val favorites = remember(allContacts) {
        allContacts.filter { it.starred }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Contacts",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (allContacts.isNotEmpty()) {
                    Text(
                        text = "${allContacts.size} contacts on device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (hasPermission) {
                IconButton(onClick = { refreshContacts() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (!hasPermission) {
            // Permission Rationale Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_contacts),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Contacts Access Required",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "To browse contacts from Google and your device, and to quick-call your favorites, please grant contacts permission.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { requestPermission.launch(Manifest.permission.READ_CONTACTS) },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Text("Grant Permission")
                    }
                }
            }
            return
        }

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search by name or number...") },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_backspace),
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.Transparent,
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        )

        Spacer(Modifier.height(8.dp))

        // Calling Target Device Selector Pill (Android-to-Android remote calling)
        if (availableAndroidDevices.isNotEmpty()) {
            var showDeviceMenu by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.clickable { showDeviceMenu = true },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = if (callViaPeer && selectedDevice?.online == true) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                            modifier = Modifier.size(8.dp),
                        ) {}
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (callViaPeer && selectedDevice != null) {
                                "Call via ${selectedDevice!!.name}${if (selectedDevice!!.online) "" else " (Offline)"}"
                            } else {
                                "Call via this phone (Local)"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "▾",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                DropdownMenu(
                    expanded = showDeviceMenu,
                    onDismissRequest = { showDeviceMenu = false },
                ) {
                    availableAndroidDevices.forEach { dev ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = if (dev.online) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
                                        shape = CircleShape,
                                        modifier = Modifier.size(8.dp),
                                    ) {}
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Call via ${dev.name}${if (dev.online) " (Connected)" else " (Offline)"}",
                                        fontWeight = if (callViaPeer && selectedDevice?.id == dev.id) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            },
                            onClick = {
                                selectedDevice = dev
                                callViaPeer = true
                                showDeviceMenu = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_call),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Call via this phone (Local)",
                                    fontWeight = if (!callViaPeer) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        },
                        onClick = {
                            callViaPeer = false
                            showDeviceMenu = false
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // Filter Chips Row
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item {
                FilterChip(
                    selected = activeFilter == ContactSourceFilter.All,
                    onClick = { activeFilter = ContactSourceFilter.All },
                    label = { Text("All (${allContacts.size})") },
                    shape = RoundedCornerShape(16.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
            item {
                FilterChip(
                    selected = activeFilter == ContactSourceFilter.Favorites,
                    onClick = { activeFilter = ContactSourceFilter.Favorites },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_star),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFFFFB300),
                        )
                    },
                    label = { Text("Favorites (${favorites.size})") },
                    shape = RoundedCornerShape(16.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
            item {
                FilterChip(
                    selected = activeFilter == ContactSourceFilter.Google,
                    onClick = { activeFilter = ContactSourceFilter.Google },
                    label = { Text("Google (${allContacts.count { it.isGoogle }})") },
                    shape = RoundedCornerShape(16.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
            item {
                FilterChip(
                    selected = activeFilter == ContactSourceFilter.Device,
                    onClick = { activeFilter = ContactSourceFilter.Device },
                    label = { Text("Device (${allContacts.count { !it.isGoogle }})") },
                    shape = RoundedCornerShape(16.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Quick-Dial Favorites Row (when not filtering away and favorites exist)
            if (favorites.isNotEmpty() && activeFilter != ContactSourceFilter.Device && searchQuery.isEmpty()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_star),
                                contentDescription = null,
                                tint = Color(0xFFFFB300),
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "FAVORITES • QUICK CALL",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(favorites, key = { "fav_${it.id}" }) { contact ->
                                FavoriteQuickDialCard(
                                    contact = contact,
                                    context = context,
                                    onClick = {
                                        val num = contact.numbers.firstOrNull()?.number
                                        if (num != null) {
                                            initiateCall(num, contact.name)
                                        } else {
                                            selectedContactForDetails = contact
                                        }
                                    },
                                    onLongClick = { selectedContactForDetails = contact },
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }

            if (filteredContacts.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No contacts matching \"$searchQuery\"" else "No contacts found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(filteredContacts, key = { it.id }) { contact ->
                    ContactRowCard(
                        contact = contact,
                        onCall = { num -> initiateCall(num, contact.name) },
                        onMessage = { num -> messagePhoneNumber(context, num) },
                        onClick = { selectedContactForDetails = contact },
                    )
                }
            }
        }
    }

    // Contact Details Modal Bottom Sheet
    selectedContactForDetails?.let { contact ->
        ModalBottomSheet(
            onDismissRequest = { selectedContactForDetails = null },
            sheetState = rememberModalBottomSheetState(),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            ContactDetailsSheetContent(
                contact = contact,
                context = context,
                callingDeviceName = if (callViaPeer) selectedDevice?.name else null,
                onCall = { num -> initiateCall(num, contact.name) },
                onMessage = { num -> messagePhoneNumber(context, num) },
                onClose = { selectedContactForDetails = null },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteQuickDialCard(
    contact: DeviceContact,
    context: Context,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val resolver = context.contentResolver
    val photoBitmap = remember(contact.photoUri) {
        contact.photoUri?.let { loadContactBitmap(resolver, it) }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Box(
            modifier = Modifier.size(58.dp),
            contentAlignment = Alignment.BottomEnd,
        ) {
            if (photoBitmap != null) {
                Image(
                    bitmap = photoBitmap.asImageBitmap(),
                    contentDescription = contact.name,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.primaryContainer, CircleShape),
                )
            } else {
                val initial = contact.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                val bgColor = remember(contact.name) { pickAvatarColor(contact.name) }
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(bgColor)
                        .border(2.dp, MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = initial,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                }
            }

            // Quick Call Floating Action Badge
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2E7D32))
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_call),
                    contentDescription = "Quick Call",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = contact.name,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ContactRowCard(
    contact: DeviceContact,
    onCall: (String) -> Unit,
    onMessage: (String) -> Unit,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val photoBitmap = remember(contact.photoUri) {
        contact.photoUri?.let { loadContactBitmap(resolver, it) }
    }
    val primaryNumber = contact.numbers.firstOrNull()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar
            if (photoBitmap != null) {
                Image(
                    bitmap = photoBitmap.asImageBitmap(),
                    contentDescription = contact.name,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape),
                )
            } else {
                val initial = contact.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                val bgColor = remember(contact.name) { pickAvatarColor(contact.name) }
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(bgColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = initial,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = contact.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (contact.starred) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            painter = painterResource(R.drawable.ic_star),
                            contentDescription = "Starred",
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }

                Spacer(Modifier.height(2.dp))

                if (primaryNumber != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = primaryNumber.number,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        primaryNumber.label?.let { lbl ->
                            Text(
                                text = "• $lbl",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Account Source Badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (contact.isGoogle) {
                        Color(0xFFE8F0FE)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = if (contact.isGoogle) "Google" else "Device",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = if (contact.isGoogle) Color(0xFF1967D2) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            // Quick Actions
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (primaryNumber != null) {
                    IconButton(
                        onClick = { onCall(primaryNumber.number) },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE8F5E9)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_call),
                                contentDescription = "Call",
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    IconButton(
                        onClick = { onMessage(primaryNumber.number) },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_message),
                                contentDescription = "Message",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactDetailsSheetContent(
    contact: DeviceContact,
    context: Context,
    callingDeviceName: String?,
    onCall: (String) -> Unit,
    onMessage: (String) -> Unit,
    onClose: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val resolver = context.contentResolver
    val photoBitmap = remember(contact.photoUri) {
        contact.photoUri?.let { loadContactBitmap(resolver, it) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Large Avatar
        if (photoBitmap != null) {
            Image(
                bitmap = photoBitmap.asImageBitmap(),
                contentDescription = contact.name,
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape),
            )
        } else {
            val initial = contact.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            val bgColor = remember(contact.name) { pickAvatarColor(contact.name) }
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(bgColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = contact.name,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (contact.starred) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_star),
                    contentDescription = "Starred",
                    tint = Color(0xFFFFB300),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Account tag
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (contact.isGoogle) Color(0xFFE8F0FE) else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = if (contact.isGoogle) "Google Account (${contact.accountType})" else "Device Local Storage",
                style = MaterialTheme.typography.labelMedium,
                color = if (contact.isGoogle) Color(0xFF1967D2) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        // Prominent Primary Call Button
        if (contact.numbers.isNotEmpty()) {
            val primaryNum = contact.numbers.first().number
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onCall(primaryNum) },
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E7D32),
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_call),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (callingDeviceName != null) "Call via $callingDeviceName" else "Call $primaryNum",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Numbers List
        Text(
            text = "PHONE NUMBERS",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        contact.numbers.forEach { numInfo ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = numInfo.number,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = numInfo.label ?: "Phone",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { onCall(numInfo.number) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_call),
                                contentDescription = "Call",
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(onClick = { onMessage(numInfo.number) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_message),
                                contentDescription = "Message",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(numInfo.number))
                                Toast.makeText(context, "Number copied", Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_clipboard),
                                contentDescription = "Copy",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun loadDeviceContacts(context: Context): List<DeviceContact> {
    val resolver = context.contentResolver
    val drafts = LinkedHashMap<Long, ContactDraft>()
    val accountMap = HashMap<Long, String>()

    // First map contactId -> accountType from RawContacts
    runCatching {
        val rawCols = arrayOf(RawContacts.CONTACT_ID, RawContacts.ACCOUNT_TYPE)
        resolver.query(RawContacts.CONTENT_URI, rawCols, null, null, null)?.use { rc ->
            val cIdIdx = rc.getColumnIndex(RawContacts.CONTACT_ID)
            val acctIdx = rc.getColumnIndex(RawContacts.ACCOUNT_TYPE)
            while (rc.moveToNext()) {
                if (cIdIdx >= 0 && acctIdx >= 0) {
                    val cId = rc.getLong(cIdIdx)
                    val acct = rc.getString(acctIdx)
                    if (acct != null) {
                        val existing = accountMap[cId]
                        if (existing == null || (!existing.contains("google", ignoreCase = true) && acct.contains("google", ignoreCase = true))) {
                            accountMap[cId] = acct
                        }
                    }
                }
            }
        }
    }.onFailure { android.util.Log.w("ContactsScreen", "Failed to query raw contacts", it) }

    val columns = arrayOf(
        Phone.CONTACT_ID,
        Phone.DISPLAY_NAME_PRIMARY,
        Phone.STARRED,
        Phone.PHOTO_THUMBNAIL_URI,
        Phone.NUMBER,
        Phone.TYPE,
        Phone.LABEL,
    )
    val order = "${Phone.STARRED} DESC, ${Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC, ${Phone.CONTACT_ID} ASC"

    try {
        resolver.query(Phone.CONTENT_URI, columns, null, null, order)?.use { c ->
            val idIdx = c.getColumnIndex(Phone.CONTACT_ID)
            val nameIdx = c.getColumnIndex(Phone.DISPLAY_NAME_PRIMARY)
            val starredIdx = c.getColumnIndex(Phone.STARRED)
            val photoIdx = c.getColumnIndex(Phone.PHOTO_THUMBNAIL_URI)
            val numIdx = c.getColumnIndex(Phone.NUMBER)
            val typeIdx = c.getColumnIndex(Phone.TYPE)
            val labelIdx = c.getColumnIndex(Phone.LABEL)

            while (c.moveToNext()) {
                val contactId = if (idIdx >= 0) c.getLong(idIdx) else continue
                val name = if (nameIdx >= 0) c.getString(nameIdx)?.trim().orEmpty() else ""
                val number = if (numIdx >= 0) c.getString(numIdx)?.trim().orEmpty() else ""
                if (name.isEmpty() || number.isEmpty()) continue

                val draft = drafts.getOrPut(contactId) {
                    val acctType = accountMap[contactId]
                    ContactDraft(
                        id = contactId.toString(),
                        name = name,
                        starred = if (starredIdx >= 0) c.getInt(starredIdx) != 0 else false,
                        photoUri = if (photoIdx >= 0) c.getString(photoIdx) else null,
                        accountType = acctType,
                        isGoogle = acctType?.contains("google", ignoreCase = true) == true,
                    )
                }

                val key = number.filter(Char::isDigit).ifEmpty { number }
                if (draft.seenNumbers.add(key)) {
                    val type = if (typeIdx >= 0) c.getInt(typeIdx) else Phone.TYPE_OTHER
                    val customLabel = if (labelIdx >= 0) c.getString(labelIdx) else null
                    draft.numbers.add(
                        ContactNumberInfo(
                            number = number,
                            label = resolvePhoneLabel(type, customLabel),
                        ),
                    )
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("ContactsScreen", "Failed to query contacts", e)
    }

    return drafts.values.map { d ->
        DeviceContact(
            id = d.id,
            name = d.name,
            starred = d.starred,
            photoUri = d.photoUri,
            numbers = d.numbers,
            accountType = d.accountType,
            isGoogle = d.isGoogle,
        )
    }
}

private class ContactDraft(
    val id: String,
    val name: String,
    val starred: Boolean,
    val photoUri: String?,
    val accountType: String?,
    val isGoogle: Boolean,
    val numbers: MutableList<ContactNumberInfo> = ArrayList(2),
    val seenNumbers: HashSet<String> = HashSet(2),
)

private fun resolvePhoneLabel(type: Int, custom: String?): String = when (type) {
    Phone.TYPE_CUSTOM -> custom?.trim()?.takeIf { it.isNotEmpty() } ?: "Custom"
    Phone.TYPE_MOBILE -> "Mobile"
    Phone.TYPE_HOME -> "Home"
    Phone.TYPE_WORK -> "Work"
    Phone.TYPE_MAIN -> "Main"
    Phone.TYPE_FAX_WORK, Phone.TYPE_FAX_HOME -> "Fax"
    Phone.TYPE_PAGER -> "Pager"
    Phone.TYPE_OTHER -> "Other"
    else -> "Phone"
}

private fun loadContactBitmap(resolver: ContentResolver, uriStr: String): Bitmap? = runCatching {
    val uri = Uri.parse(uriStr)
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
}.getOrNull()

private fun callPhoneNumber(context: Context, number: String) {
    val clean = number.trim()
    if (clean.isEmpty()) return
    runCatching {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(clean)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(callIntent)
        } else {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(clean)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
        }
    }
}

private fun messagePhoneNumber(context: Context, number: String) {
    val clean = number.trim()
    if (clean.isEmpty()) return
    runCatching {
        val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(clean)}")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(smsIntent)
    }
}

private fun pickAvatarColor(name: String): Color {
    val colors = listOf(
        Color(0xFFE53935),
        Color(0xFFD81B60),
        Color(0xFF8E24AA),
        Color(0xFF5E35B1),
        Color(0xFF3949AB),
        Color(0xFF1E88E5),
        Color(0xFF039BE5),
        Color(0xFF00ACC1),
        Color(0xFF00897B),
        Color(0xFF43A047),
        Color(0xFF7CB342),
        Color(0xFFFB8C00),
        Color(0xFFF4511E),
        Color(0xFF6D4C41),
        Color(0xFF546E7A),
    )
    val idx = ((name.hashCode().toLong() and 0x7FFFFFFF) % colors.size.toLong()).toInt()
    return colors[idx]
}
