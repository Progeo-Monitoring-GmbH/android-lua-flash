package com.progeo.luaflasher.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.progeo.luaflasher.AppGraph
import com.progeo.luaflasher.core.config.ConfigTemplate
import com.progeo.luaflasher.core.config.ConfigValidator
import com.progeo.luaflasher.core.config.Severity
import com.progeo.luaflasher.data.Profile
import com.progeo.luaflasher.data.ProfileStore
import com.progeo.luaflasher.graph
import com.progeo.luaflasher.ui.Section
import kotlinx.coroutines.launch

class ProfileEditorViewModel : ViewModel() {
    var loaded by mutableStateOf(false)
    var template by mutableStateOf<ConfigTemplate?>(null)
    var name by mutableStateOf("")
    val values = mutableStateMapOf<String, String>()
    var profileId: String? = null

    suspend fun load(graph: AppGraph, id: String?) {
        if (loaded) return
        template = graph.currentTemplate()
        graph.profiles.profiles.value.firstOrNull { it.id == id }?.let { p ->
            profileId = p.id
            name = p.name
            values.putAll(p.values)
        }
        loaded = true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(id: String?, onDone: () -> Unit) {
    val graph = LocalContext.current.graph
    val vm: ProfileEditorViewModel = viewModel(key = "profile-$id")
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) { vm.load(graph, id) }
    val template = vm.template

    // Validate as if a device hash were given; that one is entered at flash time.
    val issues = template?.let { ConfigValidator.validate(it, vm.values + (ProfileStore.DEVICE_HASH to "x")) }.orEmpty()
    val canSave = vm.name.isNotBlank() && issues.none { it.severity == Severity.ERROR }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (id == null) "New profile" else "Edit profile") },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = vm.name,
                onValueChange = { vm.name = it },
                label = { Text("Profile name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Empty fields keep the bundle's default or stay off. The device hash is entered per board when flashing.")
            template?.fields
                ?.filter { it.path != ProfileStore.DEVICE_HASH }
                ?.groupBy { it.section }
                ?.forEach { (section, fields) ->
                    Section(section) {
                        fields.forEach { field ->
                            ConfigFieldInput(field, vm.values[field.path].orEmpty(), issues.firstOrNull { it.path == field.path }) {
                                vm.values[field.path] = it
                            }
                        }
                    }
                }
            Button(
                onClick = {
                    scope.launch {
                        graph.profiles.save(
                            Profile(
                                id = vm.profileId ?: java.util.UUID.randomUUID().toString(),
                                name = vm.name.trim(),
                                values = vm.values.filterValues { it.isNotEmpty() },
                            ),
                        )
                        onDone()
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
