// The desktop app, on the same stack as NuvioDesktop: Kotlin + Compose
// Multiplatform, packaged per host (MSI, DMG, DEB).
//
// It is a client of the server in ../server — the same routes the web client
// and the app shell answer, so nothing about the catalogue, the films area or
// the 18+ streams is duplicated here. What lives here is the part the other two
// cannot have: a real window, a sidebar, and rails that scroll with a wheel.

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    // Material 3 is not part of the desktop bundle: it is its own artifact, and
    // it is the one this UI is written against.
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    // Posters arrive over HTTP and change as the rails scroll.
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    // Element-level JSON, not @Serializable classes: the server's answers are
    // read field by field and a model per route would be a second contract to
    // keep in step with the Node one.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

compose.desktop {
    application {
        mainClass = "app.hanime.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Hanime"
            packageVersion = "1.0.0"
            description = "A desktop client for your own hanime bench"
        }
    }
}
