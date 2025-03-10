package xyz.malkki.neostumbler.ui.composables.settings

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.compose.koinInject
import timber.log.Timber
import xyz.malkki.neostumbler.PREFERENCES
import xyz.malkki.neostumbler.R
import xyz.malkki.neostumbler.constants.PreferenceKeys
import xyz.malkki.neostumbler.extensions.checkMissingPermissions
import xyz.malkki.neostumbler.extensions.getActivity
import xyz.malkki.neostumbler.extensions.showToast
import xyz.malkki.neostumbler.scanner.autoscan.ActivityTransitionReceiver
import xyz.malkki.neostumbler.ui.composables.ToggleWithAction
import xyz.malkki.neostumbler.ui.composables.shared.PermissionsDialog

private fun DataStore<Preferences>.autoScanEnabled(): Flow<Boolean?> =
    data.map { it[booleanPreferencesKey(PreferenceKeys.AUTOSCAN_ENABLED)] }.distinctUntilChanged()

@SuppressLint("MissingPermission")
@Composable
fun AutoScanToggle() {
    val context = LocalContext.current

    val googleApiAvailability = GoogleApiAvailability.getInstance()

    val googleApiAvailabilityCode = googleApiAvailability.isGooglePlayServicesAvailable(context)
    val isGoogleApiAvailable = googleApiAvailabilityCode == ConnectionResult.SUCCESS
    val isGoogleApiAvailabilityUserResolvable =
        googleApiAvailability.isUserResolvableError(googleApiAvailabilityCode)

    val coroutineScope = rememberCoroutineScope()

    val settingsStore = koinInject<DataStore<Preferences>>(PREFERENCES)
    val enabled = settingsStore.autoScanEnabled().collectAsState(initial = false)

    val missingPermissionsBasic = remember {
        val neededPermissions =
            buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACTIVITY_RECOGNITION)
                    add(Manifest.permission.READ_PHONE_STATE)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        add(Manifest.permission.BLUETOOTH_SCAN)
                    } else {
                        add(Manifest.permission.BLUETOOTH)
                        add(Manifest.permission.BLUETOOTH_ADMIN)
                    }
                }
                .toTypedArray()

        mutableStateOf(context.checkMissingPermissions(*neededPermissions))
    }
    // Background location permission has to be requested separately
    val missingPermissionsAdditional = remember {
        mutableStateOf(
            context.checkMissingPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        )
    }

    val showBasicPermissionsDialog = rememberSaveable { mutableStateOf(false) }
    val showAdditionalPermissionsDialog = rememberSaveable { mutableStateOf(false) }

    suspend fun enableAutoScan() {
        try {
            // Use timeout here so that the toggle doesn't get stuck in case the function never
            // returns
            // (this can happen e.g. when using a stub implementation of GPlay services)
            withTimeout(2.seconds) { ActivityTransitionReceiver.enable(context) }

            settingsStore.edit { it[booleanPreferencesKey(PreferenceKeys.AUTOSCAN_ENABLED)] = true }

            Timber.i("Enabled activity transition listener")
        } catch (e: Exception) {
            Timber.w(e, "Failed to enable activity transition listener")

            context.showToast(ContextCompat.getString(context, R.string.autoscan_failed_to_enable))
        }
    }

    suspend fun disableAutoScan() {
        ActivityTransitionReceiver.disable(context)
        settingsStore.edit { it[booleanPreferencesKey(PreferenceKeys.AUTOSCAN_ENABLED)] = false }

        Timber.i("Disabled activity transition listener")
    }

    if (showBasicPermissionsDialog.value) {
        PermissionsDialog(
            missingPermissions = missingPermissionsBasic.value,
            permissionRationales =
                mutableMapOf<String, String>().apply {
                    put(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        stringResource(id = R.string.permission_rationale_fine_location),
                    )
                    put(
                        Manifest.permission.ACTIVITY_RECOGNITION,
                        stringResource(id = R.string.permission_rationale_activity_recognition),
                    )
                    put(
                        Manifest.permission.READ_PHONE_STATE,
                        stringResource(id = R.string.permission_rationale_read_phone_state),
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        put(
                            Manifest.permission.POST_NOTIFICATIONS,
                            stringResource(id = R.string.permission_rationale_post_notifications),
                        )
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        put(
                            Manifest.permission.BLUETOOTH_SCAN,
                            stringResource(id = R.string.permission_rationale_bluetooth),
                        )
                    } else {
                        put(
                            Manifest.permission.BLUETOOTH,
                            stringResource(id = R.string.permission_rationale_bluetooth),
                        )
                        put(
                            Manifest.permission.BLUETOOTH_ADMIN,
                            stringResource(id = R.string.permission_rationale_bluetooth),
                        )
                    }
                },
            onPermissionsGranted = { permissions ->
                showBasicPermissionsDialog.value = false

                missingPermissionsBasic.value =
                    missingPermissionsBasic.value.filter {
                        it !in permissions || permissions[it] == false
                    }

                if (
                    Manifest.permission.ACCESS_FINE_LOCATION !in missingPermissionsBasic.value &&
                        Manifest.permission.ACTIVITY_RECOGNITION !in missingPermissionsBasic.value
                ) {

                    if (missingPermissionsAdditional.value.isEmpty()) {
                        coroutineScope.launch { enableAutoScan() }
                    } else {
                        showAdditionalPermissionsDialog.value = true
                    }
                } else {
                    context.showToast(
                        ContextCompat.getString(context, R.string.permissions_not_granted)
                    )
                }
            },
        )
    }

    if (showAdditionalPermissionsDialog.value) {
        PermissionsDialog(
            missingPermissions = missingPermissionsAdditional.value,
            permissionRationales =
                mapOf(
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION to
                        stringResource(
                            id = R.string.permission_rationale_background_location_autoscan
                        )
                ),
            onPermissionsGranted = { permissions ->
                showAdditionalPermissionsDialog.value = false

                if (permissions[Manifest.permission.ACCESS_BACKGROUND_LOCATION] == true) {
                    coroutineScope.launch { enableAutoScan() }
                } else {
                    context.showToast(
                        ContextCompat.getString(context, R.string.permissions_not_granted)
                    )
                }
            },
        )
    }

    ToggleWithAction(
        title = stringResource(R.string.autoscan_when_moving),
        enabled = (isGoogleApiAvailable || isGoogleApiAvailabilityUserResolvable),
        checked = enabled.value == true,
        action = { checked ->
            // If Google APIs are not available, try to make them available before doing anything
            if (!isGoogleApiAvailable) {
                try {
                    withContext(Dispatchers.Main) {
                        googleApiAvailability
                            .makeGooglePlayServicesAvailable(context.getActivity()!!)
                            .await()
                    }
                } catch (ex: Exception) {
                    Timber.w(
                        ex,
                        "Failed to make Google Play Services available, cannot enable autoscan",
                    )
                    return@ToggleWithAction
                }
            }

            if (checked) {
                if (
                    missingPermissionsBasic.value.isEmpty() &&
                        missingPermissionsAdditional.value.isEmpty()
                ) {
                    enableAutoScan()
                } else {
                    if (missingPermissionsBasic.value.isNotEmpty()) {
                        showBasicPermissionsDialog.value = true
                    } else {
                        showAdditionalPermissionsDialog.value = true
                    }
                }
            } else {
                disableAutoScan()
            }
        },
    )
}
