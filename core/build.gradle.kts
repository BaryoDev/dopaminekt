import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.maven.publish)
}

group = "dev.baryo.dopaminekt"
version = providers.gradleProperty("VERSION_NAME").get()

kotlin {
    explicitApi()

    jvm()

    android {
        namespace = "dev.baryo.dopaminekt.core"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

mavenPublishing {
    coordinates("dev.baryo.dopaminekt", "core")
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = true))
    publishToMavenCentral()
    // Sign only when a key is in the environment, so local builds and CI need none.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set("dopaminekt core")
        description.set("Kotlin Multiplatform core of dopaminekt: progress, rewards and fixtures shared with dopaminejs.")
        url.set("https://github.com/BaryoDev/dopaminekt")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("Mozilla Public License 2.0")
                url.set("https://www.mozilla.org/en-US/MPL/2.0/")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("BaryoDev")
                name.set("Arnel Robles")
                url.set("https://github.com/BaryoDev")
            }
        }
        scm {
            url.set("https://github.com/BaryoDev/dopaminekt")
            connection.set("scm:git:https://github.com/BaryoDev/dopaminekt.git")
            developerConnection.set("scm:git:ssh://git@github.com/BaryoDev/dopaminekt.git")
        }
    }
}
