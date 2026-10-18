rootProject.name = "jython3"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

/*
 * One Gradle module per build unit. The split is load-bearing: front-cpython314 holds every
 * CPython-version-specific detail so the Stage B native front-end can replace it wholesale.
 */
include("runtime")            // org.python.core — Python 3 object model, slots, frames, exceptions
include("front-cpython314")   // Stage A: marshal v5 reader + CPython wordcode interpreter
include("javainterop")        // PyJavaType / ReflectedArgs / PyArray / package scanning
include("embedding")          // PythonInterpreter, jy3 CLI, registry
include("stdlib")             // CPythonLib.includes assembly + *_jy.py overrides
include("harness")            // golden test harness (measurement instrument, not shipped)
