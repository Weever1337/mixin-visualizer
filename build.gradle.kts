import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.kohsuke.github.GitHubBuilder
import java.nio.file.Files

buildscript {
    dependencies {
        classpath("org.kohsuke:github-api:1.314")
    }
}

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.10.4"
    id("org.jetbrains.changelog") version "2.4.0"
}

val mcdevVersion: String by project
val mcdevIdeaVersion: String by project

group = "dev.wvr"
version = "0.1.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
    maven("https://repo.spongepowered.org/maven")
}

dependencies {
    intellijPlatform {
        create("IC", "2025.1.5")
        testFramework(TestFrameworkType.Platform)

        bundledPlugin("com.intellij.java")

        plugins("com.demonwav.minecraft-dev:$mcdevIdeaVersion-$mcdevVersion")
    }

    implementation("org.ow2.asm:asm:9.9")
    implementation("org.ow2.asm:asm-tree:9.9")
    implementation("org.ow2.asm:asm-util:9.9")
    implementation("org.vineflower:vineflower:1.11.2")

    testImplementation("junit:junit:4.13.2")
    testCompileOnly("org.spongepowered:mixin:0.8.7") { isTransitive = false }
    testCompileOnly("io.github.llamalad7:mixinextras-common:0.5.4") { isTransitive = false }
}

intellijPlatform {
    pluginConfiguration {
        name = project.name
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = with(project.changelog) {
            renderItem(
                (getOrNull(project.version.toString()) ?: getUnreleased())
                    .withHeader(false)
                    .withEmptySections(false),
                Changelog.OutputType.HTML,
            )
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf(
            project.version.toString()
                .substringAfter('-', "")
                .substringBefore('.')
                .ifEmpty { "default" }
        )
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    publishPlugin {
        dependsOn(patchChangelog)
    }

    val publishGithub by registering(PublishGithubTask::class) {
        dependsOn(buildPlugin)

        githubToken.set(providers.environmentVariable("GITHUB_TOKEN").orNull)

        repoName.set("weever1337/mixin-visualizer")
        versionName.set(project.version.toString())

        changelogText.set(
            providers.provider {
                val version = project.version.toString()
                val changelog = project.changelog.getOrNull(version) ?: project.changelog.getUnreleased()
                project.changelog.renderItem(changelog, Changelog.OutputType.MARKDOWN)
            }
        )

        archiveFile.set(buildPlugin.flatMap { t ->
            t.outputs.files.singleFile.let { layout.file(project.provider { it }) }
        })
    }

    val publish by registering {
        dependsOn(publishPlugin, publishGithub)
    }
}

// https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = "https://github.com/weever1337/mixin-visualizer"
    versionPrefix = ""
}

// code from https://github.com/badasintended/ravel/blob/master/build.gradle.kts
abstract class PublishGithubTask : DefaultTask() {
    @get:Input
    abstract val githubToken: Property<String>

    @get:Input
    abstract val repoName: Property<String>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val changelogText: Property<String>

    @get:InputFile
    abstract val archiveFile: RegularFileProperty

    @TaskAction
    fun publish() {
        val token = githubToken.orNull ?: throw IllegalStateException("GITHUB_TOKEN not provided")
        val gh = GitHubBuilder().withOAuthToken(token).build()
        val repo = gh.getRepository(repoName.get())

        val release = repo.createRelease(versionName.get())
            .name(versionName.get())
            .commitish("master")
            .body(changelogText.get())
            .create()

        val zip = archiveFile.asFile.get()
        release.uploadAsset(zip, Files.probeContentType(zip.toPath()))
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}