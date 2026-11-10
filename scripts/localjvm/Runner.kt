import java.io.File
import kotlin.reflect.KFunction
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.javaMethod

fun main(args: Array<String>) {
    var pass = 0; var fail = 0
    val failures = mutableListOf<String>()
    for (cn in args) {
        val cls = Class.forName(cn)
        val kcls = cls.kotlin
        val tests = kcls.memberFunctions.filter { f ->
            f.annotations.any { it.annotationClass.simpleName == "Test" }
        }.sortedBy { it.name }
        println("== $cn (${tests.size})")
        for (t in tests) {
            val inst = cls.getDeclaredConstructor().newInstance()
            try {
                val ctor = cls.getDeclaredConstructor(); ctor.isAccessible = true
                val obj = ctor.newInstance()
                val m = t.javaMethod!!
                m.isAccessible = true
                m.invoke(obj)
                pass++
                println("  PASS ${t.name}")
            } catch (e: Throwable) {
                val c = e.cause ?: e
                fail++
                failures += "$cn.${t.name}: ${c.javaClass.simpleName}: ${c.message}"
                println("  FAIL ${t.name}: ${c.javaClass.simpleName}: ${c.message}")
            }
        }
    }
    println("\nИТОГ: PASS=$pass FAIL=$fail")
    failures.forEach { println(" - $it") }
    if (fail > 0) System.exit(1)
}
