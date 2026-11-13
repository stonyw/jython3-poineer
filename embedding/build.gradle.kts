plugins {
    `java-library`
    application
}

dependencies {
    api(project(":runtime"))
    implementation(project(":front-cpython314"))
    implementation(project(":javainterop"))
}

application {
    // Produces build/install/jy3/bin/jy3, the launcher the harness protocol speaks to.
    applicationName = "jy3"
    mainClass.set("org.python.util.jy3")
}

/*
 * `sys.executable` is the absolute path of the program that started the run, and the launcher is the
 * only thing that knows it. Gradle writes the start script, so the path is spliced in there rather
 * than guessed by the runtime: `$APP_HOME` is the installed prefix, and the script is the file the
 * user typed. A JVM entered some other way (`java -cp ... org.python.util.jy3`) has no launcher to
 * name, and `PyRuntime` keeps the bare `jy3` for it instead of inventing a path.
 */
tasks.named<org.gradle.jvm.application.tasks.CreateStartScripts>("startScripts") {
    doLast {
        val script = unixScript
        val anchor = "        -classpath \"\$CLASSPATH\" \\\n"
        val text = script.readText()
        require(text.contains(anchor)) {
            "the unix start script no longer carries the classpath argument: $script"
        }
        script.writeText(
                text.replace(
                        anchor,
                        "        \"-Djy3.executable=\$APP_HOME/bin/jy3\" \\\n" + anchor
                )
        )
    }
}

/*
 * The launcher tests run the runtime as a process. When the installed launcher exists they hand its
 * absolute path to that process, so `sys.executable` answers the same way it does for a user; the
 * corpus then spawns real children through it.
 */
tasks.test {
    dependsOn("installDist")
    systemProperty(
            "jy3.launcher",
            layout.buildDirectory.file("install/jy3/bin/jy3").get().asFile.absolutePath
    )
}
