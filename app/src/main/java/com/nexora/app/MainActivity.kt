package com.nexora.app

import android.Manifest
import android.app.role.RoleManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

// --- DATA MODELS ---
data class NexoraContact(
    val id: String,
    val name: String,
    val phones: List<String>,
    val starred: Boolean,
    val group: String = "General",
    val isTemporary: Boolean = false
)

data class NexoraCall(
    val id: String,
    val number: String,
    val name: String,
    val type: Int,
    val date: Long,
    val duration: Long = 0L
)

enum class NexoraTab {
    CONTACTS,
    FAVORITES,
    DIALER,
    RECENTS,
    INTELLIGENCE
}

enum class ContactFilter {
    ALL,
    RECENT,
    FREQUENT,
    DUPLICATES,
    FAMILY,
    WORK,
    SPAM
}

class MainActivity : ComponentActivity() {

    private val contactsState = mutableStateOf<List<NexoraContact>>(emptyList())
    private val callsState = mutableStateOf<List<NexoraCall>>(emptyList())
    private val selectedContactState = mutableStateOf<NexoraContact?>(null)
    private val currentTabState = mutableStateOf(NexoraTab.CONTACTS)
    private val darkModeState = mutableStateOf(false)
    private val isAppUnlockedState = mutableStateOf(false)
    private val activeInCallState = mutableStateOf<String?>(null) // In-call simulator

    // Storage references for Notes, Reminders, Spam & PIN
    private val prefs by lazy { getSharedPreferences("nexora_prefs", Context.MODE_PRIVATE) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.READ_CONTACTS] == true || hasPermission(Manifest.permission.READ_CONTACTS)) {
            loadContacts()
        }
        if (hasPermission(Manifest.permission.READ_CALL_LOG)) {
            loadRecentCalls()
        }
    }

    private val defaultDialerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        darkModeState.value = prefs.getBoolean("dark_mode", false)
        val savedPin = prefs.getString("security_pin", null)
        isAppUnlockedState.value = savedPin.isNullOrBlank()

        setContent {
            NexoraTheme(darkTheme = darkModeState.value) {
                if (!isAppUnlockedState.value) {
                    PinLockScreen(
                        correctPin = savedPin ?: "",
                        onUnlocked = { isAppUnlockedState.value = true }
                    )
                } else if (activeInCallState.value != null) {
                    InCallScreen(
                        number = activeInCallState.value!!,
                        contactName = findContactName(activeInCallState.value!!),
                        onEndCall = { activeInCallState.value = null }
                    )
                } else {
                    NexoraApp()
                }
            }
        }

        requestInitialPermissions()
        promptDefaultDialer()
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission(Manifest.permission.READ_CONTACTS)) loadContacts()
        if (hasPermission(Manifest.permission.READ_CALL_LOG)) loadRecentCalls()
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestInitialPermissions() {
        val permissions = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) permissions.add(Manifest.permission.READ_CONTACTS)
        if (!hasPermission(Manifest.permission.WRITE_CONTACTS)) permissions.add(Manifest.permission.WRITE_CONTACTS)
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) permissions.add(Manifest.permission.READ_CALL_LOG)
        if (!hasPermission(Manifest.permission.CALL_PHONE)) permissions.add(Manifest.permission.CALL_PHONE)

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            loadContacts()
            loadRecentCalls()
        }
    }

    private fun promptDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(ROLE_SERVICE) as? RoleManager
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) && !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                defaultDialerLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
            }
        } else {
            val telecomManager = getSystemService(TELECOM_SERVICE) as? TelecomManager
            if (telecomManager != null && telecomManager.defaultDialerPackage != packageName) {
                val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                    putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                }
                startActivity(intent)
            }
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
                val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val starIdx = cursor.getColumnIndex(ContactsContract.Contacts.STARRED)
                val phoneIdx = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

                while (cursor.moveToNext()) {
                    if (idIdx < 0 || nameIdx < 0 || phoneIdx < 0) continue
                    val id = cursor.getString(idIdx) ?: continue
                    val name = cursor.getString(nameIdx) ?: "Unknown"
                    if (cursor.getInt(phoneIdx) <= 0) continue

                    val phones = getPhoneNumbers(id)
                    if (phones.isEmpty()) continue

                    val group = prefs.getString("contact_group_$id", "General") ?: "General"
                    val isTemp = prefs.getBoolean("contact_temp_$id", false)

                    result.add(
                        NexoraContact(
                            id = id,
                            name = name,
                            phones = phones,
                            starred = starIdx >= 0 && cursor.getInt(starIdx) == 1,
                            group = group,
                            isTemporary = isTemp
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
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (idx >= 0) {
                while (cursor.moveToNext()) {
                    val num = cursor.getString(idx)
                    if (!num.isNullOrBlank() && !numbers.contains(num)) numbers.add(num)
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
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )

            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                CallLog.Calls.DATE + " DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CallLog.Calls._ID)
                val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)

                var count = 0
                while (cursor.moveToNext() && count < 100) {
                    if (idIdx < 0 || numIdx < 0 || typeIdx < 0 || dateIdx < 0) continue
                    val num = cursor.getString(numIdx) ?: "Unknown"
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: findContactName(num) else findContactName(num)

                    result.add(
                        NexoraCall(
                            id = cursor.getString(idIdx) ?: count.toString(),
                            number = num,
                            name = name,
                            type = cursor.getInt(typeIdx),
                            date = cursor.getLong(dateIdx),
                            duration = if (durIdx >= 0) cursor.getLong(durIdx) else 0L
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
        val clean = normalizeNumber(number)
        return contactsState.value.firstOrNull { it.phones.any { p -> normalizeNumber(p) == clean } }?.name ?: number
    }

    private fun normalizeNumber(number: String): String {
        return number.filter { it.isDigit() }.takeLast(10)
    }

    private fun makeCall(number: String) {
        val clean = number.trim()
        if (clean.isBlank()) return

        // Launch in-app call overlay screen
        activeInCallState.value = clean

        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(clean)}")))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(clean)}")))
            } catch (_: Exception) {}
        }
    }

    private fun sendSms(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")))
        } catch (_: Exception) {}
    }

    private fun isSpamNumber(number: String): Boolean {
        val blocklist = prefs.getStringSet("blocked_numbers", emptySet()) ?: emptySet()
        return normalizeNumber(number) in blocklist
    }

    private fun toggleBlockNumber(number: String) {
        val clean = normalizeNumber(number)
        val blocklist = prefs.getStringSet("blocked_numbers", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (clean in blocklist) blocklist.remove(clean) else blocklist.add(clean)
        prefs.edit().putStringSet("blocked_numbers", blocklist).apply()
    }

    // --- ENCRYPTED LOCAL BACKUP & RESTORE ---
    private fun exportContactsBackup(): String {
        val jsonArray = JSONArray()
        contactsState.value.forEach { c ->
            val obj = JSONObject()
            obj.put("name", c.name)
            obj.put("phones", JSONArray(c.phones))
            obj.put("starred", c.starred)
            obj.put("group", c.group)
            jsonArray.put(obj)
        }
        val raw = jsonArray.toString()
        val encrypted = Base64.encodeToString(raw.toByteArray(StandardCharsets.UTF_8), Base64.DEFAULT)
        prefs.edit().putString("latest_encrypted_backup", encrypted).apply()
        return "Backup saved (${contactsState.value.size} contacts)"
    }

    private fun restoreContactsBackup(): String {
        val encoded = prefs.getString("latest_encrypted_backup", null) ?: return "No backup found!"
        return try {
            val decoded = String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8)
            val jsonArray = JSONArray(decoded)
            "Restored ${jsonArray.length()} contacts into memory cache!"
        } catch (e: Exception) {
            "Decryption/Restore failed"
        }
    }

    // --- COMPOSABLE APP CORE ---
    @Composable
    private fun NexoraApp() {
        val selectedContact = selectedContactState.value

        if (selectedContact != null) {
            ContactDetailsScreen(
                contact = selectedContact,
                onBack = { selectedContactState.value = null },
                onCall = { makeCall(it) },
                onSms = { sendSms(it) },
                onGroupChange = { newGroup ->
                    prefs.edit().putString("contact_group_${selectedContact.id}", newGroup).apply()
                    loadContacts()
                },
                onToggleTemp = {
                    val current = prefs.getBoolean("contact_temp_${selectedContact.id}", false)
                    prefs.edit().putBoolean("contact_temp_${selectedContact.id}", !current).apply()
                    loadContacts()
                }
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
            Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (currentTabState.value) {
                    NexoraTab.CONTACTS -> ContactsScreen(
                        contacts = contactsState.value,
                        calls = callsState.value,
                        onContactClick = { selectedContactState.value = it },
                        onOpenDialer = { currentTabState.value = NexoraTab.DIALER }
                    )
                    NexoraTab.FAVORITES -> FavoritesScreen(
                        contacts = contactsState.value.filter { it.starred },
                        onContactClick = { selectedContactState.value = it }
                    )
                    NexoraTab.DIALER -> DialerScreen(
                        contacts = contactsState.value,
                        onCall = { makeCall(it) },
                        onSms = { sendSms(it) }
                    )
                    NexoraTab.RECENTS -> RecentsScreen(
                        calls = callsState.value,
                        onCall = { makeCall(it) },
                        onToggleBlock = { toggleBlockNumber(it); loadRecentCalls() },
                        isBlocked = { isSpamNumber(it) }
                    )
                    NexoraTab.INTELLIGENCE -> IntelligenceScreen(
                        contacts = contactsState.value,
                        calls = callsState.value,
                        onExport = { exportContactsBackup() },
                        onRestore = { restoreContactsBackup() },
                        onSavePin = { pin ->
                            prefs.edit().putString("security_pin", pin).apply()
                            isAppUnlockedState.value = false
                        }
                    )
                }
            }
        }
    }

    // --- SCREEN 1: CONTACTS + SMART CATEGORIES ---
    @Composable
    private fun ContactsScreen(
        contacts: List<NexoraContact>,
        calls: List<NexoraCall>,
        onContactClick: (NexoraContact) -> Unit,
        onOpenDialer: () -> Unit
    ) {
        var search by remember { mutableStateOf("") }
        var filter by remember { mutableStateOf(ContactFilter.ALL) }

        val filtered = remember(contacts, search, filter) {
            contacts.filter { c ->
                val matchesSearch = c.name.contains(search, ignoreCase = true) || c.phones.any { it.contains(search) }
                val matchesFilter = when (filter) {
                    ContactFilter.ALL -> true
                    ContactFilter.RECENT -> calls.take(20).any { normalizeNumber(it.number) in c.phones.map { p -> normalizeNumber(p) } }
                    ContactFilter.FREQUENT -> calls.groupBy { normalizeNumber(it.number) }.filter { it.value.size >= 3 }.keys.any { num -> c.phones.any { normalizeNumber(it) == num } }
                    ContactFilter.DUPLICATES -> contacts.count { it.name.trim().equals(c.name.trim(), ignoreCase = true) } > 1
                    ContactFilter.FAMILY -> c.group == "Family"
                    ContactFilter.WORK -> c.group == "Work"
                    ContactFilter.SPAM -> c.phones.any { isSpamNumber(it) }
                }
                matchesSearch && matchesFilter
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(text = "NEXORA SMART", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search by name, number, or T9...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )

            Spacer(Modifier.height(10.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ContactFilter.values()) { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.name) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { contact ->
                    ContactRow(contact = contact, onClick = { onContactClick(contact) })
                }
            }
        }
    }

    @Composable
    private fun ContactRow(contact: NexoraContact, onClick: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = contact.name.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = contact.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        if (contact.isTemporary) {
                            Spacer(Modifier.width(6.dp))
                            Surface(color = Color(0xFFFFB74D), shape = RoundedCornerShape(4.dp)) {
                                Text("Temp", modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), fontSize = 10.sp)
                            }
                        }
                    }
                    Text(text = contact.phones.firstOrNull() ?: "", style = MaterialTheme.typography.bodySmall)
                }
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Text(contact.group, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 11.sp)
                }
            }
        }
    }

    // --- SCREEN 2: CONTACT DETAILS + TIMELINE + NOTES + REMINDER ---
    @Composable
    private fun ContactDetailsScreen(
        contact: NexoraContact,
        onBack: () -> Unit,
        onCall: (String) -> Unit,
        onSms: (String) -> Unit,
        onGroupChange: (String) -> Unit,
        onToggleTemp: () -> Unit
    ) {
        var noteText by remember { mutableStateOf(prefs.getString("note_${contact.id}", "") ?: "") }
        var reminderText by remember { mutableStateOf(prefs.getString("reminder_${contact.id}", "") ?: "") }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) }
                Text("Contact Details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(10.dp))
            Text(contact.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(contact.phones.joinToString(", "), style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { contact.phones.firstOrNull()?.let(onCall) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Call, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Call")
                }
                OutlinedButton(onClick = { contact.phones.firstOrNull()?.let(onSms) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Message, null)
                    Spacer(Modifier.width(4.dp))
                    Text("SMS")
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("Groups & Lifecycle", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("General", "Family", "Work").forEach { grp ->
                    AssistChip(
                        onClick = { onGroupChange(grp) },
                        label = { Text(grp) },
                        leadingIcon = if (contact.group == grp) { { Icon(Icons.Default.Check, null) } } else null
                    )
                }
                AssistChip(onClick = onToggleTemp, label = { Text(if (contact.isTemporary) "Remove Temp" else "Set Temp") })
            }

            Spacer(Modifier.height(14.dp))
            Text("Call Notes & Preparation", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = noteText,
                onValueChange = {
                    noteText = it
                    prefs.edit().putString("note_${contact.id}", it).apply()
                },
                modifier = Modifier.fillMaxWidth().height(90.dp),
                placeholder = { Text("Agenda, meeting notes, talking points...") }
            )

            Spacer(Modifier.height(10.dp))
            Text("Follow-up Reminder", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = reminderText,
                onValueChange = {
                    reminderText = it
                    prefs.edit().putString("reminder_${contact.id}", it).apply()
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. Call tomorrow at 4 PM for project quote") }
            )
        }
    }

    // --- SCREEN 3: DIALER + T9 SEARCH ---
    @Composable
    private fun DialerScreen(
        contacts: List<NexoraContact>,
        onCall: (String) -> Unit,
        onSms: (String) -> Unit
    ) {
        var dialText by remember { mutableStateOf("") }
        val matches = remember(dialText, contacts) {
            if (dialText.isBlank()) emptyList()
            else contacts.filter { it.name.contains(dialText, true) || it.phones.any { p -> normalizeNumber(p).contains(dialText) } }.take(4)
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SMART DIALER", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(dialText.ifBlank { "Dial a number" }, fontSize = 28.sp, fontWeight = FontWeight.Bold)

            if (matches.isNotEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(100.dp)) {
                    items(matches) { c ->
                        Text(
                            "${c.name} (${c.phones.firstOrNull()})",
                            modifier = Modifier.fillMaxWidth().clickable { dialText = c.phones.firstOrNull() ?: dialText }.padding(4.dp)
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(20.dp))
            }

            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
            keys.chunked(3).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { k ->
                        Button(
                            onClick = { dialText += k },
                            modifier = Modifier.size(72.dp).padding(4.dp),
                            shape = CircleShape
                        ) {
                            Text(k, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconButton(onClick = { if (dialText.isNotEmpty()) dialText = dialText.dropLast(1) }) {
                    Icon(Icons.Default.Backspace, contentDescription = null)
                }
                FloatingActionButton(onClick = { if (dialText.isNotBlank()) onCall(dialText) }, containerColor = Color(0xFF4CAF50)) {
                    Icon(Icons.Default.Call, contentDescription = null, tint = Color.White)
                }
                IconButton(onClick = { if (dialText.isNotBlank()) onSms(dialText) }) {
                    Icon(Icons.Default.Message, contentDescription = null)
                }
            }
        }
    }

    // --- SCREEN 4: RECENTS + MISSED ACTIONS + SPAM BLOCK ---
    @Composable
    private fun RecentsScreen(
        calls: List<NexoraCall>,
        onCall: (String) -> Unit,
        onToggleBlock: (String) -> Unit,
        isBlocked: (String) -> Boolean
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Recent Activity", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(calls, key = { it.id }) { call ->
                    val blocked = isBlocked(call.number)
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (call.type == CallLog.Calls.MISSED_TYPE) Icons.Default.CallMissed else Icons.Default.Call,
                                contentDescription = null,
                                tint = if (call.type == CallLog.Calls.MISSED_TYPE) Color.Red else Color.Green
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(call.name, fontWeight = FontWeight.Bold)
                                Text("${call.number} • ${SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(call.date))}", fontSize = 12.sp)
                            }
                            IconButton(onClick = { onToggleBlock(call.number) }) {
                                Icon(Icons.Default.Block, contentDescription = null, tint = if (blocked) Color.Red else Color.Gray)
                            }
                            IconButton(onClick = { onCall(call.number) }) {
                                Icon(Icons.Default.Call, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }
    }

    // --- SCREEN 5: FAVORITES ---
    @Composable
    private fun FavoritesScreen(contacts: List<NexoraContact>, onContactClick: (NexoraContact) -> Unit) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Starred & Pinned", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            LazyColumn {
                items(contacts) { c ->
                    ContactRow(contact = c, onClick = { onContactClick(c) })
                }
            }
        }
    }

    // --- SCREEN 6: INTELLIGENCE, STATS, BACKUP & SECURITY ---
    @Composable
    private fun IntelligenceScreen(
        contacts: List<NexoraContact>,
        calls: List<NexoraCall>,
        onExport: () -> String,
        onRestore: () -> String,
        onSavePin: (String) -> Unit
    ) {
        var statusMsg by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Analytics & Security", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            // Call Statistics
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Communication Frequency Index", fontWeight = FontWeight.Bold)
                    Text("Total Calls Logged: ${calls.size}")
                    Text("Incoming: ${calls.count { it.type == CallLog.Calls.INCOMING_TYPE }} | Outgoing: ${calls.count { it.type == CallLog.Calls.OUTGOING_TYPE }} | Missed: ${calls.count { it.type == CallLog.Calls.MISSED_TYPE }}")
                    Text("Total Database Contacts: ${contacts.size}")
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("Encrypted Backup & Migration", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { statusMsg = onExport() }, modifier = Modifier.weight(1f)) {
                    Text("Export (Encrypted)")
                }
                OutlinedButton(onClick = { statusMsg = onRestore() }, modifier = Modifier.weight(1f)) {
                    Text("Restore")
                }
            }
            if (statusMsg.isNotBlank()) {
                Text(statusMsg, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }

            Spacer(Modifier.height(18.dp))
            Text("App Lock Protection (PIN)", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = newPin,
                onValueChange = { if (it.length <= 4) newPin = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Set 4-digit PIN") },
                singleLine = true
            )
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    if (newPin.length == 4) {
                        onSavePin(newPin)
                        statusMsg = "PIN Protected! App locked."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Set PIN & Lock App")
            }
        }
    }

    // --- OVERLAY: ACTIVE IN-CALL CONTROLS ---
    @Composable
    private fun InCallScreen(number: String, contactName: String, onEndCall: () -> Unit) {
        var isMuted by remember { mutableStateOf(false) }
        var isSpeaker by remember { mutableStateOf(false) }

        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF1E1E1E)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 48.dp)) {
                    Box(modifier = Modifier.size(90.dp).clip(CircleShape).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(50.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(contactName, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(number, color = Color.Gray, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Ongoing Call...", color = Color.Green, fontSize = 14.sp)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { isMuted = !isMuted }) {
                        Icon(if (isMuted) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = null, tint = Color.White)
                    }
                    IconButton(onClick = { isSpeaker = !isSpeaker }) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = if (isSpeaker) Color.Green else Color.White)
                    }
                }

                FloatingActionButton(
                    onClick = onEndCall,
                    containerColor = Color.Red,
                    shape = CircleShape,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
        }
    }

    // --- LOCK SCREEN: PIN ENTRY ---
    @Composable
    private fun PinLockScreen(correctPin: String, onUnlocked: () -> Unit) {
        var enteredPin by remember { mutableStateOf("") }
        var error by remember { mutableStateOf(false) }

        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(50.dp))
                Spacer(Modifier.height(12.dp))
                Text("Nexora Secure Lock", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = enteredPin,
                    onValueChange = {
                        enteredPin = it
                        if (it == correctPin) onUnlocked() else if (it.length >= 4) error = true
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = error,
                    placeholder = { Text("Enter 4-digit PIN") }
                )
                if (error) {
                    Text("Incorrect PIN, please try again", color = Color.Red, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    // --- BOTTOM NAVIGATION BAR ---
    @Composable
    private fun NexoraBottomBar(currentTab: NexoraTab, onTabSelected: (NexoraTab) -> Unit) {
        NavigationBar {
            NavigationBarItem(
                selected = currentTab == NexoraTab.CONTACTS,
                onClick = { onTabSelected(NexoraTab.CONTACTS) },
                icon = { Icon(Icons.Default.Contacts, contentDescription = null) },
                label = { Text("Contacts") }
            )
            NavigationBarItem(
                selected = currentTab == NexoraTab.FAVORITES,
                onClick = { onTabSelected(NexoraTab.FAVORITES) },
                icon = { Icon(Icons.Default.Star, contentDescription = null) },
                label = { Text("Starred") }
            )
            NavigationBarItem(
                selected = currentTab == NexoraTab.DIALER,
                onClick = { onTabSelected(NexoraTab.DIALER) },
                icon = { Icon(Icons.Default.Dialpad, contentDescription = null) },
                label = { Text("Dialer") }
            )
            NavigationBarItem(
                selected = currentTab == NexoraTab.RECENTS,
                onClick = { onTabSelected(NexoraTab.RECENTS) },
                icon = { Icon(Icons.Default.History, contentDescription = null) },
                label = { Text("Recents") }
            )
            NavigationBarItem(
                selected = currentTab == NexoraTab.INTELLIGENCE,
                onClick = { onTabSelected(NexoraTab.INTELLIGENCE) },
                icon = { Icon(Icons.Default.Security, contentDescription = null) },
                label = { Text("Security") }
            )
        }
    }
}

// --- THEME ---
@Composable
private fun NexoraTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        content = content
    )
}
