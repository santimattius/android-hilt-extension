package io.github.santimattius.android.hilt.trace.plugin.asm

import org.junit.Assert.assertNull
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_ABSTRACT
import org.objectweb.asm.Opcodes.ACC_INTERFACE
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ACONST_NULL
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.INVOKEINTERFACE
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.Opcodes.INVOKESTATIC
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Opcodes.V17
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.util.CheckClassAdapter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

private const val COMPONENT = "spike.DaggerSpikeApp_HiltComponents_SingletonC\$SingletonCImpl"
private const val PROVIDER = "dagger.internal.Provider"

/** A name provider that never skips a case, so a body wrapped by mistake shows up as a slice. */
internal fun instrumentEverything(body: CaseBody): String = fallbackName(body) ?: "unnamed"

private data class Member(val name: String, val desc: String, val static: Boolean)

private class Usage {
    val fields = mutableSetOf<Pair<String, String>>()
    val members = mutableSetOf<Member>()
    var isInterface = false
}

/**
 * Loads the real `$SwitchingProvider` fixtures, transformed by the production code, in a class
 * loader of their own. The class loader answers every other class the fixtures mention
 * (components, app classes, `dagger.internal.Provider`) with a synthesized class holding exactly the
 * members the fixture bytecode uses; constructors do nothing and static calls return `null`.
 * `io.github.santimattius.android.hilt.trace.HiltTrace` comes from the parent: the recording test stub.
 *
 * Every transformed class is checked with the ASM data flow verifier here, and the JVM verifies it
 * again when it is linked.
 */
internal class ExecutionRig(
    originals: List<ByteArray>,
    transform: (ByteArray) -> TransformResult,
) : ClassLoader(ExecutionRig::class.java.classLoader) {

    private val classes: Map<String, ByteArray> = originals.associate { original ->
        val result = transform(original)
        assertNull(result.warning, result.warning)
        ClassReader(result.bytes).accept(CheckClassAdapter(ClassWriter(0), true), 0)
        readClass(result.bytes).name.replace('/', '.') to result.bytes
    }

    private val usage = HashMap<String, Usage>().also { map ->
        fun of(owner: String) = map.getOrPut(owner) { Usage() }
        of(PROVIDER.replace('.', '/')).apply {
            isInterface = true
            members += Member("get", "()Ljava/lang/Object;", false)
        }
        classes.values.map(::readClass).forEach { node ->
            node.interfaces.forEach { of(it).isInterface = true }
            node.methods.flatMap { it.instructions }.forEach { insn ->
                when (insn) {
                    is FieldInsnNode -> of(insn.owner).fields += insn.name to insn.desc
                    is MethodInsnNode -> of(insn.owner).apply {
                        members += Member(insn.name, insn.desc, insn.opcode == INVOKESTATIC)
                        if (insn.opcode == INVOKEINTERFACE) isInterface = true
                    }
                    is TypeInsnNode -> of(insn.desc)
                }
            }
        }
    }

    private val providerGet by lazy { loadClass(PROVIDER).getMethod("get") }

    override fun findClass(name: String): Class<*> {
        val bytes = classes[name] ?: synthesize(name.replace('.', '/'))
        return defineClass(name, bytes, 0, bytes.size)
    }

    private fun synthesize(internal: String): ByteArray {
        val used = usage[internal] ?: Usage()
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        val access = ACC_PUBLIC or if (used.isInterface) ACC_INTERFACE or ACC_ABSTRACT else ACC_SUPER
        writer.visit(V17, access, internal, null, "java/lang/Object", null)
        used.fields.forEach { (name, desc) -> writer.visitField(ACC_PUBLIC, name, desc, null, null).visitEnd() }
        val noArgConstructor = if (used.isInterface) emptySet() else setOf(Member("<init>", "()V", false))
        for ((name, desc, static) in used.members + noArgConstructor) {
            val flags = ACC_PUBLIC or (if (static) ACC_STATIC else 0) or (if (used.isInterface) ACC_ABSTRACT else 0)
            writer.visitMethod(flags, name, desc, null, null).apply {
                if (!used.isInterface) {
                    visitCode()
                    if (name == "<init>") {
                        visitVarInsn(ALOAD, 0)
                        visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                    }
                    if (name == "<init>" || desc.endsWith(")V")) visitInsn(RETURN) else { visitInsn(ACONST_NULL); visitInsn(ARETURN) }
                    visitMaxs(0, 0)
                }
                visitEnd()
            }
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** A fresh instance of the synthesized singleton component, whose provider fields tests fill in. */
    fun outer(): Any = loadClass(COMPONENT).getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    /** Builds a provider of class [className] for [id]; its constructor takes the component first (when it has one) and `id` last. */
    fun provider(className: String, outer: Any, id: Int): Any {
        val constructor = loadClass(className).declaredConstructors.single().apply { isAccessible = true }
        val args = arrayOfNulls<Any>(constructor.parameterCount)
        if (args.size > 1) args[0] = outer
        args[args.size - 1] = id
        return constructor.newInstance(*args)
    }

    /** A `dagger.internal.Provider` that runs [create] on every `get()`. */
    fun provider(create: () -> Any?): Any =
        Proxy.newProxyInstance(this, arrayOf(loadClass(PROVIDER))) { _, _, _ -> create() }

    /** The stand-in for `DoubleCheck`: asks [delegate] once and remembers the answer. */
    fun memoized(delegate: Any): Any {
        val cache = lazy { get(delegate) }
        return provider { cache.value }
    }

    /** Calls `get()` on a provider, rethrowing what the creation threw instead of a reflection wrapper. */
    fun get(provider: Any): Any? = try {
        providerGet.invoke(provider)
    } catch (e: InvocationTargetException) {
        throw e.cause ?: e
    }
}

/** Sets the instance field [name] of a synthesized component. */
internal fun Any.set(name: String, value: Any?) = javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)

/** The rig of the create rule: every `$SwitchingProvider` among [originals] is transformed with [nameOf]. */
internal fun createRuleRig(originals: List<ByteArray>, nameOf: (CaseBody) -> String? = ::instrumentEverything) =
    ExecutionRig(originals) { transformSwitchingProvider(it, nameOf) }

/** A create rule rig over the checked-in fixtures with the given file names. */
internal fun rigOf(vararg fixtures: String) = createRuleRig(fixtures.map(::fixtureBytes))
