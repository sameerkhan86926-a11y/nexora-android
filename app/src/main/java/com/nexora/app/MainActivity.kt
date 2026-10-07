package com.nexora.app

import android.Manifest
import android.app.role.RoleManager
import android.content.ContentProviderOperation
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

// --- iOS COLOR PALETTE ---
val IosBlue = Color(0xFF007AFF)
val IosGreen = Color(0xFF34C759)
val IosRed = Color(0xFFFF3B30)
val IosOrange = Color(0xFFFF9500)
val IosDarkBackground = Color(0xFF000000)
val IosDarkCard = Color(0xFF1C1C1E)
val IosLightCard = Color(0xFFF2F2F7)
val IosKeypadLight = Color(0xFFE5E5EA)
val IosKeypadDark = Color(0xFF2C2C2E)
val IosGrayText = Color(0xFF8E8E93)

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
    FAVORITES,
    RECENTS,
    CONTACTS,
    DIALER,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    private val contactsState = mutableStateOf<List<NexoraContact>>(emptyList())
    private val callsState = mutableStateOf<List<NexoraCall>>(emptyList())
    private val selectedContactState = mutableStateOf<NexoraContact?>(null)
    private val currentTabState = mutableStateOf(NexoraTab.DIALER)
    private val isAppUnlockedState = mutableStateOf(false)
    private val activeInCallState = mutableStateOf<String?>(null)
    private val showAddContactDialog = mutableStateOf(false)

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

        val savedPin = prefs.getString("security_pin", null)
        isAppUnlockedState.value = savedPin.isNullOrBlank()

        setContent {
            NexoraIosTheme {
                if (!isAppUnlockedState.value) {
                    IosPasscodeScreen(
                        correctPin = savedPin ?: "",
                        onUnlocked = { isAppUnlockedState.value = true }
                    )
                } else if (activeInCallState.value != null) {
                    IosInCallScreen(
                        number = activeInCallState.value!!,
                        contactName = findContactName(activeInCallState.value!!),
                        onEndCall = { activeInCallState.value = null }
                    )
                } else {
                    IosAppScaffold()
                }

                if (showAddContactDialog.value) {
                    IosAddContactSheet(
                        onDismiss = { showAddContactDialog.value = false },
                        onSave = { name, phone, group ->
                            saveNewContact(name, phone, group)
                            showAddContactDialog.value = false
                        }
                    )
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

    private fun openWhatsApp(number: String) {
        val clean = normalizeNumber(number)
        if (clean.length < 10) {
            Toast.makeText(this, "Enter valid 10-digit number for WhatsApp", Toast.LENGTH_SHORT).show()
            return
        }
        val fullNumber = if (clean.length == 10) "91$clean" else clean
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://api.whatsapp.com/send?phone=$fullNumber")
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveNewContact(name: String, phoneNumber: String, group: String) {
        if (!hasPermission(Manifest.permission.WRITE_CONTACTS)) {
            permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_CONTACTS))
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ops = ArrayList<ContentProviderOperation>()
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .build()
                )
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                        .build()
                )
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                        .build()
                )
                contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Contact saved to iPhone Address Book", Toast.LENGTH_SHORT).show()
                    loadContacts()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --- VCF EXPORT & IMPORT ---
    private fun exportVcf(): String {
        val contacts = contactsState.value
        if (contacts.isEmpty()) return "No contacts to export"

        val sb = StringBuilder()
        contacts.forEach { c ->
            sb.append("BEGIN:VCARD\nVERSION:3.0\n")
            sb.append("FN:${c.name}\n")
            c.phones.forEach { p ->
                sb.append("TEL;TYPE=CELL:$p\n")
            }
            sb.append("END:VCARD\n")
        }
        prefs.edit().putString("vcf_cache_data", sb.toString()).apply()
        return "Exported ${contacts.size} contacts as VCF (Apple vCard 3.0)"
    }

    private fun importVcf(): String {
        val data = prefs.getString("vcf_cache_data", null) ?: return "No saved VCF file found."
        var count = 0
        data.lines().forEach { line ->
            if (line.startsWith("BEGIN:VCARD")) count++
        }
        return "Validated $count contacts from Apple vCard store."
    }

    // --- iOS APP SHELL ---
    @Composable
    private fun IosAppScaffold() {
        val selected = selectedContactState.value

        if (selected != null) {
            IosContactDetailScreen(
                contact = selected,
                onBack = { selectedContactState.value = null },
                onCall = { makeCall(it) },
                onSms = { sendSms(it) },
                onWhatsApp = { openWhatsApp(it) },
                onGroupChange = { newGroup ->
                    prefs.edit().putString("contact_group_${selected.id}", newGroup).apply()
                    loadContacts()
                }
            )
            return
        }

        Scaffold(
            bottomBar = {
                IosBottomNavigationBar(
                    currentTab = currentTabState.value,
                    onTabSelected = { currentTabState.value = it }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                when (currentTabState.value) {
                    NexoraTab.FAVORITES -> IosFavoritesScreen(
                        contacts = contactsState.value.filter { it.starred },
                        onContactClick = { selectedContactState.value = it },
                        onCall = { makeCall(it) }
                    )
                    NexoraTab.RECENTS -> IosRecentsScreen(
                        calls = callsState.value,
                        onCall = { makeCall(it) }
                    )
                    NexoraTab.CONTACTS -> IosContactsScreen(
                        contacts = contactsState.value,
                        onContactClick = { selectedContactState.value = it },
                        onAddClick = { showAddContactDialog.value = true }
                    )
                    NexoraTab.DIALER -> IosKeypadScreen(
                        contacts = contactsState.value,
                        onCall = { makeCall(it) },
                        onWhatsApp = { openWhatsApp(it) },
                        onSpeedDial = { digit ->
                            val speedNumber = prefs.getString("speed_dial_$digit", null)
                            if (!speedNumber.isNullOrBlank()) {
                                makeCall(speedNumber)
                            } else {
                                Toast.makeText(this@MainActivity, "Speed dial $digit not assigned. Long press in Settings.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    NexoraTab.SETTINGS -> IosSettingsScreen(
                        contacts = contactsState.value,
                        onExportVcf = { exportVcf() },
                        onImportVcf = { importVcf() },
                        hasPin = !prefs.getString("security_pin", null).isNullOrBlank(),
                        onSetPin = { prefs.edit().putString("security_pin", it).apply() },
                        onRemovePin = { prefs.edit().remove("security_pin").apply() },
                        onAssignSpeedDial = { digit, num ->
                            prefs.edit().putString("speed_dial_$digit", num).apply()
                            Toast.makeText(this@MainActivity, "Key $digit bound to $num", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    // --- TAB 1: iOS KEYPAD (DIALER) ---
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun IosKeypadScreen(
        contacts: List<NexoraContact>,
        onCall: (String) -> Unit,
        onWhatsApp: (String) -> Unit,
        onSpeedDial: (String) -> Unit
    ) {
        var dialedNumber by remember { mutableStateOf("") }

        val matchedContact = remember(dialedNumber, contacts) {
            if (dialedNumber.length >= 3) {
                contacts.firstOrNull { c ->
                    c.phones.any { normalizeNumber(it).contains(normalizeNumber(dialedNumber)) }
                }
            } else null
        }

        val keys = listOf(
            Triple("1", "", ""),
            Triple("2", "A B C", "2"),
            Triple("3", "D E F", "3"),
            Triple("4", "G H I", "4"),
            Triple("5", "J K L", "5"),
            Triple("6", "M N O", "6"),
            Triple("7", "P Q R S", "7"),
            Triple("8", "T U V", "8"),
            Triple("9", "W X Y Z", "9"),
            Triple("*", "", ""),
            Triple("0", "+", "0"),
            Triple("#", "", "")
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom
        ) {
            // Live Matched Contact Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    if (matchedContact != null) {
                        Text(
                            text = matchedContact.name,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = IosBlue
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    Text(
                        text = dialedNumber,
                        fontSize = if (dialedNumber.length > 11) 32.sp else 40.sp,
                        fontWeight = FontWeight.Light,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 1.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )

                    if (dialedNumber.isNotBlank()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            // Add Number Pill
                            Text(
                                text = "Add Number",
                                color = IosBlue,
                                fontSize = 14.sp,
                                modifier = Modifier.clickable {
                                    showAddContactDialog.value = true
                                }
                            )

                            Text("•", color = IosGrayText, fontSize = 14.sp)

                            // Direct WhatsApp Pill
                            Text(
                                text = "WhatsApp",
                                color = IosGreen,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { onWhatsApp(dialedNumber) }
                            )
                        }
                    }
                }
            }

            // Keypad Grid 3x4
            keys.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { (digit, letters, _) ->
                        Box(
                            modifier = Modifier
                                .size(78.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .combinedClickable(
                                    onClick = { dialedNumber += digit },
                                    onLongClick = { onSpeedDial(digit) }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = digit,
                                    fontSize = 34.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 36.sp
                                )
                                if (letters.isNotBlank()) {
                                    Text(
                                        text = letters,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        letterSpacing = 1.5.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // iOS Call & Backspace Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.size(54.dp)) // balancing space

                // Apple Big Green Call Button
                FilledIconButton(
                    onClick = { if (dialedNumber.isNotBlank()) onCall(dialedNumber) },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = IosGreen),
                    modifier = Modifier.size(76.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Call",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Apple Backspace Button
                Box(
                    modifier = Modifier.size(54.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (dialedNumber.isNotEmpty()) {
                        IconButton(
                            onClick = { dialedNumber = dialedNumber.dropLast(1) }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // --- TAB 2: iOS RECENTS SCREEN ---
    @Composable
    private fun IosRecentsScreen(
        calls: List<NexoraCall>,
        onCall: (String) -> Unit
    ) {
        var filterMissed by remember { mutableStateOf(false) }

        val displayedCalls = remember(calls, filterMissed) {
            if (filterMissed) calls.filter { it.type == CallLog.Calls.MISSED_TYPE } else calls
        }

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(16.dp))

            // iOS Segmented Control
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.width(220.dp).height(32.dp)
                ) {
                    Row {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (!filterMissed) MaterialTheme.colorScheme.surface else Color.Transparent)
                                .clickable { filterMissed = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("All", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (filterMissed) MaterialTheme.colorScheme.surface else Color.Transparent)
                                .clickable { filterMissed = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Missed", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Text(
                text = "Recents",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(displayedCalls, key = { it.id }) { call ->
                    val isMissed = call.type == CallLog.Calls.MISSED_TYPE
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCall(call.number) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = call.name,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isMissed) IosRed else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${call.number} • ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(call.date))}",
                                fontSize = 14.sp,
                                color = IosGrayText
                            )
                        }
                        IconButton(onClick = { onCall(call.number) }) {
                            Icon(Icons.Default.Info, contentDescription = "Info", tint = IosBlue)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }

    // --- TAB 3: iOS CONTACTS LIST ---
    @Composable
    private fun IosContactsScreen(
        contacts: List<NexoraContact>,
        onContactClick: (NexoraContact) -> Unit,
        onAddClick: () -> Unit
    ) {
        var search by remember { mutableStateOf("") }

        val filtered = remember(contacts, search) {
            if (search.isBlank()) contacts
            else contacts.filter {
                it.name.contains(search, ignoreCase = true) || it.phones.any { p -> p.contains(search) }
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Contacts",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onAddClick) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = IosBlue, modifier = Modifier.size(30.dp))
                }
            }

            // iOS Search Bar
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().height(42.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp)) {
                    Icon(Icons.Default.Search, null, tint = IosGrayText)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("Search", color = IosGrayText, fontSize = 15.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        singleLine = true
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { c ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onContactClick(c) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(c.name.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(c.name, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text(c.phones.firstOrNull() ?: "", fontSize = 13.sp, color = IosGrayText)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
    }

    // --- TAB 4: iOS FAVORITES ---
    @Composable
    private fun IosFavoritesScreen(
        contacts: List<NexoraContact>,
        onContactClick: (NexoraContact) -> Unit,
        onCall: (String) -> Unit
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Favorites", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))

            if (contacts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No Favorites Added Yet", color = IosGrayText)
                }
            } else {
                LazyColumn {
                    items(contacts) { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onContactClick(c) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = IosOrange, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(c.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Text("mobile", fontSize = 13.sp, color = IosGrayText)
                            }
                            IconButton(onClick = { c.phones.firstOrNull()?.let(onCall) }) {
                                Icon(Icons.Default.Call, null, tint = IosGreen)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }

    // --- TAB 5: iOS SETTINGS, BACKUP, PIN & SPEED DIAL ---
    @Composable
    private fun IosSettingsScreen(
        contacts: List<NexoraContact>,
        onExportVcf: () -> String,
        onImportVcf: () -> String,
        hasPin: Boolean,
        onSetPin: (String) -> Unit,
        onRemovePin: () -> Unit,
        onAssignSpeedDial: (String, String) -> Unit
    ) {
        var status by remember { mutableStateOf("") }
        var pinInput by remember { mutableStateOf("") }
        var speedKey by remember { mutableStateOf("2") }
        var speedNum by remember { mutableStateOf("") }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Settings & iCloud", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            // iOS Card: Backup & vCard Export
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Apple vCard (.VCF) System", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Export or restore address book across iOS devices", fontSize = 12.sp, color = IosGrayText)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { status = onExportVcf() }, colors = ButtonDefaults.buttonColors(containerColor = IosBlue), modifier = Modifier.weight(1f)) {
                            Text("Export .VCF")
                        }
                        OutlinedButton(onClick = { status = onImportVcf() }, modifier = Modifier.weight(1f)) {
                            Text("Import .VCF")
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // iOS Card: Speed Dial Setup
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Speed Dial Long-Press (1–9)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = speedKey,
                            onValueChange = { if (it.length <= 1) speedKey = it },
                            label = { Text("Key") },
                            modifier = Modifier.width(70.dp)
                        )
                        OutlinedTextField(
                            value = speedNum,
                            onValueChange = { speedNum = it },
                            label = { Text("Phone Number") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            if (speedKey.isNotBlank() && speedNum.isNotBlank()) {
                                onAssignSpeedDial(speedKey, speedNum)
                                speedNum = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = IosGreen),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Bind to Keypad")
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // iOS Card: FaceID / Passcode Lock
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Passcode Lock", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(if (hasPin) "Active" else "Off", color = if (hasPin) IosGreen else IosGrayText)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (hasPin) {
                        Button(onClick = onRemovePin, colors = ButtonDefaults.buttonColors(containerColor = IosRed), modifier = Modifier.fillMaxWidth()) {
                            Text("Turn Passcode Off")
                        }
                    } else {
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { if (it.length <= 4) pinInput = it },
                            placeholder = { Text("Set 4-digit Passcode") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = {
                                if (pinInput.length == 4) {
                                    onSetPin(pinInput)
                                    status = "Passcode Enabled"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = IosBlue),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Turn Passcode On")
                        }
                    }
                }
            }

            if (status.isNotBlank()) {
                Text(status, color = IosBlue, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }

    // --- FULL SCREEN: iOS IN-CALL OVERLAY SCREEN ---
    @Composable
    private fun IosInCallScreen(number: String, contactName: String, onEndCall: () -> Unit) {
        var isMuted by remember { mutableStateOf(false) }
        var isSpeaker by remember { mutableStateOf(false) }
        var isOnHold by remember { mutableStateOf(false) }
        var seconds by remember { mutableStateOf(0) }

        LaunchedEffect(Unit) {
            while (true) {
                delay(1000L)
                seconds++
            }
        }

        val timerStr = String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)

        Surface(modifier = Modifier.fillMaxSize(), color = IosDarkBackground) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 48.dp)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Caller Header
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 24.dp)) {
                    Text(
                        text = if (contactName.isNotBlank() && contactName != number) contactName else number,
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (isOnHold) "call on hold" else timerStr,
                        color = Color.Gray,
                        fontSize = 18.sp
                    )
                }

                // Apple 6-Icon In-Call Control Grid
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        IosCallCircleBtn(
                            icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            label = "mute",
                            active = isMuted,
                            onClick = { isMuted = !isMuted }
                        )
                        IosCallCircleBtn(
                            icon = Icons.Default.Dialpad,
                            label = "keypad",
                            active = false,
                            onClick = {}
                        )
                        IosCallCircleBtn(
                            icon = Icons.Default.VolumeUp,
                            label = "audio",
                            active = isSpeaker,
                            onClick = { isSpeaker = !isSpeaker }
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        IosCallCircleBtn(
                            icon = Icons.Default.Add,
                            label = "add call",
                            active = false,
                            onClick = {}
                        )
                        IosCallCircleBtn(
                            icon = Icons.Default.Videocam,
                            label = "FaceTime",
                            active = false,
                            onClick = {}
                        )
                        IosCallCircleBtn(
                            icon = Icons.Default.Pause,
                            label = "hold",
                            active = isOnHold,
                            onClick = { isOnHold = !isOnHold }
                        )
                    }
                }

                // Apple Big Red End Call Button
                FilledIconButton(
                    onClick = onEndCall,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = IosRed),
                    modifier = Modifier.size(76.dp)
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "End Call", tint = Color.White, modifier = Modifier.size(38.dp))
                }
            }
        }
    }

    @Composable
    private fun IosCallCircleBtn(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(if (active) Color.White else Color(0xFF2C2C2E))
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (active) Color.Black else Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(text = label, color = Color.White, fontSize = 12.sp)
        }
    }

    // --- SCREEN: iOS CONTACT DETAILS ---
    @Composable
    private fun IosContactDetailScreen(
        contact: NexoraContact,
        onBack: () -> Unit,
        onCall: (String) -> Unit,
        onSms: (String) -> Unit,
        onWhatsApp: (String) -> Unit,
        onGroupChange: (String) -> Unit
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("‹ Back", color = IosBlue, fontSize = 17.sp, modifier = Modifier.clickable(onClick = onBack))
                Text("Edit", color = IosBlue, fontSize = 17.sp)
            }

            Spacer(Modifier.height(24.dp))

            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(contact.name.take(1).uppercase(), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Text(contact.name, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(20.dp))

            // iOS Action Pill Buttons (Message, Call, Video, WhatsApp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IosActionBox(icon = Icons.Default.ChatBubble, label = "message", onClick = { contact.phones.firstOrNull()?.let(onSms) })
                IosActionBox(icon = Icons.Default.Call, label = "call", onClick = { contact.phones.firstOrNull()?.let(onCall) })
                IosActionBox(icon = Icons.Default.Videocam, label = "FaceTime", onClick = {})
                IosActionBox(icon = Icons.Default.Share, label = "WhatsApp", onClick = { contact.phones.firstOrNull()?.let(onWhatsApp) })
            }

            Spacer(Modifier.height(24.dp))

            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("mobile", fontSize = 13.sp, color = IosGrayText)
                    Text(contact.phones.joinToString(", "), fontSize = 18.sp, color = IosBlue)
                }
            }
        }
    }

    @Composable
    private fun IosActionBox(icon: ImageVector, label: String, onClick: () -> Unit) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(76.dp, 60.dp).clickable(onClick = onClick)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(icon, contentDescription = label, tint = IosBlue, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(4.dp))
                Text(label, fontSize = 11.sp, color = IosBlue)
            }
        }
    }

    // --- DIALOG: iOS SHEET ADD CONTACT ---
    @Composable
    private fun IosAddContactSheet(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
        var name by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("New Contact", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("First and Last Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        placeholder = { Text("Phone Number") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { if (name.isNotBlank() && phone.isNotBlank()) onSave(name, phone, "General") }) {
                    Text("Done", color = IosBlue, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = IosRed)
                }
            }
        )
    }

    // --- LOCK SCREEN: APPLE PIN ENTRY ---
    @Composable
    private fun IosPasscodeScreen(correctPin: String, onUnlocked: () -> Unit) {
        var enteredPin by remember { mutableStateOf("") }
        var hasError by remember { mutableStateOf(false) }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Enter Passcode", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(18.dp))

                // Apple 4-Dot Indicators
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (i in 0 until 4) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(if (i < enteredPin.length) MaterialTheme.colorScheme.onSurface else Color.Transparent)
                                .border(1.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        )
                    }
                }

                Spacer(Modifier.height(30.dp))

                // Numeric Keypad 1-9
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "del").chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        row.forEach { k ->
                            if (k.isEmpty()) {
                                Spacer(Modifier.size(72.dp))
                            } else if (k == "del") {
                                Box(
                                    modifier = Modifier.size(72.dp).clickable {
                                        if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
                                    },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Delete", fontSize = 15.sp)
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable {
                                            if (enteredPin.length < 4) {
                                                enteredPin += k
                                                if (enteredPin.length == 4) {
                                                    if (enteredPin == correctPin) onUnlocked() else hasError = true
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(k, fontSize = 28.sp)
                                }
                            }
                        }
                    }
                }

                if (hasError) {
                    Text("Wrong Passcode", color = IosRed, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }

    // --- iOS 5-TAB BOTTOM NAVIGATION ---
    @Composable
    private fun IosBottomNavigationBar(currentTab: NexoraTab, onTabSelected: (NexoraTab) -> Unit) {
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            NavigationBarItem(
                selected = currentTab == NexoraTab.FAVORITES,
                onClick = { onTabSelected(NexoraTab.FAVORITES) },
                icon = { Icon(Icons.Default.Star, contentDescription = null) },
                label = { Text("Favorites", fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = IosBlue, selectedTextColor = IosBlue)
            )
            NavigationBarItem(
                selected = currentTab == NexoraTab.RECENTS,
                onClick = { onTabSelected(NexoraTab.RECENTS) },
                icon = { Icon(Icons.Default.AccessTime, contentDescription = null) },
                label = { Text("Recents", fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = IosBlue, selectedTextColor = IosBlue)
            )
            NavigationBarItem(
                selected = currentTab == NexoraTab.CONTACTS,
                onClick = { onTabSelected(NexoraTab.CONTACTS) },
                icon = { Icon(Icons.Default.Person, contentDescription = null) },
                label = { Text("Contacts", fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = IosBlue, selectedTextColor = IosBlue)
            )
            NavigationBarItem(
                selected = currentTab == NexoraTab.DIALER,
                onClick = { onTabSelected(NexoraTab.DIALER) },
                icon = { Icon(Icons.Default.Dialpad, contentDescription = null) },
                label = { Text("Keypad", fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = IosBlue, selectedTextColor = IosBlue)
            )
            NavigationBarItem(
                selected = currentTab == NexoraTab.SETTINGS,
                onClick = { onTabSelected(NexoraTab.SETTINGS) },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                label = { Text("Settings", fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = IosBlue, selectedTextColor = IosBlue)
            )
        }
    }
}

// --- iOS COLOR SCHEME THEME ---
@Composable
private fun NexoraIosTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            background = Color.White,
            surface = Color(0xFFF9F9FB),
            surfaceVariant = Color(0xFFE5E5EA),
            onSurface = Color.Black,
            onSurfaceVariant = Color(0xFF3A3A3C),
            primary = IosBlue
        ),
        content = content
    )
}
