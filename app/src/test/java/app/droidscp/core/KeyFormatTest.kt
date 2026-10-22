package app.droidscp.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFormatTest {
    private fun pem(label: String) = "-----BEGIN $label-----\nAAAAB3NzaC1\n-----END $label-----\n"

    @Test fun acceptsCommonPrivateKeyFormats() {
        listOf("OPENSSH PRIVATE KEY", "RSA PRIVATE KEY", "EC PRIVATE KEY", "DSA PRIVATE KEY", "PRIVATE KEY",
            "ENCRYPTED PRIVATE KEY", "SSH2 ENCRYPTED PRIVATE KEY").forEach {
            assertTrue(it, looksLikePrivateKey(pem(it)))
        }
    }

    @Test fun acceptsPuttyKeys() {
        assertTrue(looksLikePrivateKey("PuTTY-User-Key-File-3: ssh-ed25519\nEncryption: none\n"))
        assertTrue(looksLikePrivateKey("PuTTY-User-Key-File-2: ssh-rsa\n"))
    }

    @Test fun toleratesBomAndLeadingWhitespace() {
        assertTrue(looksLikePrivateKey("﻿\r\n  " + pem("OPENSSH PRIVATE KEY")))
    }

    @Test fun rejectsPublicKeysAndGarbage() {
        assertFalse(looksLikePrivateKey("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMq user@host"))
        assertFalse(looksLikePrivateKey(pem("PUBLIC KEY")))
        assertFalse(looksLikePrivateKey(pem("CERTIFICATE")))
        assertFalse(looksLikePrivateKey("-----BEGIN OPENSSH PRIVATE"))   // header terpotong
        assertFalse(looksLikePrivateKey(""))
        assertFalse(looksLikePrivateKey("halo dunia, ini bukan kunci"))
        assertFalse(looksLikePrivateKey("PRIVATE KEY"))                  // kata saja tanpa penanda PEM
    }
}
