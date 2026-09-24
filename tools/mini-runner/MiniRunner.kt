package io.ucc.tools

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import kotlin.system.exitProcess

/**
 * Minimal test runner for environments without JUnit on the classpath.
 * Discovers classes under the given directory, runs every public no-arg
 * method annotated with @kotlin.test.Test (a typealias for org.junit.Test). Not a JUnit replacement — CI uses
 * Gradle + JUnit. Supports @BeforeTest/@AfterTest and expected-failure via
 * kotlin.test.assertFailsWith inside the test body.
 */
fun main(args: Array<String>) {
    val dir = File(args[0])
    val loader = URLClassLoader(arrayOf(dir.toURI().toURL()), Thread.currentThread().contextClassLoader)
    val classNames = dir.walkTopDown()
        .filter { it.isFile && it.extension == "class" && !it.name.contains('$') }
        .map { it.relativeTo(dir).path.removeSuffix(".class").replace(File.separatorChar, '.') }
        .toList()

    var run = 0
    var failed = 0
    val failures = mutableListOf<String>()
    for (cn in classNames) {
        val cls = try { loader.loadClass(cn) } catch (e: Throwable) { continue }
        val tests = cls.methods.filter { m -> m.annotations.any { it.annotationClass.qualifiedName == "org.junit.Test" } }
        if (tests.isEmpty()) continue
        val befores = cls.methods.filter { m -> m.annotations.any { it.annotationClass.qualifiedName == "org.junit.Before" } }
        val afters = cls.methods.filter { m -> m.annotations.any { it.annotationClass.qualifiedName == "org.junit.After" } }
        for (t in tests) {
            run++
            val instance = try { cls.getDeclaredConstructor().newInstance() } catch (e: Throwable) {
                failed++; failures += "$cn: cannot instantiate: $e"; continue
            }
            try {
                befores.forEach { it.invoke(instance) }
                t.invoke(instance)
                afters.forEach { it.invoke(instance) }
                println("  ok   ${cls.simpleName}.${t.name}")
            } catch (e: InvocationTargetException) {
                failed++
                val cause = e.targetException
                failures += "${cls.simpleName}.${t.name}: $cause"
                println("  FAIL ${cls.simpleName}.${t.name}: $cause")
                cause.stackTrace.take(6).forEach { println("         at $it") }
            }
        }
    }
    println("-- $run tests, $failed failed")
    failures.forEach { println("   $it") }
    if (failed > 0 || run == 0) exitProcess(1)
}
