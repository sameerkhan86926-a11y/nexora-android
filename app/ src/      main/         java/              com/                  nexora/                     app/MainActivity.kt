package com.nexora.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

data class NexoraContact(
    val id: String,
    val name: String,
    val phone: String
)

class MainActivity : ComponentActivity() {

    private var contacts by mutableStateOf<List<NexoraContact>>(emptyList())

    private val contactPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val granted =
                permissions[Manifest.permission.READ_CONTACTS] == true

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
                    NexoraApp()
                }
            }
        }

        checkContactPermission()
    }

    private fun checkContactPermission() {
        val permission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS
        )

        if (permission == PackageManager.PERMISSION_GRANTED) {
            loadContacts()
        } else {
            contactPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS
                )
            )
        }
    }

    private fun loadContacts() {
        val result = mutableListOf<NexoraContact>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->

            val idIndex =
                cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                )

            val nameIndex =
                cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )

            val phoneIndex =
                cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )

            while (cursor.moveToNext()) {

                val id = cursor.getString(idIndex) ?: ""
                val name = cursor.getString(nameIndex) ?: "Unknown"
                val phone = cursor.getString(phoneIndex) ?: ""

                result.add(
                    NexoraContact(
                        id = id,
                        name = name,
                        phone = phone
                    )
                )
            }
        }

        contacts = result
    }

    private fun makeCall(phone: String) {

        val intent = Intent(
            Intent.ACTION_CALL,
            Uri.parse("tel:${Uri.encode(phone)}")
        )

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(intent)
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.CALL_PHONE),
                1001
            )
        }
    }

    private fun sendSms(phone: String) {

        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("smsto:${Uri.encode(phone)}")
        )

        startActivity(intent)
    }

    @androidx.compose.runtime.Composable
    private fun NexoraApp() {

        var searchQuery by remember {
            mutableStateOf("")
        }

        var selectedContact by remember {
            mutableStateOf<NexoraContact?>(null)
        }

        val filteredContacts = contacts.filter { contact ->

            val query = searchQuery.trim()

            query.isEmpty() ||
                    contact.name.contains(
                        query,
                        ignoreCase = true
                    ) ||
                    contact.phone.contains(query)
        }

        if (selectedContact != null) {

            ContactDetailsScreen(
                contact = selectedContact!!,
                onBack = {
                    selectedContact = null
                },
                onCall = {
                    makeCall(selectedContact!!.phone)
                },
                onSms = {
                    sendSms(selectedContact!!.phone)
                }
            )

        } else {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {

                Text(
                    text = "NEXORA",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Smart Phone & Contacts",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(
                    modifier = Modifier.height(20.dp)
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text("Search contacts")
                    },
                    placeholder = {
                        Text("Name or phone number")
                    }
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
                            modifier = Modifier.height(8.dp)
                        )

                        Text(
                            text = "Allow Contacts permission to use NEXORA."
                        )

                        Spacer(
                            modifier = Modifier.height(16.dp)
                        )

                        Button(
                            onClick = {
                                checkContactPermission()
                            }
                        ) {
                            Text("Allow Contacts")
                        }
                    }

                } else {

                    Text(
                        text = "${filteredContacts.size} contacts",
                        style = MaterialTheme.typography.labelLarge
                    )

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {

                        items(
                            items = filteredContacts,
                            key = {
                                "${it.id}_${it.phone}"
                            }
                        ) { contact ->

                            ContactItem(
                                contact = contact,
                                onClick = {
                                    selectedContact = contact
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ContactItem(
        contact: NexoraContact,
        onClick: () -> Unit
    ) {

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .clickable {
                    onClick()
                }
        ) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Column(
                    modifier = Modifier.weight(1f)
                ) {

                    Text(
                        text = contact.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(
                        modifier = Modifier.height(4.dp)
                    )

                    Text(
                        text = contact.phone,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Text(
                    text = "View",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ContactDetailsScreen(
        contact: NexoraContact,
        onBack: () -> Unit,
        onCall: () -> Unit,
        onSms: () -> Unit
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
                modifier = Modifier.height(20.dp)
            )

            Text(
                text = contact.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            Text(
                text = contact.phone,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(
                modifier = Modifier.height(30.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                Button(
                    onClick = onCall,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Call")
                }

                Button(
                    onClick = onSms,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Message")
                }
            }
        }
    }
}
