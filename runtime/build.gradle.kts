plugins {
    `java-library`
}

// Deliberately zero CPython-version-specific types here: see decision D2 in PLAN.md.

/*
 * The one exception the rule allows: the instruction set is the seam the two front-end stages share,
 * so its numbers live here. They are read off an interpreter rather than transcribed, which means the
 * build has to know which interpreter and keep the committed table honest against it. The reference
 * binary is resolved exactly as :front-cpython314 resolves it, for the same reason — the generator and
 * the reader must be the same 3.14.
 */
val refPython =
        (findProperty("jython3.refPython") as String?)
                ?: System.getenv("JY3_REF_PYTHON")
                ?: "python3.14"

val checkOpcodes =
        tasks.register("checkOpcodes", Exec::class) {
            description = "Fails if Opcodes.java no longer matches the reference interpreter's set"
            group = "verification"

            // Captured as plain values so the configuration cache can serialise the task.
            val python: String = refPython
            val generator = file("tools/gen_opcodes.py")
            val table = file("src/main/java/org/python/core/Opcodes.java")
            inputs.file(generator)
            inputs.file(table)
            outputs.cacheIf("a freshness check produces no artifact worth keeping") { true }
            commandLine(python, generator.absolutePath, "--check", table.absolutePath)
        }

tasks.named("check") {
    dependsOn(checkOpcodes)
}
