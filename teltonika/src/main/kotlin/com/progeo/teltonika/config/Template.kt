package com.progeo.teltonika.config

import com.progeo.teltonika.api.RouterException
import com.progeo.teltonika.api.RutosClient
import com.progeo.teltonika.api.flag
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json

/**
 * Desired router configuration, delivered by the backend per region and model.
 *
 * Declarative and endpoint-driven: each section names a RutOS config endpoint (one config
 * section, e.g. `/api/network/lan/config/lan`) and the values it must have. The app reads the
 * section, shows what differs, writes only the differences and reads them back. The app needs
 * no knowledge of each endpoint: the template carries it, so a firmware with other endpoints
 * needs a new template, not a new app.
 *
 * String values may contain placeholders: `{serial}`, `{mac}`, `{mac_last4}`, `{model}` and
 * `{wifi_key}` (a per-router Wi-Fi key kept in the router's record).
 */
@Serializable
data class RouterTemplate(
    val format: Int = 1,
    val id: String,
    val version: Int,
    val title: String,
    val region: String? = null,
    val models: List<String> = emptyList(),
    val sections: List<TemplateSection>,
    /** How to reach the router after the template is applied (placeholders allowed). */
    val access: AccessSpec? = null,
) {
    fun fits(model: String?) = models.isEmpty() || (model != null && models.any { it.equals(model, ignoreCase = true) })

    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

@Serializable
data class TemplateSection(
    val title: String,
    val endpoint: String,
    val values: Map<String, JsonElement>,
    /**
     * Changing this section may cut the phone's connection (LAN IP, Wi-Fi, firewall): it is
     * applied after all others and not read back.
     */
    @SerialName("affects_connection") val affectsConnection: Boolean = false,
)

@Serializable
data class AccessSpec(
    @SerialName("lan_ip") val lanIp: String? = null,
    val ssid: String? = null,
    @SerialName("wifi_key") val wifiKey: String? = null,
)

/** One value that differs. */
data class FieldChange(val key: String, val current: JsonElement?, val desired: JsonElement) {
    val secret: Boolean get() = SECRET.containsMatchIn(key)

    companion object {
        private val SECRET = Regex("key|pass|psk|secret|pin|token", RegexOption.IGNORE_CASE)
    }
}

data class SectionPlan(val section: TemplateSection, val changes: List<FieldChange>, val error: String? = null)

/** The differences between a router and a template. No changes and no errors: the router matches. */
data class ConfigPlan(val template: RouterTemplate, val sections: List<SectionPlan>, val access: AccessSpec?) {
    val changeCount: Int get() = sections.sumOf { it.changes.size }
    val errors: List<SectionPlan> get() = sections.filter { it.error != null }
    val inSync: Boolean get() = changeCount == 0 && errors.isEmpty()
    val affectsConnection: Boolean get() = sections.any { it.section.affectsConnection && it.changes.isNotEmpty() }
}

sealed interface SectionResult {
    val section: TemplateSection

    data class Applied(override val section: TemplateSection) : SectionResult

    /** Written; not read back because it may have cut the connection. */
    data class AppliedUnverified(override val section: TemplateSection) : SectionResult

    data class Mismatch(override val section: TemplateSection, val keys: List<String>) : SectionResult

    data class Failed(override val section: TemplateSection, val message: String) : SectionResult
}

data class ApplyResult(val results: List<SectionResult>) {
    val ok: Boolean get() = results.all { it is SectionResult.Applied || it is SectionResult.AppliedUnverified }
    val needsReconnect: Boolean get() = results.any { it is SectionResult.AppliedUnverified }
}

class TemplateException(message: String) : RouterException(message)

object TemplateEngine {
    private val PLACEHOLDER = Regex("\\{([a-z0-9_]+)\\}")

    /** Placeholder values for one router. */
    fun variables(serial: String, mac: String?, model: String?, wifiKey: String?): Map<String, String> = buildMap {
        put("serial", serial)
        mac?.let {
            val hex = it.replace(":", "").uppercase()
            put("mac", hex)
            put("mac_last4", hex.takeLast(4))
        }
        model?.let { put("model", it) }
        wifiKey?.let { put("wifi_key", it) }
    }

    /**
     * Fills the placeholders and lays the router's own [overrides] (endpoint → key → value) over
     * the template's values. Throws [TemplateException] for a placeholder without a value.
     */
    fun resolve(
        template: RouterTemplate,
        variables: Map<String, String>,
        overrides: Map<String, Map<String, JsonElement>> = emptyMap(),
    ): RouterTemplate {
        fun fill(text: String): String = PLACEHOLDER.replace(text) { m ->
            variables[m.groupValues[1]] ?: throw TemplateException("The template needs '{${m.groupValues[1]}}', which this router does not have.")
        }

        fun fill(e: JsonElement): JsonElement = when {
            e is JsonPrimitive && e.isString -> JsonPrimitive(fill(e.content))
            e is JsonArray -> JsonArray(e.map(::fill))
            else -> e
        }
        val sections = template.sections.map { s ->
            val values = LinkedHashMap(s.values.mapValues { (_, v) -> fill(v) })
            overrides[s.endpoint]?.forEach { (k, v) -> values[k] = v }
            s.copy(values = values)
        }
        val access = template.access?.let { a -> AccessSpec(a.lanIp?.let(::fill), a.ssid?.let(::fill), a.wifiKey?.let(::fill)) }
        return template.copy(sections = sections, access = access)
    }

    /** Reads every section from the router and lists what differs from the resolved [template]. */
    suspend fun plan(client: RutosClient, template: RouterTemplate): ConfigPlan {
        val sections = template.sections.map { section ->
            try {
                val current = sectionObject(client.get(section.endpoint))
                SectionPlan(section, diff(current, section.values))
            } catch (e: RouterException) {
                SectionPlan(section, emptyList(), e.userMessage)
            }
        }
        return ConfigPlan(template, sections, template.access)
    }

    /**
     * Writes the changed values only, section by section. Sections that affect the connection go
     * last and are not read back; all others are read back and compared.
     */
    suspend fun apply(
        client: RutosClient,
        plan: ConfigPlan,
        onSection: (index: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
    ): ApplyResult {
        val todo = plan.sections.filter { it.changes.isNotEmpty() && it.error == null }
            .sortedBy { it.section.affectsConnection }
        val results = ArrayList<SectionResult>()
        for ((i, p) in todo.withIndex()) {
            onSection(i, todo.size, p.section.title)
            val data = JsonObject(p.changes.associate { it.key to it.desired })
            try {
                client.put(p.section.endpoint, data)
                if (p.section.affectsConnection) {
                    results += SectionResult.AppliedUnverified(p.section)
                    continue
                }
                val after = sectionObject(client.get(p.section.endpoint))
                val wrong = diff(after, data).map { it.key }
                results += if (wrong.isEmpty()) SectionResult.Applied(p.section) else SectionResult.Mismatch(p.section, wrong)
            } catch (e: RouterException) {
                results += SectionResult.Failed(p.section, e.userMessage)
                // A connection-affecting section that failed may still have cut us off: stop here.
                if (p.section.affectsConnection) break
            }
        }
        return ApplyResult(results)
    }

    /** The keys of [desired] whose value in [current] is different. */
    fun diff(current: JsonObject, desired: Map<String, JsonElement>): List<FieldChange> =
        desired.mapNotNull { (key, want) ->
            val have = current[key]
            if (same(have, want)) null else FieldChange(key, have, want)
        }

    /** A config answer is the section object, or a one-element list holding it. */
    private fun sectionObject(data: JsonElement): JsonObject = when (data) {
        is JsonObject -> data
        is JsonArray -> data.singleOrNull() as? JsonObject ?: throw TemplateException("The endpoint returned a list, not one config section.")
        else -> JsonObject(emptyMap())
    }

    /** RutOS keeps most values as text: `"1"` equals `1` and `true`; lists compare item by item. */
    internal fun same(have: JsonElement?, want: JsonElement): Boolean {
        if (have == null || have is JsonNull) return want is JsonNull || (want is JsonPrimitive && want.content.isEmpty())
        return when {
            have is JsonArray && want is JsonArray -> have.size == want.size && have.zip(want).all { (a, b) -> same(a, b) }
            have is JsonArray && want is JsonPrimitive -> have.size == 1 && same(have[0], want)
            have is JsonPrimitive && want is JsonPrimitive -> {
                val a = have.content
                val b = want.content
                a == b || (a.flag() != null && a.flag() == b.flag()) || (a.toDoubleOrNull() != null && a.toDoubleOrNull() == b.toDoubleOrNull())
            }
            else -> have == want
        }
    }
}
