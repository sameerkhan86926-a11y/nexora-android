package com.nexora.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
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

class MainActivity : ComponentActivity() {

    private val contactsState =
        mutableStateOf<List<NexoraContact>>(emptyList())

    private val callsState =
        mutableStateOf<List<NexoraCall>>(emptyList())

    private val selectedContactState =
        mutableStateOf<NexoraContact?>(null)

    private val currentTabState =
        mutableStateOf(NexoraTab.CONTACTS)

    private val darkModeState =
        mutableStateOf(false)

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

        setContent {
            NexoraTheme(
                darkTheme = darkModeState.value
            ) {
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
            permissionLauncher.launch(
                permissions.toTypedArray()
            )
        } else {
            loadContacts()
            loadRecentCalls()
        }
    }

    private fun loadContacts() {

        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return
        }

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

            val idIndex =
                cursor.getColumnIndex(
                    ContactsContract.Contacts._ID
                )

            val nameIndex =
                cursor.getColumnIndex(
                    ContactsContract.Contacts.DISPLAY_NAME
                )

            val starredIndex =
                cursor.getColumnIndex(
                    ContactsContract.Contacts.STARRED
                )

            val phoneIndex =
                cursor.getColumnIndex(
                    ContactsContract.Contacts.HAS_PHONE_NUMBER
                )

            while (cursor.moveToNext()) {

                if (
                    idIndex < 0 ||
                    nameIndex < 0 ||
                    phoneIndex < 0
                ) {
                    continue
                }

                val id =
                    cursor.getString(idIndex)

                val name =
                    cursor.getString(nameIndex)
                        ?: "Unknown"

                val hasPhone =
                    cursor.getInt(phoneIndex) > 0

                if (!hasPhone) {
                    continue
                }

                val phones =
                    getPhoneNumbers(id)

                if (phones.isEmpty()) {
                    continue
                }

                val starred =
                    if (starredIndex >= 0) {
                        cursor.getInt(starredIndex) == 1
                    } else {
                        false
                    }

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

        contactsState.value = result
    }

    private fun getPhoneNumbers(
        contactId: String
    ): List<String> {

        val numbers = mutableListOf<String>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null
        )?.use { cursor ->

            val index =
                cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )

            if (index >= 0) {

                while (cursor.moveToNext()) {

                    val number =
                        cursor.getString(index)

                    if (
                        !number.isNullOrBlank() &&
                        !numbers.contains(number)
                    ) {
                        numbers.add(number)
                    }
                }
            }
        }

        return numbers
    }

    private fun loadRecentCalls() {

        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) {
            return
        }

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

            val idIndex =
                cursor.getColumnIndex(CallLog.Calls._ID)

            val numberIndex =
                cursor.getColumnIndex(CallLog.Calls.NUMBER)

            val nameIndex =
                cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)

            val typeIndex =
                cursor.getColumnIndex(CallLog.Calls.TYPE)

            val dateIndex =
                cursor.getColumnIndex(CallLog.Calls.DATE)

            var count = 0

            while (cursor.moveToNext() && count < 100) {

                if (
                    idIndex < 0 ||
                    numberIndex < 0 ||
                    typeIndex < 0 ||
                    dateIndex < 0
                ) {
                    continue
                }

                val id =
                    cursor.getString(idIndex)

                val number =
                    cursor.getString(numberIndex)
                        ?: "Unknown"

                val name =
                    if (nameIndex >= 0) {
                        cursor.getString(nameIndex)
                            ?: findContactName(number)
                    } else {
                        findContactName(number)
                    }

                val type =
                    cursor.getInt(typeIndex)

                val date =
                    cursor.getLong(dateIndex)

                result.add(
                    NexoraCall(
                        id = id,
                        number = number,
                        name = name,
                        type = type,
                        date = date
                    )
                )

                count++
            }
        }

        callsState.value = result
    }

    private fun findContactName(
        number: String
    ): String {

        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return number
        }

        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            ),
            "${ContactsContract.CommonDataKinds.Phone.NUMBER} = ?",
            arrayOf(number),
            null
        )?.use { cursor ->

            if (cursor.moveToFirst()) {

                val index =
                    cursor.getColumnIndex(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    )

                if (index >= 0) {
                    return cursor.getString(index)
                        ?: number
                }
            }
        }

        return number
    }

    private fun makeCall(number: String) {

        val cleanNumber =
            number.trim()

        if (cleanNumber.isBlank()) {
            return
        }

        if (!hasPermission(Manifest.permission.CALL_PHONE)) {

            singlePermissionLauncher.launch(
                Manifest.permission.CALL_PHONE
            )

            return
        }

        try {

            val intent =
                Intent(
                    Intent.ACTION_CALL,
                    Uri.parse(
                        "tel:" +
                            Uri.encode(cleanNumber)
                    )
                )

            startActivity(intent)

        } catch (_: Exception) {

            val dialIntent =
                Intent(
                    Intent.ACTION_DIAL,
                    Uri.parse(
                        "tel:" +
                            Uri.encode(cleanNumber)
                    )
                )

            startActivity(dialIntent)
        }
    }

    private fun sendSms(number: String) {

        val intent =
            Intent(
                Intent.ACTION_SENDTO,
                Uri.parse(
                    "smsto:" +
                        Uri.encode(number)
                )
            )

        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun addContact() {

        val intent =
            Intent(
                Intent.ACTION_INSERT
            ).apply {
                type =
                    ContactsContract.Contacts.CONTENT_TYPE
            }

        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun editContact(
        contact: NexoraContact
    ) {

        val uri =
            ContentUris.withAppendedId(
                ContactsContract.Contacts.CONTENT_URI,
                contact.id.toLong()
            )

        val intent =
            Intent(
                Intent.ACTION_EDIT,
                uri
            )

        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun deleteContact(
        contact: NexoraContact
    ) {

        if (!hasPermission(Manifest.permission.WRITE_CONTACTS)) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.WRITE_CONTACTS
                )
            )
            return
        }

        val uri =
            ContentUris.withAppendedId(
                ContactsContract.Contacts.CONTENT_URI,
                contact.id.toLong()
            )

        try {
            contentResolver.delete(
                uri,
                null,
                null
            )

            selectedContactState.value = null
            loadContacts()

        } catch (_: Exception) {
        }
    }

    private fun toggleFavorite(
        contact: NexoraContact
    ) {

        if (!hasPermission(Manifest.permission.WRITE_CONTACTS)) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.WRITE_CONTACTS
                )
            )
            return
        }

        val uri =
            ContentUris.withAppendedId(
                ContactsContract.Contacts.CONTENT_URI,
                contact.id.toLong()
            )

        val values =
            android.content.ContentValues().apply {
                put(
                    ContactsContract.Contacts.STARRED,
                    if (contact.starred) 0 else 1
                )
            }

        try {

            contentResolver.update(
                uri,
                values,
                null,
                null
            )

            loadContacts()

            selectedContactState.value =
                contactsState.value.firstOrNull {
                    it.id == contact.id
                }

        } catch (_: Exception) {
        }
    }

    @Composable
    private fun NexoraApp() {

        val selectedContact =
            selectedContactState.value

        if (selectedContact != null) {

            ContactDetailsScreen(
                contact = selectedContact,
                onBack = {
                    selectedContactState.value = null
                },
                onCall = {
                    makeCall(it)
                },
                onSms = {
                    sendSms(it)
                },
                onEdit = {
                    editContact(selectedContact)
                },
                onDelete = {
                    deleteContact(selectedContact)
                },
                onFavorite = {
                    toggleFavorite(selectedContact)
                }
            )

            return
        }

        Scaffold(
            bottomBar = {
                NexoraBottomBar(
                    currentTab = currentTabState.value,
                    onTabSelected = {
                        currentTabState.value = it
                    }
                )
            }
        ) { padding ->

            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {

                when (currentTabState.value) {

                    NexoraTab.CONTACTS -> {

                        ContactsScreen(
                            contacts = contactsState.value,
                            onContactClick = {
                                selectedContactState.value = it
                            },
                            onAdd = {
                                addContact()
                            }
                        )
                    }

                    NexoraTab.FAVORITES -> {

                        FavoritesScreen(
                            contacts =
                                contactsState.value.filter {
                                    it.starred
                                },
                            onContactClick = {
                                selectedContactState.value = it
                            },
                            onAdd = {
                                addContact()
                            }
                        )
                    }

                    NexoraTab.DIALER -> {

                        DialerScreen(
                            onCall = {
                                makeCall(it)
                            },
                            onSms = {
                                sendSms(it)
                            }
                        )
                    }

                    NexoraTab.RECENTS -> {

                        RecentsScreen(
                            calls = callsState.value,
                            onCall = {
                                makeCall(it)
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ContactsScreen(
        contacts: List<NexoraContact>,
        onContactClick: (NexoraContact) -> Unit,
        onAdd: () -> Unit
    ) {

        var search by remember {
            mutableStateOf("")
        }

        val filtered =
            remember(
                contacts,
                search
            ) {

                if (search.isBlank()) {
                    contacts
                } else {
                    contacts.filter {
                        it.name.contains(
                            search,
                            ignoreCase = true
                        ) ||
                            it.phones.any {
                                number ->
                                number.contains(search)
                            }
                    }
                }
            }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Column {

                    Text(
                        text = "NEXORA",
                        style =
                            MaterialTheme.typography.headlineMedium,
                        fontWeight =
                            FontWeight.Bold
                    )

                    Text(
                        text = "Contacts",
                        style =
                            MaterialTheme.typography.bodyMedium
                    )
                }

                Button(
                    onClick = onAdd
                ) {
                    Text("Add")
                }
            }

            Spacer(
                modifier = Modifier.height(16.dp)
            )

            OutlinedTextField(
                value = search,
                onValueChange = {
                    search = it
                },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text("Search contacts")
                },
                singleLine = true
            )

            Spacer(
                modifier = Modifier.height(12.dp)
            )

            Text(
                text =
                    "${filtered.size} contacts",
                style =
                    MaterialTheme.typography.bodyMedium
            )

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            if (filtered.isEmpty()) {

                EmptyState(
                    title =
                        if (search.isBlank()) {
                            "No contacts found"
                        } else {
                            "No matching contacts"
                        },
                    message =
                        if (search.isBlank()) {
                            "Your phone contacts will appear here."
                        } else {
                            "Try another name or phone number."
                        }
                )

            } else {

                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {

                    items(
                        filtered,
                        key = {
                            it.id
                        }
                    ) { contact ->

                        ContactRow(
                            contact = contact,
                            onClick = {
                                onContactClick(contact)
                            }
                        )
                    }
                }
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
                horizontalArrangement =
                    Arrangement.SpaceBetween,
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Column {

                    Text(
                        text = "Favorites",
                        style =
                            MaterialTheme.typography.headlineMedium,
                        fontWeight =
                            FontWeight.Bold
                    )

                    Text(
                        text =
                            "${contacts.size} favorite contacts"
                    )
                }

                Button(
                    onClick = onAdd
                ) {
                    Text("Add")
                }
            }

            Spacer(
                modifier = Modifier.height(16.dp)
            )

            if (contacts.isEmpty()) {

                EmptyState(
                    title = "No favorites",
                    message =
                        "Open a contact and mark it as favorite."
                )

            } else {

                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {

                    items(
                        contacts,
                        key = {
                            it.id
                        }
                    ) { contact ->

                        ContactRow(
                            contact = contact,
                            onClick = {
                                onContactClick(contact)
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ContactRow(
        contact: NexoraContact,
        onClick: () -> Unit
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = onClick
                )
                .padding(
                    vertical = 12.dp
                ),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            val firstLetter =
                contact.name
                    .trim()
                    .firstOrNull()
                    ?.uppercase()
                    ?: "?"

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme
                            .primaryContainer
                    ),
                contentAlignment =
                    Alignment.Center
            ) {

                Text(
                    text = firstLetter,
                    fontWeight =
                        FontWeight.Bold
                )
            }

            Spacer(
                modifier = Modifier.width(14.dp)
            )

            Column(
                modifier =
                    Modifier.weight(1f)
            ) {

                Text(
                    text = contact.name,
                    style =
                        MaterialTheme.typography
                            .titleMedium,
                    fontWeight =
                        FontWeight.SemiBold
                )

                Text(
                    text =
                        contact.phones.firstOrNull()
                            ?: "",
                    style =
                        MaterialTheme.typography
                            .bodyMedium
                )

                if (contact.phones.size > 1) {

                    Text(
                        text =
                            "+${contact.phones.size - 1} more number",
                        style =
                            MaterialTheme.typography
                                .bodySmall
                    )
                }
            }

            if (contact.starred) {

                Text(
                    text = "Favorite",
                    style =
                        MaterialTheme.typography
                            .labelSmall
                )
            }
        }

        Divider()
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

            TextButton(
                onClick = onBack
            ) {
                Text("Back")
            }

            Spacer(
                modifier = Modifier.height(12.dp)
            )

            Box(
                modifier = Modifier
                    .size(86.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme
                            .primaryContainer
                    ),
                contentAlignment =
                    Alignment.Center
            ) {

                Text(
                    text =
                        contact.name
                            .firstOrNull()
                            ?.uppercase()
                            ?: "?",
                    style =
                        MaterialTheme.typography
                            .headlineLarge,
                    fontWeight =
                        FontWeight.Bold
                )
            }

            Spacer(
                modifier = Modifier.height(16.dp)
            )

            Text(
                text = contact.name,
                style =
                    MaterialTheme.typography
                        .headlineSmall,
                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                modifier = Modifier.height(16.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(10.dp)
            ) {

                Button(
                    onClick = {
                        contact.phones
                            .firstOrNull()
                            ?.let(onCall)
                    },
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text("Call")
                }

                OutlinedButton(
                    onClick = {
                        contact.phones
                            .firstOrNull()
                            ?.let(onSms)
                    },
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text("SMS")
                }
            }

            Spacer(
                modifier = Modifier.height(12.dp)
            )

            OutlinedButton(
                onClick = onFavorite,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (contact.starred) {
                        "Remove Favorite"
                    } else {
                        "Add to Favorites"
                    }
                )
            }

            Spacer(
                modifier = Modifier.height(20.dp)
            )

            Text(
                text = "Phone numbers",
                style =
                    MaterialTheme.typography.titleMedium,
                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            contact.phones.forEach { number ->

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            vertical = 4.dp
                        ),
                    colors =
                        CardDefaults.cardColors()
                ) {

                    Column(
                        modifier =
                            Modifier.padding(14.dp)
                    ) {

                        Text(
                            text = number,
                            style =
                                MaterialTheme.typography
                                    .bodyLarge
                        )

                        Row {

                            TextButton(
                                onClick = {
                                    onCall(number)
                                }
                            ) {
                                Text("Call")
                            }

                            TextButton(
                                onClick = {
                                    onSms(number)
                                }
                            ) {
                                Text("SMS")
                            }
                        }
                    }
                }
            }

            Spacer(
                modifier = Modifier.height(20.dp)
            )

            OutlinedButton(
                onClick = onEdit,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Edit Contact")
            }

            Spacer(
                modifier = Modifier.height(8.dp)
            )

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
        onCall: (String) -> Unit,
        onSms: (String) -> Unit
    ) {

        var number by remember {
            mutableStateOf("")
        }

        val keys =
            listOf(
                "1", "2", "3",
                "4", "5", "6",
                "7", "8", "9",
                "*", "0", "#"
            )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = 24.dp,
                    vertical = 20.dp
                ),
            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Text(
                text = "Dial Pad",
                style =
                    MaterialTheme.typography
                        .headlineMedium,
                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                modifier = Modifier.height(20.dp)
            )

            Text(
                text =
                    if (number.isBlank()) {
                        "Enter number"
                    } else {
                        number
                    },
                style =
                    MaterialTheme.typography
                        .headlineSmall,
                textAlign =
                    TextAlign.Center,
                modifier =
                    Modifier.fillMaxWidth()
            )

            Spacer(
                modifier = Modifier.height(20.dp)
            )

            keys.chunked(3).forEach { row ->

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.SpaceEvenly
                ) {

                    row.forEach { key ->

                        Button(
                            onClick = {
                                number += key
                            },
                            modifier =
                                Modifier
                                    .size(78.dp)
                        ) {
                            Text(
                                text = key,
                                style =
                                    MaterialTheme
                                        .typography
                                        .titleLarge
                            )
                        }
                    }
                }

                Spacer(
                    modifier = Modifier.height(10.dp)
                )
            }

            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(10.dp)
            ) {

                OutlinedButton(
                    onClick = {
                        if (number.isNotEmpty()) {
                            number =
                                number.dropLast(1)
                        }
                    }
                ) {
                    Text("Delete")
                }

                Button(
                    onClick = {
                        if (number.isNotBlank()) {
                            onCall(number)
                        }
                    }
                ) {
                    Text("Call")
                }

                OutlinedButton(
                    onClick = {
                        if (number.isNotBlank()) {
                            onSms(number)
                        }
                    }
                ) {
                    Text("SMS")
                }
            }
        }
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
                style =
                    MaterialTheme.typography
                        .headlineMedium,
                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                modifier = Modifier.height(16.dp)
            )

            if (calls.isEmpty()) {

                EmptyState(
                    title = "No recent calls",
                    message =
                        "Your recent phone calls will appear here."
                )

            } else {

                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {

                    items(
                        calls,
                        key = {
                            it.id
                        }
                    ) { call ->

                        RecentCallRow(
                            call = call,
                            onCall = {
                                onCall(call.number)
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun RecentCallRow(
        call: NexoraCall,
        onCall: () -> Unit
    ) {

        val callType =
            when (call.type) {
                CallLog.Calls.INCOMING_TYPE ->
                    "Incoming"

                CallLog.Calls.OUTGOING_TYPE ->
                    "Outgoing"

                CallLog.Calls.MISSED_TYPE ->
                    "Missed"

                CallLog.Calls.REJECTED_TYPE ->
                    "Rejected"

                else ->
                    "Call"
            }

        val dateText =
            SimpleDateFormat(
                "dd MMM, hh:mm a",
                Locale.getDefault()
            ).format(
                Date(call.date)
            )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    vertical = 12.dp
                ),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Column(
                modifier =
                    Modifier.weight(1f)
            ) {

                Text(
                    text =
                        if (call.name.isBlank()) {
                            call.number
                        } else {
                            call.name
                        },
                    style =
                        MaterialTheme.typography
                            .titleMedium,
                    fontWeight =
                        FontWeight.SemiBold
                )

                Text(
                    text = call.number,
                    style =
                        MaterialTheme.typography
                            .bodyMedium
                )

                Text(
                    text =
                        "$callType • $dateText",
                    style =
                        MaterialTheme.typography
                            .bodySmall
                )
            }

            OutlinedButton(
                onClick = onCall
            ) {
                Text("Call")
            }
        }

        Divider()
    }

    @Composable
    private fun EmptyState(
        title: String,
        message: String
    ) {

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment =
                Alignment.CenterHorizontally,
            verticalArrangement =
                Arrangement.Center
        ) {

            Text(
                text = title,
                style =
                    MaterialTheme.typography
                        .titleLarge,
                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            Text(
                text = message,
                textAlign =
                    TextAlign.Center
            )
        }
    }

    @Composable
    private fun NexoraBottomBar(
        currentTab: NexoraTab,
        onTabSelected: (NexoraTab) -> Unit
    ) {

        Surface(
            tonalElevation = 4.dp
        ) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(
                        horizontal = 8.dp,
                        vertical = 8.dp
                    ),
                horizontalArrangement =
                    Arrangement.SpaceEvenly
            ) {

                BottomItem(
                    title = "Contacts",
                    selected =
                        currentTab ==
                            NexoraTab.CONTACTS,
                    onClick = {
                        onTabSelected(
                            NexoraTab.CONTACTS
                        )
                    }
                )

                BottomItem(
                    title = "Favorites",
                    selected =
                        currentTab ==
                            NexoraTab.FAVORITES,
                    onClick = {
                        onTabSelected(
                            NexoraTab.FAVORITES
                        )
                    }
                )

                BottomItem(
                    title = "Dialer",
                    selected =
                        currentTab ==
                            NexoraTab.DIALER,
                    onClick = {
                        onTabSelected(
                            NexoraTab.DIALER
                        )
                    }
                )

                BottomItem(
                    title = "Recents",
                    selected =
                        currentTab ==
                            NexoraTab.RECENTS,
                    onClick = {
                        onTabSelected(
                            NexoraTab.RECENTS
                        )
                    }
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

        TextButton(
            onClick = onClick
        ) {

            Text(
                text = title,
                fontWeight =
                    if (selected) {
                        FontWeight.Bold
                    } else {
                        FontWeight.Normal
                    }
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
        colorScheme =
            if (darkTheme) {
                androidx.compose.material3.darkColorScheme()
            } else {
                androidx.compose.material3.lightColorScheme()
            },
        content = content
    )
}
