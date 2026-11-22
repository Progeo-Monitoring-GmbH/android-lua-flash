package com.progeo.luaflasher.ui.flash

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.progeo.luaflasher.AppGraph
import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.core.config.ConfigIssue
import com.progeo.luaflasher.core.config.ConfigTemplate
import com.progeo.luaflasher.core.config.ConfigValidator
import com.progeo.luaflasher.core.config.DeviceConfigBuilder
import com.progeo.luaflasher.core.config.LuaJson
import com.progeo.luaflasher.core.nodemcu.InstallMode
import com.progeo.luaflasher.core.nodemcu.InstallOptions
import com.progeo.luaflasher.data.Profile
import com.progeo.luaflasher.flash.FlashJob
import kotlinx.serialization.json.JsonObject

/** Choices on the wizard's first screen; survives rotation. */
class FlashSetupViewModel : ViewModel() {
    var bundleId by mutableStateOf<String?>(null)
    var profileId by mutableStateOf<String?>(null)
        private set
    var deviceHash by mutableStateOf("")
    var mode by mutableStateOf(InstallMode.FULL)
    var keepWifi by mutableStateOf(false)
    var ignoreFirmwareMismatch by mutableStateOf(false)
    var template by mutableStateOf<ConfigTemplate?>(null)
    var error by mutableStateOf<String?>(null)
    var starting by mutableStateOf(false)
    private var templateFor: String? = null
    private var initialProfileApplied = false

    /**
     * Sections edited on the "Device config" cards for this flash: each replaces the whole
     * section of the profile (null removes it, e.g. a router section). The stored profile stays as it is.
     */
    val sectionEdits = mutableStateMapOf<String, JsonObject?>()

    /** Selects a profile and takes over its device hash (it can still be changed by hand). */
    fun selectProfile(profile: Profile?) {
        profileId = profile?.id
        profile?.deviceHash?.let { deviceHash = it }
        sectionEdits.clear()
    }

    /** Opened through "Flash" on a profile: preselect it once. */
    fun applyInitialProfile(profile: Profile?) {
        if (initialProfileApplied || profile == null) return
        initialProfileApplied = true
        selectProfile(profile)
    }

    suspend fun loadTemplate(graph: AppGraph) {
        val id = bundleId ?: return
        if (templateFor == id) return
        template = try {
            ConfigTemplate.parse(graph.bundles.load(id).template)
        } catch (e: FlasherException) {
            error = e.userMessage
            null
        }
        templateFor = id
    }

    /** The profile's config with the sections edited here. */
    private fun profileConfig(graph: AppGraph): JsonObject {
        var config = graph.profiles.get(profileId)?.config ?: JsonObject(emptyMap())
        for ((section, values) in sectionEdits) config = LuaJson.with(config, section, values)
        return config
    }

    fun setSection(name: String, values: JsonObject?) {
        sectionEdits[name] = values
    }

    /** The complete config tree that will be written. */
    fun config(graph: AppGraph): JsonObject? = template?.let { DeviceConfigBuilder.build(it, profileConfig(graph), deviceHash) }

    fun issues(graph: AppGraph): List<ConfigIssue> {
        val t = template ?: return emptyList()
        return ConfigValidator.validate(t, config(graph) ?: return emptyList())
    }

    fun job(graph: AppGraph) = FlashJob(
        bundleId = bundleId!!,
        profileConfig = profileConfig(graph),
        deviceHash = deviceHash.trim(),
        options = InstallOptions(mode = mode, keepWifi = keepWifi, ignoreFirmwareMismatch = ignoreFirmwareMismatch),
    )
}
