package com.lensalpr.app.lock

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Turns the entry code into something that survives someone reading the preferences file.
 *
 * Twelve digits is a small space — a trillion combinations, which a desktop would walk through in
 * minutes against a plain hash. PBKDF2 with a six-figure iteration count makes each guess cost
 * real time, so the three-attempt limit is not the only thing standing between a stolen phone and
 * the plate history on it.
 */
object LockCrypto {

    const val ITERATIONS = 160_000
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    class Record(val salt: ByteArray, val hash: ByteArray, val iterations: Int)

    fun create(password: String, iterations: Int = ITERATIONS): Record {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        return Record(salt, derive(password, salt, iterations), iterations)
    }

    fun matches(record: Record, password: String): Boolean {
        val candidate = derive(password, record.salt, record.iterations)
        // Constant time: a comparison that returns early leaks how much of the hash matched.
        return MessageDigest.isEqual(candidate, record.hash)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
