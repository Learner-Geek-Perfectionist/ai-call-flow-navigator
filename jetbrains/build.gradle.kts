plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

val hostOs = System.getProperty("os.name").lowercase()
val userHome = System.getProperty("user.home")
val androidStudioCandidates = when {
    hostOs.contains("mac") -> listOf(
        "/Applications/Android Studio.app/Contents",
        "$userHome/Applications/Android Studio.app/Contents"
    )
    hostOs.contains("win") -> listOfNotNull(
        System.getenv("ProgramFiles")?.let { "$it/Android/Android Studio" },
        System.getenv("LOCALAPPDATA")?.let { "$it/Programs/Android Studio" }
    )
    else -> listOf(
        "/opt/android-studio",
        "/usr/local/android-studio",
        "$userHome/android-studio"
    )
}
val androidStudioPath = providers.gradleProperty("androidStudioPath").orNull
    ?: providers.environmentVariable("ANDROID_STUDIO_PATH").orNull
    ?: androidStudioCandidates.firstOrNull { file(it).isDirectory }
    ?: throw GradleException(
        "Android Studio was not found. Set ANDROID_STUDIO_PATH or -PandroidStudioPath=<IDE home>."
    )

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    intellijPlatform {
        local(androidStudioPath)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
    test {
        useJUnitPlatform()
    }

    patchPluginXml {
        sinceBuild.set(providers.gradleProperty("pluginSinceBuild"))
        untilBuild.set(providers.gradleProperty("pluginUntilBuild"))
    }
}

intellijPlatform {
    pluginConfiguration {
        id = providers.gradleProperty("pluginGroup")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        description = """
            Loads Call Flow JSON files produced by local AI tools for the current
            Android Studio project. Explore complete call flows with native step-by-step
            playback and exact source navigation, without settings or network services.
        """.trimIndent()

        vendor {
            name = "YoungX"
            url = "https://github.com/Learner-Geek-Perfectionist/ai-call-flow-navigator"
        }
    }

    pluginVerification {
        ides {
            local(file(androidStudioPath))
        }
    }
}
