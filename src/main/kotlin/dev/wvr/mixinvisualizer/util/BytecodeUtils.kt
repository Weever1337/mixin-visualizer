package dev.wvr.mixinvisualizer.util

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.java.decompiler.main.Fernflower
import org.jetbrains.java.decompiler.main.extern.IBytecodeProvider
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IResultSaver
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.util.TraceClassVisitor
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.jar.Manifest

object BytecodeUtils {
    fun readBytecode(file: VirtualFile): ByteArray {
        return file.contentsToByteArray()
    }

    fun readClassNode(bytes: ByteArray): ClassNode {
        val reader = ClassReader(bytes)
        val node = ClassNode()
        reader.accept(node, 0)
        return node
    }

    fun writeClassNode(node: ClassNode): ByteArray {
        val writer = object : ClassWriter(COMPUTE_FRAMES) {
            override fun getCommonSuperClass(type1: String, type2: String): String {
                return try {
                    super.getCommonSuperClass(type1, type2)
                } catch (e: Exception) {
                    "java/lang/Object"
                }
            }
        }

        try {
            node.accept(writer)
            return writer.toByteArray()
        } catch (_: Throwable) {
            val simpleWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)
            node.accept(simpleWriter)
            return simpleWriter.toByteArray()
        }
    }

    fun decompile(className: String, bytes: ByteArray): String {
        return try {
            runFernflower(className, bytes)
        } catch (e: Throwable) {
            toAsmTrace(bytes, "DECOMPILER ERROR: ${e.message}")
        }
    }

    fun toAsmTrace(bytes: ByteArray, headerMessage: String = ""): String {
        return try {
            val reader = ClassReader(bytes)
            val sw = StringWriter()
            if (headerMessage.isNotEmpty()) sw.append("// $headerMessage\n\n")
            reader.accept(TraceClassVisitor(PrintWriter(sw)), 0)
            sw.toString()
        } catch (e: Exception) {
            "// Error reading bytecode: ${e.message}"
        }
    }

    private fun runFernflower(className: String, bytes: ByteArray): String {
        var result = ""
        val saver = object : IResultSaver {
            override fun saveFolder(path: String) {}
            override fun copyFile(source: String, path: String, entryName: String) {}
            override fun createArchive(path: String, archiveName: String, manifest: Manifest) {}
            override fun saveDirEntry(path: String, archiveName: String, entryName: String) {}
            override fun copyEntry(source: String, path: String, archiveName: String, entry: String) {}
            override fun saveClassEntry(
                path: String?,
                archiveName: String?,
                qualifiedName: String?,
                entryName: String?,
                content: String?
            ) {
                if (entryName?.endsWith(".class") == true && entryName.contains(className.substringAfterLast('/'))) {
                    result = content!!
                }
            }

            override fun closeArchive(path: String, archiveName: String) {}

            override fun saveClassFile(path: String, qualifiedName: String, entryName: String, content: String, mapping: IntArray?) {
                result = content
            }
        }

        val provider = IBytecodeProvider { _, _ -> bytes }
        val logger = object : IFernflowerLogger() {
            override fun writeMessage(message: String, severity: Severity) {}
            override fun writeMessage(message: String, severity: Severity, t: Throwable) {}
        }

        val options = mapOf(
            "rbr" to "0",    // hide bridge methods
            "rsy" to "0",    // hide synthetic members
            "ind" to "    ", // 4 spaces
            "bto" to "1",    // bytecode-to-object only (memory)
            "nco" to "1",    // pattern matching instanceOf (cleaner code)
            "hdc" to "0"     // hide empty default constructor
        )

        val fernflower = Fernflower(provider, saver, options, logger)
        fernflower.addSource(File("$className.class"))
        fernflower.decompileContext()

        return result.ifEmpty { "// Decompiled content was empty or class mismatch." }
    }
}