import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    base
}

allprojects {
    group = "org.jython3"
    version = "0.1.0-M0"
}

subprojects {
    plugins.withType<JavaPlugin> {
        configure<JavaPluginExtension> {
            // Java 21 LTS is the floor and the target. JDK 27 may run the build; it must not be the target.
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
            withSourcesJar()
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set(21)
            options.encoding = "UTF-8"
            options.compilerArgs.add("-parameters")
        }

        dependencies {
            "testImplementation"(platform(rootProject.libs.junit.bom))
            "testImplementation"(rootProject.libs.junit.jupiter)
            "testImplementation"(rootProject.libs.assertj.core)
            "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                exceptionFormat = TestExceptionFormat.FULL
                events("failed", "skipped")
            }
        }
    }
}

/** Prints the JDK the build actually resolved, so jenv selection is verifiable in one command. */
tasks.register("doctor") {
    group = "verification"
    description = "Reports jenv/daemon/toolchain Java versions and the CPython reference binary."
    notCompatibleWithConfigurationCache("prints live environment state")
    doLast {
        val jh = System.getProperty("java.home")
        val release = File(jh, "release").takeIf { it.exists() }
            ?.readLines()?.firstOrNull { it.startsWith("JAVA_VERSION=") }
        println("gradle daemon java.home : $jh")
        println("  release               : ${release?.replace("JAVA_VERSION=", "") ?: "unknown"}")
        println("  vendor                : ${System.getProperty("java.vendor")}")
        println("  JAVA_HOME env         : ${System.getenv("JAVA_HOME") ?: "unset"}")
        println("  jenv .java-version    : ${File(rootDir, ".java-version").takeIf { it.exists() }?.readText()?.trim()}")
        println("  build target (release): 21")
    }
}
