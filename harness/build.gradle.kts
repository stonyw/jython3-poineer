import java.io.File

plugins {
    application
}

application {
    mainClass.set("org.jython3.tools.harness.HarnessMain")
}

dependencies {
    implementation(rootProject.libs.assertj.core)
}

/*
 * The harness measures Jython3 against CPython's own regression suite. Test sources are vendored,
 * never committed, so provenance is a property of the build.
 *
 * Provenance rule that cost us a day in M0: the tree MUST come from the same build of CPython that
 * acts as the reference backend. A checkout of the `3.14` branch is 3.14.9+ development, and its
 * `test.support` calls `_testlimitedcapi` symbols a 3.14.8 binary does not export — the harness then
 * reports a runtime CRASH instead of a version-skew error. Default to the interpreter's own Lib/test;
 * override only to measure against a deliberately pinned source tree.
 */
val refPython =
        (findProperty("jython3.refPython") as String?)
                ?: System.getenv("JY3_REF_PYTHON")
                ?: "python3.14"

val syncCpythonTests =
        tasks.register("syncCpythonTests") {
            description = "Vendores the reference interpreter's own Lib/test into lib-python/3.14/test"
            group = "verification"

            // Captured as plain values so the task stays serialisable by the configuration cache:
            // a doLast closure may not reference the build script itself.
            val pythonExec: String = refPython
            val overrideDir: File? =
                    (findProperty("jython3.cpythonTestDir") as String?)?.let { file(it).canonicalFile }
            val testRoot: File = rootProject.file("lib-python/3.14/test")

            doLast {
                val src =
                        overrideDir
                                ?: run {
                                    val probe =
                                            ProcessBuilder(
                                                            pythonExec,
                                                            "-c",
                                                            "import sysconfig,os;print(os.path.join(sysconfig.get_paths()['stdlib'],'test'))")
                                                    .redirectErrorStream(true)
                                                    .start()
                                    val out = probe.inputStream.bufferedReader().readText().trim()
                                    if (probe.waitFor() != 0) {
                                        throw GradleException(
                                                "cannot query '$pythonExec' for its stdlib path: $out\n" +
                                                        "Install CPython 3.14, or pass -Pjython3.refPython=<bin>, " +
                                                        "or -Pjython3.cpythonTestDir=<dir>.")
                                    }
                                    File(out)
                                }
                if (!src.isDirectory) {
                    throw GradleException(
                            "$pythonExec ships no test package at $src (some distros strip it). " +
                                    "Point -Pjython3.cpythonTestDir at a matching CPython Lib/test.")
                }
                if (testRoot.exists()) {
                    testRoot.deleteRecursively()
                }
                testRoot.mkdirs()
                var copied = 0
                src.walkTopDown()
                        .filter { it.isFile }
                        .filterNot { it.path.contains("__pycache__") || it.name.endsWith(".pyc") }
                        .forEach { f ->
                            val out = File(testRoot, f.relativeTo(src).path)
                            out.parentFile.mkdirs()
                            f.copyTo(out, overwrite = true)
                            copied++
                        }
                logger.lifecycle("vendored $copied test files from $src")
            }
        }

val harnessCpythonRef =
        tasks.register("harnessCpythonRef", JavaExec::class.java) {
            description =
                    "Runs the smoke suite through the reference CPython backend (validates the instrument)"
            group = "verification"
            dependsOn(syncCpythonTests)
            mainClass.set("org.jython3.tools.harness.HarnessMain")
            classpath = sourceSets.main.get().runtimeClasspath
            // Suite/expected/lib-python paths in HarnessMain are written relative to the repo root.
            workingDir = rootDir
            args("--backend=cpython-ref", "--suite=smoke")
            // The reference backend must be green: a broken instrument invalidates every milestone.
        }

val harnessJy3 =
        tasks.register("harnessJy3", JavaExec::class.java) {
            description = "Runs the smoke suite through the jy3 backend"
            group = "verification"
            dependsOn(syncCpythonTests, ":embedding:installDist")
            mainClass.set("org.jython3.tools.harness.HarnessMain")
            classpath = sourceSets.main.get().runtimeClasspath
            workingDir = rootDir
            args("--backend=jy3", "--suite=smoke", "--jy3=embedding/build/install/jy3/bin/jy3")
            // Until M1 produces a working runtime every jy3 result is a surprise. Re-enable at M1.
            isIgnoreExitValue = true
        }

tasks.named("check") { dependsOn(harnessCpythonRef) }
