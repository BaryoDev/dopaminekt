import java.io.File
import java.security.MessageDigest

plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

val fixturePin: String = libs.versions.dopaminejs.get()
val specDir: File = layout.projectDirectory.dir("spec/core").asFile
val checksumsFile: File = layout.projectDirectory.file("spec/CHECKSUMS").asFile

fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

fun fixtureFiles(): List<File> =
    specDir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(specDir).invariantSeparatorsPath }.toList()

fun run(dir: File, vararg command: String): String {
    val process = ProcessBuilder(*command).directory(dir).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "${command.joinToString(" ")} failed:\n$output" }
    return output
}

// Needs network. Uses whatever registry npm is configured for.
tasks.register("syncFixtures") {
    group = "dopaminekt"
    description = "Copies spec/ from the pinned dopaminejs npm tarball into spec/core and writes spec/CHECKSUMS."
    doLast {
        val tmp = File(temporaryDir, "pack").apply { deleteRecursively(); mkdirs() }
        val out = run(tmp, "npm", "pack", "dopaminejs@$fixturePin", "--pack-destination", tmp.absolutePath)
        val tarball = File(tmp, out.trim().lines().last())
        check(tarball.isFile) { "npm pack did not produce a tarball: $out" }
        run(tmp, "tar", "-xzf", tarball.absolutePath, "package/spec")
        val packed = File(tmp, "package/spec")
        check(packed.isDirectory) { "dopaminejs@$fixturePin has no spec/ directory" }
        specDir.deleteRecursively()
        packed.copyRecursively(specDir)
        val lines = fixtureFiles().map { "${sha256(it)}  ${it.relativeTo(specDir).invariantSeparatorsPath}" }
        checksumsFile.writeText((listOf("dopaminejs $fixturePin") + lines).joinToString("\n") + "\n")
        println("Synced ${lines.size} files from dopaminejs@$fixturePin")
    }
}

// Offline. Fails when a file under spec/core differs from spec/CHECKSUMS.
val verifyFixtures = tasks.register("verifyFixtures") {
    group = "verification"
    description = "Checks spec/core against spec/CHECKSUMS without network."
    doLast {
        check(checksumsFile.isFile) { "spec/CHECKSUMS is missing. Run ./gradlew syncFixtures." }
        val lines = checksumsFile.readLines().filter { it.isNotBlank() }
        val pinned = lines.first().removePrefix("dopaminejs ")
        val problems = mutableListOf<String>()
        if (pinned != fixturePin) problems += "spec/CHECKSUMS is for dopaminejs $pinned but the pin is $fixturePin"
        val expected = lines.drop(1).associate { it.substringAfter("  ") to it.substringBefore("  ") }
        for ((path, hash) in expected) {
            val file = File(specDir, path)
            when {
                !file.isFile -> problems += "spec/core/$path is missing"
                sha256(file) != hash -> problems += "spec/core/$path drifted from spec/CHECKSUMS"
            }
        }
        for (file in fixtureFiles()) {
            val path = file.relativeTo(specDir).invariantSeparatorsPath
            if (path !in expected) problems += "spec/core/$path is not listed in spec/CHECKSUMS"
        }
        if (problems.isNotEmpty()) {
            throw GradleException(problems.joinToString("\n") + "\nRun ./gradlew syncFixtures to restore them.")
        }
    }
}

tasks.named("check") { dependsOn(verifyFixtures) }
