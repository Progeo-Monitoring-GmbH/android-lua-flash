package com.progeo.teltonika

import com.progeo.teltonika.api.DeviceInfo
import com.progeo.teltonika.firmware.FirmwareChannel
import com.progeo.teltonika.firmware.FirmwareCheck
import com.progeo.teltonika.firmware.FirmwarePolicy
import com.progeo.teltonika.firmware.FirmwareVersion
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirmwareTest {
    private fun v(text: String) = FirmwareVersion.parse(text)!!

    @Test
    fun `versions parse and compare`() {
        val a = v("RUT2M_R_00.07.06.1")
        assertEquals("RUT2M", a.family)
        assertEquals("R", a.type)
        assertEquals(listOf(0, 7, 6, 1), a.numbers)
        assertTrue(v("RUT2M_R_00.07.06.1") < v("RUT2M_R_00.07.10"))
        assertTrue(v("RUT9M_R_00.07.06") < v("RUT9M_R_00.07.06.1"))
        assertEquals(0, v("RUT9M_R_00.07.06").compareTo(v("RUT9M_R_00.07.06.0")))
        assertEquals(null, v("7.6.1").family)
        assertNull(FirmwareVersion.parse("unknown"))
    }

    private val channel = FirmwareChannel.parse(
        """
        {"format":1,"updated":"2026-11-20T10:00:00Z",
         "images":[
          {"id":"rut2m-0718","version":"RUT2M_R_00.07.18","family":"RUT2M","models":["RUT241"],
           "url":"routers/RUT2M_R_00.07.18_WEBUI.bin","size":20000000,"sha256":"aa","min_from":"RUT2M_R_00.07.06"},
          {"id":"rut2m-0718-us","version":"RUT2M_R_00.07.18.2","family":"RUT2M","models":["RUT241"],
           "url":"routers/x.bin","size":1,"sha256":"bb"},
          {"id":"rut9m-0718","version":"RUT9M_R_00.07.18","family":"RUT9M","models":["RUT901","RUT986"],
           "url":"routers/y.bin","size":1,"sha256":"cc"}],
         "approved":[
          {"model":"RUT241","image":"rut2m-0718"},
          {"model":"RUT241","image":"rut2m-0718-us","product_code_prefix":"RUT2410300"},
          {"model":"RUT986","image":"rut9m-0718"}],
         "future_field":true}
        """.trimIndent().toByteArray(),
    )

    @Test
    fun `approved image per model and variant`() {
        assertEquals("rut2m-0718", channel.approvedFor("RUT241", "RUT241010000")!!.id)
        assertEquals("rut2m-0718-us", channel.approvedFor("RUT241", "RUT241030000")!!.id)
        assertEquals("rut9m-0718", channel.approvedFor("rut986")!!.id)
        assertNull(channel.approvedFor("RUT901"))
    }

    @Test
    fun `newer channel format is refused`() {
        assertFailsWith<IllegalArgumentException> { FirmwareChannel.parse("""{"format":2}""".toByteArray()) }
    }

    @Test
    fun `policy checks`() {
        val image = channel.image("rut2m-0718")!!
        assertIs<FirmwareCheck.Ok>(FirmwarePolicy.check(image, "RUT241", v("RUT2M_R_00.07.10"))).also { assertEquals(false, it.downgrade) }
        assertEquals(FirmwareCheck.AlreadyInstalled, FirmwarePolicy.check(image, "RUT241", v("RUT2M_R_00.07.18")))
        assertIs<FirmwareCheck.Ok>(FirmwarePolicy.check(image, "RUT241", v("RUT2M_R_00.07.19"))).also { assertTrue(it.downgrade) }
        assertIs<FirmwareCheck.Refused>(FirmwarePolicy.check(image, "RUT241", v("RUT2M_R_00.07.04")))
        assertIs<FirmwareCheck.Refused>(FirmwarePolicy.check(image, "RUT986", v("RUT2M_R_00.07.10")))
        assertIs<FirmwareCheck.Refused>(FirmwarePolicy.check(image, "RUT241", v("RUT9M_R_00.07.10")))
        assertIs<FirmwareCheck.Refused>(FirmwarePolicy.check(image, "RUT241", null))
    }

    @Test
    fun `without a model the firmware family decides`() {
        assertEquals("rut2m-0718", channel.approvedForFamily("rut2m")!!.id)
        assertEquals("rut9m-0718", channel.approvedForFamily("RUT9M")!!.id)
        assertNull(channel.approvedForFamily("RUTX"))

        val image = channel.image("rut2m-0718")!!
        assertIs<FirmwareCheck.Ok>(FirmwarePolicy.check(image, null, v("RUT2M_R_00.07.10")))
        assertIs<FirmwareCheck.Refused>(FirmwarePolicy.check(image, null, v("RUT9M_R_00.07.10")))
        assertIs<FirmwareCheck.Refused>(FirmwarePolicy.check(image, null, v("7.10")))
        assertIs<FirmwareCheck.Refused>(FirmwarePolicy.check(image, null, null))
    }

    @Test
    fun `several images for one family are ambiguous`() {
        val two = FirmwareChannel.parse(
            """
            {"images":[
              {"id":"a","version":"RUT9M_R_00.07.18","family":"RUT9M","models":["RUT901"],"url":"a","size":1,"sha256":"a"},
              {"id":"b","version":"RUT9M_R_00.07.19","family":"RUT9M","models":["RUT986"],"url":"b","size":1,"sha256":"b"}],
             "approved":[{"model":"RUT901","image":"a"},{"model":"RUT986","image":"b"}]}
            """.trimIndent().toByteArray(),
        )
        assertNull(two.approvedForFamily("RUT9M"))
    }

    @Test
    fun `model found anywhere in the device answer`() {
        fun device(json: String) = DeviceInfo.from(Json.parseToJsonElement(json))
        assertEquals("RUT241", device("""{"data":{"mnfinfo":{"name":"RUT241010000"},"static":{"fw_version":"RUT2M_R_00.07.06.1"}}}""").modelName)
        assertEquals("RUT901", device("""{"model":"RUT901"}""").modelName)
        // The firmware family is no model.
        assertNull(device("""{"static":{"fw_version":"RUT2M_R_00.07.06.1","hostname":"router"}}""").modelName)
    }
}
