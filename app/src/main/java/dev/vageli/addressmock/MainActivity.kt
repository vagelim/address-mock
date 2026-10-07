package dev.vageli.addressmock

import android.Manifest
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            AppTheme {
                MainScreen(sharedText = sharedText, onSharedConsumed = { sharedText = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText = Geocoding.cleanSharedText(it) }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(sharedText: String?, onSharedConsumed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val recentStore = remember { RecentPlaces(context) }

    val mockState by MockLocationService.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Place>?>(null) }
    var selected by remember { mutableStateOf<Place?>(null) }
    var recent by remember { mutableStateOf(recentStore.load()) }
    var isMockApp by remember { mutableStateOf(true) }

    LifecycleResumeEffect(Unit) {
        isMockApp = isSelectedMockApp(context)
        onPauseOrDispose { }
    }

    fun search() {
        if (query.isBlank()) return
        focus.clearFocus()
        loading = true
        scope.launch {
            val found = Geocoding.search(context, query)
            loading = false
            results = found
            if (found.size == 1) selected = found.first()
        }
    }

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            query = sharedText
            onSharedConsumed()
            search()
        }
    }

    fun startMock(place: Place) {
        MockLocationService.clearError()
        MockLocationService.start(context, place)
        recent = recentStore.add(place)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val fine = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val place = selected
        if (fine && place != null) startMock(place)
        else if (!fine) Toast.makeText(context, "Location permission is required", Toast.LENGTH_LONG).show()
    }

    fun requestStart(place: Place) {
        val needed = buildList {
            if (!hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33 && !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isEmpty()) startMock(place) else permissionLauncher.launch(needed.toTypedArray())
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Address Mock") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!isMockApp) {
                item { SetupCard(context) }
            }

            item { StatusCard(mockState, onStop = { MockLocationService.stop(context) }) }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Address, place, or lat, lng") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    trailingIcon = {
                        if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = { search() }) { Icon(Icons.Filled.Search, "Search") }
                    },
                )
            }

            selected?.let { place ->
                item {
                    SelectedCard(
                        place = place,
                        running = (mockState as? MockState.Running)?.place == place,
                        onStart = { requestStart(place) },
                        onStop = { MockLocationService.stop(context) },
                        onMap = { openInMaps(context, place) },
                    )
                }
            }

            results?.let { list ->
                item { SectionHeader(if (list.isEmpty()) "No results" else "Results") }
                items(list) { place ->
                    PlaceRow(place, highlighted = place == selected, onClick = { selected = place })
                }
            }

            if (recent.isNotEmpty()) {
                item { SectionHeader("Recent") }
                items(recent, key = { "${it.lat},${it.lng}" }) { place ->
                    PlaceRow(
                        place,
                        highlighted = place == selected,
                        onClick = { selected = place },
                        onRemove = { recent = recentStore.remove(place) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupCard(context: Context) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Setup required", fontWeight = FontWeight.Bold)
            Text(
                "1. Enable Developer options (tap Build number 7 times in About phone).\n" +
                    "2. In Developer options, tap \"Select mock location app\" and choose Address Mock."
            )
            Button(onClick = { openDeveloperOptions(context) }) { Text("Open Developer options") }
        }
    }
}

@Composable
private fun StatusCard(state: MockState, onStop: () -> Unit) {
    val (title, body, color) = when (state) {
        is MockState.Idle -> Triple("Not mocking", "Your real location is being used.", MaterialTheme.colorScheme.surfaceVariant)
        is MockState.Running -> Triple("Mocking active", "${state.place.label}\n${state.place.coords}", MaterialTheme.colorScheme.primaryContainer)
        is MockState.Error -> Triple("Error", state.message, MaterialTheme.colorScheme.errorContainer)
    }
    Card(colors = CardDefaults.cardColors(containerColor = color), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            if (state is MockState.Running) {
                Spacer(Modifier.size(8.dp))
                Button(onClick = onStop) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun SelectedCard(place: Place, running: Boolean, onStart: () -> Unit, onStop: () -> Unit, onMap: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(place.label, fontWeight = FontWeight.Bold)
            Text(place.coords, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) Button(onClick = onStop) { Text("Stop") }
                else Button(onClick = onStart) { Text("Set location here") }
                OutlinedButton(onClick = onMap) { Text("View on map") }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun PlaceRow(place: Place, highlighted: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (highlighted) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocationOn, null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(place.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(place.coords, style = MaterialTheme.typography.bodySmall)
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Remove") }
            }
        }
    }
}

private fun hasPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun isSelectedMockApp(context: Context): Boolean {
    val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= 29) {
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
    } else {
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun openDeveloperOptions(context: Context) {
    val intents = listOf(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
    )
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
}

private fun openInMaps(context: Context, place: Place) {
    val uri = Uri.parse("geo:${place.lat},${place.lng}?q=${place.lat},${place.lng}(${Uri.encode(place.label)})")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No maps app installed", Toast.LENGTH_SHORT).show()
    }
}
