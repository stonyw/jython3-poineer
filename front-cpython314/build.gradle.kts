import java.io.File

plugins {
    `java-library`
}

dependencies {
    api(project(":runtime"))
}

/*
 * The marshal expectations are generated, never committed, and — the provenance rule that cost a
 * day in M0 — must come from the same CPython build that produces them. A checkout of the `3.14`
 * branch is 3.14.9+ development, whose marshal stream is not the one a 3.14.8 binary writes, so the
 * default is the interpreter itself rather than a source tree.
 */
val refPython =
        (findProperty("jython3.refPython") as String?)
                ?: System.getenv("JY3_REF_PYTHON")
                ?: "python3.14"

val syncMarshalFixtures =
        tasks.register("syncMarshalFixtures") {
            description = "Generates marshal expectations with the reference interpreter"
            group = "verification"

            // Captured as plain values so the task stays serialisable by the configuration cache:
            // a doLast closure may not reference the build script itself.
            val pythonExec: String = refPython
            val generator: File = file("tools/gen_marshal_fixtures.py")
            val fixtures: File = layout.buildDirectory.file("marshal-fixtures.txt").get().asFile
            inputs.file(generator)
            outputs.file(fixtures)

            doLast {
                val process =
                        ProcessBuilder(pythonExec, generator.absolutePath)
                                .redirectErrorStream(false)
                                .start()
                val text = process.inputStream.bufferedReader().readText()
                val err = process.errorStream.bufferedReader().readText()
                if (process.waitFor() != 0) {
                    throw GradleException(
                            "'$pythonExec' could not generate marshal fixtures: $err\n" +
                                    "Install CPython 3.14, or pass -Pjython3.refPython=<bin>.")
                }
                fixtures.parentFile.mkdirs()
                fixtures.writeText(text)
                val sections = text.lineSequence().count { it.startsWith("=== ") }
                logger.lifecycle("generated $sections marshal sections with $pythonExec")
            }
        }

tasks.withType<Test>().configureEach {
    dependsOn(syncMarshalFixtures)
    systemProperty(
            "jython3.marshalFixtures",
            layout.buildDirectory.file("marshal-fixtures.txt").get().asFile.absolutePath)
}
