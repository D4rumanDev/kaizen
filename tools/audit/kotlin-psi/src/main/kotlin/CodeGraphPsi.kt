import com.google.gson.GsonBuilder
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.lexer.KtTokens
import java.io.File

private data class RefData(val name: String, val line: Int, val offset: Int, val qualified: String?)
private data class DeclData(
    val id: String,
    val name: String,
    val kind: String,
    val fqName: String,
    val file: String,
    val line: Int,
    val endLine: Int,
    val startOffset: Int,
    val endOffset: Int,
    val visibility: String,
    val modifiers: List<String>,
    val annotations: List<String>,
    val parentId: String?,
    val signature: String,
    val refs: List<RefData>
)
private data class ImportData(val fqName: String, val alias: String?, val allUnder: Boolean)
private data class LocalData(val name: String, val line: Int, val kind: String, val owner: String, val uses: Int)
private data class FileData(
    val path: String,
    val packageName: String,
    val imports: List<ImportData>,
    val declarations: List<DeclData>,
    val locals: List<LocalData>,
    val references: List<String>,
    val syntaxErrors: List<String>
)
private data class OutputData(val parser: String, val kotlinPsiVersion: String, val files: List<FileData>)

private fun lineOf(element: PsiElement): Int = element.containingFile.viewProvider.document?.getLineNumber(element.textOffset)?.plus(1) ?: 1
private fun endLineOf(element: PsiElement): Int = element.containingFile.viewProvider.document?.getLineNumber((element.textRange.endOffset - 1).coerceAtLeast(element.textOffset))?.plus(1) ?: lineOf(element)
private fun parentDeclaration(element: PsiElement?): KtDeclaration? {
    var p = element?.parent
    while (p != null) {
        if (p is KtDeclaration) return p
        p = p.parent
    }
    return null
}
private fun nearestTracked(element: PsiElement, ids: Map<KtNamedDeclaration, String>): KtNamedDeclaration? {
    var p = element.parent
    while (p != null) {
        if (p is KtNamedDeclaration && ids.containsKey(p)) return p
        p = p.parent
    }
    return null
}
private fun kindOf(d: KtNamedDeclaration): String = when (d) {
    is KtNamedFunction -> "fun"
    is KtProperty -> "property"
    is KtClass -> if (d.isInterface()) "interface" else if (d.hasModifier(KtTokens.ENUM_KEYWORD)) "enum" else if (d.hasModifier(KtTokens.ANNOTATION_KEYWORD)) "annotation" else "class"
    is KtObjectDeclaration -> if (d.isCompanion()) "companion" else "object"
    is KtTypeAlias -> "typealias"
    is KtSecondaryConstructor -> "constructor"
    else -> d::class.simpleName ?: "declaration"
}
private fun modifiersOf(d: KtModifierListOwner): List<String> = d.modifierList?.text?.let { text ->
    Regex("[A-Za-z]+").findAll(text).map { it.value }.distinct().toList()
} ?: emptyList()
private fun annotationsOf(d: KtModifierListOwner): List<String> = d.annotationEntries.mapNotNull { it.shortName?.asString() }.distinct()
private fun signatureOf(d: KtNamedDeclaration): String = d.text.lineSequence().firstOrNull()?.trim()?.take(300) ?: d.name.orEmpty()

@OptIn(CompilerConfiguration.Internals::class, K1Deprecation::class)
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: CodeGraphPsi <projectRoot> <output.json>" }
    val root = File(args[0]).canonicalFile
    val output = File(args[1]).canonicalFile
    val sourceRoots = listOf("app/src")
        .map { File(root, it) }.filter { it.isDirectory }
    val files = sourceRoots.flatMap { base -> base.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        .distinctBy { it.canonicalPath }.sortedBy { it.path }

    val disposable = Disposer.newDisposable("code-graph-psi")
    try {
        val configuration = CompilerConfiguration()
        val environment = KotlinCoreEnvironment.createForProduction(
            disposable,
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        )
        val psiFactory = KtPsiFactory(environment.project, false)
        val results = files.map { source ->
            // IntelliJ PSI documents require consistent line separators. Real-world
            // projects may contain mixed CRLF/LF/CR endings, so normalize before parsing.
            val content = source.readText(Charsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
            val virtualName = source.name
            val ktFile = psiFactory.createFile(virtualName, content)
            val relative = root.toPath().relativize(source.canonicalFile.toPath()).toString().replace(File.separatorChar, '/')
            val declarations: List<KtNamedDeclaration> = PsiTreeUtil
                .findChildrenOfType(ktFile, KtNamedDeclaration::class.java)
                .filter { declaration ->
                    declaration.name != null &&
                        declaration !is KtParameter &&
                        declaration !is KtPropertyAccessor &&
                        declaration !is KtEnumEntry
                }
                .sortedBy { declaration -> declaration.textOffset }
                .toList()
            val ids = declarations.mapIndexed { index, d -> d to "$relative#${d.textOffset}:$index" }.toMap()
            val declData: List<DeclData> = declarations.map { d: KtNamedDeclaration ->
                val id = ids.getValue(d)
                val parent = nearestTracked(d, ids)
                val parentId = parent?.let { ids[it] }
                val refs = PsiTreeUtil.findChildrenOfType(d, KtSimpleNameExpression::class.java)
                    // La declaración registrada más cercana: una referencia dentro de un valor por defecto, un accesor, un bloque init o una desestructuración
                    // (que no son nodos del grafo) cuenta para la declaración que los contiene, no se pierde.
                    .filter { nearestTracked(it, ids) === d }
                    .mapNotNull { ref ->
                        val name = ref.getReferencedName()
                        if (name.isBlank() || name == "this" || name == "super") null
                        else {
                            val q = ref.parent as? KtDotQualifiedExpression
                            RefData(name, lineOf(ref), ref.textOffset, q?.text?.take(250))
                        }
                    }.distinctBy { it.offset to it.name }
                val visibility = when {
                    d.hasModifier(KtTokens.PRIVATE_KEYWORD) -> "private"
                    d.hasModifier(KtTokens.PROTECTED_KEYWORD) -> "protected"
                    d.hasModifier(KtTokens.INTERNAL_KEYWORD) -> "internal"
                    else -> "public"
                }
                val ownerNames = generateSequence(d.parent) { it.parent }.filterIsInstance<KtNamedDeclaration>().mapNotNull { it.name }.toList().asReversed()
                val fq = (listOf(ktFile.packageFqName.asString()) + ownerNames + d.name!!).filter { it.isNotBlank() }.joinToString(".")
                DeclData(
                    id, d.name!!, kindOf(d), fq, relative,
                    lineOf(d), endLineOf(d), d.textOffset, d.textRange.endOffset,
                    visibility, modifiersOf(d), annotationsOf(d), parentId, signatureOf(d), refs
                )
            }
            val localData: List<LocalData> = PsiTreeUtil
                .findChildrenOfType(ktFile, KtNamedFunction::class.java)
                .flatMap { fn: KtNamedFunction ->
                val params = PsiTreeUtil.findChildrenOfType(fn, KtParameter::class.java).filter { parentDeclaration(it) === fn }
                val props = PsiTreeUtil.findChildrenOfType(fn, KtProperty::class.java).filter { parentDeclaration(it) === fn }
                val locals: List<Pair<KtNamedDeclaration, String>> =
                    params.map { parameter -> parameter as KtNamedDeclaration to "parameter" } +
                        props.map { property -> property as KtNamedDeclaration to "local" }
                locals.mapNotNull { (decl, kind) ->
                    val name = decl.name ?: return@mapNotNull null
                    val uses = PsiTreeUtil.findChildrenOfType(fn, KtSimpleNameExpression::class.java).count { it.getReferencedName() == name && it.textOffset != decl.textOffset }
                    LocalData(name, lineOf(decl), kind, fn.name ?: "<anonymous>", uses)
                }
            }
            // Parse import text instead of relying on compiler-version-specific PSI accessors.
            val imports: List<ImportData> = ktFile.importDirectives.mapNotNull { directive: KtImportDirective ->
                val importText = directive.text.trim()
                val match = Regex("^import\\s+([A-Za-z_][A-Za-z0-9_.]*)(\\.\\*)?(?:\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*))?$")
                    .matchEntire(importText) ?: return@mapNotNull null
                val fq = match.groupValues[1]
                ImportData(fq, match.groupValues[3].ifBlank { null }, match.groupValues[2].isNotEmpty())
            }
            val fileReferences = PsiTreeUtil.findChildrenOfType(ktFile, KtSimpleNameExpression::class.java)
                .filter { ref -> generateSequence(ref.parent) { it.parent }.none { it is KtImportDirective } }
                .map { it.getReferencedName() }.filter { it.isNotBlank() && it != "this" && it != "super" }.distinct()
            val errors = PsiTreeUtil.findChildrenOfType(ktFile, PsiErrorElement::class.java)
                .map { "${lineOf(it)}: ${it.errorDescription}" }
            FileData(relative, ktFile.packageFqName.asString(), imports, declData, localData, fileReferences, errors)
        }
        output.parentFile.mkdirs()
        output.writeText(GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(OutputData("Kotlin PSI", "2.4.10", results)), Charsets.UTF_8)
    } finally {
        Disposer.dispose(disposable)
    }
}
