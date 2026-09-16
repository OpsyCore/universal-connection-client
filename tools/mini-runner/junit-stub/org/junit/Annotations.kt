// Minimal stand-ins for the JUnit 4 annotations that kotlin-test-junit's
// typealiases (kotlin.test.Test / BeforeTest / AfterTest / Ignore) point to.
// Used ONLY by tools/local-check.sh in sandboxes without Maven access.
// Gradle/CI uses the real junit:junit artifact.
package org.junit

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Test(val expected: kotlin.reflect.KClass<out Throwable> = None::class, val timeout: Long = 0L) {
    class None private constructor() : Throwable()
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Before

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class After

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Ignore(val value: String = "")
