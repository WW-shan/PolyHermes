package com.wrbug.polymarketbot.util

import com.wrbug.polymarketbot.config.SecretKeyValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class CryptoUtilsLegacyKeyTest {

    private val currentKey = "a".repeat(64) + "current"
    private val legacyJwtSecret = "legacy-jwt-secret-used-by-old-docker-deployments"

    private fun crypto(key: String, legacy: List<String> = emptyList()) =
        CryptoUtils().apply { configureKeys(key, legacy) }

    @Test
    fun `decrypts data encrypted with legacy key and reports it`() {
        val oldCipher = crypto(legacyJwtSecret).encrypt("0xprivate-key")
        val utils = crypto(currentKey, listOf("unused-crypto-secret", legacyJwtSecret))

        val result = utils.decryptWithKeyInfo(oldCipher)
        assertEquals("0xprivate-key", result.plainText)
        assertTrue(result.usedLegacyKey)
        assertEquals("0xprivate-key", utils.decrypt(oldCipher))
    }

    @Test
    fun `re-encrypts legacy data with current key`() {
        val oldCipher = crypto(legacyJwtSecret).encrypt("secret-value")
        val utils = crypto(currentKey, listOf(legacyJwtSecret))

        val newCipher = utils.reEncryptIfLegacy(oldCipher)
        assertNotNull(newCipher)
        assertEquals("secret-value", crypto(currentKey).decrypt(newCipher!!))
        assertNull(utils.reEncryptIfLegacy(newCipher))
    }

    @Test
    fun `current key data does not use legacy key`() {
        val utils = crypto(currentKey, listOf(legacyJwtSecret))
        val cipher = utils.encrypt("hello")
        assertFalse(utils.decryptWithKeyInfo(cipher).usedLegacyKey)
    }

    @Test
    fun `fails when no candidate key matches`() {
        val oldCipher = crypto("some-other-unknown-secret-key-value").encrypt("x".repeat(40))
        val utils = crypto(currentKey, listOf(legacyJwtSecret))
        assertThrows(RuntimeException::class.java) { utils.decrypt(oldCipher) }
        assertThrows(RuntimeException::class.java) { utils.reEncryptIfLegacy(oldCipher) }
    }

    @Test
    fun `legacy keys are deduplicated and exclude current key`() {
        val utils = crypto(currentKey, listOf(currentKey, legacyJwtSecret, legacyJwtSecret, " "))
        assertEquals(listOf(legacyJwtSecret), utils.legacyKeys())
    }

    @Test
    fun `secret validator rejects missing default and short secrets`() {
        assertNotNull(SecretKeyValidator.validateSecret("JWT_SECRET", null))
        assertNotNull(SecretKeyValidator.validateSecret("JWT_SECRET", ""))
        assertNotNull(SecretKeyValidator.validateSecret("JWT_SECRET", "your-secret-key-change-in-production"))
        assertNotNull(SecretKeyValidator.validateSecret("ADMIN_RESET_PASSWORD_KEY", "change-me-in-production"))
        assertNotNull(SecretKeyValidator.validateSecret("ENCRYPTION_KEY", "short-key"))
        assertNull(SecretKeyValidator.validateSecret("ENCRYPTION_KEY", "0123456789abcdef".repeat(4)))
    }

    @Test
    fun `secret validator fails startup only under prod profile`() {
        val devEnv = MockEnvironment().withProperty("jwt.secret", "your-secret-key-change-in-production")
        devEnv.setActiveProfiles("dev")
        SecretKeyValidator(devEnv).validate()

        val prodEnv = MockEnvironment().withProperty("jwt.secret", "your-secret-key-change-in-production")
        prodEnv.setActiveProfiles("prod")
        assertThrows(IllegalStateException::class.java) { SecretKeyValidator(prodEnv).validate() }

        val good = "0123456789abcdef".repeat(4)
        val okEnv = MockEnvironment()
            .withProperty("jwt.secret", good)
            .withProperty("encryption.key", good + "e")
            .withProperty("admin.reset-password.key", good + "r")
        okEnv.setActiveProfiles("prod")
        SecretKeyValidator(okEnv).validate()
    }
}
