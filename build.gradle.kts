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

fun run(dir: File, vararg command: String, cleanNpmEnv: Boolean = false): String {
    val builder = ProcessBuilder(*command).directory(dir).redirectErrorStream(true)
    if (cleanNpmEnv) builder.environment().keys.removeAll { it.startsWith("NPM_CONFIG_", ignoreCase = true) }
    val process = builder.start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "${command.joinToString(" ")} failed:\n$output" }
    return output
}

val commitPin = Regex("[0-9a-f]{40}")

// Needs network. Copies a pinned dopaminejs spec/ into dest. A 40 character pin is a commit on
// BaryoDev/dopaminejs, anything else is a public npm release. Ignores the user's npm config, runs no scripts.
fun fetchSpec(dest: File, tmp: File) {
    tmp.deleteRecursively(); tmp.mkdirs()
    if (commitPin.matches(fixturePin)) {
        val repo = File(tmp, "repo")
        run(tmp, "git", "clone", "--quiet", "--no-checkout", "https://github.com/BaryoDev/dopaminejs.git", repo.absolutePath)
        run(repo, "git", "checkout", "--quiet", fixturePin, "--", "spec")
        val packed = File(repo, "spec")
        check(packed.isDirectory) { "dopaminejs@$fixturePin has no spec/ directory" }
        dest.deleteRecursively()
        packed.copyRecursively(dest)
        return
    }
    val out = run(
        tmp, "npm", "pack", "dopaminejs@$fixturePin",
        "--registry=https://registry.npmjs.org/", "--userconfig=/dev/null", "--ignore-scripts",
        "--pack-destination", tmp.absolutePath,
        cleanNpmEnv = true,
    )
    val tarball = File(tmp, out.trim().lines().last())
    check(tarball.isFile) { "npm pack did not produce a tarball: $out" }
    run(tmp, "tar", "-xzf", tarball.absolutePath, "package/spec", "package/package.json")
    val packedVersion = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(File(tmp, "package/package.json").readText())?.groupValues?.get(1)
    check(packedVersion == fixturePin) { "tarball is dopaminejs@$packedVersion, expected $fixturePin" }
    val packed = File(tmp, "package/spec")
    check(packed.isDirectory) { "dopaminejs@$fixturePin has no spec/ directory" }
    dest.deleteRecursively()
    packed.copyRecursively(dest)
}

tasks.register("syncFixtures") {
    group = "dopaminekt"
    description = "Copies spec/ from the pinned dopaminejs into spec/core and writes spec/CHECKSUMS."
    doLast {
        fetchSpec(specDir, File(temporaryDir, "pack"))
        val lines = fixtureFiles().map { "${sha256(it)}  ${it.relativeTo(specDir).invariantSeparatorsPath}" }
        checksumsFile.writeText((listOf("dopaminejs $fixturePin") + lines).joinToString("\n") + "\n")
        println("Synced ${lines.size} files from dopaminejs@$fixturePin")
    }
}

// Needs network. Fails when spec/core is not byte for byte the spec of the pinned upstream.
tasks.register("verifyUpstream") {
    group = "verification"
    description = "Compares spec/core with the pinned dopaminejs spec/ fetched from upstream."
    doLast {
        val fresh = File(temporaryDir, "upstream-spec")
        fetchSpec(fresh, File(temporaryDir, "fetch"))
        fun files(dir: File) = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to sha256(it) }
        val upstream = files(fresh)
        val local = files(specDir)
        val problems = (upstream.keys + local.keys).sorted().mapNotNull { path ->
            when {
                path !in local -> "spec/core/$path is missing, upstream has it"
                path !in upstream -> "spec/core/$path is not in upstream"
                local[path] != upstream[path] -> "spec/core/$path differs from upstream"
                else -> null
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(problems.joinToString("\n") + "\nRun ./gradlew syncFixtures to restore them.")
        }
        println("spec/core matches dopaminejs@$fixturePin (${local.size} files)")
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
