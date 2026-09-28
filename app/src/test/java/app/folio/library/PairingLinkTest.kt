package app.folio.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingLinkTest {
    @Test fun readsAPairingLink() {
        assertEquals(
            PairingLink("https://books.example.com", "reader", "secret"),
            PairingLink.from("folio-pair://booklore?address=https%3A%2F%2Fbooks.example.com&user=reader&password=secret"),
        )
    }

    @Test fun decodesEscapedCharacters() {
        val pairing = PairingLink.from("folio-pair://booklore?address=https%3A%2F%2Fbooks.example.com%3A8443&user=a%40b.com&password=p%40ss%2Bword")
        assertEquals("https://books.example.com:8443", pairing?.address)
        assertEquals("a@b.com", pairing?.user)
        assertEquals("p@ss+word", pairing?.password)
    }

    /** URLDecoder would read this as a space and sign in with the wrong password. */
    @Test fun keepsAPlusInAPasswordIntact() {
        assertEquals("a+b", PairingLink.from("folio-pair://booklore?address=https%3A%2F%2Fb.example.com&user=r&password=a%2Bb")?.password)
    }

    @Test fun rejectsLinksThatAreNotOurs() {
        assertNull(PairingLink.from("https://booklore?address=https%3A%2F%2Fb.example.com&user=r&password=s"))
        assertNull(PairingLink.from("folio-pair://telegram?address=https%3A%2F%2Fb.example.com&user=r&password=s"))
        assertNull(PairingLink.from("folio-pair://booklore?address=https%3A%2F%2Fb.example.com&user=r"))
        assertNull(PairingLink.from("folio-pair://booklore?address=b.example.com&user=r&password=s"))
        assertNull(PairingLink.from("folio-pair://booklore?address=javascript%3Aalert(1)&user=r&password=s"))
        assertNull(PairingLink.from("folio-pair://booklore"))
        assertNull(PairingLink.from(null))
    }

    /** Opening a book must never be mistaken for a request to connect a library. */
    @Test fun anOpenedBookIsNotAPairingLink() {
        assertNull(PairingLink.from("content://com.android.providers.downloads/document/42"))
        assertNull(PairingLink.from("https://books.example.com/download/alice.epub"))
    }
}
