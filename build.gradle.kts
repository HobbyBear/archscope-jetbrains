import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    java
    id("org.jetbrains.intellij.platform")
}

group = "com.archscope"
version = "0.7.11"

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3.6")
        pluginVerifier()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:deprecation")
    }

    test {
        useJUnitPlatform()
        for (name in listOf(
            "archscope.benchmarkRepo",
            "archscope.benchmarkOutput",
            "archscope.benchmarkCommits",
            "archscope.cacheDir",
            "archscope.reportArchiveDir",
            "archscope.previewInput",
            "archscope.previewOutput",
            "archscope.previewDark",
            "archscope.functionPreviewInput",
            "archscope.functionPreviewOutput",
            "archscope.domainBenchmarkRepo",
            "archscope.domainBenchmarkPrompt",
            "archscope.domainBenchmarkLanguage",
            "archscope.domainBenchmarkOutput",
            "archscope.domainBenchmarkHtmlOutput",
            "archscope.domainCliWorkingDirectory",
            "archscope.domainCustomPrompt",
            "archscope.domainBusinessContext",
            "archscope.domainCodeReadingPrompt",
            "archscope.domainSystemPrompt",
            "archscope.domainRefinePrompt",
            "archscope.domainRefineInput",
            "archscope.domainRefineOutput",
            "archscope.domainRefineHtmlOutput",
            "archscope.modelProvider",
            "archscope.liveFunctionRepository",
            "archscope.modelAuditDir"
        )) {
            System.getProperty(name)?.let { systemProperty(name, it) }
        }
        testLogging.showStandardStreams = true
    }

    patchPluginXml {
        sinceBuild = "243"
    }

}

intellijPlatform {
    pluginConfiguration {
        name = "CodeBecause"
        version = project.version.toString()
        description = """
            Analyze a user-defined business topic with a configurable local CLI provider,
            then refine evidence-backed business-flow reports interactively.
        """.trimIndent()

        ideaVersion {
            sinceBuild = "243"
        }

        vendor {
            name = "CodeBecause"
            email = "18881934641@163.com"
            url = "https://github.com/HobbyBear/archscope-jetbrains"
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.6")
            create(IntelliJPlatformType.GoLand, "2024.3.6")
        }
    }
}
