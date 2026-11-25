package dev.stealthcompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveNotificationTest {
    @Test fun recognizesEnglishAndItalianCodeLabels() {
        assertTrue(SensitiveNotification.containsCode("Your verification code is 123456"))
        assertTrue(SensitiveNotification.containsCode("Il tuo codice di sicurezza è 123456"))
        assertTrue(SensitiveNotification.containsCode("OTP"))
        assertTrue(SensitiveNotification.containsCode("  123456 "))
    }

    @Test fun aNumberInAnOrdinarySentenceIsNotItselfACode() {
        assertFalse(SensitiveNotification.containsCode("The invoice number is 123456"))
        assertFalse(SensitiveNotification.containsCode("Ci vediamo alle 12:30"))
    }
}
