package net.hlan.sushi

import org.json.JSONArray
import org.json.JSONObject

data class Play(
    val id: Long = 0,
    val name: String,
    val description: String,
    val scriptTemplate: String,
    val parametersJson: String = "[]",
    val managed: Boolean = false
)

data class PlayParameter(
    val key: String,
    val label: String,
    val required: Boolean = true,
    val secret: Boolean = false,
    val default: String? = null,
    val description: String? = null,
    val example: String? = null
)

object PlayParameters {
    private val placeholderRegex = Regex("\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}")

    fun decode(json: String): List<PlayParameter> {
        if (json.isBlank()) {
            return emptyList()
        }
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val key = item.optString("key").trim()
                    if (key.isBlank()) {
                        continue
                    }
                    val label = item.optString("label").trim().ifBlank { key }
                    add(
                        PlayParameter(
                            key = key,
                            label = label,
                            required = item.optBoolean("required", true),
                            secret = item.optBoolean("secret", false),
                            default = item.optString("default").takeIf { it.isNotEmpty() },
                            description = item.optString("description").takeIf { it.isNotEmpty() },
                            example = item.optString("example").takeIf { it.isNotEmpty() }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun encode(parameters: List<PlayParameter>): String {
        val array = JSONArray()
        parameters.forEach { parameter ->
            val obj = JSONObject()
                .put("key", parameter.key)
                .put("label", parameter.label)
                .put("required", parameter.required)
                .put("secret", parameter.secret)
            parameter.default?.let { obj.put("default", it) }
            parameter.description?.let { obj.put("description", it) }
            parameter.example?.let { obj.put("example", it) }
            array.put(obj)
        }
        return array.toString()
    }

    fun inferFromTemplate(scriptTemplate: String): List<PlayParameter> {
        return placeholderRegex
            .findAll(scriptTemplate)
            .map { it.groupValues[1] }
            .distinct()
            .map { key ->
                PlayParameter(
                    key = key,
                    label = key.replace('_', ' ').replaceFirstChar { ch -> ch.uppercase() }
                )
            }
            .toList()
    }
}
