package com.hazia.emivita.feature.medicines

import android.app.Activity.RESULT_OK
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.dao.MedicineWithTags
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.ui.components.parseHexColor
import com.hazia.emivita.ui.icons.MedicineIcons
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicinesScreen(
    profileId: Long,
    medicineRepository: MedicineRepository,
    onAddMedicine: () -> Unit,
    onAddMedicineWithBarcode: (barcode: String) -> Unit,
    onOpenMedicine: (medicineId: Long) -> Unit,
) {
    val medicines by produceState<List<MedicineWithTags>>(initialValue = emptyList(), profileId) {
        medicineRepository.observeForProfile(profileId).collect { value = it }
    }
    val scope = rememberCoroutineScope()
    var notFoundCode by remember { mutableStateOf<String?>(null) }

    // "Scan to find" (spec §3/§9 item 1) -- purely local: scan a code, look it
    // up against Medicine.barcodeValue in the on-device DB. No internet
    // permission needed or declared; scanning itself is delegated to whatever
    // scanner app is installed (e.g. Binary Eye), same as "Scan barcode" on
    // the Add form.
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val code = result.data?.getStringExtra("SCAN_RESULT") ?: return@rememberLauncherForActivityResult
            scope.launch {
                val match = medicineRepository.getByBarcodeValue(code)
                if (match != null) onOpenMedicine(match.id) else notFoundCode = code
            }
        }
    }
    var scanUnavailable by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Medicines") },
                actions = {
                    IconButton(onClick = {
                        try {
                            scanLauncher.launch(Intent("com.google.zxing.client.android.SCAN"))
                            scanUnavailable = false
                        } catch (e: ActivityNotFoundException) {
                            scanUnavailable = true
                        }
                    }) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan to find")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddMedicine) {
                Icon(Icons.Filled.Add, contentDescription = "Add medicine")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (scanUnavailable) {
                Text(
                    "No barcode scanner app installed (e.g. Binary Eye from F-Droid).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (medicines.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No medicines yet. Tap + to add your first one.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    items(medicines, key = { it.medicine.id }) { entry ->
                        MedicineCard(entry, onClick = { onOpenMedicine(entry.medicine.id) })
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }

    val pendingCode = notFoundCode
    if (pendingCode != null) {
        AlertDialog(
            onDismissRequest = { notFoundCode = null },
            title = { Text("Not found") },
            text = { Text("No medicine matches that code yet. Add it as a new one?") },
            confirmButton = {
                TextButton(onClick = {
                    notFoundCode = null
                    onAddMedicineWithBarcode(pendingCode)
                }) { Text("Add new") }
            },
            dismissButton = { TextButton(onClick = { notFoundCode = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MedicineCard(entry: MedicineWithTags, onClick: () -> Unit) {
    val medicine = entry.medicine
    val accent = medicine.color?.let { parseHexColor(it) } ?: Color(0xFFB8ABA9)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.14f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = accent, modifier = Modifier.size(44.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(MedicineIcons.iconFor(medicine.iconKey), contentDescription = null, tint = Color.White)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(medicine.name, style = MaterialTheme.typography.titleMedium)
                if (entry.tags.isNotEmpty()) {
                    Row {
                        entry.tags.take(3).forEach { tag ->
                            AssistChip(onClick = {}, enabled = false, label = { Text(tag.name, style = MaterialTheme.typography.labelSmall) })
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
            }
        }
    }
}
