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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

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
    DIALER,
    RECENTS,
    CONTACTS,
    FAVORITES,
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
    private val currentTabState = mutableStateOf(NexoraTab.DIALER)
    private val darkModeState = mutableStateOf(false)
    private val isAppUnlockedState = mutableStateOf(false)
    private val activeInCallState = mutableStateOf<String?>(null)

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
            "Restored ${jsonArray.length()} contacts!"
        } catch (e: Exception) {
            "Decryption failed"
        }
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
                    NexoraTab.DIALER -> ModernDialerScreen(
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
                    NexoraTab.CONTACTS -> ContactsScreen(
                        contacts = contactsState.value,
                        calls = callsState.value,
                        onContactClick = { selectedContactState.value = it }
                    )
                    NexoraTab.FAVORITES -> FavoritesScreen(
                        contacts = contactsState.value.filter { it.starred },
                        onContactClick = { selectedContactState.value = it }
                    )
                    NexoraTab.INTELLIGENCE -> IntelligenceScreen(
                        contacts = contactsState.value,
                        calls = callsState.value,
                        onExport = { exportContactsBackup() },
                        onRestore = { restoreContactsBackup() },
                        hasActivePin = !prefs.getString("security_pin", null).isNullOrBlank(),
                        onSavePin = { pin -> prefs.edit().putString("security_pin", pin).apply() },
                        onRemovePin = { prefs.edit().remove("security_pin").apply() }
                    )
                }
            }
        }
    }

    // --- PHOTO MATCHING IN-CALL SCREEN ---
    @Composable
    private fun InCallScreen(number: String, contactName: String, onEndCall: () -> Unit) {
        var isRecording by remember { mutableStateOf(false) }
        var isMuted by remember { mutableStateOf(false) }
        var isOnHold by remember { mutableStateOf(false) }
        var isSpeaker by remember { mutableStateOf(false) }
        var callSeconds by remember { mutableStateOf(0) }

        // Live Call Timer
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000L)
                callSeconds++
            }
        }

        val formattedDuration = String.format(Locale.getDefault(), "%02d:%02d", callSeconds / 60, callSeconds % 60)

        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF111315)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 40.dp)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Header: Contact Details & Timer
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 28.dp)) {
                    Box(
                        modifier = Modifier
                            .size(86.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF23272B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(52.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = if (contactName.isNotBlank() && contactName != number) contactName else number,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(text = if (contactName != number) number else "Calling...", color = Color.Gray, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (isOnHold) "Call on Hold" else formattedDuration,
                        color = if (isOnHold) Color(0xFFFFB74D) else Color(0xFF81C784),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Middle 6-Button Grid (Exact match from photo)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Row 1
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        InCallGridButton(
                            icon = Icons.Default.GraphicEq,
                            label = if (isRecording) "Recording..." else "Start recording",
                            isActive = isRecording,
                            onClick = { isRecording = !isRecording }
                        )
                        InCallGridButton(
                            icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            label = if (isMuted) "Unmute" else "Mute",
                            isActive = isMuted,
                            onClick = { isMuted = !isMuted }
                        )
                        InCallGridButton(
                            icon = Icons.Default.Add,
                            label = "Add call",
                            isActive = false,
                            onClick = { }
                        )
                    }

                    // Row 2
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        InCallGridButton(
                            icon = Icons.Default.Videocam,
                            label = "Video call",
                            isActive = false,
                            onClick = { }
                        )
                        InCallGridButton(
                            icon = Icons.Default.Pause,
                            label = if (isOnHold) "Unhold" else "Hold",
                            isActive = isOnHold,
                            onClick = { isOnHold = !isOnHold }
                        )
                        InCallGridButton(
                            icon = Icons.Default.Contacts,
                            label = "Contacts",
                            isActive = false,
                            onClick = { }
                        )
                    }
                }

                // Bottom Action Controls: Speaker, End Call, Keypad
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Speaker Button
                    FilledIconButton(
                        onClick = { isSpeaker = !isSpeaker },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isSpeaker) Color.White else Color(0xFF23272B)
                        ),
                        modifier = Modifier.size(60.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Speaker",
                            tint = if (isSpeaker) Color.Black else Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // End Call Button (Center Big Red)
                    FilledIconButton(
                        onClick = onEndCall,
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFEA4335)),
                        modifier = Modifier.size(76.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "End Call",
                            tint = Color.White,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    // Dialpad Button
                    FilledIconButton(
                        onClick = { },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF23272B)),
                        modifier = Modifier.size(60.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Dialpad,
                            contentDescription = "Keypad",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun InCallGridButton(
        icon: ImageVector,
        label: String,
        isActive: Boolean,
        onClick: () -> Unit
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(90.dp)
                .clickable(onClick = onClick)
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(if (isActive) Color.White else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isActive) Color.Black else Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = label,
                color = if (isActive) Color(0xFF81C784) else Color(0xFFD0D0D0),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }

    // --- REFINED HIGH-END DIALER ---
    @Composable
    private fun ModernDialerScreen(
        contacts: List<NexoraContact>,
        onCall: (String) -> Unit,
        onSms: (String) -> Unit
    ) {
        var dialText by remember { mutableStateOf("") }

        val matches = remember(dialText, contacts) {
            if (dialText.isBlank()) emptyList()
            else contacts.filter {
                it.name.contains(dialText, ignoreCase = true) ||
                    it.phones.any { p -> normalizeNumber(p).contains(dialText) }
            }.take(4)
        }

        val dialpadKeys = listOf(
            Triple("1", "", ""),
            Triple("2", "ABC", "2"),
            Triple("3", "DEF", "3"),
            Triple("4", "GHI", "4"),
            Triple("5", "JKL", "5"),
            Triple("6", "MNO", "6"),
            Triple("7", "PQRS", "7"),
            Triple("8", "TUV", "8"),
            Triple("9", "WXYZ", "9"),
            Triple("*", "", ""),
            Triple("0", "+", "0"),
            Triple("#", "", "")
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.Bottom
        ) {
            if (matches.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(bottom = 8.dp)
                ) {
                    items(matches) { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { dialText = c.phones.firstOrNull() ?: dialText }
                                .padding(vertical = 8.dp, horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = c.name.take(1).uppercase(),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(c.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(c.phones.firstOrNull() ?: "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = dialText,
                    fontSize = if (dialText.length > 10) 30.sp else 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                if (dialText.isNotBlank()) {
                    val contactMatch = contacts.firstOrNull { it.phones.any { p -> normalizeNumber(p) == normalizeNumber(dialText) } }
                    if (contactMatch != null) {
                        Text(
                            text = contactMatch.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            dialpadKeys.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { (digit, letters, _) ->
                        Surface(
                            onClick = { dialText += digit },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.size(76.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = digit,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (letters.isNotBlank()) {
                                    Text(
                                        text = letters,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (dialText.isNotBlank()) onSms(dialText) },
                    modifier = Modifier.size(54.dp)
                ) {
                    if (dialText.isNotBlank()) {
                        Icon(
                            imageVector = Icons.Default.ChatBubble,
                            contentDescription = "SMS",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                FilledIconButton(
                    onClick = { if (dialText.isNotBlank()) onCall(dialText) },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF2E7D32)),
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(imageVector = Icons.Default.Call, contentDescription = "Call", tint = Color.White, modifier = Modifier.size(32.dp))
                }

                IconButton(
                    onClick = { if (dialText.isNotEmpty()) dialText = dialText.dropLast(1) },
                    modifier = Modifier.size(54.dp)
                ) {
                    if (dialText.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Backspace,
                            contentDescription = "Backspace",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // --- SCREEN: CONTACTS LIST ---
    @Composable
    private fun ContactsScreen(
        contacts: List<NexoraContact>,
        calls: List<NexoraCall>,
        onContactClick: (NexoraContact) -> Unit
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
            Text("Contacts", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search name or number...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = RoundedCornerShape(16.dp),
                singleLine = true
            )

            Spacer(Modifier.height(10.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ContactFilter.values()) { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.name) },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

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
            shape = RoundedCornerShape(14.dp)
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
                    Text(text = contact.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(text = contact.phones.firstOrNull() ?: "", style = MaterialTheme.typography.bodySmall)
                }
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Text(contact.group, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp)
                }
            }
        }
    }

    // --- SCREEN: RECENTS ---
    @Composable
    private fun RecentsScreen(
        calls: List<NexoraCall>,
        onCall: (String) -> Unit,
        onToggleBlock: (String) -> Unit,
        isBlocked: (String) -> Boolean
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Recents", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(calls, key = { it.id }) { call ->
                    val blocked = isBlocked(call.number)
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(12.dp)) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (call.type == CallLog.Calls.MISSED_TYPE) Icons.Default.CallMissed else Icons.Default.Call,
                                contentDescription = null,
                                tint = if (call.type == CallLog.Calls.MISSED_TYPE) Color.Red else Color.Green
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(call.name, fontWeight = FontWeight.SemiBold)
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

    // --- SCREEN: FAVORITES ---
    @Composable
    private fun FavoritesScreen(contacts: List<NexoraContact>, onContactClick: (NexoraContact) -> Unit) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Favorites", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            LazyColumn {
                items(contacts) { c ->
                    ContactRow(contact = c, onClick = { onContactClick(c) })
                }
            }
        }
    }

    // --- SCREEN: CONTACT DETAILS ---
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
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                Text("Contact Details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(10.dp))
            Text(contact.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(contact.phones.joinToString(", "), style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { contact.phones.firstOrNull()?.let(onCall) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Call, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Call")
                }
                OutlinedButton(onClick = { contact.phones.firstOrNull()?.let(onSms) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Message, null)
                    Spacer(Modifier.width(6.dp))
                    Text("SMS")
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Groups & Lifecycle", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
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
            Text("Call Notes", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = noteText,
                onValueChange = {
                    noteText = it
                    prefs.edit().putString("note_${contact.id}", it).apply()
                },
                modifier = Modifier.fillMaxWidth().height(90.dp),
                placeholder = { Text("Add agenda or context...") }
            )

            Spacer(Modifier.height(12.dp))
            Text("Follow-up Reminder", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = reminderText,
                onValueChange = {
                    reminderText = it
                    prefs.edit().putString("reminder_${contact.id}", it).apply()
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Reminder note...") }
            )
        }
    }

    // --- SCREEN: SECURITY + PIN LOCK + BACKUP ---
    @Composable
    private fun IntelligenceScreen(
        contacts: List<NexoraContact>,
        calls: List<NexoraCall>,
        onExport: () -> String,
        onRestore: () -> String,
        hasActivePin: Boolean,
        onSavePin: (String) -> Unit,
        onRemovePin: () -> Unit
    ) {
        var statusMsg by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var lockStatus by remember { mutableStateOf(hasActivePin) }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Security & Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("App Lock PIN", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                if (lockStatus) "App is protected with PIN" else "Protection is disabled",
                                fontSize = 13.sp,
                                color = if (lockStatus) Color(0xFF2E7D32) else Color.Gray
                            )
                        }
                        Icon(
                            if (lockStatus) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = if (lockStatus) Color(0xFF2E7D32) else Color.Gray
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    if (lockStatus) {
                        Button(
                            onClick = {
                                onRemovePin()
                                lockStatus = false
                                statusMsg = "App Lock PIN has been removed!"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Remove / Disable PIN Lock")
                        }
                    } else {
                        OutlinedTextField(
                            value = newPin,
                            onValueChange = { if (it.length <= 4) newPin = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Set 4-digit PIN") },
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (newPin.length == 4) {
                                    onSavePin(newPin)
                                    lockStatus = true
                                    newPin = ""
                                    statusMsg = "New PIN set successfully!"
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save PIN & Lock App")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Encrypted Backup & Recovery", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { statusMsg = onExport() }, modifier = Modifier.weight(1f)) {
                            Text("Export Backup")
                        }
                        OutlinedButton(onClick = { statusMsg = onRestore() }, modifier = Modifier.weight(1f)) {
                            Text("Restore")
                        }
                    }
                }
            }

            if (statusMsg.isNotBlank()) {
                Text(
                    text = statusMsg,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
    }

    // --- LOCK SCREEN PIN ---
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
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("Nexora Secure Lock", fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
                selected = currentTab == NexoraTab.INTELLIGENCE,
                onClick = { onTabSelected(NexoraTab.INTELLIGENCE) },
                icon = { Icon(Icons.Default.Security, contentDescription = null) },
                label = { Text("Security") }
            )
        }
    }
}

@Composable
private fun NexoraTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        content = content
    )
}
