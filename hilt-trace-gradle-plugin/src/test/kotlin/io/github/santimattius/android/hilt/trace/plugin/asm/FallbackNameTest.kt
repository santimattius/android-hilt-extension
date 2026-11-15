package io.github.santimattius.android.hilt.trace.plugin.asm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ACONST_NULL
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.CHECKCAST
import org.objectweb.asm.Opcodes.DUP
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.Opcodes.INVOKESTATIC
import org.objectweb.asm.Opcodes.INVOKEVIRTUAL
import org.objectweb.asm.Opcodes.NEW

/** Which class names a case body whose binding is missing from the catalogue: the one that produces the returned value. */
class FallbackNameTest {

    private fun name(code: MethodVisitor.() -> Unit): String? {
        val node = readClass(syntheticProvider(caseCount = 1) { code(); visitInsn(ARETURN) })
        val body = (locateCaseBodies(node.methods.single { it.name == "get" }) as Dispatch.Cases).bodies.single()
        return fallbackName(body)
    }

    private fun MethodVisitor.static(owner: String, desc: String = "()Ljava/lang/Object;") =
        visitMethodInsn(INVOKESTATIC, owner, "make", desc, false)

    private fun MethodVisitor.construct(type: String, args: MethodVisitor.() -> Unit = {}, desc: String = "()V") {
        visitTypeInsn(NEW, type)
        visitInsn(DUP)
        args()
        visitMethodInsn(INVOKESPECIAL, type, "<init>", desc, false)
    }

    @Test
    fun `a static factory called with another static factory is named after the outer one`() {
        assertEquals("p.OuterFactory", name {
            static("p/ArgFactory")
            static("p/OuterFactory", "(Ljava/lang/Object;)Ljava/lang/Object;")
        })
    }

    @Test
    fun `a constructor called with a static factory is named after the constructed type`() {
        assertEquals("p.Foo", name {
            construct("p/Foo", { static("p/ArgFactory") }, "(Ljava/lang/Object;)V")
        })
    }

    @Test
    fun `a static factory called with a new object is named after the factory`() {
        assertEquals("p.OuterFactory", name {
            construct("p/Bar")
            static("p/OuterFactory", "(Ljava/lang/Object;)Ljava/lang/Object;")
        })
    }

    @Test
    fun `a constructor called with a new object is named after the outer type`() {
        assertEquals("p.Foo", name {
            construct("p/Foo", { construct("p/Bar") }, "(Ljava/lang/Object;)V")
        })
    }

    @Test
    fun `a cast after the producing call does not change the name`() {
        assertEquals("p.OuterFactory", name {
            static("p/ArgFactory")
            static("p/OuterFactory", "(Ljava/lang/Object;)Ljava/lang/Object;")
            visitTypeInsn(CHECKCAST, "p/Thing")
        })
    }

    @Test
    fun `an assisted factory case is named after the anonymous factory it constructs`() {
        assertEquals("p.Outer\$1", name { construct("p/Outer\$1", { visitInsn(ACONST_NULL) }, "(Ljava/lang/Object;)V") })
    }

    @Test
    fun `any other terminal instruction keeps the first new else first static rule`() {
        assertEquals("p.Foo", name {
            construct("p/Foo")
            visitMethodInsn(INVOKEVIRTUAL, "p/Foo", "build", "()Ljava/lang/Object;", false)
        })
        assertEquals("p.FirstFactory", name {
            static("p/FirstFactory")
            visitMethodInsn(INVOKEVIRTUAL, "p/Foo", "build", "()Ljava/lang/Object;", false)
        })
    }

    @Test
    fun `a body with neither a new nor a static call has no name`() {
        assertNull(name { visitLdcInsn("constant") })
    }
}
