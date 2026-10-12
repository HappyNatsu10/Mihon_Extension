import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "DeviantArt"
    versionCode = 14
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "all"
        baseUrl = "https://www.deviantart.com"
    }

    deeplink {
        host("www.deviantart.com")
        host("deviantart.com")
        path("/..*/gallery/..*")
        path("/..*/art/..*")
    }
}

android {
    sourceSets {
        named("test") {
            java.directories.add("test")
            kotlin.directories.add("test")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation(libs.bundles.common)
    testImplementation(libs.tachiyomi.lib.v16)
    testImplementation(libs.junit)
}

tasks.matching { it.name.startsWith("ksp") && it.name.contains("UnitTest") }.configureEach {
    enabled = false
}
