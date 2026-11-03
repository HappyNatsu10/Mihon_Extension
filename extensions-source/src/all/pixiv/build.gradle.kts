import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Pixiv"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    listOf("en", "ja", "zh", "zh-tw", "ko").forEach {
        source {
            lang = it
            baseUrl = "https://www.pixiv.net"
        }
    }

    deeplink {
        host("pixiv.net")
        host("www.pixiv.net")
        path("/en/artworks/..*")
        path("/artworks/..*")
        path("/en/users/..*")
        path("/users/..*")
        path("/user/..*/series/..*")
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

tasks.withType<Test> {
    testLogging {
        showStandardStreams = true
    }
}
