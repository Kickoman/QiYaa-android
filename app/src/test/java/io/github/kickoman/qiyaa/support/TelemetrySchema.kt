package io.github.kickoman.qiyaa.support

import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.io.File

object TelemetrySchema {
    private val schema: JsonSchema by lazy {
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(File(Spec.root, "telemetry/schemas/batch.schema.json").readText())
    }

    fun errors(batch: String): List<String> {
        val messages = schema.validate(batch, InputFormat.JSON).map { it.message }
        return messages.sorted()
    }

    fun examples(): List<File> {
        val files = File(Spec.root, "telemetry/examples").listFiles().orEmpty()
        return files.filter { it.name.endsWith(".json") }.sortedBy(File::getName)
    }

    fun example(name: String): File {
        val file = File(Spec.root, "telemetry/examples/$name")
        check(file.isFile) { "No telemetry example $file" }
        return file
    }
}
