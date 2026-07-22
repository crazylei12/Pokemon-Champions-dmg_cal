import groovy.json.JsonSlurper
import java.security.KeyStore
import java.security.MessageDigest
import org.gradle.api.tasks.Sync

val packageMetadataFile = rootProject.layout.projectDirectory.file("../package.json")
val packageMetadata = JsonSlurper().parseText(
    providers.fileContents(packageMetadataFile).asText.get(),
) as Map<*, *>
val appVersionName = packageMetadata["version"] as? String
    ?: error("package.json must define a string version")
val appVersionCode = (packageMetadata["androidVersionCode"] as? Number)?.toInt()
    ?: error("package.json must define a numeric androidVersionCode")
val releaseVariantFile = rootProject.layout.projectDirectory.file("../config/android-release-variant.txt")
val releaseVariant = providers.fileContents(releaseVariantFile).asText.get()
    .trim()
check(releaseVariant in setOf("standard", "replay")) {
    "config/android-release-variant.txt must be either standard or replay"
}

fun requiredSigningEnvironment(name: String): String? = System.getenv(name)?.takeIf(String::isNotBlank)

val stableSigningStorePath = requiredSigningEnvironment("POKEMON_CHAMPIONS_SIGNING_STORE_FILE")
val stableSigningStorePassword = requiredSigningEnvironment("POKEMON_CHAMPIONS_SIGNING_STORE_PASSWORD")
val stableSigningKeyAlias = requiredSigningEnvironment("POKEMON_CHAMPIONS_SIGNING_KEY_ALIAS")
val stableSigningKeyPassword = requiredSigningEnvironment("POKEMON_CHAMPIONS_SIGNING_KEY_PASSWORD")
val signingValues = listOf(
    stableSigningStorePath,
    stableSigningStorePassword,
    stableSigningKeyAlias,
    stableSigningKeyPassword,
)
val hasStableSigningConfiguration = signingValues.all { it != null }
check(signingValues.all { it == null } || hasStableSigningConfiguration) {
    "Release signing configuration is incomplete. Provision it with tools/android/provision-release-signing.ps1."
}
val stableSigningStore = stableSigningStorePath?.let(::file)
val expectedStableSignerSha256 = rootProject.file("../config/release-signing-certificate.sha256")
    .readText(Charsets.UTF_8).trim().uppercase()
val requestedReleaseBuild = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
val hasStableSigningStore = stableSigningStore?.isFile == true

check(!requestedReleaseBuild || hasStableSigningStore) {
    "Stable Pokemon Champions signing key is required for release builds: ${stableSigningStore?.absolutePath ?: "not configured"}"
}

if (hasStableSigningConfiguration && hasStableSigningStore) {
    val stableSigningKeyStore = KeyStore.getInstance("PKCS12").apply {
        stableSigningStore!!.inputStream().use { load(it, stableSigningStorePassword!!.toCharArray()) }
    }
    val stableSigningCertificate = stableSigningKeyStore.getCertificate(stableSigningKeyAlias!!)
        ?: error("Signing alias '$stableSigningKeyAlias' is missing from ${stableSigningStore!!.absolutePath}")
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

val mlKitLatinLicenseArtifacts by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val supportedAndroidAbis = listOf("arm64-v8a", "x86_64")
val requestedAndroidAbis = providers.gradleProperty("androidAbis").orNull
    ?.split(',')
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?: supportedAndroidAbis
check(requestedAndroidAbis.isNotEmpty() && requestedAndroidAbis.all(supportedAndroidAbis::contains)) {
    "androidAbis must contain only: ${supportedAndroidAbis.joinToString()}"
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
        buildConfigField("String", "RELEASE_VARIANT", "\"$releaseVariant\"")
    }

    val stableUpdateSigning = if (hasStableSigningConfiguration && hasStableSigningStore) {
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
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        buildConfig = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            if (requestedAndroidAbis == supportedAndroidAbis) {
                include("arm64-v8a", "x86_64")
            } else {
                include(*requestedAndroidAbis.toTypedArray())
            }
            isUniversalApk = false
        }
    }

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.get().asFile.resolve("generated/recognitionAssets"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.get().asFile.resolve("generated/legalAssets"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.get().asFile.resolve("generated/releaseMetadata"))
}

val syncReleaseMetadata by tasks.registering(Sync::class) {
    from(rootProject.file("../config/android-release-variant.txt")) {
        rename { "release-variant.txt" }
    }
    into(layout.buildDirectory.dir("generated/releaseMetadata"))
}

val syncRecognitionAssets by tasks.registering(Sync::class) {
    from(rootProject.file("../src/data/localization/zh-Hans.json"))
    from(rootProject.file("../src/data/recognition/team-preview.safe-zone-roi.zh-Hans.v2.json"))
    from(rootProject.file("../src/data/recognition/android/team-preview-templates-v2.bin"))
    from(rootProject.file("../src/data/recognition/android/team-preview-templates-v2.json"))
    into(layout.buildDirectory.dir("generated/recognitionAssets/recognition"))
}

val validateRecognitionFeaturePack by tasks.registering {
    val binaryFile = rootProject.file("../src/data/recognition/android/team-preview-templates-v2.bin")
    val metadataFile = rootProject.file("../src/data/recognition/android/team-preview-templates-v2.json")
    val roiFile = rootProject.file("../src/data/recognition/team-preview.safe-zone-roi.zh-Hans.v2.json")
    inputs.files(binaryFile, metadataFile, roiFile)
    doLast {
        fun sha256(file: java.io.File): String = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        check(binaryFile.isFile && binaryFile.length() > 0) {
            "Required Android recognition feature pack is missing: ${binaryFile.absolutePath}"
        }
        check(metadataFile.isFile && metadataFile.length() > 0) {
            "Required Android recognition feature metadata is missing: ${metadataFile.absolutePath}"
        }
        val metadata = JsonSlurper().parse(metadataFile) as Map<*, *>
        check(metadata["binaryFormat"] == "PTVFEAT2") { "Unexpected recognition feature pack format" }
        val binary = metadata["binary"] as? Map<*, *> ?: error("Recognition metadata is missing binary details")
        val expectedBytes = (binary["bytes"] as? Number)?.toLong()
            ?: error("Recognition metadata is missing binary byte count")
        val expectedSha256 = binary["sha256"] as? String
            ?: error("Recognition metadata is missing binary SHA-256")
        check(binaryFile.length() == expectedBytes) {
            "Recognition feature pack size mismatch: expected $expectedBytes, found ${binaryFile.length()}"
        }
        check(sha256(binaryFile).equals(expectedSha256, ignoreCase = true)) {
            "Recognition feature pack SHA-256 does not match its metadata"
        }
        val roi = metadata["roi"] as? Map<*, *> ?: error("Recognition metadata is missing ROI details")
        val expectedRoiSha256 = roi["sha256"] as? String ?: error("Recognition metadata is missing ROI SHA-256")
        check(sha256(roiFile).equals(expectedRoiSha256, ignoreCase = true)) {
            "Recognition ROI SHA-256 does not match feature pack metadata"
        }
    }
}

syncRecognitionAssets.configure { dependsOn(validateRecognitionFeaturePack) }

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
    from({ mlKitLatinLicenseArtifacts.files.map { zipTree(it) } }) {
        include("third_party_licenses.json", "third_party_licenses.txt")
        into("licenses/ml-kit/latin")
    }
    into(layout.buildDirectory.dir("generated/legalAssets"))
}

tasks.named("preBuild").configure {
    dependsOn(syncReleaseMetadata)
    dependsOn(syncRecognitionAssets)
    dependsOn(syncDamageAssets)
    dependsOn(syncLegalAssets)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.window:window:1.5.1")
    implementation("androidx.window:window-java:1.5.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.opencv:opencv:4.13.0")
    add(mlKitLicenseArtifacts.name, "com.google.mlkit:text-recognition-chinese:16.0.1@aar")
    add(mlKitLatinLicenseArtifacts.name, "com.google.mlkit:text-recognition:16.0.1@aar")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
