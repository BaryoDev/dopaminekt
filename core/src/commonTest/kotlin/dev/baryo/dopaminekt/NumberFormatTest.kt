package dev.baryo.dopaminekt

import kotlin.test.Test
import kotlin.test.assertEquals

class NumberFormatTest {
    @Test
    fun printsLikeJavaScript() {
        assertEquals("12345678.5", jsNumber(12345678.5))
        assertEquals("0.30000000000000004", jsNumber(0.1 + 0.2))
        assertEquals("1e-7", jsNumber(1e-7))
        assertEquals("1.5e-7", jsNumber(1.5e-7))
        assertEquals("0.000001", jsNumber(1e-6))
        assertEquals("1e+21", jsNumber(1e21))
        assertEquals("123456789012345680000", jsNumber(123456789012345680000.0))
        assertEquals("-0.5", jsNumber(-0.5))
        assertEquals("0", jsNumber(-0.0))
        assertEquals("100", jsNumber(100.0))
        assertEquals("1.2345678901234568e+21", jsNumber(1.2345678901234568e21))
    }

    @Test
    fun rendersNumbersAsJsonText() {
        assertEquals("12345678.5", number(12345678.5).toString())
        assertEquals("1e-7", number(1e-7).toString())
        assertEquals("1e+21", number(1e21).toString())
        assertEquals("9007199254740991", number(9_007_199_254_740_991.0).toString())
        assertEquals("100", number(100.0).toString())
        assertEquals("123456789012345680000", number(123456789012345680000.0).toString())
    }
}
