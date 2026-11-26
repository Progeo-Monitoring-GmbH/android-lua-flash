package com.progeo.teltonika.api

import com.progeo.teltonika.firmware.FirmwareVersion
import com.progeo.teltonika.label.RouterLabels
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the router says about itself. Every field may be missing on some firmware version. */
data class DeviceInfo(
    val serial: String?,
    /** `RUT241010000`: model plus regional/hardware variant. */
    val productCode: String?,
    val model: String?,
    val mac: String?,
    val firmware: String?,
    val hardwareRevision: String?,
    val hostname: String?,
    val uptimeSeconds: Long?,
    val batch: String?,
    val raw: JsonElement,
) {
    val firmwareVersion: FirmwareVersion? get() = firmware?.let(FirmwareVersion::parse)

    /**
     * `RUT241`, from the model field, the product code or, as field names differ between firmware
     * versions, any text in the answer that names a model (`mnfinfo.name` = `RUT241010000`, …).
     */
    val modelName: String?
        get() = model?.let(::modelIn) ?: productCode?.let(::modelIn) ?: anyModelIn(raw)

    companion object {
        /** `RUT241`, `RUT901`, `RUTX11`; not the firmware family `RUT2M`. */
        private val MODEL = Regex("\\bRUT[A-Z]?\\d{2,3}")

        private fun modelIn(text: String) = MODEL.find(text.uppercase())?.value

        private fun anyModelIn(json: JsonElement): String? = when (json) {
            is JsonObject -> json.values.firstNotNullOfOrNull(::anyModelIn)
            is JsonArray -> json.firstNotNullOfOrNull(::anyModelIn)
            is JsonPrimitive -> if (json.isString) modelIn(json.content) else null
        }

        fun from(json: JsonElement) = DeviceInfo(
            serial = json.find("serial", "serial_num", "serial_number", "sn"),
            // In the spec the product code (`RUT241010000`) is `mnfinfo.name`.
            productCode = json.find("product_code", "productcode", "mnf_name", "code")
                ?: ((json as? JsonObject)?.get("mnfinfo") as? JsonObject)?.str("name"),
            model = json.find("model", "device_name", "product_name", "devicename"),
            mac = json.find("mac", "lan_mac", "mac_address", "maceth")?.let { RouterLabels.normalizeMac(it) ?: it },
            firmware = json.find("fw_version", "firmware", "firmware_version", "fwversion"),
            hardwareRevision = json.find("hw_version", "hwver", "hardware_version", "hw_revision"),
            hostname = json.find("hostname"),
            uptimeSeconds = json.find("uptime").number()?.toLong(),
            batch = json.find("batch", "batch_num", "batch_number"),
            raw = json,
        )
    }
}

enum class SignalQuality(val label: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Poor"),
    NONE("No signal"),
}

/** LTE signal values of one modem. */
data class Signal(val rssi: Int?, val rsrp: Int?, val rsrq: Double?, val sinr: Double?) {
    /**
     * The worse of the RSRP and SINR ratings (usual LTE thresholds): RSRP ≥ -80 / -90 / -100 dBm,
     * SINR ≥ 20 / 13 / 0 dB. Without LTE values, RSSI ≥ -65 / -75 / -85 dBm.
     */
    val quality: SignalQuality
        get() {
            val byRsrp = rsrp?.let { rate(it.toDouble(), -80.0, -90.0, -100.0) }
            val bySinr = sinr?.let { rate(it, 20.0, 13.0, 0.0) }
            val ratings = listOfNotNull(byRsrp, bySinr)
            if (ratings.isNotEmpty()) return ratings.maxBy { it.ordinal }
            return rssi?.let { rate(it.toDouble(), -65.0, -75.0, -85.0) } ?: SignalQuality.NONE
        }

    /** 0…1 for a gauge: RSRP from -125 to -70 dBm, else RSSI from -110 to -50 dBm. */
    val level: Float
        get() = when {
            rsrp != null -> ((rsrp + 125) / 55f).coerceIn(0f, 1f)
            rssi != null -> ((rssi + 110) / 60f).coerceIn(0f, 1f)
            else -> 0f
        }

    private fun rate(v: Double, excellent: Double, good: Double, fair: Double) = when {
        v >= excellent -> SignalQuality.EXCELLENT
        v >= good -> SignalQuality.GOOD
        v >= fair -> SignalQuality.FAIR
        else -> SignalQuality.POOR
    }
}

/** One mobile modem with its SIM. */
data class ModemStatus(
    val id: String?,
    val name: String?,
    val imei: String?,
    val operator: String?,
    val technology: String?,
    val band: String?,
    val registration: String?,
    val cellId: String?,
    val signal: Signal,
    val activeSim: String?,
    val simState: String?,
    val pinState: String?,
    val iccid: String?,
    val imsi: String?,
    val temperature: Double?,
    val raw: JsonObject,
) {
    companion object {
        fun from(o: JsonObject) = ModemStatus(
            id = o.find("id"),
            name = o.find("name", "model"),
            imei = o.find("imei"),
            operator = o.find("operator", "provider", "oper"),
            technology = o.find("conntype", "network_type", "net_mode", "service_mode", "ntype"),
            band = o.find("band", "active_band"),
            registration = o.find("netstate", "net_state", "registration", "reg_state"),
            cellId = o.find("cellid", "cell_id", "cid"),
            signal = Signal(
                rssi = o.find("rssi", "signal").number()?.toInt(),
                rsrp = o.find("rsrp").number()?.toInt(),
                rsrq = o.find("rsrq").number(),
                sinr = o.find("sinr").number(),
            ),
            activeSim = o.find("active_sim", "sim", "simslot", "sim_slot"),
            simState = o.find("simstate", "sim_state", "simcard_state"),
            pinState = o.find("pinstate", "pin_state"),
            iccid = o.find("iccid"),
            imsi = o.find("imsi"),
            temperature = o.find("temperature", "temp").number(),
            raw = o,
        )

        fun list(json: JsonElement) = json.objects().map(::from)
    }
}

data class InterfaceStatus(
    val name: String,
    val up: Boolean?,
    val protocol: String?,
    val device: String?,
    val ipv4: String?,
    val rxBytes: Long?,
    val txBytes: Long?,
    val uptimeSeconds: Long?,
) {
    companion object {
        fun from(o: JsonObject) = InterfaceStatus(
            name = o.find("id", "name", "interface", "ifname") ?: "?",
            up = o.find("up", "is_up", "status", "state").flag(),
            protocol = o.find("proto", "protocol"),
            device = o.find("device", "l3_device", "ifname"),
            // `ipv4-address: [{address, mask}]`; a plain search could land on an IPv6 entry.
            ipv4 = ((o["ipv4-address"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("address")
                ?: o.find("ipaddr", "ipv4", "ip"),
            rxBytes = o.find("rx_bytes", "rx").number()?.toLong(),
            txBytes = o.find("tx_bytes", "tx").number()?.toLong(),
            uptimeSeconds = o.find("uptime").number()?.toLong(),
        )

        fun list(json: JsonElement) = json.objects().map(::from)
    }
}

data class DhcpLease(val hostname: String?, val ip: String?, val mac: String?) {
    companion object {
        fun from(o: JsonObject) = DhcpLease(
            hostname = o.find("hostname", "name"),
            ip = o.find("ipaddr", "ip", "ipv4", "address"),
            mac = o.find("macaddr", "mac")?.let { RouterLabels.normalizeMac(it) ?: it },
        )

        fun list(json: JsonElement) = json.objects().map(::from)
    }
}
