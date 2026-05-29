import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.tasks.Jar

plugins {
    `java-library`
    `maven-publish`
}

repositories {
    mavenCentral()
}

val javacExports = listOf(
    "jdk.compiler/com.sun.tools.javac.api",
    "jdk.compiler/com.sun.tools.javac.code",
    "jdk.compiler/com.sun.tools.javac.tree",
    "jdk.compiler/com.sun.tools.javac.util",
)

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(
        javacExports.flatMap { export -> listOf("--add-exports", "$export=ALL-UNNAMED") },
    )
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs(javacExports.map { export -> "--add-exports=$export=ALL-UNNAMED" })
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Automatic-Module-Name"] = "net.ikvm.javarefplugin"
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = rootProject.name
            pom {
                name = "java-ref-plugin"
                description = project.description.toString()
                url = "https://github.com/ikvmnet/java-ref-plugin"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                developers {
                    developer {
                        id = "ikvmnet"
                        name = "IKVM"
                    }
                }
                scm {
                    connection = "scm:git:https://github.com/ikvmnet/java-ref-plugin.git"
                    developerConnection = "scm:git:ssh://git@github.com/ikvmnet/java-ref-plugin.git"
                    url = "https://github.com/ikvmnet/java-ref-plugin"
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            val repositoryPath = providers.environmentVariable("GITHUB_REPOSITORY")
                .orElse("ikvmnet/java-ref-plugin")
            url = uri("https://maven.pkg.github.com/${repositoryPath.get()}")
            credentials {
                username = providers.gradleProperty("githubUsername")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orNull
                password = providers.gradleProperty("githubToken")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orNull
            }
        }
    }
}
