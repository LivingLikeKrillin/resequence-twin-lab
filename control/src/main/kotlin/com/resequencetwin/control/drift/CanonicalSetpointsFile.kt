package com.resequencetwin.control.drift

import org.yaml.snakeyaml.Yaml
import java.io.File

/** Parses koshei's model/recipe-setpoints.yaml (the shared Git-canonical desired contract). */
object CanonicalSetpointsFile {
    data class Loaded(val endpoint: String, val setpoints: List<RecipeSetpoint>)

    @Suppress("UNCHECKED_CAST")
    fun load(path: String): Loaded {
        val root = File(path).inputStream().use { Yaml().load<Map<String, Any?>>(it) }
        val endpoint = root["endpoint"] as String
        val raw = root["setpoints"] as Map<String, Map<String, Any?>>
        val setpoints = raw.map { (key, e) ->
            RecipeSetpoint(
                key = key,
                nodeId = e["nodeId"] as String,
                desired = (e["desired"] as Number).toDouble(),
                tolerance = (e["tolerance"] as? Number)?.toDouble() ?: 1.0,
            )
        }
        return Loaded(endpoint, setpoints)
    }
}
