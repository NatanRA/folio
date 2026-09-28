package app.folio.library

import java.io.ByteArrayOutputStream
import java.net.URI

/**
 * A BookLore connection carried by a pairing link, as produced by
 * `scripts/make-pairing-code.swift` and scanned with a camera.
 *
 * Parsing a link is not the same as acting on one: a code can come from anywhere and this one
 * carries a password, so the caller confirms with the reader before anything is saved.
 *
 * Built on plain JVM URI handling rather than `android.net.Uri` so it can be unit tested, and
 * with its own percent decoding because `URLDecoder` reads `+` as a space, which would quietly
 * corrupt any password containing one.
 */
data class PairingLink(val address: String, val user: String, val password: String) {
    companion object {
        const val SCHEME = "folio-pair"
        private const val HOST = "booklore"

        fun from(link: String?): PairingLink? {
            if (link.isNullOrBlank() || link.length > 4096) return null
            val uri = runCatching { URI(link) }.getOrNull() ?: return null
            if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
            if (!HOST.equals(uri.host, ignoreCase = true)) return null
            val fields = query(uri.rawQuery ?: return null)
            val address = fields["address"]?.trim().orEmpty()
            val user = fields["user"]?.trim().orEmpty()
            val password = fields["password"].orEmpty()
            if (!address.startsWith("https://") && !address.startsWith("http://")) return null
            if (runCatching { URI(address).host }.getOrNull().isNullOrEmpty()) return null
            if (user.isEmpty() || password.isEmpty()) return null
            return PairingLink(address, user, password)
        }

        private fun query(raw: String): Map<String, String> {
            val fields = HashMap<String, String>()
            for (pair in raw.split('&')) {
                val separator = pair.indexOf('=')
                if (separator <= 0) continue
                val name = decode(pair.substring(0, separator)) ?: continue
                val value = decode(pair.substring(separator + 1)) ?: continue
                fields[name] = value
            }
            return fields
        }

        private fun decode(value: String): String? {
            val bytes = ByteArrayOutputStream()
            var index = 0
            while (index < value.length) {
                val character = value[index]
                if (character == '%') {
                    if (index + 2 >= value.length) return null
                    val byte = value.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
                    bytes.write(byte)
                    index += 3
                } else {
                    bytes.write(character.toString().toByteArray(Charsets.UTF_8))
                    index += 1
                }
            }
            return String(bytes.toByteArray(), Charsets.UTF_8)
        }
    }
}
