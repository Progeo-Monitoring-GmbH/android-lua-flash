package com.progeo.teltonika.credentials

import com.progeo.teltonika.api.DeviceInfo
import com.progeo.teltonika.label.Credentials
import com.progeo.teltonika.label.RouterLabel
import com.progeo.teltonika.label.RouterLabels
import java.security.SecureRandom

object Passwords {
    // No look-alikes (0/O, 1/l/I) and no characters that need quoting anywhere.
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val DIGITS = "23456789"
    private const val ALL = UPPER + LOWER + DIGITS

    /** RutOS rule: at least 8 characters with an upper-case letter, a lower-case letter and a digit. */
    fun meetsRutosRules(password: String) =
        password.length >= 8 && password.any { it.isUpperCase() } && password.any { it.isLowerCase() } && password.any { it.isDigit() }

    /** A random password that meets the RutOS rules. */
    fun generate(length: Int = 16, random: SecureRandom = SecureRandom()): String {
        require(length >= 8)
        val chars = CharArray(length) { ALL[random.nextInt(ALL.length)] }
        // Guarantee one of each class at random positions.
        val positions = (0 until length).shuffled(java.util.Random(random.nextLong())).take(3)
        chars[positions[0]] = UPPER[random.nextInt(UPPER.length)]
        chars[positions[1]] = LOWER[random.nextInt(LOWER.length)]
        chars[positions[2]] = DIGITS[random.nextInt(DIGITS.length)]
        return String(chars)
    }
}

/** A password to try, and where it came from. */
data class LoginCandidate(val credentials: Credentials, val kind: Kind) {
    enum class Kind(val label: String) {
        CURRENT("stored password"),
        PENDING("new password (not confirmed yet)"),
        FACTORY("factory password from the label"),
        MANUAL("typed password"),
    }
}

object LoginOrder {
    /**
     * The passwords to try, in order: the current one, a pending one from an interrupted password
     * change, the factory password (from the scanned label, else the one stored when the router was
     * first seen). Duplicates are dropped.
     */
    fun candidates(
        username: String,
        current: String?,
        pending: String?,
        factory: Credentials?,
        label: RouterLabel?,
    ): List<LoginCandidate> {
        val list = ArrayList<LoginCandidate>()
        current?.let { list += LoginCandidate(Credentials(username, it), LoginCandidate.Kind.CURRENT) }
        pending?.let { list += LoginCandidate(Credentials(username, it), LoginCandidate.Kind.PENDING) }
        (label?.login ?: factory)?.let { list += LoginCandidate(it, LoginCandidate.Kind.FACTORY) }
        return list.distinctBy { it.credentials }
    }
}

/** Is the router we are logged into the one that was scanned? */
object Identity {
    sealed interface Result {
        data object Match : Result

        /** The router does not report enough to tell. */
        data class Unknown(val reason: String) : Result

        data class Mismatch(val expected: String, val found: String) : Result
    }

    fun check(expectedSerial: String, expectedMac: String?, device: DeviceInfo): Result {
        val serial = device.serial?.trim()
        if (serial != null && serial != expectedSerial) return Result.Mismatch("serial $expectedSerial", "serial $serial")
        val mac = device.mac?.let { RouterLabels.normalizeMac(it) }
        val wantMac = expectedMac?.let { RouterLabels.normalizeMac(it) }
        if (mac != null && wantMac != null && mac != wantMac) return Result.Mismatch("MAC $wantMac", "MAC $mac")
        return when {
            serial != null -> Result.Match
            mac != null && wantMac != null -> Result.Match
            else -> Result.Unknown("The router reports neither serial number nor MAC.")
        }
    }
}
