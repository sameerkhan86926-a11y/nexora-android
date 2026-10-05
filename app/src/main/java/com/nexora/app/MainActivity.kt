package com.nexora.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

data class NexoraContact(
    val id: String,
    val name: String,
    val phone: String
)

class MainActivity : ComponentActivity() {

    private val contacts = mutableStateOf<List<NexoraContact>>(emptyList())

    private val requestContactsPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                loadContacts()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    NexoraContactsScreen(
                        contacts = contacts.value,
                        onRequestPermission = {
                            requestContactsPermission.launch(
                                Manifest.permission.READ_CONTACTS
                            )
                        }
                    )
                }
            }
        }

        checkContactsPermission()
    }

    private fun checkContactsPermission() {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            loadContacts()
        } else {
            requestContactsPermission.launch(
                Manifest.permission.READ_CONTACTS
            )
        }
    }

    private fun loadContacts() {
        val result = mutableListOf<NexoraContact>()

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
        )

        contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.Contacts.DISPLAY_NAME + " ASC"
        )?.use { cursor ->

            val idIndex =
                cursor.getColumnIndex(ContactsContract.Contacts._ID)

            val nameIndex =
                cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)

            val hasPhoneIndex =
                cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

            while (cursor.moveToNext()) {

                if (
                    idIndex < 0 ||
                    nameIndex < 0 ||
                    hasPhoneIndex < 0
                ) {
                    continue
                }

                val id = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex) ?: "Unknown"

                val hasPhone =
                    cursor.getInt(hasPhoneIndex) > 0

                if (!hasPhone) {
                    continue
                }

                val phone = getPhoneNumber(id)

                if (phone.isNotEmpty()) {
                    result.add(
                        NexoraContact(
                            id = id,
                            name = name,
                            phone = phone
                        )
                    )
                }
            }
        }

        contacts.value = result
    }

    private fun getPhoneNumber(contactId: String): String {

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

            if (cursor.moveToFirst()) {

                val index =
                    cursor.getColumnIndex(
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    )

                if (index >= 0) {
                    return cursor.getString(index) ?: ""
                }
            }
        }

        return ""
    }
}

@androidx.compose.runtime.Composable
private fun NexoraContactsScreen(
    contacts: List<NexoraContact>,
    onRequestPermission: () -> Unit
) {

    var searchQuery by remember {
        mutableStateOf("")
    }

    val filteredContacts = remember(
        contacts,
        searchQuery
    ) {

        if (searchQuery.isBlank()) {
            contacts
        } else {
            contacts.filter {
                it.name.contains(
                    searchQuery,
                    ignoreCase = true
                ) ||
                it.phone.contains(searchQuery)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {

        Text(
            text = "NEXORA",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "Contacts",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = {
                searchQuery = it
            },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text("Search contacts")
            },
            singleLine = true
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        if (contacts.isEmpty()) {

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {

                Text(
                    text = "No contacts found",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(
                    modifier = Modifier.height(12.dp)
                )

                Button(
                    onClick = onRequestPermission
                ) {
                    Text("Allow Contacts")
                }
            }

        } else {

            Text(
                text = "${filteredContacts.size} contacts",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {

                items(
                    items = filteredContacts,
                    key = { it.id }
                ) { contact ->

                    ContactRow(
                        contact = contact
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ContactRow(
    contact: NexoraContact
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { }
            .padding(
                vertical = 14.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Column(
            modifier = Modifier.weight(1f)
        ) {

            Text(
                text = contact.name,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(
                modifier = Modifier.height(3.dp)
            )

            Text(
                text = contact.phone,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
