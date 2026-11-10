package com.santimattius.android.hilt.trace.plugin.asm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.GOTO
import org.objectweb.asm.Opcodes.INVOKESTATIC
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

class CaseLocatorTest {

    private fun ClassNode.method(name: String): MethodNode = methods.single { it.name == name }

    private fun bodies(node: ClassNode, name: String): List<CaseBody> =
        (locateCaseBodies(node.method(name)) as Dispatch.Cases).bodies

    private val singleton = readClass(fixtureBytes("SingletonCImpl\$SwitchingProvider"))

    @Test
    fun `split methods give one body per tableswitch id and the router is recognized`() {
        assertEquals(100, bodies(singleton, "get0").size)
        assertEquals(25, bodies(singleton, "get1").size)
        assertSame(Dispatch.Router, locateCaseBodies(singleton.method("get")))
    }

    @Test
    fun `lookupswitch body is located from its label up to the return`() {
        val node = readClass(fixtureBytes("ActivityRetainedCImpl\$SwitchingProvider"))
        val body = bodies(node, "get").single()
        val call = generateSequence(body.label.next) { it.next }.filterIsInstance<MethodInsnNode>().first()
        assertEquals(INVOKESTATIC, call.opcode)
        assertEquals("provideActivityRetainedLifecycle", call.name)
        assertEquals(ARETURN, body.ret.opcode)
        assertTrue(generateSequence(body.label.next) { it.next }.takeWhile { it !== body.ret }.none { it.opcode == ARETURN })
    }

    @Test
    fun `bodies out of id order are matched by their jump table label`() {
        val node = readClass(syntheticProvider(caseCount = 3, layout = listOf(2, 0, 1)))
        val strings = bodies(node, "get").map { body ->
            generateSequence(body.label.next) { it.next }.filterIsInstance<LdcInsnNode>().first().cst
        }
        // The locator reports bodies in code layout order, each starting at its own label.
        assertEquals(listOf("case2", "case0", "case1"), strings)
        assertEquals(3, bodies(node, "get").map { it.label }.distinct().size)
    }

    @Test
    fun `tableswitch entries that jump to the default label are not case bodies`() {
        val node = readClass(syntheticProvider(caseCount = 3, holes = setOf(1)))
        val strings = bodies(node, "get").map { generateSequence(it.label.next) { n -> n.next }.filterIsInstance<LdcInsnNode>().first().cst }
        assertEquals(listOf("case0", "case2"), strings)
    }

    @Test
    fun `a body with a branch is an unrecognized shape`() {
        val node = readClass(syntheticProvider(caseCount = 2) { id ->
            val end = org.objectweb.asm.Label()
            visitJumpInsn(GOTO, end)
            visitLabel(end)
            visitLdcInsn("case$id")
            visitInsn(ARETURN)
        })
        assertSame(Dispatch.Unknown, locateCaseBodies(node.method("get")))
        assertNotSame(Dispatch.Router, locateCaseBodies(node.method("get")))
    }

    @Test
    fun `a method that does not switch on the id field is an unrecognized shape`() {
        assertSame(Dispatch.Unknown, locateCaseBodies(singleton.method("<init>")))
    }
}
