import org.gradle.api.tasks.Sync

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
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.get().asFile.resolve("generated/recognitionAssets"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.get().asFile.resolve("generated/legalAssets"))
}

val syncRecognitionAssets by tasks.registering(Sync::class) {
    from(rootProject.file("../src/data/localization/zh-Hans.json"))
    from(rootProject.file("../src/data/recognition/team-preview.safe-zone-roi.zh-Hans.v1.json"))
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
