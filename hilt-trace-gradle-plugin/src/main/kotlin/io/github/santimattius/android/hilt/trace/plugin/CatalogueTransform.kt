package io.github.santimattius.android.hilt.trace.plugin

import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath

/** Artifact type produced by [CatalogueTransform]: a directory holding `META-INF/hilt-trace/<name>.json`. */
internal const val CATALOGUE_ARTIFACT_TYPE = "hilt-trace-catalogue"

/** Reduces one classpath entry (jar or classes directory) to the catalogue files it contains. */
@CacheableTransform
internal abstract class CatalogueTransform : TransformAction<TransformParameters.None> {

    @get:InputArtifact
    @get:Classpath
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        extractCatalogues(inputArtifact.get().asFile, outputs.dir("catalogues"))?.let(Logging.getLogger(CatalogueTransform::class.java)::warn)
    }
}
