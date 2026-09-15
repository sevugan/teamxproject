// `core` is a standalone Gradle build so the night rules can be compiled and tested on
// any JVM, with no Android SDK installed (see the root build, which pulls it in with
// `includeBuild`). Run it directly with: gradle -p core test
rootProject.name = "core"
