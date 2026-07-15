import groovy.json.JsonSlurper
import java.security.KeyStore
import java.security.MessageDigest
import org.gradle.api.tasks.Sync

val packageMetadata = JsonSlurper().parse(rootProject.file("../package.json")) as Map<*, *>
val appVersionName = packageMetadata["version"] as? String
    ?: error("package.json must define a string version")
val appVersionCode = (packageMetadata["androidVersionCode"] as? Number)?.toInt()
    ?: error("package.json must define a numeric androidVersionCode")

fun environmentOrDefault(name: String, defaultValue: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: defaultValue

val stableSigningStore = file(
    environmentOrDefault(
        "POKEMON_CHAMPIONS_SIGNING_STORE_FILE",
        "${System.getProperty("user.home")}/.android/pokemon-champions-update.keystore",
    ),
)
val stableSigningStorePassword = environmentOrDefault("POKEMON_CHAMPIONS_SIGNING_STORE_PASSWORD", "android")
val stableSigningKeyAlias = environmentOrDefault("POKEMON_CHAMPIONS_SIGNING_KEY_ALIAS", "androiddebugkey")
val stableSigningKeyPassword = environmentOrDefault("POKEMON_CHAMPIONS_SIGNING_KEY_PASSWORD", "android")
val expectedStableSignerSha256 = "1D0A58B38FEBE62B7E20484CF971A59C65A5DC3F61103004E19F01CB34D83065"
val requestedReleaseBuild = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
val hasStableSigningStore = stableSigningStore.isFile

check(!requestedReleaseBuild || hasStableSigningStore) {
    "Stable Pokemon Champions signing key is required for release builds: ${stableSigningStore.absolutePath}"
}

if (hasStableSigningStore) {
    val stableSigningKeyStore = KeyStore.getInstance("PKCS12").apply {
        stableSigningStore.inputStream().use { load(it, stableSigningStorePassword.toCharArray()) }
    }
    val stableSigningCertificate = stableSigningKeyStore.getCertificate(stableSigningKeyAlias)
        ?: error("Signing alias '$stableSigningKeyAlias' is missing from ${stableSigningStore.absolutePath}")
    val stableSignerSha256 = MessageDigest.getInstance("SHA-256")
        .digest(stableSigningCertificate.encoded)
        .joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    check(stableSignerSha256 == expectedStableSignerSha256) {
        "Stable Pokemon Champions signing key fingerprint mismatch: $stableSignerSha256"
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val mlKitLicenseArtifacts by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

android {
    namespace = "com.crazylei12.pokemonchampionsassistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.crazylei12.pokemonchampionsassistant"
        minSdk = 33
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    val stableUpdateSigning = if (hasStableSigningStore) {
        signingConfigs.create("stableUpdate") {
            storeFile = stableSigningStore
            storePassword = stableSigningStorePassword
            keyAlias = stableSigningKeyAlias
            keyPassword = stableSigningKeyPassword
        }
    } else {
        null
    }

    buildTypes {
        getByName("debug") {
            stableUpdateSigning?.let { signingConfig = it }
        }
        getByName("release") {
            stableUpdateSigning?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.get().asFile.resolve("generated/recognitionAssets"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.get().asFile.resolve("generated/legalAssets"))
}

val syncRecognitionAssets by tasks.registering(Sync::class) {
    from(rootProject.file("../src/data/localization/zh-Hans.json"))
    from(rootProject.file("../src/data/recognition/team-preview.safe-zone-roi.zh-Hans.v2.json"))
    from(rootProject.file("../src/data/recognition/android/team-preview-templates-v2.bin"))
    from(rootProject.file("../src/data/recognition/android/team-preview-templates-v2.json"))
    into(layout.buildDirectory.dir("generated/recognitionAssets/recognition"))
}

val syncDamageAssets by tasks.registering(Sync::class) {
    from(rootProject.file("../src/data/damage/champions-presets.json"))
    into(layout.buildDirectory.dir("generated/recognitionAssets/damage"))
}

val syncLegalAssets by tasks.registering(Sync::class) {
    from(rootProject.file("../THIRD_PARTY_NOTICES.md")) {
        into("licenses")
    }
    from(rootProject.file("../third_party/licenses")) {
        include("*.txt")
        into("licenses")
    }
    from(rootProject.file("../src/data/localization/sources/42arch-pokemon-dataset-zh/LICENSE")) {
        rename { "42arch-pokemon-dataset-zh-MIT.txt" }
        into("licenses")
    }
    from({ mlKitLicenseArtifacts.files.map { zipTree(it) } }) {
        include("third_party_licenses.json", "third_party_licenses.txt")
        into("licenses/ml-kit")
    }
    into(layout.buildDirectory.dir("generated/legalAssets"))
}

tasks.named("preBuild").configure {
    dependsOn(syncRecognitionAssets)
    dependsOn(syncDamageAssets)
    dependsOn(syncLegalAssets)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("org.opencv:opencv:4.13.0")
    add(mlKitLicenseArtifacts.name, "com.google.mlkit:text-recognition-chinese:16.0.1@aar")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
