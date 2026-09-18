package com.lensalpr.app.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The door, checked without a phone.
 *
 * Two things matter here and both are one-way: a code that opens the phone when it should, and a
 * count that cannot be talked out of locking it. Everything about the lock that can be tested off
 * the device is tested here, because the parts that cannot — the white sheet, the remote unlock —
 * are only ever exercised in a situation nobody wants to rehearse on a real drive.
 */
class LockTest {

    // Faster than the shipped count; this is about behaviour, not about how long a guess costs.
    private val rounds = 1_000

    @Test
    fun `the right code matches`() {
        val record = LockCrypto.create("123456789012", rounds)
        assertTrue(LockCrypto.matches(record, "123456789012"))
    }

    @Test
    fun `a wrong code does not`() {
        val record = LockCrypto.create("123456789012", rounds)
        assertFalse(LockCrypto.matches(record, "123456789013"))
        assertFalse(LockCrypto.matches(record, "12345678901"))
        assertFalse(LockCrypto.matches(record, ""))
    }

    @Test
    fun `the same code twice gives different stored hashes`() {
        // Two phones with the same code, or one phone whose code was re-set: the stored bytes must
        // not be equal, or one cracked hash would open every install that shares the digits.
        val first = LockCrypto.create("000000000000", rounds)
        val second = LockCrypto.create("000000000000", rounds)
        assertFalse(first.salt.contentEquals(second.salt))
        assertFalse(first.hash.contentEquals(second.hash))
    }

    @Test
    fun `the code itself is nowhere in what gets stored`() {
        val code = "314159265358"
        val record = LockCrypto.create(code, rounds)
        assertNotEquals(code, String(record.hash, Charsets.ISO_8859_1))
        assertFalse(String(record.hash, Charsets.ISO_8859_1).contains(code))
    }

    @Test
    fun `only twelve digits are accepted`() {
        assertTrue(LockPolicy.isWellFormed("000000000000"))
        assertFalse(LockPolicy.isWellFormed("00000000000"))
        assertFalse(LockPolicy.isWellFormed("0000000000000"))
        assertFalse(LockPolicy.isWellFormed("00000000000a"))
        assertFalse(LockPolicy.isWellFormed("            "))
        assertFalse(LockPolicy.isWellFormed(""))
    }

    @Test
    fun `three misses lock the phone and no further miss un-locks it`() {
        assertEquals(3, LockPolicy.attemptsLeft(0))
        assertEquals(2, LockPolicy.attemptsLeft(1))
        assertEquals(1, LockPolicy.attemptsLeft(2))
        assertEquals(0, LockPolicy.attemptsLeft(3))

        assertFalse(LockPolicy.isLockedOut(2))
        assertTrue(LockPolicy.isLockedOut(3))
        // A counter that kept climbing past the limit must stay locked, not wrap into daylight.
        assertTrue(LockPolicy.isLockedOut(9))
        assertEquals(0, LockPolicy.attemptsLeft(9))
    }
}
