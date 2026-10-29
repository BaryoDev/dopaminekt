package dev.baryo.dopaminekt

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleLoadsTest {
    @Test
    fun moduleLoads() {
        assertEquals("dopaminekt-core", MODULE_NAME)
    }
}
