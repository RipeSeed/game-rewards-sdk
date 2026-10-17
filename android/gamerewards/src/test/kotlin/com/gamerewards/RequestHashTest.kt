package com.gamerewards

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestHashTest {
    @Test
    fun matchesSpecTestVector() {
        assertEquals(
            "6a219e645cf83a6a7ca21e93bf865b797ba302ad4a8006752a26f90f6171eb49",
            requestHash("demo", "u-123", "bm9uY2U", 1700000000000),
        )
    }
}
