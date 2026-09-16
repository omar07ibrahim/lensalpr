package com.lensalpr.app.lock

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The password maths, kept free of Android so it can be unit-tested.
 *
 * A 12-digit code has a trillion values; a plain hash of it would fall to a laptop in minutes if
 * the preferences file ever left the phone. PBKDF2 with a per-phone salt makes every guess cost the
 * same tens of milliseconds it costs the lock screen, which is enough for a code that also wipes
 * the phone after three misses.
 */
object LockCrypto {

    const val PASSWORD_LENGTH = 12
    const val ITERATIONS = 20_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    /** A stored password: the salt it was hashed with, the hash, and the work factor used. */
    data class Record(val salt: ByteArray, val hash: ByteArray, val iterations: Int)

    /** Exactly twelve ASCII digits; nothing else is ever accepted as a password. */
    fun isValid(password: String): Boolean =
        password.length == PASSWORD_LENGTH && password.all { it in '0'..'9' }

    fun create(password: String, random: SecureRandom = SecureRandom()): Record {
        require(isValid(password)) { "password must be $PASSWORD_LENGTH digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return Record(salt, derive(password, salt, ITERATIONS), ITERATIONS)
    }

    /** Constant-time comparison, so a wrong first digit is not measurably faster than a wrong last one. */
    fun matches(record: Record, password: String): Boolean {
        if (!isValid(password)) return false
        val candidate = derive(password, record.salt, record.iterations)
        return MessageDigest.isEqual(candidate, record.hash)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
