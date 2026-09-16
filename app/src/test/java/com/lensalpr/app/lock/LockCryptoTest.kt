package com.lensalpr.app.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockCryptoTest {

    @Test
    fun `only twelve digits are a password`() {
        assertTrue(LockCrypto.isValid("123456789012"))
        assertFalse(LockCrypto.isValid("12345678901"))
        assertFalse(LockCrypto.isValid("1234567890123"))
        assertFalse(LockCrypto.isValid("12345678901a"))
        assertFalse(LockCrypto.isValid("１２３４５６７８９０１２"))
        assertFalse(LockCrypto.isValid(""))
    }

    @Test
    fun `the right password matches and every other one does not`() {
        val record = LockCrypto.create("123456789012")
        assertTrue(LockCrypto.matches(record, "123456789012"))
        assertFalse(LockCrypto.matches(record, "123456789013"))
        assertFalse(LockCrypto.matches(record, "023456789012"))
        assertFalse(LockCrypto.matches(record, "12345678901"))
        assertFalse(LockCrypto.matches(record, ""))
    }

    @Test
    fun `the same password never hashes the same way twice`() {
        val a = LockCrypto.create("000000000000")
        val b = LockCrypto.create("000000000000")
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.hash.contentEquals(b.hash))
        assertTrue(LockCrypto.matches(a, "000000000000"))
        assertTrue(LockCrypto.matches(b, "000000000000"))
    }

    @Test
    fun `three misses and the phone is wiped`() {
        assertEquals(3, LockPolicy.attemptsLeft(0))
        assertEquals(2, LockPolicy.attemptsLeft(1))
        assertEquals(1, LockPolicy.attemptsLeft(2))
        assertEquals(0, LockPolicy.attemptsLeft(3))
        assertEquals(0, LockPolicy.attemptsLeft(7))
        assertFalse(LockPolicy.wipesOnNextMiss(0))
        assertFalse(LockPolicy.wipesOnNextMiss(1))
        assertTrue(LockPolicy.wipesOnNextMiss(2))
    }
}
