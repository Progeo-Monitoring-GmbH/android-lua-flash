package com.progeo.luaflasher.ui.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.progeo.luaflasher.AppGraph
import com.progeo.luaflasher.backend.AuthState
import com.progeo.luaflasher.backend.RemoteLocation
import com.progeo.luaflasher.ui.LoginForm
import com.progeo.luaflasher.ui.MessageCard
import com.progeo.luaflasher.ui.Section

private const val MAX_MATCHES = 30

/**
 * First step of a new profile: find the project's location (name, city, postcode, address or
 * project id), then load the config its controller last sent to the server. Can be skipped
 * to start from the template defaults.
 */
@Composable
fun NewProfileLocation(vm: ProfileEditorViewModel, graph: AppGraph) {
    val auth by graph.backend.state.collectAsState()
    LaunchedEffect(auth) { if (auth is AuthState.LoggedIn) vm.loadLocations(graph) }

    if (!vm.locationStep) {
        TextButton(onClick = { vm.locationStep = true }) { Text("Start from a location instead…") }
        return
    }

    Section("Location") {
        when {
            auth is AuthState.LoggedOut -> {
                LoginForm(
                    vm.loginForm,
                    onSubmit = { vm.login(graph) },
                    hint = "Log in to pick the project's location; the app then loads the config of its controller from the server.",
                )
            }
            vm.location == null -> LocationSearch(vm, graph)
            else -> SelectedLocation(vm, graph, vm.location!!)
        }
        vm.locationProgress?.let { Progress(it) }
        vm.locationError?.let { MessageCard(it) }
        vm.locationInfo?.let { MessageCard(it, error = false) }
        if (vm.location == null) {
            TextButton(onClick = { vm.locationStep = false }) { Text("Skip, start from the template defaults") }
        }
    }
}

@Composable
private fun LocationSearch(vm: ProfileEditorViewModel, graph: AppGraph) {
    val all = vm.locations
    OutlinedTextField(
        value = vm.locationQuery,
        onValueChange = { vm.locationQuery = it },
        label = { Text("Search location") },
        placeholder = { Text("name, city, postcode, address or project id") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (vm.locationQuery.isNotEmpty()) ({
            IconButton(onClick = { vm.locationQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear") }
        }) else null,
        singleLine = true,
        enabled = all != null,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    if (all == null) {
        if (vm.locationProgress == null) OutlinedButton(onClick = { vm.loadLocations(graph, force = true) }) { Text("Load locations again") }
        return
    }
    if (all.isEmpty()) {
        Text("Your account sees no locations.")
        return
    }
    val query = vm.locationQuery
    val matches = remember(all, query) { all.filter { it.matches(query) } }
    if (query.isBlank()) {
        Text("${all.size} locations. Type to narrow them down.", style = MaterialTheme.typography.bodySmall)
    }
    if (matches.isEmpty()) {
        Text("No location matches “$query”.")
        return
    }
    matches.take(MAX_MATCHES).forEach { loc ->
        Card(
            Modifier
                .fillMaxWidth()
                .clickable { vm.selectLocation(graph, loc) },
        ) { LocationText(loc, Modifier.padding(12.dp)) }
    }
    if (matches.size > MAX_MATCHES) {
        Text("${matches.size - MAX_MATCHES} more; refine the search.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SelectedLocation(vm: ProfileEditorViewModel, graph: AppGraph, loc: RemoteLocation) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LocationText(loc, Modifier.weight(1f))
            TextButton(onClick = { vm.changeLocation() }) { Text("Change") }
        }
    }
    val devices = vm.devices
    if (devices != null && devices.size > 1) {
        Text("Controller", style = MaterialTheme.typography.labelLarge)
        devices.forEach { d ->
            val chosen = vm.device?.id == d.id
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = vm.locationProgress == null) { vm.selectDevice(graph, d) },
                colors = if (chosen) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
            ) {
                Text(d.label, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    // No config on the server yet: offer to ask the controller for it.
    if (vm.device != null && vm.source == null && vm.locationProgress == null && vm.locationError == null) {
        when (vm.requestStatus) {
            null -> OutlinedButton(onClick = { vm.requestConfig(graph) }, modifier = Modifier.fillMaxWidth()) {
                Text("Request the config from the controller")
            }
            "pending" -> Text("Requested; the controller has not picked the request up yet.", style = MaterialTheme.typography.bodySmall)
            "sent" -> Text("The controller has the request and is sending its config.", style = MaterialTheme.typography.bodySmall)
            else -> Text("Request status: ${vm.requestStatus}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LocationText(loc: RemoteLocation, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                loc.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            loc.projectId?.let {
                Spacer(Modifier.width(8.dp))
                Text("#$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        loc.addressLine.takeIf { it.isNotEmpty() && it != loc.title }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Progress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(text)
    }
}
