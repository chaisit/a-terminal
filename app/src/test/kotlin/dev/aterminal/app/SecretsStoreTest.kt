package dev.aterminal.app

import java.security.GeneralSecurityException
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretsStoreTest {
    @Test fun `bad tag anywhere in the cause chain means undecryptable`() {
        assertTrue(SecretsStore.isUndecryptable(AEADBadTagException()))
        assertTrue(SecretsStore.isUndecryptable(GeneralSecurityException("wrapped", AEADBadTagException())))
    }

    @Test fun `other keystore failures must not wipe secrets`() {
        // เช่น Keystore ยังไม่พร้อมชั่วคราว: ลบแล้ว host pin หาย = เสียการป้องกัน MITM โดยไม่จำเป็น
        assertFalse(SecretsStore.isUndecryptable(KeyStoreException("busy")))
        assertFalse(SecretsStore.isUndecryptable(IllegalStateException(RuntimeException("x"))))
    }

    @Test fun `self-referencing cause chain terminates`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(SecretsStore.isUndecryptable(a))
    }
}
