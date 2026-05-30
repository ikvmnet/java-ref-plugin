import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.tasks.Jar

plugins {
    `java-library`
    `maven-publish`
}

repositories {
    mavenCentral()
}

val java8Compiler = javaToolchains.compilerFor {
    languageVersion = JavaLanguageVersion.of(8)
}

val java8ToolsJar = files(java8Compiler.map { it.metadata.installationPath.file("lib/tools.jar") })

dependencies {
    compileOnly(java8ToolsJar)
    testCompileOnly(java8ToolsJar)
    testRuntimeOnly(java8ToolsJar)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(8)
    }
    withSourcesJar()
}

tasks.named<JavaCompile>("compileJava") {
    javaCompiler = java8Compiler
    sourceCompatibility = JavaVersion.VERSION_1_8.toString()
    targetCompatibility = JavaVersion.VERSION_1_8.toString()
}

tasks.named<JavaCompile>("compileTestJava") {
    javaCompiler = java8Compiler
    sourceCompatibility = JavaVersion.VERSION_1_8.toString()
    targetCompatibility = JavaVersion.VERSION_1_8.toString()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Automatic-Module-Name"] = "org.ikvm.javarefplugin"
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
