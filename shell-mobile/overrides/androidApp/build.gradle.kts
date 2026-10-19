import java.util.Properties

fun readXcconfigValue(file: File, key: String): String? {
    if (!file.exists()) return null
    return file.readLines()
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .map { line ->
            val separatorIndex = line.indexOf('=')
            line.substring(0, separatorIndex).trim() to line.substring(separatorIndex + 1).trim()
        }
        .firstOrNull { (entryKey, _) -> entryKey == key }
        ?.second
}

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sentry.android.gradle)
}

val localProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
fun envOrLocalProperty(key: String): String? =
    providers.environmentVariable(key).orNull?.trim()?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }

// The signing identity this fork publishes with is the phone shell's own key
// (`android/keystore/release.jks`), read from the environment first so a
// publish run never needs a password written into a file. `local.properties`
// still wins when it is the only place the paths exist.
val releaseStoreFile = envOrLocalProperty("NUVIO_RELEASE_STORE_FILE")
val releaseStorePassword = envOrLocalProperty("NUVIO_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = envOrLocalProperty("NUVIO_RELEASE_KEY_ALIAS")
val releaseKeyPassword = envOrLocalProperty("NUVIO_RELEASE_KEY_PASSWORD")
val releaseKeystore = releaseStoreFile?.let(rootProject::file)

/**
 * A KEY=VALUE file, read the way the desktop build reads this repo's `.env`.
 * Used only to find the MyAnimeList client secret, which is never written to
 * a tracked file: it is laid into BuildConfig at build time so the in-app
 * relay can complete a confidential registration's token exchange.
 */
fun readEnvValue(file: File, key: String): String? {
    if (!file.exists()) return null
    return file.readLines()
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .map { line ->
            val at = line.indexOf('=')
            line.substring(0, at).trim() to line.substring(at + 1).trim().trim('"', '\'')
        }
        .firstOrNull { (entryKey, _) -> entryKey == key }
        ?.second
        ?.takeIf { it.isNotBlank() }
}

/** `local.properties` → environment → this repo's `.env`, in that order. */
val malClientSecret: String =
    localProps.getProperty("malClientSecret")?.trim()?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable("MAL_CLIENT_SECRET").orNull?.trim()?.takeIf { it.isNotBlank() }
        ?: readEnvValue(rootProject.file("../../.env"), "MAL_CLIENT_SECRET")
        ?: readEnvValue(rootProject.file(".env"), "MAL_CLIENT_SECRET")
        ?: ""

val sentryAuthToken = envOrLocalProperty("SENTRY_AUTH_TOKEN")
val sentryOrg = envOrLocalProperty("SENTRY_ORG")
val sentryProject = envOrLocalProperty("SENTRY_PROJECT")
val sentryMappingUploadEnabled = sentryAuthToken != null && sentryOrg != null && sentryProject != null
val appVersionConfigFile = rootProject.file("iosApp/Configuration/Version.xcconfig")
val releaseAppVersionName = providers.gradleProperty("nuvio.app.versionName").orNull
    ?: readXcconfigValue(appVersionConfigFile, "MARKETING_VERSION")
    ?: error("MARKETING_VERSION is missing from ${appVersionConfigFile.path}")
val releaseAppVersionCode = readXcconfigValue(appVersionConfigFile, "CURRENT_PROJECT_VERSION")
    ?.toIntOrNull()
    ?: error("CURRENT_PROJECT_VERSION is missing or invalid in ${appVersionConfigFile.path}")
val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
val buildsReleaseApks = requestedTaskNames.any {
    it.startsWith("assemble", ignoreCase = true) && it.endsWith("Release", ignoreCase = true)
}

android {
    namespace = "com.nuvio.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.android.compileSdkMinor.get().toInt()

    signingConfigs {
        create("release") {
            if (releaseKeystore != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.nuvio.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = releaseAppVersionCode
        versionName = releaseAppVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The in-app relay's half of a confidential MAL registration. Empty
        // means the relay forwards the exchange without a secret, and MAL
        // answers `invalid_client` — which the settings card reports rather
        // than hiding behind a timeout.
        buildConfigField("String", "MAL_CLIENT_SECRET", "\"${malClientSecret.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
        }
        create("playstore") {
            dimension = "distribution"
        }
    }

    sourceSets.getByName("full") {
        manifest.srcFile("src/full/AndroidManifest.xml")
        jniLibs.directories.add("../composeApp/src/full/jniLibs")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so"
            )
        }
    }

    androidResources {
        noCompress += "cvr"
    }

    splits {
        abi {
            isEnable = buildsReleaseApks
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            // A published APK is one download for every phone: the release
            // build writes the per-ABI splits *and* a universal one, and the
            // universal file is what the GitHub release carries. The updater
            // picks an ABI split if a release ever carries them, and falls
            // back to the single APK otherwise.
            isUniversalApk = true
        }
    }

    buildTypes {
        getByName("release") {
            val minifyRelease = providers.gradleProperty("releaseMinifyEnabled")
                .map(String::toBooleanStrict)
                .getOrElse(true)
            isMinifyEnabled = minifyRelease
            isShrinkResources = minifyRelease
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "../composeApp/proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
            ndk {
                debugSymbolLevel = "FULL"
            }
        }

        /**
         * The phone build people actually run, with the optimisation the
         * debug variant leaves out.
         *
         * Debug is what this checkout can sign, and it is also the build a
         * slow phone feels most: no R8, no shrinking, debug logging on. This
         * type is the release pipeline — minified, shrunk, non-debuggable —
         * signed with the debug key and installed under the same package as
         * the debug build (`com.nuviodebug.com`, set below), so `adb install
         * -r` upgrades in place and keeps every preference.
         *
         * Nothing about the interface changes; only the bytecode does. The
         * variant is opt-in (`install-mobile.sh --fast`), because a minified
         * build is harder to read a stack trace out of.
         */
        create("fast") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "../composeApp/proguard-rules.pro",
            )
        }
    }
    //
    // The JVM tests for the in-process server's routes (`MangaRouteTest`).
    // org.json is real on a device but a stub in a unit test, so the tests
    // link the real implementation; the default-value option is what lets a
    // route that logs be tested at all, since `android.util.Log` is a stub
    // too. Same two dependencies, and the same reason, as the phone shell's
    // own tests (`android/app/build.gradle`).
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

androidComponents {
    // One package id for both of the builds this repository installs by hand:
    // a debug build and the optimised `fast` build are the same app to the
    // phone, so switching between them keeps the account, library and settings.
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.applicationId.set("com.nuviodebug.com")
    }
    onVariants(selector().withBuildType("fast")) { variant ->
        variant.applicationId.set("com.nuviodebug.com")
    }
    // The published build is the successor to the WebView phone shell, not a
    // second app: it keeps that shell's package id and is signed with its key
    // (`android/keystore/release.jks`), so an installed 1.0.x app updates into
    // this one in place instead of leaving a stale copy behind. Upstream's own
    // `com.nuvio.app` would collide with any installed Nuvio and could not
    // update the shell at all.
    onVariants(selector().withBuildType("release")) { variant ->
        variant.applicationId.set("app.hanime.shell")
    }
}

sentry {
    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(sentryMappingUploadEnabled)
    uploadNativeSymbols.set(false)
    autoUploadNativeSymbols.set(false)
    includeNativeSources.set(false)
    includeSourceContext.set(false)
    autoUploadSourceContext.set(false)
    includeDependenciesReport.set(false)
    telemetry.set(false)
    sentryAuthToken?.let(authToken::set)
    sentryOrg?.let(org::set)
    sentryProject?.let(projectName::set)
    // `fast` is a hand-installed build with no Sentry release behind it, and
    // the published build has none either: this fork has no Sentry project, no
    // token and no org, and the upload task still runs after R8 and fails —
    // here with `'F:\Reverse' is not recognized`, because the plugin's
    // generated helper does not quote a project path with spaces. Skipping the
    // plugin for `release` is the honest fix: there is nothing to upload, and
    // crash reporting itself is unchanged (the SDK is initialised from the
    // manifest with whatever DSN the build was given).
    ignoredBuildTypes.set(setOf("debug", "fast", "release"))
    autoInstallation {
        enabled.set(false)
    }
    tracingInstrumentation {
        enabled.set(false)
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.appcompat)
    implementation(libs.compose.runtime)
    // The MyAnimeList relay resolves MAL's hosts through DNS-over-HTTPS when
    // the phone's own resolver refuses them (see Mal.java), and OkHttp is the
    // one client here that can be handed a resolver. It is already in the APK
    // through composeApp; this only makes it compile-visible to the relay.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    debugImplementation(libs.compose.uiTooling)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:${libs.versions.composeMultiplatform.get()}")
    debugImplementation("androidx.compose.ui:ui-test-manifest:${libs.versions.composeMultiplatform.get()}")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
