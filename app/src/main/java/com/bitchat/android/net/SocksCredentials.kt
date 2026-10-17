package com.bitchat.android.net

import java.net.InetSocketAddress
import java.security.SecureRandom

/** Per-start SOCKS5 username/password: only Wesh's own clients can use the local Tor port. */
class SocksCredentials(val username: String, val password: String) {
    override fun toString() = "SocksCredentials(redacted)"

    companion object {
        private val random = SecureRandom()

        fun random(): SocksCredentials = SocksCredentials(token(), token())

        private fun token(): String {
            val bytes = ByteArray(16).also(random::nextBytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** Above the well-known ports and below Android's ephemeral range (32768 and up). */
        fun randomPort(): Int = 10_000 + random.nextInt(22_768)
    }
}

/** The address our proxy actually bound, with the credentials it accepts. */
class SocksAuth(val address: InetSocketAddress, val credentials: SocksCredentials) {
    override fun toString() = "SocksAuth($address, redacted)"
}
