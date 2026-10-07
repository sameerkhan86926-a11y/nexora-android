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
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

// --- TELECOM SERVICE IN-APP ENGINE ---
class NexoraInCallService : InCallService() {
    companion object {
        val currentCall = MutableStateFlow<Call?>(null)
        val callAudioStateFlow = MutableStateFlow<CallAudioState?>(null)
        private var instance: NexoraInCallService? = null

        fun answerCall() { currentCall.value?.answer(0) }
        fun rejectCall() { currentCall.value?.reject(false, null) }
        fun disconnectCall() { currentCall.value?.disconnect() }
        fun setMuted(muted: Boolean) { instance?.setMuted(muted) }
        fun toggleSpeaker(enable: Boolean) {
            val route = if (enable) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
            instance?.setAudioRoute(route)
        }
        fun holdCall(hold: Boolean) {
            if (hold) currentCall.value?.hold() else currentCall.value?.unhold()
        }
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call?, state: Int) {
            if (state == Call.STATE_DISCONNECTED) {
                currentCall.value = null
            }
        }
    }

    override fun onCallAdded(call: Call?) {
        super.onCallAdded(call)
        instance = this
        currentCall.value = call
        call?.registerCallback(callback)
    }

    override fun onCallRemoved(call: Call?) {
        super.onCallRemoved(call)
        call?.unregisterCallback(callback)
        if (currentCall.value == call) {
            currentCall.value = null
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        callAudioStateFlow.value = audioState
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        currentCall.value = null
    }
}

val IosBlue = Color(0xFF007AFF)
val IosGreen = Color(0xFF34C759)
val IosRed = Color(0xFFFF3B30)
val IosOrange = Color(0xFFFF9500)
val IosGrayText = Color(0xFF8E8E93)

data class NexoraContact(
    val id: String,
    val name: String,
    val phones: List<String>,
    val email: String = "",
    val company: String = "",
    val photoUri: String? = null,
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
    private val showAddContactDialog = mutableStateOf(false)
    private val editingContactState = mutableStateOf<NexoraContact?>(null)

    private val themePreference = mutableStateOf("system")
    private val prefs by lazy { getSharedPreferences("nexora_prefs", Context.MODE_PRIVATE) }
    private val KEY_ALIAS = "NexoraAESKey"

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

        if (prefs.getBoolean("secure_screen", false)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        themePreference.value = prefs.getString("theme_mode", "system") ?: "system"
        val savedPin = prefs.getString("security_pin", null)
        isAppUnlockedState.value = savedPin.isNullOrBlank()

        initKeyStore()

        setContent {
            val isDark = when (themePreference.value) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            NexoraIosTheme(darkTheme = isDark) {
                val activeTelecomCall by NexoraInCallService.currentCall.collectAsState()

                if (!isAppUnlockedState.value) {
                    IosPasscodeScreen(
                        correctPin = savedPin ?: "",
                        onUnlocked = { isAppUnlockedState.value = true }
                    )
                } else if (activeTelecomCall != null) {
                    RealInCallScreen(call = activeTelecomCall!!)
                } else {
                    IosAppScaffold()
                }

                if (showAddContactDialog.value) {
                    IosContactEditSheet(
                        contact = editingContactState.value,
                        onDismiss = {
                            showAddContactDialog.value = false
                            editingContactState.value = null
                        },
                        onSave = { name, phone, email, company, group ->
                            if (editingContactState.value != null) {
                                updateContact(editingContactState.value!!.id, name, phone, email, company, group)
                            } else {
                                saveNewContact(name, phone, email, company, group)
                            }
                            showAddContactDialog.value = false
                            editingContactState.value = null
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
        val permissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun promptDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(ROLE_SERVICE) as? RoleManager
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) && !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                defaultDialerLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
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
                ContactsContract.Contacts.PHOTO_URI,
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
                val photoIdx = cursor.getColumnIndex(ContactsContract.Contacts.PHOTO_URI)
                val phoneIdx = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

                while (cursor.moveToNext()) {
                    if (idIdx < 0 || nameIdx < 0 || phoneIdx < 0) continue
                    val id = cursor.getString(idIdx) ?: continue
                    val name = cursor.getString(nameIdx) ?: "Unknown"
                    if (cursor.getInt(phoneIdx) <= 0) continue

                    val phones = getPhoneNumbers(id)
                    if (phones.isEmpty()) continue

                    val photoUri = if (photoIdx >= 0) cursor.getString(photoIdx) else null
                    val group = prefs.getString("contact_group_$id", "General") ?: "General"
                    val isTemp = prefs.getBoolean("contact_temp_$id", false)

                    result.add(
                        NexoraContact(
                            id = id,
                            name = name,
                            phones = phones,
                            photoUri = photoUri,
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

    private fun saveNewContact(name: String, phoneNumber: String, email: String, company: String, group: String) {
        if (!hasPermission(Manifest.permission.WRITE_CONTACTS)) return

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
                if (email.isNotBlank()) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                            .build()
                    )
                }
                contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Saved to Contacts", Toast.LENGTH_SHORT).show()
                    loadContacts()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateContact(contactId: String, name: String, phone: String, email: String, company: String, group: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ops = ArrayList<ContentProviderOperation>()
                ops.add(
                    ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                        .withSelection("${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?", arrayOf(contactId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE))
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                        .build()
                )
                ops.add(
                    ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                        .withSelection("${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?", arrayOf(contactId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE))
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                        .build()
                )
                contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Contact Updated", Toast.LENGTH_SHORT).show()
                    loadContacts()
                    selectedContactState.value = null
                }
            } catch (_: Exception) {}
        }
    }

    private fun deleteContact(contactId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId.toLong())
            contentResolver.delete(uri, null, null)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Contact Deleted", Toast.LENGTH_SHORT).show()
                selectedContactState.value = null
                loadContacts()
            }
        }
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

                while (cursor.moveToNext()) {
                    if (idIdx < 0 || numIdx < 0 || typeIdx < 0 || dateIdx < 0) continue
                    val num = cursor.getString(numIdx) ?: "Unknown"
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: findContactName(num) else findContactName(num)

                    result.add(
                        NexoraCall(
                            id = cursor.getString(idIdx) ?: "",
                            number = num,
                            name = name,
                            type = cursor.getInt(typeIdx),
                            date = cursor.getLong(dateIdx),
                            duration = if (durIdx >= 0) cursor.getLong(durIdx) else 0L
                        )
                    )
                }
            }

            withContext(Dispatchers.Main) {
                callsState.value = result
            }
        }
    }

    private fun deleteCallLog(callId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} = ?", arrayOf(callId))
            withContext(Dispatchers.Main) {
                loadRecentCalls()
            }
        }
    }

    private fun deleteAllCallsForNumber(number: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls.NUMBER} = ?", arrayOf(number))
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Logs Cleared", Toast.LENGTH_SHORT).show()
                loadRecentCalls()
            }
        }
    }

    private fun getAvailableSimAccounts(): List<PhoneAccountHandle> {
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return emptyList()
        return if (hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            telecomManager.callCapablePhoneAccounts
        } else emptyList()
    }

    private fun makeCallWithSim(number: String, simHandle: PhoneAccountHandle? = null) {
        val clean = number.trim()
        if (clean.isBlank()) return
        val uri = Uri.parse("tel:${Uri.encode(clean)}")
        val intent = Intent(Intent.ACTION_CALL, uri)
        if (simHandle != null) {
            intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, simHandle)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_DIAL, uri))
        }
    }

    private fun getT9Representation(name: String): String {
        return name.uppercase().map { ch ->
            when (ch) {
                'A', 'B', 'C' -> '2'
                'D', 'E', 'F' -> '3'
                'G', 'H', 'I' -> '4'
                'J', 'K', 'L' -> '5'
                'M', 'N', 'O' -> '6'
                'P', 'Q', 'R', 'S' -> '7'
                'T', 'U', 'V' -> '8'
                'W', 'X', 'Y', 'Z' -> '9'
                else -> ch
            }
        }.joinToString("")
    }

    private fun initKeyStore() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val keySpec = android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(keySpec)
            keyGenerator.generateKey()
        }
    }

    private fun exportEncryptedBackup(): String {
        val json = JSONArray()
        contactsState.value.forEach { c ->
            val obj = JSONObject()
            obj.put("name", c.name)
            obj.put("phones", JSONArray(c.phones))
            obj.put("group", c.group)
            json.put(obj)
        }
        val raw = json.toString().toByteArray(Charsets.UTF_8)

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val secretKey = (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(raw)

        val finalPayload = android.util.Base64.encodeToString(iv + encrypted, android.util.Base64.NO_WRAP)
        prefs.edit().putString("aes_backup_store", finalPayload).apply()
        return "Backup AES-256 Encrypted (${contactsState.value.size} contacts)"
    }

    private fun restoreEncryptedBackup(): String {
        val payload = prefs.getString("aes_backup_store", null) ?: return "No backup found!"
        return try {
            val allBytes = android.util.Base64.decode(payload, android.util.Base64.NO_WRAP)
            val iv = allBytes.copyOfRange(0, 12)
            val cipherText = allBytes.copyOfRange(12, allBytes.size)

            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val secretKey = (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val plainBytes = cipher.doFinal(cipherText)
            val json = JSONArray(String(plainBytes, Charsets.UTF_8))

            "Restored ${json.length()} contacts from Secure Enclave"
        } catch (e: Exception) {
            "Decryption Error: ${e.message}"
        }
    }

    private fun findContactName(number: String): String {
        val clean = normalizeNumber(number)
        return contactsState.value.firstOrNull { it.phones.any { p -> normalizeNumber(p) == clean } }?.name ?: number
    }

    private fun normalizeNumber(number: String): String {
        return number.filter { it.isDigit() }.takeLast(10)
    }

    private fun openWhatsApp(number: String) {
        val clean = normalizeNumber(number)
        val full = if (clean.length == 10) "91$clean" else clean
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$full")))
        } catch (_: Exception) {
            Toast.makeText(this, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openTelegram(number: String) {
        val clean = normalizeNumber(number)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/+91$clean")))
        } catch (_: Exception) {
            Toast.makeText(this, "Telegram not installed", Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    private fun IosAppScaffold() {
        val selected = selectedContactState.value

        if (selected != null) {
            IosContactDetailScreen(
                contact = selected,
                onBack = { selectedContactState.value = null },
                onCall = { makeCallWithSim(it) },
                onSms = { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$it"))) },
                onWhatsApp = { openWhatsApp(it) },
                onTelegram = { openTelegram(it) },
                onEdit = {
                    editingContactState.value = selected
                    showAddContactDialog.value = true
                },
                onDelete = { deleteContact(selected.id) }
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
                        onCall = { makeCallWithSim(it) }
                    )
                    NexoraTab.RECENTS -> IosRecentsScreen(
                        calls = callsState.value,
                        onCall = { makeCallWithSim(it) },
                        onDeleteCall = { deleteCallLog(it) },
                        onDeleteAll = { deleteAllCallsForNumber(it) }
                    )
                    NexoraTab.CONTACTS -> IosContactsScreen(
                        contacts = contactsState.value,
                        onContactClick = { selectedContactState.value = it },
                        onAddClick = {
                            editingContactState.value = null
                            showAddContactDialog.value = true
                        }
                    )
                    NexoraTab.DIALER -> IosKeypadScreen(
                        contacts = contactsState.value,
                        simAccounts = getAvailableSimAccounts(),
                        onCall = { num, sim -> makeCallWithSim(num, sim) },
                        onWhatsApp = { openWhatsApp(it) },
                        onSpeedDial = { digit ->
                            val speed = prefs.getString("speed_dial_$digit", null)
                            if (!speed.isNullOrBlank()) makeCallWithSim(speed)
                            else Toast.makeText(this@MainActivity, "Assign Key $digit in Settings", Toast.LENGTH_SHORT).show()
                        }
                    )
                    NexoraTab.SETTINGS -> IosSettingsScreen(
                        contacts = contactsState.value,
                        calls = callsState.value,
                        themeMode = themePreference.value,
                        onThemeChange = { mode ->
                            themePreference.value = mode
                            prefs.edit().putString("theme_mode", mode).apply()
                        },
                        onExportAes = { exportEncryptedBackup() },
                        onRestoreAes = { restoreEncryptedBackup() },
                        hasPin = !prefs.getString("security_pin", null).isNullOrBlank(),
                        onSetPin = { prefs.edit().putString("security_pin", it).apply() },
                        onRemovePin = { prefs.edit().remove("security_pin").apply() },
                        isScreenSecured = prefs.getBoolean("secure_screen", false),
                        onToggleScreenSecurity = { sec ->
                            prefs.edit().putBoolean("secure_screen", sec).apply()
                            if (sec) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        },
                        onAssignSpeedDial = { digit, num ->
                            prefs.edit().putString("speed_dial_$digit", num).apply()
                            Toast.makeText(this@MainActivity, "Key $digit bound", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun IosKeypadScreen(
        contacts: List<NexoraContact>,
        simAccounts: List<PhoneAccountHandle>,
        onCall: (String, PhoneAccountHandle?) -> Unit,
        onWhatsApp: (String) -> Unit,
        onSpeedDial: (String) -> Unit
    ) {
        var dialedNumber by remember { mutableStateOf("") }
        var selectedSimIndex by remember { mutableIntStateOf(0) }

        val matchedContacts = remember(dialedNumber, contacts) {
            if (dialedNumber.isBlank()) emptyList()
            else {
                contacts.filter { c ->
                    c.phones.any { normalizeNumber(it).contains(dialedNumber) } ||
                        getT9Representation(c.name).contains(dialedNumber)
                }.take(3)
            }
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
            if (matchedContacts.isNotEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(110.dp)) {
                    items(matchedContacts) { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { dialedNumber = c.phones.firstOrNull() ?: dialedNumber }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Person, null, tint = IosBlue, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(c.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(c.phones.firstOrNull() ?: "", fontSize = 12.sp, color = IosGrayText)
                            }
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 12.dp)) {
                Text(
                    text = dialedNumber,
                    fontSize = if (dialedNumber.length > 11) 32.sp else 40.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 1.sp,
                    maxLines = 1
                )
                if (dialedNumber.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Text("Add Number", color = IosBlue, fontSize = 13.sp, modifier = Modifier.clickable { showAddContactDialog.value = true })
                        Text("•", color = IosGrayText, fontSize = 13.sp)
                        Text("WhatsApp", color = IosGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onWhatsApp(dialedNumber) })
                    }
                }
            }

            if (simAccounts.size > 1) {
                Row(
                    modifier = Modifier.padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    simAccounts.forEachIndexed { idx, _ ->
                        FilterChip(
                            selected = selectedSimIndex == idx,
                            onClick = { selectedSimIndex = idx },
                            label = { Text("SIM ${idx + 1}") }
                        )
                    }
                }
            }

            keys.chunked(3).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { (digit, letters, _) ->
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .combinedClickable(
                                    onClick = { dialedNumber += digit },
                                    onLongClick = { onSpeedDial(digit) }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(digit, fontSize = 32.sp, fontWeight = FontWeight.Normal)
                                if (letters.isNotBlank()) {
                                    Text(letters, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.size(54.dp))
                FilledIconButton(
                    onClick = {
                        val sim = simAccounts.getOrNull(selectedSimIndex)
                        if (dialedNumber.isNotBlank()) onCall(dialedNumber, sim)
                    },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = IosGreen),
                    modifier = Modifier.size(76.dp)
                ) {
                    Icon(Icons.Default.Call, null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
                Box(modifier = Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                    if (dialedNumber.isNotEmpty()) {
                        IconButton(onClick = { dialedNumber = dialedNumber.dropLast(1) }) {
                            Icon(Icons.AutoMirrored.Filled.Backspace, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun IosRecentsScreen(
        calls: List<NexoraCall>,
        onCall: (String) -> Unit,
        onDeleteCall: (String) -> Unit,
        onDeleteAll: (String) -> Unit
    ) {
        var filterMissed by remember { mutableStateOf(false) }
        val displayed = if (filterMissed) calls.filter { it.type == CallLog.Calls.MISSED_TYPE } else calls

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.width(220.dp).height(32.dp)) {
                    Row {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(7.dp)).background(if (!filterMissed) MaterialTheme.colorScheme.surface else Color.Transparent).clickable { filterMissed = false },
                            contentAlignment = Alignment.Center
                        ) { Text("All", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(7.dp)).background(if (filterMissed) MaterialTheme.colorScheme.surface else Color.Transparent).clickable { filterMissed = true },
                            contentAlignment = Alignment.Center
                        ) { Text("Missed", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }

            Text("Recents", fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(displayed, key = { it.id }) { call ->
                    val isMissed = call.type == CallLog.Calls.MISSED_TYPE
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onCall(call.number) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(call.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (isMissed) IosRed else MaterialTheme.colorScheme.onSurface)
                            Text("${call.number} • ${SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(call.date))} (${call.duration}s)", fontSize = 13.sp, color = IosGrayText)
                        }
                        IconButton(onClick = { onDeleteCall(call.id) }) {
                            Icon(Icons.Default.DeleteOutline, null, tint = IosRed)
                        }
                        IconButton(onClick = { onDeleteAll(call.number) }) {
                            Icon(Icons.Default.ClearAll, null, tint = IosGrayText)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
    }

    @Composable
    private fun IosContactsScreen(
        contacts: List<NexoraContact>,
        onContactClick: (NexoraContact) -> Unit,
        onAddClick: () -> Unit
    ) {
        var search by remember { mutableStateOf("") }
        val filtered = if (search.isBlank()) contacts else contacts.filter { it.name.contains(search, true) || it.phones.any { p -> p.contains(search) } }

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Contacts", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onAddClick) { Icon(Icons.Default.Add, null, tint = IosBlue, modifier = Modifier.size(30.dp)) }
            }

            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().height(42.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp)) {
                    Icon(Icons.Default.Search, null, tint = IosGrayText)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("Search", color = IosGrayText, fontSize = 15.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent),
                        singleLine = true
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onContactClick(c) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            Text(c.name.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(c.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(c.phones.firstOrNull() ?: "", fontSize = 13.sp, color = IosGrayText)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
    }

    @Composable
    private fun IosFavoritesScreen(contacts: List<NexoraContact>, onContactClick: (NexoraContact) -> Unit, onCall: (String) -> Unit) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Favorites", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            LazyColumn {
                items(contacts) { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onContactClick(c) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Star, null, tint = IosOrange, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(c.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(c.phones.firstOrNull() ?: "", fontSize = 13.sp, color = IosGrayText)
                        }
                        IconButton(onClick = { c.phones.firstOrNull()?.let(onCall) }) { Icon(Icons.Default.Call, null, tint = IosGreen) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
    }

    @Composable
    private fun IosSettingsScreen(
        contacts: List<NexoraContact>,
        calls: List<NexoraCall>,
        themeMode: String,
        onThemeChange: (String) -> Unit,
        onExportAes: () -> String,
        onRestoreAes: () -> String,
        hasPin: Boolean,
        onSetPin: (String) -> Unit,
        onRemovePin: () -> Unit,
        isScreenSecured: Boolean,
        onToggleScreenSecurity: (Boolean) -> Unit,
        onAssignSpeedDial: (String, String) -> Unit
    ) {
        var status by remember { mutableStateOf("") }
        var pinInput by remember { mutableStateOf("") }
        var speedKey by remember { mutableStateOf("2") }
        var speedNum by remember { mutableStateOf("") }

        val totalTalkTime = calls.sumOf { it.duration }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Settings & Intelligence", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))

            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Call Intelligence & Analytics", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Total Calls Logged: ${calls.size}", fontSize = 13.sp)
                    Text("Total Talk Time: ${totalTalkTime / 60} minutes (${totalTalkTime}s)", fontSize = 13.sp)
                    Text("Incoming: ${calls.count { it.type == CallLog.Calls.INCOMING_TYPE }} | Outgoing: ${calls.count { it.type == CallLog.Calls.OUTGOING_TYPE }} | Missed: ${calls.count { it.type == CallLog.Calls.MISSED_TYPE }}", fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Appearance Theme", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        listOf("system", "light", "dark").forEach { m ->
                            FilterChip(selected = themeMode == m, onClick = { onThemeChange(m) }, label = { Text(m.replaceFirstChar { it.uppercase() }) })
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Hardware Keystore AES-256 Backup", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { status = onExportAes() }, colors = ButtonDefaults.buttonColors(containerColor = IosBlue), modifier = Modifier.weight(1f)) {
                            Text("Export Backup")
                        }
                        OutlinedButton(onClick = { status = onRestoreAes() }, modifier = Modifier.weight(1f)) {
                            Text("Restore")
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Anti-Screenshot Guard", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Block capture & screen recordings", fontSize = 12.sp, color = IosGrayText)
                    }
                    Switch(checked = isScreenSecured, onCheckedChange = onToggleScreenSecurity)
                }
            }

            if (status.isNotBlank()) {
                Text(status, color = IosBlue, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    @Composable
    private fun RealInCallScreen(call: Call) {
        val callState = call.state
        val details = call.details
        val number = details?.handle?.schemeSpecificPart ?: "Unknown"
        val name = findContactName(number)

        val audioState by NexoraInCallService.callAudioStateFlow.collectAsState()
        val isMuted = audioState?.isMuted == true
        val isSpeaker = audioState?.route == CallAudioState.ROUTE_SPEAKER

        var timerSeconds by remember { mutableIntStateOf(0) }
        LaunchedEffect(callState) {
            if (callState == Call.STATE_ACTIVE) {
                while (true) {
                    delay(1000L)
                    timerSeconds++
                }
            }
        }

        val stateText = when (callState) {
            Call.STATE_RINGING -> "Incoming Call..."
            Call.STATE_DIALING -> "Calling..."
            Call.STATE_HOLDING -> "On Hold"
            Call.STATE_ACTIVE -> String.format(Locale.getDefault(), "%02d:%02d", timerSeconds / 60, timerSeconds % 60)
            else -> "Connecting..."
        }

        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 48.dp).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 28.dp)) {
                    Text(name, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(stateText, color = Color.Gray, fontSize = 18.sp)
                }

                if (callState == Call.STATE_RINGING) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        FilledIconButton(
                            onClick = { NexoraInCallService.rejectCall() },
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = IosRed),
                            modifier = Modifier.size(76.dp)
                        ) {
                            Icon(Icons.Default.CallEnd, null, tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                        FilledIconButton(
                            onClick = { NexoraInCallService.answerCall() },
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = IosGreen),
                            modifier = Modifier.size(76.dp)
                        ) {
                            Icon(Icons.Default.Call, null, tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            IosCallCircleBtn(
                                icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                label = "mute",
                                active = isMuted,
                                onClick = { NexoraInCallService.setMuted(!isMuted) }
                            )
                            IosCallCircleBtn(
                                icon = Icons.Default.VolumeUp,
                                label = "speaker",
                                active = isSpeaker,
                                onClick = { NexoraInCallService.toggleSpeaker(!isSpeaker) }
                            )
                            IosCallCircleBtn(
                                icon = Icons.Default.Pause,
                                label = "hold",
                                active = callState == Call.STATE_HOLDING,
                                onClick = { NexoraInCallService.holdCall(callState != Call.STATE_HOLDING) }
                            )
                        }
                    }

                    FilledIconButton(
                        onClick = { NexoraInCallService.disconnectCall() },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = IosRed),
                        modifier = Modifier.size(76.dp)
                    ) {
                        Icon(Icons.Default.CallEnd, null, tint = Color.White, modifier = Modifier.size(38.dp))
                    }
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
                Icon(icon, contentDescription = label, tint = if (active) Color.Black else Color.White, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(label, color = Color.White, fontSize = 12.sp)
        }
    }

    @Composable
    private fun IosContactDetailScreen(
        contact: NexoraContact,
        onBack: () -> Unit,
        onCall: (String) -> Unit,
        onSms: (String) -> Unit,
        onWhatsApp: (String) -> Unit,
        onTelegram: (String) -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("‹ Back", color = IosBlue, fontSize = 17.sp, modifier = Modifier.clickable(onClick = onBack))
                Text("Edit", color = IosBlue, fontSize = 17.sp, modifier = Modifier.clickable(onClick = onEdit))
            }
            Spacer(Modifier.height(20.dp))
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Text(contact.name.take(1).uppercase(), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Text(contact.name, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IosActionBox(icon = Icons.Default.ChatBubble, label = "message") { contact.phones.firstOrNull()?.let(onSms) }
                IosActionBox(icon = Icons.Default.Call, label = "call") { contact.phones.firstOrNull()?.let(onCall) }
                IosActionBox(icon = Icons.Default.Share, label = "WhatsApp") { contact.phones.firstOrNull()?.let(onWhatsApp) }
                IosActionBox(icon = Icons.Default.Send, label = "Telegram") { contact.phones.firstOrNull()?.let(onTelegram) }
            }
            Spacer(Modifier.height(20.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("mobile", fontSize = 13.sp, color = IosGrayText)
                    Text(contact.phones.joinToString(", "), fontSize = 17.sp, color = IosBlue)
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = onDelete, colors = ButtonDefaults.buttonColors(containerColor = IosRed), modifier = Modifier.fillMaxWidth()) {
                Text("Delete Contact")
            }
        }
    }

    @Composable
    private fun IosActionBox(icon: ImageVector, label: String, onClick: () -> Unit) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(76.dp, 60.dp).clickable(onClick = onClick)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(icon, null, tint = IosBlue, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(4.dp))
                Text(label, fontSize = 11.sp, color = IosBlue)
            }
        }
    }

    @Composable
    private fun IosContactEditSheet(
        contact: NexoraContact?,
        onDismiss: () -> Unit,
        onSave: (String, String, String, String, String) -> Unit
    ) {
        var name by remember { mutableStateOf(contact?.name ?: "") }
        var phone by remember { mutableStateOf(contact?.phones?.firstOrNull() ?: "") }
        var email by remember { mutableStateOf(contact?.email ?: "") }
        var company by remember { mutableStateOf(contact?.company ?: "") }
        var group by remember { mutableStateOf(contact?.group ?: "General") }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (contact != null) "Edit Contact" else "New Contact", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, placeholder = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = phone, onValueChange = { phone = it }, placeholder = { Text("Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = email, onValueChange = { email = it }, placeholder = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = company, onValueChange = { company = it }, placeholder = { Text("Company") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = { if (name.isNotBlank() && phone.isNotBlank()) onSave(name, phone, email, company, group) }) {
                    Text("Done", color = IosBlue, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel", color = IosRed) }
            }
        )
    }

    @Composable
    private fun IosPasscodeScreen(correctPin: String, onUnlocked: () -> Unit) {
        var enteredPin by remember { mutableStateOf("") }
        var hasError by remember { mutableStateOf(false) }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Enter Passcode", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(18.dp))
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
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "del").chunked(3).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { k ->
                            if (k.isEmpty()) {
                                Spacer(Modifier.size(72.dp))
                            } else if (k == "del") {
                                Box(modifier = Modifier.size(72.dp).clickable { if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1) }, contentAlignment = Alignment.Center) {
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
                                ) { Text(k, fontSize = 28.sp) }
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

    @Composable
    private fun IosBottomNavigationBar(currentTab: NexoraTab, onTabSelected: (NexoraTab) -> Unit) {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            NavigationBarItem(
                selected = currentTab == NexoraTab.FAVORITES,
                onClick = { onTabSelected(NexoraTab.FAVORITES) },
                icon = { Icon(Icons.Default.Star, null) },
                label = { Text("Favorites", fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = IosBlue, selectedTextColor = IosBlue)
            )
            NavigationBarItem(
                selected = currentTab == NexoraTab.RECENTS,
                onClick = { onTabSelected(NexoraTab.RECENTS) },
                icon = { Icon(Icons.Default.AccessTime, null) },
                label = { Text("Recents", fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = IosBlue, selectedTextColor = IosBlue)
            )
            NavigationBarItem(
                selected = currentTab == NexoraTab.CONTACTS,
                onClick = { onTabSelected(NexoraTab.CONTACTS) },
                icon = { Icon(Icons.Default.Person, null) },
                label = { Text("Contacts", fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = IosBlue, selectedTextColor = IosBlue)
            )
            NavigationBarItem(
                selected = currentTab == NexoraTab.DIALER,
                onClick = { onTabSelected(NexoraTab.DIALER) },
                icon = { Icon(Icons.Default.Dialpad, null) },
                label = { Text("Keypad", fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = IosBlue, selectedTextColor = IosBlue)
            )
            NavigationBarItem(
                selected = currentTab == NexoraTab.SETTINGS,
                onClick = { onTabSelected(NexoraTab.SETTINGS) },
                icon = { Icon(Icons.Default.Settings, null) },
                label = { Text("Settings", fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = IosBlue, selectedTextColor = IosBlue)
            )
        }
    }
}

@Composable
private fun NexoraIosTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) {
            darkColorScheme(
                background = Color.Black,
                surface = Color(0xFF1C1C1E),
                surfaceVariant = Color(0xFF2C2C2E),
                onSurface = Color.White,
                onSurfaceVariant = Color(0xFFA1A1A6),
                primary = IosBlue
            )
        } else {
            lightColorScheme(
                background = Color.White,
                surface = Color(0xFFF9F9FB),
                surfaceVariant = Color(0xFFE5E5EA),
                onSurface = Color.Black,
                onSurfaceVariant = Color(0xFF3A3A3C),
                primary = IosBlue
            )
        },
        content = content
    )
}
