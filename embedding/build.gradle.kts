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
