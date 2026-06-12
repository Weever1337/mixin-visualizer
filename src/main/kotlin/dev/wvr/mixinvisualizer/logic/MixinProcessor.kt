package dev.wvr.mixinvisualizer.logic

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import dev.wvr.mixinvisualizer.util.BytecodeUtils
import org.objectweb.asm.Handle
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodNode
import java.io.File

class MixinProcessor(private val project: Project) {
    private val transformer = MixinTransformer()

    private data class ResolvedBytecode(
        val targetRef: String,
        val targetBytes: ByteArray,
        val mixinBytes: ByteArray?,
        val allMixinBytes: List<ByteArray>
    )

    companion object {
        private const val MIXIN_ANNOTATION = "org.spongepowered.asm.mixin.Mixin"
        private const val DEFAULT_PRIORITY = 1000

        // variant: 0=original full, 1=transformed full, 2=original compact, 3=transformed compact
        private data class CacheKey(val hash: Long, val showBytecode: Boolean, val variant: Int)

        private const val CACHE_LIMIT = 48
        private val renderCache = object : LinkedHashMap<CacheKey, String>(CACHE_LIMIT, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, String>): Boolean {
                return size > CACHE_LIMIT
            }
        }

        private fun cacheGet(key: CacheKey): String? = synchronized(renderCache) { renderCache[key] }

        private fun cachePut(key: CacheKey, content: String) {
            synchronized(renderCache) { renderCache[key] = content }
        }
    }

    fun process(
        mixinFile: VirtualFile,
        showBytecode: Boolean,
        applyAll: Boolean = false,
        compact: Boolean = false
    ): Pair<String, String> {
        val resolved = try {
            resolveBytecode(mixinFile, applyAll)
        } catch (e: ProcessError) {
            return e.original to e.message!!
        } catch (e: Throwable) {
            return "" to "// Error: ${e.message}\n${e.stackTraceToString()}"
        }

        return try {
            val mixinBytes = resolved.mixinBytes
                ?: return renderOriginalFull(resolved, showBytecode) to "// PLEASE COMPILE THE PROJECT FIRST (Ctrl+F9)"

            val mixins = if (applyAll && resolved.allMixinBytes.isNotEmpty()) resolved.allMixinBytes
            else listOf(mixinBytes)

            var combinedHash = contentKey(resolved.targetBytes)
            for (m in mixins) combinedHash = combinedHash * 31 + contentKey(m)

            if (!compact) {
                val original = renderOriginalFull(resolved, showBytecode)
                val transformed = cacheGet(CacheKey(combinedHash, showBytecode, 1)) ?: run {
                    val (node, _) = applyMixins(resolved.targetBytes, mixins)
                    val content = render(resolved.targetRef, BytecodeUtils.writeClassNode(node), showBytecode)
                    cachePut(CacheKey(combinedHash, showBytecode, 1), content)
                    content
                }
                return original to transformed
            }

            val origKey = CacheKey(combinedHash, showBytecode, 2)
            val transKey = CacheKey(combinedHash, showBytecode, 3)
            val cachedOrig = cacheGet(origKey)
            val cachedTrans = cacheGet(transKey)
            if (cachedOrig != null && cachedTrans != null) return cachedOrig to cachedTrans

            val originalNode = BytecodeUtils.readClassNode(resolved.targetBytes)
            val (transformedNode, affected) = applyMixins(resolved.targetBytes, mixins)

            if (affected.methods.isEmpty() && affected.fields.isEmpty()) {
                val original = renderOriginalFull(resolved, showBytecode)
                val transformed = render(resolved.targetRef, BytecodeUtils.writeClassNode(transformedNode), showBytecode)
                return original to "// No injections were applied (compact view has nothing to show)\n\n$transformed"
            }

            prune(originalNode, affected)
            prune(transformedNode, affected)

            val original = render(resolved.targetRef, BytecodeUtils.writeClassNode(originalNode), showBytecode)
            val transformed = render(resolved.targetRef, BytecodeUtils.writeClassNode(transformedNode), showBytecode)
            cachePut(origKey, original)
            cachePut(transKey, transformed)
            original to transformed
        } catch (e: Throwable) {
            "" to "// Error: ${e.message}\n${e.stackTraceToString()}"
        }
    }

    private fun renderOriginalFull(resolved: ResolvedBytecode, showBytecode: Boolean): String {
        val key = CacheKey(contentKey(resolved.targetBytes), showBytecode, 0)
        cacheGet(key)?.let { return it }
        val content = render(resolved.targetRef, resolved.targetBytes, showBytecode)
        cachePut(key, content)
        return content
    }

    private class AffectedMembers(val methods: Set<String>, val fields: Set<String>)

    private fun applyMixins(targetBytes: ByteArray, mixins: List<ByteArray>): Pair<ClassNode, AffectedMembers> {
        val node = BytecodeUtils.readClassNode(targetBytes)

        val methodsBefore = node.methods.associate { methodKey(it) to methodFingerprint(it) }
        val fieldsBefore = node.fields.map { it.name + ":" + it.desc }.toSet()

        for (mixinBytes in mixins) {
            transformer.transform(node, BytecodeUtils.readClassNode(mixinBytes))
        }

        val affectedMethods = node.methods
            .filter { methodsBefore[methodKey(it)] != methodFingerprint(it) }
            .map { methodKey(it) }
            .toSet()
        val affectedFields = node.fields
            .map { it.name + ":" + it.desc }
            .filter { it !in fieldsBefore }
            .toSet()

        return node to AffectedMembers(affectedMethods, affectedFields)
    }

    private fun methodKey(m: MethodNode) = m.name + m.desc

    private fun methodFingerprint(m: MethodNode): Long {
        var h = (m.access.toLong() * 31 + m.instructions.size())
        var insn = m.instructions.first
        while (insn != null) {
            if (insn.opcode != -1) h = h * 31 + insn.opcode
            insn = insn.next
        }
        return h
    }

    private fun prune(node: ClassNode, affected: AffectedMembers) {
        val keep = HashSet(affected.methods)
        val byKey = node.methods.associateBy { methodKey(it) }
        val queue = ArrayDeque(keep)
        while (queue.isNotEmpty()) {
            val method = byKey[queue.removeFirst()] ?: continue
            var insn = method.instructions.first
            while (insn != null) {
                if (insn is InvokeDynamicInsnNode) {
                    for (arg in insn.bsmArgs) {
                        if (arg is Handle && arg.owner == node.name) {
                            val key = arg.name + arg.desc
                            if (keep.add(key)) queue.add(key)
                        }
                    }
                }
                insn = insn.next
            }
        }

        node.methods.retainAll { methodKey(it) in keep }
        node.fields.retainAll { (it.name + ":" + it.desc) in affected.fields }
    }

    private fun render(targetRef: String, bytes: ByteArray, showBytecode: Boolean): String {
        return if (showBytecode) BytecodeUtils.toAsmTrace(bytes)
        else BytecodeUtils.decompile(targetRef, bytes)
    }

    private fun contentKey(bytes: ByteArray): Long {
        var h = 1125899906842597L
        for (b in bytes) h = 31 * h + b
        return h * 31 + bytes.size
    }

    private class ProcessError(val original: String, message: String) : RuntimeException(message)

    private fun resolveBytecode(vFile: VirtualFile, applyAll: Boolean): ResolvedBytecode {
        return DumbService.getInstance(project).runReadActionInSmartMode<ResolvedBytecode> {
            val freshPsi = PsiManager.getInstance(project).findFile(vFile) as? PsiJavaFile
                ?: throw ProcessError("Err", "// Not a java file")

            val clazz = freshPsi.classes.firstOrNull()
                ?: throw ProcessError("", "// Class not found")
            val targetRef = findTargetClasses(clazz).firstOrNull()
                ?: throw ProcessError("", "// No @Mixin annotation")

            val targetPsi = JavaPsiFacade.getInstance(project)
                .findClass(targetRef, GlobalSearchScope.allScope(project))
                ?: throw ProcessError("", "// Target $targetRef not found")

            val targetBytes = findBytecode(targetPsi)
                ?: throw ProcessError("", "// Original bytecode not found")

            val mixinBytes = findBytecode(clazz)
            val allMixins = if (applyAll) findAllMixinBytes(targetRef, clazz, mixinBytes) else emptyList()

            ResolvedBytecode(targetRef, targetBytes, mixinBytes, allMixins)
        }
    }

    private fun findAllMixinBytes(
        targetRef: String,
        currentMixin: PsiClass,
        currentMixinBytes: ByteArray?
    ): List<ByteArray> {
        data class Entry(val priority: Int, val name: String, val bytes: ByteArray)

        val entries = mutableListOf<Entry>()
        val mixinAnnotation = JavaPsiFacade.getInstance(project)
            .findClass(MIXIN_ANNOTATION, GlobalSearchScope.allScope(project))

        if (mixinAnnotation != null) {
            val found = AnnotatedElementsSearch
                .searchPsiClasses(mixinAnnotation, GlobalSearchScope.projectScope(project))
                .findAll()
            for (cls in found) {
                if (targetRef !in findTargetClasses(cls)) continue
                val bytes = findBytecode(cls) ?: continue
                entries.add(Entry(findMixinPriority(cls), cls.qualifiedName ?: "", bytes))
            }
        }

        if (currentMixinBytes != null && entries.none { it.bytes.contentEquals(currentMixinBytes) }) {
            entries.add(Entry(findMixinPriority(currentMixin), currentMixin.qualifiedName ?: "", currentMixinBytes))
        }

        return entries.sortedWith(compareBy({ it.priority }, { it.name })).map { it.bytes }
    }

    private fun findMixinPriority(mixinClass: PsiClass): Int {
        val ann = mixinClass.getAnnotation(MIXIN_ANNOTATION) ?: return DEFAULT_PRIORITY
        val value = ann.findAttributeValue("priority")
        return (value as? PsiLiteralExpression)?.value as? Int ?: DEFAULT_PRIORITY
    }

    private fun findTargetClasses(mixinClass: PsiClass): List<String> {
        val ann = mixinClass.getAnnotation(MIXIN_ANNOTATION) ?: return emptyList()
        val result = mutableListOf<String>()

        fun add(value: PsiAnnotationMemberValue?) {
            when (value) {
                is PsiClassObjectAccessExpression -> result.add(value.operand.type.canonicalText)
                is PsiLiteralExpression -> (value.value as? String)?.let { result.add(it.replace('/', '.')) }
                is PsiArrayInitializerMemberValue -> value.initializers.forEach { add(it) }
                else -> {}
            }
        }

        add(ann.findAttributeValue("value"))
        add(ann.findAttributeValue("targets"))
        return result
    }

    private fun findBytecode(psiClass: PsiClass): ByteArray? {
        val vFile = psiClass.containingFile?.virtualFile ?: return null
        if (vFile.fileType.isBinary) return vFile.contentsToByteArray()

        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        val module = fileIndex.getModuleForFile(vFile) ?: return null

        val pkg = (psiClass.containingFile as? PsiClassOwner)?.packageName ?: ""
        val binaryName = getBinaryName(psiClass)
        val relPath = pkg.replace('.', '/') + "/" + binaryName + ".class"

        val compilerExt = CompilerModuleExtension.getInstance(module)
        if (compilerExt != null) {
            for (output in listOfNotNull(compilerExt.compilerOutputPath, compilerExt.compilerOutputPathForTests)) {
                val file = File(output.path, relPath)
                if (file.exists()) return file.readBytes()
            }
        }

        val sourceRoot = fileIndex.getSourceRootForFile(vFile)
        if (sourceRoot != null) {
            for (root in gradleOutputCandidates(sourceRoot.path)) {
                val file = File(root, relPath)
                if (file.exists()) return file.readBytes()
            }
        }
        return null
    }

    private fun gradleOutputCandidates(sourceRootPath: String): List<String> {
        val marker = "/src/"
        val normalized = sourceRootPath.replace('\\', '/')
        val idx = normalized.lastIndexOf(marker)
        if (idx == -1) return emptyList()

        val base = normalized.substring(0, idx)
        val parts = normalized.substring(idx + marker.length).split('/')
        val sourceSet = parts.getOrNull(0) ?: return emptyList()
        val lang = parts.getOrNull(1)

        val langs = if (lang != null) listOf(lang, "java", "kotlin") else listOf("java", "kotlin")
        return langs.distinct().map { "$base/build/classes/$it/$sourceSet" }
    }

    private fun getBinaryName(psiClass: PsiClass): String {
        val parts = mutableListOf<String>()
        var current: PsiClass? = psiClass
        while (current != null) {
            parts.add(current.name ?: "")
            current = current.containingClass
        }
        return parts.reversed().joinToString("$")
    }
}
