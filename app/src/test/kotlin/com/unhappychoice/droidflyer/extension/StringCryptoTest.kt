package com.unhappychoice.droidflyer.extension

import com.winterbe.expekt.should
import org.junit.Test

class StringCryptoTest {
    @Test
    fun toHmacSHA256ReturnsHexDigest() {
        "The quick brown fox jumps over the lazy dog".toHmacSHA256("key")
            .should.equal("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8")
    }
}
