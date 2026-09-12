package com.hazia.emivita.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.export.EmergencyCardExporter
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.core.data.repository.ProfileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import androidx.compose.ui.platform.LocalResources

/**
 * Medical ID (spec section 9's parking lot, promoted in section 7 R48).
 *
 * Everything on this screen is text the user typed and the app never
 * interprets. There is no allergy checking, no interaction warning, no phone
 * number validation -- section 9's entry fixes that boundary ("informational
 * text only, never algorithmically checked against anything"), and it is what
 * keeps the exported card a document rather than a medical claim.
 *
 * The export is the point of the screen, not an afterthought on it. Android's
 * own Emergency Information already covers the lock-screen case better than
 * this app could, being readable without unlocking the phone at all; what it
 * cannot do is produce a page to hand a new doctor or fold into a wallet.
 *
 * This screen is also why Profile.name is now a field anyone sees. EmivitaApp
 * creates the single profile silently as "Me", and justified the fixed string
 * on the grounds that a profile's name is never displayed anywhere. An
 * emergency card prints it at the top, so that stopped being true here --
 * which is why Name is the first field and the one field the export refuses to
 * run without.
 */
@Composable
fun MedicalIdScreen(
    profileId: Long,
    profileRepository: ProfileRepository,
    medicineRepository: MedicineRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // LocalResources, not LocalContext, for strings resolved inside a
    // callback: Compose's own lint (LocalContextGetResourceValueCall) flags
    // context.getString() on a captured context because it will not see a
    // configuration change; this one does (§9 #19).
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    var profile by remember { mutableStateOf<Profile?>(null) }
    var loaded by remember { mutableStateOf(false) }

    var name by remember { mutableStateOf("") }
    var bloodType by remember { mutableStateOf("") }
    var allergies by remember { mutableStateOf("") }
    var doctorName by remember { mutableStateOf("") }
    var doctorPhone by remember { mutableStateOf("") }
    var doctorAddress by remember { mutableStateOf("") }
    var pharmacyName by remember { mutableStateOf("") }
    var pharmacyPhone by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(profileId) {
        val existing = profileRepository.getById(profileId)
        profile = existing
        if (existing != null) {
            // "Me" is EmivitaApp's placeholder for a name nothing used to
            // display. Showing it here as though the user had chosen it would
            // invite them to leave it, and then hand a doctor a card headed
            // "Me" -- so it is treated as the absence it actually is.
            name = existing.name.takeIf { it != PLACEHOLDER_PROFILE_NAME }.orEmpty()
            bloodType = existing.bloodType.orEmpty()
            allergies = existing.allergies.orEmpty()
            doctorName = existing.doctorName.orEmpty()
            doctorPhone = existing.doctorPhone.orEmpty()
            doctorAddress = existing.doctorAddress.orEmpty()
            pharmacyName = existing.pharmacyName.orEmpty()
            pharmacyPhone = existing.pharmacyPhone.orEmpty()
        }
        loaded = true
    }

    fun edited(base: Profile) = base.copy(
        name = name.trim().ifBlank { PLACEHOLDER_PROFILE_NAME },
        bloodType = bloodType.trim().ifBlank { null },
        allergies = allergies.trim().ifBlank { null },
        doctorName = doctorName.trim().ifBlank { null },
        doctorPhone = doctorPhone.trim().ifBlank { null },
        doctorAddress = doctorAddress.trim().ifBlank { null },
        pharmacyName = pharmacyName.trim().ifBlank { null },
        pharmacyPhone = pharmacyPhone.trim().ifBlank { null },
    )

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val base = profile ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                // Saved before exporting, not merely alongside it: a card
                // printed from on-screen text the user never committed would
                // disagree with the database next time this screen opened, and
                // the paper copy is the one that ends up in a wallet.
                val updated = edited(base)
                profileRepository.update(updated)
                profile = updated
                val medicines = medicineRepository.observeForProfile(profileId).first().map { it.medicine }
                val document = EmergencyCardExporter.buildPdf(updated, medicines, labels = EmergencyCardExporter.CardLabels.from(resources))
                context.contentResolver.openOutputStream(uri)?.use {
                    EmergencyCardExporter.writePdf(document, it)
                }
            } catch (e: Exception) {
                saveError = resources.getString(R.string.medical_id_export_failed, e.message ?: resources.getString(R.string.common_unknown_error))
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        DetailHeader(stringResource(R.string.medical_id_medical_id), onBack)

        if (!loaded) {
            CircularProgressIndicator()
            return@Column
        }
        val base = profile
        if (base == null) {
            Text(stringResource(R.string.medical_id_profile_not_found), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        Text(
            stringResource(R.string.medical_id_printed_onto_an_emergency_card_you_can_hand),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Field(stringResource(R.string.common_name), name, { name = it }, stringResource(R.string.medical_id_name_helper))
        Field(stringResource(R.string.medical_id_blood_type), bloodType, { bloodType = it }, stringResource(R.string.medical_id_blood_type_helper))
        Field(
            stringResource(R.string.medical_id_allergies),
            allergies,
            { allergies = it },
            stringResource(R.string.medical_id_allergies_helper),
            singleLine = false,
        )

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.medical_id_doctor), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Field(stringResource(R.string.medical_id_doctor_name), doctorName, { doctorName = it })
        Field(stringResource(R.string.medical_id_doctor_phone), doctorPhone, { doctorPhone = it })
        Field(stringResource(R.string.medical_id_doctor_address), doctorAddress, { doctorAddress = it }, singleLine = false)

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.medical_id_pharmacy), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Field(stringResource(R.string.medical_id_pharmacy_name), pharmacyName, { pharmacyName = it })
        Field(stringResource(R.string.medical_id_pharmacy_phone), pharmacyPhone, { pharmacyPhone = it })

        Spacer(Modifier.height(20.dp))
        Row {
            Button(onClick = {
                scope.launch {
                    try {
                        val updated = edited(base)
                        profileRepository.update(updated)
                        profile = updated
                        onBack()
                    } catch (e: Exception) {
                        saveError = resources.getString(R.string.medical_id_save_failed, e.message ?: resources.getString(R.string.common_unknown_error))
                    }
                }
            }) { Text(stringResource(R.string.common_save)) }
            OutlinedButton(
                // A card headed with a placeholder is worse than no card, so
                // this is the one field the export insists on. Every other
                // field may legitimately be blank -- blood type and allergies
                // print "Not recorded", and an empty contact block is omitted.
                enabled = name.isNotBlank(),
                onClick = { exportLauncher.launch("emivita-emergency-card.pdf") },
                modifier = Modifier.padding(start = 12.dp),
            ) { Text(stringResource(R.string.medical_id_export_card_pdf)) }
        }
        if (name.isBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.medical_id_add_a_name_before_exporting_the_card_is),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        saveError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** EmivitaApp's silent-creation placeholder. Held as its own constant rather
 *  than imported because the two uses are opposites: there it is a value to
 *  write, here it is a value to recognise and refuse to show. */
private const val PLACEHOLDER_PROFILE_NAME = "Me"

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    helper: String? = null,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
    )
    if (helper != null) {
        Text(
            helper,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(10.dp))
}
