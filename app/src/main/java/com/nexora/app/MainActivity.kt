package com.nexora.app

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NexoraContact(
    val id: String,
    val name: String,
    val phones: List<String>,
    val starred: Boolean
)

data class NexoraCall(
    val id: String,
    val number: String,
    val name: String,
    val type: Int,
    val date: Long
)

enum class NexoraTab {
    CONTACTS,
    FAVORITES,
    DIALER,
    RECENTS
}

enum class ContactFilter {
    ALL,
    RECENT,
    FREQUENT,
    DUPLICATES
}

class MainActivity : ComponentActivity() {

    private val contactsState = mutableStateOf<List<NexoraContact>>(emptyList())
    private val callsState = mutableStateOf<List<NexoraCall>>(emptyList())
    private val selectedContactState = mutableStateOf<NexoraContact?>(null)
    private val currentTabState = mutableStateOf(NexoraTab.CONTACTS)
    private val darkModeState = mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val contactsAllowed =
                result[Manifest.permission.READ_CONTACTS] == true ||
                    hasPermission(Manifest.permission.READ_CONTACTS)

            if (contactsAllowed) {
                loadContacts()
            }

            if (hasPermission(Manifest.permission.READ_CALL_LOG)) {
                loadRecentCalls()
            }
        }

    private val singlePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                loadContacts()
                loadRecentCalls()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        darkModeState.value =
            getSharedPreferences("nexora_settings", MODE_PRIVATE)
                .getBoolean("dark_mode", false)

        setContent {
            NexoraTheme(darkTheme = darkModeState.value) {
                NexoraApp()
            }
        }

        requestInitialPermissions()
    }

    override fun onResume() {
        super.onResume()

        if (hasPermission(Manifest.permission.READ_CONTACTS)) {
            loadContacts()
        }

        if (hasPermission(Manifest.permission.READ_CALL_LOG)) {
            loadRecentCalls()
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestInitialPermissions() {
        val permissions = mutableListOf<String>()

        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            permissions.add(Manifest.permission.READ_CONTACTS)
        }

        if (!hasPermission(Manifest.permission.WRITE_CONTACTS)) {
            permissions.add(Manifest.permission.WRITE_CONTACTS)
        }

        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) {
            permissions.add(Manifest.permission.READ_CALL_LOG)
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            loadContacts()
            loadRecentCalls()
        }
    }

    private fun loadContacts() {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return

        lifecycleScope.launch(Dispatchers.IO) {
            val result = mutableListOf<NexoraContact>()
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.STARRED,
                ContactsContract.Contacts.HAS_PHONE_NUMBER
            )

            contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.Contacts.DISPLAY_NAME + " COLLATE LOCALIZED ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val starredIndex = cursor.getColumnIndex(ContactsContract.Contacts.STARRED)
                val phoneIndex = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

                while (cursor.moveToNext()) {
                    if (idIndex < 0 || nameIndex < 0 || phoneIndex < 0) continue

                    val id = cursor.getString(idIndex) ?: continue
                    val name = cursor.getString(nameIndex) ?: "Unknown"
                    val hasPhone = cursor.getInt(phoneIndex) > 0

                    if (!hasPhone) continue

                    val phones = getPhoneNumbers(id)
                    if (phones.isEmpty()) continue

                    val starred = if (starredIndex >= 0) cursor.getInt(starredIndex) == 1 else false

                    result.add(
                        NexoraContact(
                            id = id,
                            name = name,
                            phones = phones,
                            starred = starred
                        )
                    )
                }
            }

            withContext(Dispatchers.Main) {
                contactsState.value = result
            }
        }
    }

    private fun getPhoneNumbers(contactId: String): List<String> {
        val numbers = mutableListOf<String>()
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)

        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (index >= 0) {
                while (cursor.moveToNext()) {
                    val number = cursor.getString(index)
                    if (!number.isNullOrBlank() && !numbers.contains(number)) {
                        numbers.add(number)
                    }
                }
            }
        }
        return numbers
    }

    private fun loadRecentCalls() {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) return

        lifecycleScope.launch(Dispatchers.IO) {
            val result = mutableListOf<NexoraCall>()
            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE
            )

            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                CallLog.Calls.DATE + " DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(CallLog.Calls._ID)
                val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)

                var count = 0
                while (cursor.moveToNext() && count < 100) {
                    if (idIndex < 0 || numberIndex < 0 || typeIndex < 0 || dateIndex < 0) continue

                    val id = cursor.getString(idIndex) ?: continue
                    val number = cursor.getString(numberIndex) ?: "Unknown"
                    val name = if (nameIndex >= 0) {
                        cursor.getString(nameIndex) ?: findContactName(number)
                    } else {
                        findContactName(number)
                    }

                    result.add(
                        NexoraCall(
                            id = id,
                            number = number,
                            name = name,
                            type = cursor.getInt(typeIndex),
                            date = cursor.getLong(dateIndex)
                        )
                    )
                    count++
                }
            }

            withContext(Dispatchers.Main) {
                callsState.value = result
            }
        }
    }

    private fun findContactName(number: String): String {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return number

        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            "${ContactsContract.CommonDataKinds.Phone.NUMBER} = ?",
            arrayOf(number),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                if (index >= 0) {
                    return cursor.getString(index) ?: number
                }
            }
        }
        return number
    }

    private fun makeCall(number: String) {
        val cleanNumber = number.trim()
        if (cleanNumber.isBlank()) return

        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            singlePermissionLauncher.launch(Manifest.permission.CALL_PHONE)
            return
        }

        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(cleanNumber)}")))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(cleanNumber)}")))
            } catch (_: Exception) {}
        }
    }

    private fun sendSms(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")))
        } catch (_: Exception) {}
    }

    private fun addContact() {
        try {
            startActivity(
                Intent(Intent.ACTION_INSERT).apply {
                    type = ContactsContract.Contacts.CONTENT_TYPE
                }
            )
        } catch (_: Exception) {}
    }

    private fun editContact(contact: NexoraContact) {
        try {
            val uri = ContentUris.withAppendedId(
                ContactsContract.Contacts.CONTENT_URI,
                contact.id.toLong()
            )
            startActivity(Intent(Intent.ACTION_EDIT, uri))
        } catch (_: Exception) {}
    }

    private fun deleteContact(contact: NexoraContact) {
        if (!hasPermission(Manifest.permission.WRITE_CONTACTS)) {
            permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_CONTACTS))
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val uri = ContentUris.withAppendedId(
                    ContactsContract.Contacts.CONTENT_URI,
                    contact.id.toLong()
                )
                contentResolver.delete(uri, null, null)
                withContext(Dispatchers.Main) {
                    selectedContactState.value = null
                    loadContacts()
                }
            } catch (_: Exception) {}
        }
    }

    private fun toggleFavorite(contact: NexoraContact) {
        if (!hasPermission(Manifest.permission.WRITE_CONTACTS)) {
            permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_CONTACTS))
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val uri = ContentUris.withAppendedId(
                ContactsContract.Contacts.CONTENT_URI,
                contact.id.toLong()
            )
            val values = android.content.ContentValues().apply {
                put(ContactsContract.Contacts.STARRED, if (contact.starred) 0 else 1)
            }

            try {
                contentResolver.update(uri, values, null, null)
                withContext(Dispatchers.Main) {
                    loadContacts()
                    selectedContactState.value = contactsState.value.firstOrNull { it.id == contact.id }
                }
            } catch (_: Exception) {}
        }
    }

    private fun setDarkMode(enabled: Boolean) {
        darkModeState.value = enabled
        getSharedPreferences("nexora_settings", MODE_PRIVATE)
            .edit()
            .putBoolean("dark_mode", enabled)
            .apply()
    }

    private fun normalizeNumber(number: String): String {
        return number.filter { it.isDigit() }.takeLast(10)
    }

    private fun recentContactIds(contacts: List<NexoraContact>): Set<String> {
        val recentNumbers = callsState.value
            .take(30)
            .map { normalizeNumber(it.number) }
            .filter { it.isNotBlank() }
            .toSet()

        return contacts
            .filter { contact -> contact.phones.any { normalizeNumber(it) in recentNumbers } }
            .map { it.id }
            .toSet()
    }

    private fun frequentContactIds(contacts: List<NexoraContact>): Set<String> {
        val counts = mutableMapOf<String, Int>()
        callsState.value.forEach { call ->
            val normalized = normalizeNumber(call.number)
            if (normalized.isNotBlank()) {
                counts[normalized] = (counts[normalized] ?: 0) + 1
            }
        }

        return contacts
            .filter { contact -> contact.phones.any { (counts[normalizeNumber(it)] ?: 0) >= 2 } }
            .sortedByDescending { contact -> contact.phones.maxOfOrNull { counts[normalizeNumber(it)] ?: 0 } ?: 0 }
            .map { it.id }
            .toSet()
    }

    private fun duplicateContactIds(contacts: List<NexoraContact>): Set<String> {
        val groups = contacts
            .flatMap { contact ->
                contact.phones.map { number -> normalizeNumber(number) to contact.id }
            }
            .filter { it.first.length >= 7 }
            .groupBy { it.first }

        return groups
            .filterValues { it.map { pair -> pair.second }.distinct().size > 1 }
            .values
            .flatten()
            .map { it.second }
            .toSet()
    }

    @Composable
    private fun NexoraApp() {
        val selectedContact = selectedContactState.value

        if (selectedContact != null) {
            ContactDetailsScreen(
                contact = selectedContact,
                onBack = { selectedContactState.value = null },
                onCall = { makeCall(it) },
                onSms = { sendSms(it) },
                onEdit = { editContact(selectedContact) },
                onDelete = { deleteContact(selectedContact) },
                onFavorite = { toggleFavorite(selectedContact) }
            )
            return
        }

        Scaffold(
            bottomBar = {
                NexoraBottomBar(
                    currentTab = currentTabState.value,
                    onTabSelected = { currentTabState.value = it }
                )
            }
        ) { padding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (currentTabState.value) {
                    NexoraTab.CONTACTS -> ContactsScreen(
                        contacts = contactsState.value,
                        calls = callsState.value,
                        onContactClick = { selectedContactState.value = it },
                        onAdd = { addContact() },
                        onOpenDialer = { currentTabState.value = NexoraTab.DIALER },
                        onToggleDark = { setDarkMode(!darkModeState.value) },
                        darkMode = darkModeState.value
                    )
                    NexoraTab.FAVORITES -> FavoritesScreen(
                        contacts = contactsState.value.filter { it.starred },
                        onContactClick = { selectedContactState.value = it },
                        onAdd = { addContact() }
                    )
                    NexoraTab.DIALER -> DialerScreen(
                        contacts = contactsState.value,
                        onCall = { makeCall(it) },
                        onSms = { sendSms(it) }
                    )
                    NexoraTab.RECENTS -> RecentsScreen(
                        calls = callsState.value,
                        onCall = { makeCall(it) }
                    )
                }
            }
        }
    }

    @Composable
    private fun ContactsScreen(
        contacts: List<NexoraContact>,
        calls: List<NexoraCall>,
        onContactClick: (NexoraContact) -> Unit,
        onAdd: () -> Unit,
        onOpenDialer: () -> Unit,
        onToggleDark: () -> Unit,
        darkMode: Boolean
    ) {
        var search by remember { mutableStateOf("") }
        var filter by remember { mutableStateOf(ContactFilter.ALL) }

        val recentIds = remember(contacts, calls) { recentContactIds(contacts) }
        val frequentIds = remember(contacts, calls) { frequentContactIds(contacts) }
        val duplicateIds = remember(contacts) { duplicateContactIds(contacts) }

        val filtered = remember(contacts, search, filter, recentIds, frequentIds, duplicateIds) {
            var result = when (filter) {
                ContactFilter.ALL -> contacts
                ContactFilter.RECENT -> contacts.filter { it.id in recentIds }
                ContactFilter.FREQUENT -> contacts.filter { it.id in frequentIds }
                ContactFilter.DUPLICATES -> contacts.filter { it.id in duplicateIds }
            }

            if (search.isNotBlank()) {
                result.filter {
                    it.name.contains(search, ignoreCase = true) ||
                        it.phones.any { number -> number.contains(search) }
                }
            } else {
                result
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "NEXORA",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Smart Phone & Contacts",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Row {
                    TextButton(onClick = onToggleDark) {
                        Text(if (darkMode) "Light" else "Dark")
                    }
                    Button(
                        onClick = onAdd,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Add")
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatItem(value = contacts.size.toString(), label = "Contacts")
                    StatItem(value = contacts.count { it.starred }.toString(), label = "Favorites")
                    StatItem(value = calls.size.toString(), label = "Calls")
                    StatItem(value = duplicateIds.size.toString(), label = "Duplicates")
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search name or number") },
                singleLine = true
            )

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterButton(
                    title = "All",
                    selected = filter == ContactFilter.ALL,
                    onClick = { filter = ContactFilter.ALL }
                )
                FilterButton(
                    title = "Recent",
                    selected = filter == ContactFilter.RECENT,
                    onClick = { filter = ContactFilter.RECENT }
                )
                FilterButton(
                    title = "Frequent",
                    selected = filter == ContactFilter.FREQUENT,
                    onClick = { filter = ContactFilter.FREQUENT }
                )
                FilterButton(
                    title = "Duplicate",
                    selected = filter == ContactFilter.DUPLICATES,
                    onClick = { filter = ContactFilter.DUPLICATES }
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${filtered.size} contacts",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(onClick = onOpenDialer) {
                    Text("Open Dialer")
                }
            }

            Spacer(Modifier.height(4.dp))

            if (filtered.isEmpty()) {
                EmptyState(
                    title = when (filter) {
                        ContactFilter.DUPLICATES -> "No duplicate contacts"
                        ContactFilter.RECENT -> "No recent contacts"
                        ContactFilter.FREQUENT -> "No frequent contacts"
                        ContactFilter.ALL -> if (search.isBlank()) "No contacts found" else "No matching contacts"
                    },
                    message = if (search.isBlank()) "Your phone contacts will appear here." else "Try another name or phone number."
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { contact ->
                        ContactRow(
                            contact = contact,
                            onClick = { onContactClick(contact) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun StatItem(value: String, label: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }

    // RowScope add karne se Modifier.weight() resolve ho jayega
    @Composable
    private fun RowScope.FilterButton(
        title: String,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        if (selected) {
            Button(
                onClick = onClick,
                modifier = Modifier.weight(1f),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                Text(title)
            }
        } else {
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.weight(1f),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                Text(title)
            }
        }
    }

    @Composable
    private fun FavoritesScreen(
        contacts: List<NexoraContact>,
        onContactClick: (NexoraContact) -> Unit,
        onAdd: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Favorites",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(text = "${contacts.size} favorite contacts")
                }
                Button(onClick = onAdd) {
                    Text("Add")
                }
            }

            Spacer(Modifier.height(16.dp))

            if (contacts.isEmpty()) {
                EmptyState(
                    title = "No favorites",
                    message = "Open a contact and mark it as favorite."
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(contacts, key = { it.id }) { contact ->
                        ContactRow(
                            contact = contact,
                            onClick = { onContactClick(contact) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ContactRow(contact: NexoraContact, onClick: () -> Unit) {
        val firstLetter = contact.name.trim().firstOrNull()?.uppercase() ?: "?"

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = firstLetter, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = contact.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = contact.phones.firstOrNull() ?: "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (contact.phones.size > 1) {
                        Text(
                            text = "+${contact.phones.size - 1} more number",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (contact.starred) {
                    Text(
                        text = "Favorite",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    @Composable
    private fun ContactDetailsScreen(
        contact: NexoraContact,
        onBack: () -> Unit,
        onCall: (String) -> Unit,
        onSms: (String) -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit,
        onFavorite: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            TextButton(onClick = onBack) {
                Text("Back")
            }

            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .size(86.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = contact.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { contact.phones.firstOrNull()?.let(onCall) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Call")
                }

                OutlinedButton(
                    onClick = { contact.phones.firstOrNull()?.let(onSms) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("SMS")
                }
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = onFavorite,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (contact.starred) "Remove Favorite" else "Add to Favorites")
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = "Phone numbers",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(contact.phones) { number ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(text = number, style = MaterialTheme.typography.bodyLarge)
                            Row {
                                TextButton(onClick = { onCall(number) }) {
                                    Text("Call")
                                }
                                TextButton(onClick = { onSms(number) }) {
                                    Text("SMS")
                                }
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onEdit,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Edit Contact")
            }

            Spacer(Modifier.height(6.dp))

            TextButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Delete Contact")
            }
        }
    }

    @Composable
    private fun DialerScreen(
        contacts: List<NexoraContact>,
        onCall: (String) -> Unit,
        onSms: (String) -> Unit
    ) {
        var number by remember { mutableStateOf("") }

        val matches = remember(number, contacts) {
            if (number.isBlank()) {
                emptyList()
            } else {
                contacts.filter { contact ->
                    t9Matches(contact.name, number) ||
                        contact.phones.any {
                            normalizeNumber(it).contains(normalizeNumber(number))
                        }
                }.take(5)
            }
        }

        val keys = listOf(
            "1" to "", "2" to "ABC", "3" to "DEF",
            "4" to "GHI", "5" to "JKL", "6" to "MNO",
            "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
            "*" to "", "0" to "+", "#" to ""
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Dialer",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = number.ifBlank { "Enter number or search contact" },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            if (matches.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                ) {
                    items(matches, key = { it.id }) { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    number = contact.phones.firstOrNull() ?: number
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = contact.name.firstOrNull()?.uppercase() ?: "?"
                                )
                            }

                            Spacer(Modifier.width(10.dp))

                            Column {
                                Text(
                                    text = contact.name,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(text = contact.phones.firstOrNull() ?: "")
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }

            keys.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { (key, letters) ->
                        Button(
                            onClick = { number += key },
                            modifier = Modifier.size(82.dp),
                            shape = CircleShape
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = key, style = MaterialTheme.typography.titleLarge)
                                if (letters.isNotBlank()) {
                                    Text(text = letters, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { if (number.isNotEmpty()) number = number.dropLast(1) }) {
                    Text("Delete")
                }
                Button(onClick = { if (number.isNotBlank()) onCall(number) }) {
                    Text("Call")
                }
                OutlinedButton(onClick = { if (number.isNotBlank()) onSms(number) }) {
                    Text("SMS")
                }
            }
        }
    }

    private fun t9Matches(name: String, input: String): Boolean {
        val digits = input.filter { it.isDigit() }
        if (digits.isBlank()) return false

        val normalizedName = name.uppercase(Locale.getDefault()).filter { it.isLetterOrDigit() }

        val t9 = normalizedName.map { char ->
            when (char) {
                in 'A'..'C' -> '2'
                in 'D'..'F' -> '3'
                in 'G'..'I' -> '4'
                in 'J'..'L' -> '5'
                in 'M'..'O' -> '6'
                in 'P'..'S' -> '7'
                in 'T'..'V' -> '8'
                in 'W'..'Z' -> '9'
                else -> char
            }
        }.joinToString("")

        return t9.contains(digits)
    }

    @Composable
    private fun RecentsScreen(
        calls: List<NexoraCall>,
        onCall: (String) -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Text(
                text = "Recent Calls",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Latest ${calls.size} calls",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(14.dp))

            if (calls.isEmpty()) {
                EmptyState(
                    title = "No recent calls",
                    message = "Your recent phone calls will appear here."
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(calls, key = { it.id }) { call ->
                        RecentCallRow(
                            call = call,
                            onCall = { onCall(call.number) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun RecentCallRow(call: NexoraCall, onCall: () -> Unit) {
        val callType = when (call.type) {
            CallLog.Calls.INCOMING_TYPE -> "Incoming"
            CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
            CallLog.Calls.MISSED_TYPE -> "Missed"
            CallLog.Calls.REJECTED_TYPE -> "Rejected"
            else -> "Call"
        }

        val dateText = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(call.date))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = call.name.ifBlank { call.number },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = call.number,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "$callType • $dateText",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                OutlinedButton(onClick = onCall) {
                    Text("Call")
                }
            }
        }
    }

    @Composable
    private fun EmptyState(title: String, message: String) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(text = message, textAlign = TextAlign.Center)
        }
    }

    @Composable
    private fun NexoraBottomBar(
        currentTab: NexoraTab,
        onTabSelected: (NexoraTab) -> Unit
    ) {
        Surface(tonalElevation = 5.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BottomItem(
                    title = "Contacts",
                    selected = currentTab == NexoraTab.CONTACTS,
                    onClick = { onTabSelected(NexoraTab.CONTACTS) }
                )
                BottomItem(
                    title = "Favorites",
                    selected = currentTab == NexoraTab.FAVORITES,
                    onClick = { onTabSelected(NexoraTab.FAVORITES) }
                )
                BottomItem(
                    title = "Dialer",
                    selected = currentTab == NexoraTab.DIALER,
                    onClick = { onTabSelected(NexoraTab.DIALER) }
                )
                BottomItem(
                    title = "Recents",
                    selected = currentTab == NexoraTab.RECENTS,
                    onClick = { onTabSelected(NexoraTab.RECENTS) }
                )
            }
        }
    }

    @Composable
    private fun BottomItem(
        title: String,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        TextButton(onClick = onClick) {
            Text(
                text = title,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun NexoraTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) {
            androidx.compose.material3.darkColorScheme()
        } else {
            androidx.compose.material3.lightColorScheme()
        },
        content = content
    )
}
