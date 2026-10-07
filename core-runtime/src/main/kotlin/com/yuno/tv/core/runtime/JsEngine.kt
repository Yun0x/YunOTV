package com.yuno.tv.core.runtime

import org.mozilla.javascript.*

class JsDeadline : Error("TIMEOUT")
/** Interpreted execution: bounded stack, no JVM bytecode generation or Java integration. */
class JsEngine(private val bridge: DrpyBridge, timeoutMs: Long = RuntimePolicy.DEADLINE_MS) : AutoCloseable {
    private var deadline = System.nanoTime() + timeoutMs * 1_000_000
    private val factory = object : ContextFactory() {
        override fun hasFeature(cx: Context, featureIndex: Int): Boolean =
            if(featureIndex==Context.FEATURE_E4X)false else super.hasFeature(cx,featureIndex)
        override fun makeContext(): Context = super.makeContext().apply {
            optimizationLevel = -1; languageVersion = Context.VERSION_ES6
            instructionObserverThreshold = 5000; maximumInterpreterStackDepth = 128
            setClassShutter { false }
            wrapFactory = object : WrapFactory() {
                override fun wrap(cx: Context, scope: Scriptable, obj: Any?, staticType: Class<*>?): Any? {
                    if (obj == null || obj is String || obj is Number || obj is Boolean) return obj
                    throw RuntimeFailure("JAVA_ACCESS_DENIED")
                }
            }
        }
        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) throw JsDeadline()
        }
    }
    private var scope: ScriptableObject? = null
    fun resetDeadline(timeoutMs: Long) { deadline = System.nanoTime() + timeoutMs * 1_000_000 }
    private fun <T> context(block: (Context, ScriptableObject) -> T): T {
        val cx = factory.enterContext()
        try {
            val root = scope ?: cx.initSafeStandardObjects().also { root ->
                scope = root
                listOf("Packages", "java", "javax", "android", "org", "com", "JavaAdapter", "JavaImporter", "importClass", "importPackage", "load", "quit", "getClass").forEach { root.delete(it) }
                root.defineProperty("__bridge", object : BaseFunction() {
                    override fun call(cx: Context, callScope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any {
                        val name = Context.toString(args.firstOrNull() ?: "")
                        return bridge.call(name, args.drop(1).map { Context.toString(it) })
                    }
                }, ScriptableObject.READONLY or ScriptableObject.PERMANENT or ScriptableObject.DONTENUM)
            }
            return block(cx, root)
        } finally { Context.exit() }
    }
    fun evaluate(script: String): String {
        RuntimePolicy.requireSize(script.toByteArray(), RuntimePolicy.JS_BYTES)
        return context { cx, root -> Context.toString(cx.evaluateString(root, script, "plugin", 1, null)) }
    }
    override fun close() { scope = null }
}
