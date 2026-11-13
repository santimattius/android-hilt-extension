package io.github.santimattius.android.hilt.trace.compiler

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.github.santimattius.android.hilt.trace.compiler.catalogue.Binding
import io.github.santimattius.android.hilt.trace.compiler.catalogue.BindingKind
import io.github.santimattius.android.hilt.trace.compiler.catalogue.CATALOGUE_DIRECTORY
import io.github.santimattius.android.hilt.trace.compiler.catalogue.CATALOGUE_EXTENSION
import io.github.santimattius.android.hilt.trace.compiler.catalogue.Catalogue
import io.github.santimattius.android.hilt.trace.compiler.catalogue.EntryPoint
import io.github.santimattius.android.hilt.trace.compiler.catalogue.InjectionTarget
import io.github.santimattius.android.hilt.trace.compiler.catalogue.JsonWriter
import io.github.santimattius.android.hilt.trace.compiler.catalogue.catalogueFileStem
import java.security.MessageDigest

/** Registers [CatalogueProcessor] through `META-INF/services` (KSP only, no kapt). */
public class CatalogueProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        CatalogueProcessor(environment)
}

private const val MODULE_OPTION = "hiltTrace.module"
private const val CONSTRUCTOR = "<init>"
private const val ENTRY_POINT = "dagger.hilt.EntryPoint"
private const val INSTALL_IN = "dagger.hilt.InstallIn"
private const val ANDROID_ENTRY_POINT = "dagger.hilt.android.AndroidEntryPoint"
private val INJECT = listOf("javax.inject.Inject", "jakarta.inject.Inject")
private val QUALIFIER = setOf("javax.inject.Qualifier", "jakarta.inject.Qualifier")
private val SCOPE = setOf("javax.inject.Scope", "jakarta.inject.Scope")

/**
 * Scans Dagger and Hilt declarations and writes one `META-INF/hilt-trace/<module>.json` per module.
 * Annotations are matched by qualified name, so the processor needs no Dagger or Hilt dependency.
 * The file is written in [finish], once, and is written even when nothing matched.
 */
internal class CatalogueProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private val bindings = linkedSetOf<Binding>()
    private val entryPoints = linkedSetOf<EntryPoint>()
    private val targets = linkedSetOf<InjectionTarget>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        functions(resolver, "dagger.Provides").forEach { methodBinding(BindingKind.PROVIDES, it)?.let(bindings::add) }
        functions(resolver, "dagger.Binds").forEach { methodBinding(BindingKind.BINDS, it)?.let(bindings::add) }
        INJECT.forEach { annotation ->
            constructors(resolver, annotation).forEach { constructorBinding(BindingKind.INJECT, it)?.let(bindings::add) }
        }
        constructors(resolver, "dagger.assisted.AssistedInject")
            .forEach { constructorBinding(BindingKind.ASSISTED_INJECT, it)?.let(bindings::add) }
        classes(resolver, ENTRY_POINT).forEach { entryPointsOf(resolver, it) }
        classes(resolver, ANDROID_ENTRY_POINT).forEach { declaration ->
            declaration.qualifiedName?.asString()?.let { targets += InjectionTarget(it) }
        }
        return emptyList()
    }

    override fun finish() {
        val catalogue = Catalogue(
            module = moduleId(),
            bindings = bindings.sortedWith(compareBy({ it.owner }, { it.method }, { it.kind })),
            entryPoints = entryPoints.sortedWith(compareBy({ it.interfaceName }, { it.method }, { it.descriptor })),
            injectionTargets = targets.sortedBy { it.type },
        )
        environment.codeGenerator
            .createNewFileByPath(Dependencies.ALL_FILES, "$CATALOGUE_DIRECTORY/${catalogueFileStem(catalogue.module)}", CATALOGUE_EXTENSION)
            .bufferedWriter()
            .use { it.write(JsonWriter.write(catalogue)) }
    }

    /** The `hiltTrace.module` option, or a stable hash of the processed symbol names plus a warning. */
    private fun moduleId(): String {
        environment.options[MODULE_OPTION]?.takeIf { it.isNotBlank() }?.let { return it }
        val names = bindings.map { "${it.kind}:${it.owner}.${it.method}" } +
            entryPoints.map { "${it.interfaceName}.${it.method}${it.descriptor}" } +
            targets.map { it.type }
        val digest = MessageDigest.getInstance("SHA-256").digest(names.sorted().joinToString("\n").toByteArray())
        val id = "unnamed-" + digest.take(4).joinToString("") { "%02x".format(it) }
        environment.logger.warn(
            "KSP argument $MODULE_OPTION is not set; using the generated module id '$id'. " +
                "Set ksp { arg(\"$MODULE_OPTION\", \"<module path>\") } for a stable catalogue name.",
        )
        return id
    }

    private fun functions(resolver: Resolver, annotation: String) =
        resolver.getSymbolsWithAnnotation(annotation).filterIsInstance<KSFunctionDeclaration>()

    private fun constructors(resolver: Resolver, annotation: String) =
        functions(resolver, annotation).filter { it.simpleName.asString() == CONSTRUCTOR }

    private fun classes(resolver: Resolver, annotation: String) =
        resolver.getSymbolsWithAnnotation(annotation).filterIsInstance<KSClassDeclaration>()

    private fun methodBinding(kind: BindingKind, function: KSFunctionDeclaration): Binding? {
        val type = function.returnType?.resolve()?.declaration?.qualifiedName?.asString() ?: return null
        return Binding(
            kind = kind,
            type = type,
            qualifier = function.annotationsMarkedBy(QUALIFIER).firstOrNull()?.render(),
            scope = function.annotationsMarkedBy(SCOPE).firstOrNull()?.qualifiedName(),
            owner = function.parentDeclaration?.qualifiedName?.asString().orEmpty(),
            method = function.simpleName.asString(),
        )
    }

    private fun constructorBinding(kind: BindingKind, constructor: KSFunctionDeclaration): Binding? {
        val owner = constructor.parentDeclaration as? KSClassDeclaration ?: return null
        val name = owner.qualifiedName?.asString() ?: return null
        return Binding(
            kind = kind,
            type = name,
            qualifier = null,
            scope = owner.annotationsMarkedBy(SCOPE).firstOrNull()?.qualifiedName(),
            owner = name,
            method = CONSTRUCTOR,
        )
    }

    // mapToJvmSignature is the only KSP source of the JVM descriptor; its experimental status is contained here.
    @OptIn(KspExperimental::class)
    private fun entryPointsOf(resolver: Resolver, declaration: KSClassDeclaration) {
        val interfaceName = declaration.qualifiedName?.asString() ?: return
        val component = installInComponents(declaration)
        declaration.getDeclaredFunctions().forEach { function ->
            val descriptor = resolver.mapToJvmSignature(function) ?: return@forEach
            entryPoints += EntryPoint(interfaceName, function.simpleName.asString(), descriptor, component)
        }
    }

    private fun installInComponents(declaration: KSClassDeclaration): String =
        declaration.annotations.firstOrNull { it.qualifiedName() == INSTALL_IN }
            ?.arguments.orEmpty()
            .flatMap { argument ->
                when (val value = argument.value) {
                    is KSType -> listOf(value)
                    is List<*> -> value.filterIsInstance<KSType>()
                    else -> emptyList()
                }
            }
            .mapNotNull { it.declaration.qualifiedName?.asString() }
            .joinToString(",")

    private fun KSAnnotation.qualifiedName(): String? =
        annotationType.resolve().declaration.qualifiedName?.asString()

    /** Annotations on this element whose annotation class carries one of the [markers]. */
    private fun KSAnnotated.annotationsMarkedBy(markers: Set<String>): List<KSAnnotation> =
        annotations.filter { annotation ->
            annotation.annotationType.resolve().declaration.annotations.any { it.qualifiedName() in markers }
        }.toList()

    /** Qualified name, plus the explicit arguments when present, for example `javax.inject.Named(value=io)`. */
    private fun KSAnnotation.render(): String {
        val name = qualifiedName().orEmpty()
        if (arguments.isEmpty()) return name
        return arguments.joinToString(", ", "$name(", ")") { "${it.name?.asString()}=${it.value}" }
    }
}
