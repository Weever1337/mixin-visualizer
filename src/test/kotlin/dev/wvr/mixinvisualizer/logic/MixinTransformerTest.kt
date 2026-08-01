package dev.wvr.mixinvisualizer.logic

import dev.wvr.mixinvisualizer.util.BytecodeUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.BasicValue
import org.objectweb.asm.tree.analysis.BasicVerifier

class MixinTransformerTest {
    @Test
    fun redirectCallKeepsReceiver() {
        val code = apply("RedirectCall")
        assertFalse(code.contains("<unknown>"))
        assertContains(code, "helper.hashCode() > 0")
    }

    @Test
    fun redirectStaticHandler() {
        val code = apply("RedirectStatic")
        assertFalse(code.contains("this + 1000"))
        assertContains(code, "+ 1000")
    }

    @Test
    fun redirectCapturesTargetArgs() {
        assertContains(apply("RedirectCapture"), "+ mult")
    }

    @Test
    fun redirectFieldWrite() {
        val code = apply("RedirectField")
        assertContains(code, "this.counter + 20")
        assertContains(code, "System.out.println(")
    }

    @Test
    fun modifyConstantByValue() {
        val code = apply("ConstantInt")
        assertContains(code, "this.moreCounter(20)")
        assertContains(code, "local = 5;")
    }

    @Test
    fun modifyConstantFloat() {
        assertContains(apply("ConstantFloat"), "this.half(0.5F)")
    }

    @Test
    fun modifyConstantByType() {
        assertContains(apply("ConstantByType"), "this.anyInt(2)")
    }

    @Test
    fun modifyVariableByOrdinal() {
        val code = apply("VariableByOrdinal")
        assertContains(code, "result = this.bumpResult(result);")
        assertFalse(code.contains("(int)s"))
    }

    @Test
    fun modifyVariableByName() {
        assertContains(apply("VariableByName"), "s = this.shout(s);")
    }

    @Test
    fun modifyVariableHeadArg() {
        val code = apply("VariableHeadArg")
        assertContains(code, "base = this.doubleBase(base);")
        assertTrue(code.indexOf("this.doubleBase(base)") < code.indexOf("base * 0.5F"))
    }

    @Test
    fun modifyVariableAmbiguousIsSkipped() {
        assertFalse(apply("VariableAmbiguous").contains("this.ambiguous("))
    }

    @Test
    fun injectHead() {
        val code = apply("InjectHead")
        assertContains(code, "System.out.println(\"head\");")
        assertTrue(code.indexOf("println(\"head\")") < code.indexOf("int local = 5;"))
    }

    @Test
    fun injectReturnValue() {
        val code = apply("ReturnPeek")
        assertFalse(code.contains("cir"))
        assertContains(code, "return result > 10 ? 10 : result;")
    }

    @Test
    fun injectCancelKeepsRunningHandler() {
        val code = apply("CancelThenWork")
        val println = code.indexOf("println(\"after cancel\")")
        assertTrue(println in 0 until code.indexOf("int local = 5;"))
        assertFalse(code.substring(code.indexOf("public void tick()"), println).contains("else"))
    }

    @Test
    fun injectCancelAtTheEndReturnsRightAway() {
        val code = apply("CancelTail")
        assertContains(code, "System.out.println(\"stop\");")
        assertFalse(code.contains("boolean"))
    }

    @Test
    fun injectSetReturnValueKeepsRunningHandler() {
        val code = apply("SetReturnThenWork")
        assertContains(code, "System.out.println(\"still here\");")
        assertTrue(code.indexOf("println(\"still here\")") < code.indexOf("input * 2"))
    }

    @Test
    fun injectInvokeAssign() {
        val code = apply("AfterAssign")
        assertTrue(code.indexOf("Helper.scale(mult);") < code.indexOf("println(\"after scale\")"))
        assertTrue(code.indexOf("println(\"after scale\")") < code.indexOf("return f * i;"))
    }

    @Test
    fun injectLocalCapture() {
        val code = apply("CaptureLocals")
        assertContains(code, "System.out.println(local);")
    }

    @Test
    fun modifyArg() {
        assertContains(apply("ModArg"), "this.helper.doWork(this.modArg(local));")
    }

    @Test
    fun modifyExpressionValueWithLocal() {
        val code = apply("ExprValue")
        assertContains(code, "this.helper.isReady()")
        assertContains(code, "this.ready(")
        assertContains(code, ", local)")
    }

    @Test
    fun modifyReturnValue() {
        assertContains(apply("ReturnValue"), "this.doubled(")
    }

    @Test
    fun wrapWithCondition() {
        val code = apply("WrapCondition")
        assertContains(code, "if (this.onlyIf(")
        assertContains(code, ".doWork(local);")
    }

    @Test
    fun wrapOperation() {
        assertContains(apply("WrapOp"), "this.wrapWork(")
    }

    @Test
    fun wrapMethod() {
        val code = apply("WrapWhole")
        assertContains(code, "return this.wrapValue(")
        assertContains(code, "getValue\$original(int input)")
    }

    @Test
    fun overwrite() {
        val code = apply("OverwriteValue")
        assertContains(code, "return input + input;")
        assertFalse(code.contains("s.length()"))
    }

    @Test
    fun uniqueFieldWithInitializer() {
        val code = apply("UniqueField")
        assertContains(code, "bonus = 42")
        assertContains(code, "int bonus()")
    }

    private fun apply(vararg mixins: String): String {
        val target = BytecodeUtils.readClassNode(classBytes("fixtures/Target"))
        for (mixin in mixins) {
            MixinTransformer().transform(target, BytecodeUtils.readClassNode(classBytes("fixtures/Mixins\$$mixin")))
        }
        val bytes = BytecodeUtils.writeClassNode(target)
        verify(bytes)
        return BytecodeUtils.decompile("fixtures.Target", bytes)
    }

    private fun verify(bytes: ByteArray) {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        for (method in node.methods) {
            try {
                Analyzer<BasicValue>(BasicVerifier()).analyze(node.name, method)
            } catch (e: AnalyzerException) {
                fail("broken bytecode in ${method.name}${method.desc}: ${e.message}")
            }
        }
    }

    private fun classBytes(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("$name.class")!!.use { it.readBytes() }

    private fun assertContains(code: String, part: String) {
        assertTrue("expected `$part` in:\n$code", code.contains(part))
    }
}
