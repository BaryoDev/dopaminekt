package dev.baryo.dopaminekt

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Same rule as the verifyFixtures Gradle task: spec/core must match spec/CHECKSUMS. */
class FixturesInSyncTest {
    private val specRoot = File(System.getProperty("dopaminekt.specRoot") ?: "../spec")
    private val specDir = File(specRoot, "core")
    private val checksums = File(specRoot, "CHECKSUMS")

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun relative(file: File): String = file.relativeTo(specDir).invariantSeparatorsPath

    @Test
    fun fixturesMatchChecksums() {
        assertTrue(checksums.isFile, "spec/CHECKSUMS is missing. Run ./gradlew syncFixtures.")
        val lines = checksums.readLines().filter { it.isNotBlank() }
        val problems = mutableListOf<String>()

        val pinned = lines.first().removePrefix("dopaminejs ")
        val pin = System.getProperty("dopaminekt.fixturePin")
        if (pin != null && pinned != pin) {
            problems += "spec/CHECKSUMS is for dopaminejs $pinned but the pin is $pin"
        }

        val expected = lines.drop(1).associate { it.substringAfter("  ") to it.substringBefore("  ") }
        for ((path, hash) in expected) {
            val file = File(specDir, path)
            when {
                !file.isFile -> problems += "spec/core/$path is missing"
                sha256(file) != hash -> problems += "spec/core/$path drifted from spec/CHECKSUMS"
            }
        }
        for (file in specDir.walkTopDown().filter { it.isFile }) {
            if (relative(file) !in expected) problems += "spec/core/${relative(file)} is not listed in spec/CHECKSUMS"
        }

        assertTrue(problems.isEmpty(), problems.joinToString("\n") + "\nRun ./gradlew syncFixtures to restore them.")
    }
}
