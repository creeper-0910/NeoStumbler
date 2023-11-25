package xyz.malkki.neostumbler.ui.screens

import android.Manifest
import android.os.Build
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.malkki.neostumbler.R
import xyz.malkki.neostumbler.db.entities.ReportWithStats
import xyz.malkki.neostumbler.extensions.checkMissingPermissions
import xyz.malkki.neostumbler.extensions.defaultLocale
import xyz.malkki.neostumbler.scanner.ScannerService
import xyz.malkki.neostumbler.ui.composables.BatteryOptimizationsDialog
import xyz.malkki.neostumbler.ui.composables.PermissionsDialog
import xyz.malkki.neostumbler.ui.composables.ReportUploadButton
import xyz.malkki.neostumbler.ui.composables.getAddress
import xyz.malkki.neostumbler.ui.composables.rememberServiceConnection
import xyz.malkki.neostumbler.ui.viewmodel.ReportsViewModel
import xyz.malkki.neostumbler.utils.PermissionHelper
import xyz.malkki.neostumbler.utils.geocoder.CachingGeocoder
import xyz.malkki.neostumbler.utils.geocoder.Geocoder
import xyz.malkki.neostumbler.utils.geocoder.PlatformGeocoder
import xyz.malkki.neostumbler.utils.showMapWithMarkerIntent
import java.util.Date
import android.location.Geocoder as AndroidGeocoder

@Composable
fun ReportsScreen() {
    Column {
        Row {
            ForegroundScanningButton()
            Spacer(modifier = Modifier.weight(1.0f))
            ReportUploadButton()
        }
        ReportStats()
        Reports()
    }
}

@Composable
private fun ReportStats(reportsViewModel: ReportsViewModel = viewModel()) {
    val context = LocalContext.current

    val reportsTotal = reportsViewModel.reportsTotal.observeAsState()
    val reportsNotUploaded = reportsViewModel.reportsNotUploaded.observeAsState()
    val reportsLastUploaded = reportsViewModel.lastUpload.observeAsState()

    val lastUploadedText = reportsLastUploaded.value?.let {
        val millis = it.toEpochMilli()

        DateFormat.getMediumDateFormat(context).format(millis) + " " + DateFormat.getTimeFormat(context).format(millis)
    }

    Column(modifier = Modifier
        .wrapContentHeight()) {
        Text(text = stringResource(R.string.reports_total, reportsTotal.value ?: ""))
        Text(text = stringResource(R.string.reports_not_uploaded, reportsNotUploaded.value ?: ""))
        Text(text = stringResource(R.string.reports_last_uploaded, lastUploadedText ?: ""))
    }
}

private val requiredPermissions = mutableListOf<String>()
    .apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    .toTypedArray()

@Composable
fun ForegroundScanningButton() {
    val context = LocalContext.current

    val serviceConnection = rememberServiceConnection(getService = ScannerService.ScannerServiceBinder::getService)

    val showBatteryOptimizationsDialog = remember {
        mutableStateOf(false)
    }

    val showPermissionDialog = remember {
        mutableStateOf(false)
    }

    val missingPermissions = remember {
        context.checkMissingPermissions(*requiredPermissions)
    }

    val onPermissionsGranted: (Map<String, Boolean>) -> Unit = { permissions ->
        showPermissionDialog.value = false

        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            showBatteryOptimizationsDialog.value = true
        } else {
            Toast.makeText(context, context.getString(R.string.permissions_not_granted), Toast.LENGTH_SHORT).show()
        }
    }

    if (showPermissionDialog.value) {
        PermissionsDialog(
            missingPermissions = missingPermissions,
            permissionRationales = PermissionHelper.PERMISSION_RATIONALES,
            onPermissionsGranted = onPermissionsGranted
        )
    }

    if (showBatteryOptimizationsDialog.value) {
        BatteryOptimizationsDialog(onBatteryOptimizationsDisabled = {
            showBatteryOptimizationsDialog.value = false
            context.startForegroundService(ScannerService.startIntent(context))
        })
    }

    Button(
        onClick = {
            if (ScannerService.serviceRunning) {
                context.startService(ScannerService.stopIntent(context))
            } else {
                if (Manifest.permission.ACCESS_FINE_LOCATION !in missingPermissions) {
                    showBatteryOptimizationsDialog.value = true
                } else {
                    showPermissionDialog.value = true
                }
            }
        }
    ) {
        val stringResId = if (serviceConnection.value != null) {
            R.string.stop_scanning
        } else {
            R.string.start_scanning
        }

        Text(text = stringResource(stringResId))
    }
}

@Composable
private fun Reports(reportsViewModel: ReportsViewModel = viewModel()) {
    val reports = reportsViewModel.reports.observeAsState(initial = emptyList())

    val context = LocalContext.current
    val geocoder = remember {
        CachingGeocoder(PlatformGeocoder(AndroidGeocoder(context, context.defaultLocale), 1))
    }

    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(text = stringResource(R.string.reports), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
        LazyColumn {
            itemsIndexed(
                reports.value,
                key =  { _: Int, report: ReportWithStats -> report.reportId },
                itemContent = { _, report ->
                    Report(report = report, geocoder = geocoder)
                }
            )
        }
    }
}

@Composable
private fun Report(report: ReportWithStats, geocoder: Geocoder) {
    val context = LocalContext.current

    val address = getAddress(report.latitude, report.longitude, geocoder = geocoder)

    val date = Date.from(report.timestamp)
    val dateStr = "${DateFormat.getMediumDateFormat(context).format(date)} ${DateFormat.getTimeFormat(context).format(date)}"

    val intent = showMapWithMarkerIntent(report.latitude, report.longitude)
    val canShowMap = intent.resolveActivity(context.packageManager) != null

    Column(modifier = Modifier
        .padding(vertical = 4.dp)
        .wrapContentHeight()) {
        Row(modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()) {
            Text(modifier = Modifier.wrapContentSize(), text = dateStr, style = TextStyle(fontSize = 14.sp))
            Spacer(modifier = Modifier.weight(1.0f))
            StationCount(iconRes = R.drawable.wifi_14sp, iconDescription = stringResource(R.string.wifi_icon_description), count = report.wifiAccessPointCount)
            Spacer(modifier = Modifier.width(2.dp))
            StationCount(iconRes = R.drawable.cell_tower_14sp, iconDescription = stringResource(R.string.cell_tower_icon_description), count = report.cellTowerCount)
            Spacer(modifier = Modifier.width(2.dp))
            StationCount(iconRes = R.drawable.bluetooth_14sp, iconDescription = stringResource(R.string.bluetooth_icon_description), count = report.bluetoothBeaconCount)
        }
        if (canShowMap) {
            ClickableText(text = AnnotatedString(address.value), style = TextStyle(fontSize = 14.sp, color = Color.Blue), onClick = {
                context.startActivity(intent)
            })
        } else {
            Text(text = address.value, style = TextStyle(fontSize = 14.sp))
        }
    }
}

@Composable
private fun StationCount(iconRes: Int, iconDescription: String, count: Int) {
    Row(modifier = Modifier.wrapContentSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Icon(painter = painterResource(iconRes), contentDescription = iconDescription)
        Spacer(modifier = Modifier.width(2.dp))
        Text(modifier = Modifier
            .wrapContentWidth()
            .fillMaxHeight(), text = count.toString(), style = TextStyle(fontSize = 14.sp))
    }
}