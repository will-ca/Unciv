package com.unciv.scripting.serialization

import kotlinx.serialization.*
//import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
//import kotlinx.serialization.json.decodeFromJsonElement
//import kotlinx.serialization.modules.SerializersModule

/**
 * Json serialization that accepts Any?, and converts non-primitive values to string keys using InstanceTokenizer.
 */
object TokenizingJson {

    /**
     * KotlinX serializer that automatically converts non-primitive values to string tokens on serialization, and automatically replaces string tokens with the real Kotlin/JVM objects they represent on detokenization.
     *
     * I tested the serialization function, but I'm not sure it's actually used anywhere since I think PathElements (which use it for params) are only ever received and not sent.
     *
     * Adapted from https://stackoverflow.com/a/66158603/12260302
     */
    object TokenizingSerializer: KSerializer<Any?> {

        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Any?")

        /**
         * Only these types will be serialized. Everything else will be replaced with a string key from InstanceTokenizer.
         */
        @Suppress("UNCHECKED_CAST")
        private val dataTypeSerializers: Map<String, KSerializer<Any?>> =
            //Tried replacing this with a generic fun<T>serialize and serializer<T>. The change in signature prevents serialize from being overridden correctly, I guess.
            //Also tried generic fun<T>getSerializer(v:T)=serialize<T>() as KSerializer<Any?>.
            mapOf(
                "Boolean" to serializer<Boolean>(),
                "Byte" to serializer<Byte>(),
                "Char" to serializer<Char>(),
                "Double" to serializer<Double>(),
                "Float" to serializer<Float>(),
                "Int" to serializer<Int>(),
                "Long" to serializer<Long>(),
                "Short" to serializer<Short>(),
                "String" to serializer<String>()
            ).mapValues { (_, v) -> v as KSerializer<Any?> }

        override fun serialize(encoder: Encoder, value: Any?) {
            // Hum. I tested this, but I'm not sure it's actually used anywhere since I think PathElements (which use it for params) are only ever received and not sent.
            if (value == null) {
                encoder.encodeNull()
            } else {
                val classname = value!!::class.simpleName!!
                if (classname in dataTypeSerializers) {// && !isTokenizationMandatory(value)) {
                    encoder.encodeSerializableValue(dataTypeSerializers[value!!::class.simpleName!!]!!, value!!)
                } else {
                    encoder.encodeString(InstanceTokenizer.getToken(value!!))
                }
            }
        }

        override fun deserialize(decoder: Decoder): Any? {
            if (decoder is JsonDecoder) {
                val jsonLiteral = (decoder as JsonDecoder).decodeJsonElement()
                val rawval: Any? = getJsonReal(jsonLiteral)
                return InstanceTokenizer.getReal(rawval)
            } else {
                throw UnsupportedOperationException("Decoding is not supported by TokenizingSerializer for ${decoder::class.simpleName}.")
            }
        }
    }

    /**
     * KotlinX JSON entrypoint with default properties to make it easier to make and work with IPC packets.
     */
    val json = Json {
        explicitNulls = true; //Disable these if it becomes a problem.
        encodeDefaults = true;
//        serializersModule = serializersModule
    }

//    /**
//     * Forbid some objects from being serialized as normal JSON values.
//     *
//     * com.unciv.models.ruleset.Building, for example, and resumably all other types that inherit from Stats, implements Iterable<*> and thus gets serialized as JSON Arrays by default even though it's probably better to tokenize them.
//     * Python example: civInfo.cities[0].cityConstructions.getBuildableBuildings()
//     * (Should return list of Building tokens, not list of lists of tokens for seemingly unrelated stats.)
//     *
//     * @param value Value or instance to check.
//     * @return Whether the given value is required to be tokenized.
//     */
//    private fun isTokenizationMandatory(value: Any?): Boolean {
//        // TODO: Remove this?
//        if (value == null) {
//            return false
//        }
//        val qualname = value::class.qualifiedName
//        return qualname != null && qualname.startsWith("com.unciv")
//        // Originally, containers would be serialized when requested so they could be efficiently traversed in scripting languages with their own semantics.
//        // With the switch away from bind-by-path to bind-by-reference, that behaviour is now a greater deviation from the convention of everything on the scripting side being a wrapper for Kotlin/JVM instances by default.
//    }

    // @return Whether the value corresponds to a primitive JSON value type.
    fun isNotPrimitive(value: Any?): Boolean {
        return when (value) {
            null -> false
            is String -> false // TODO: Chars?
            is Boolean -> false
            is Number -> false
            else -> true
        }
    }

    /**
     * Get a KotlinX JsonElement for any Kotlin/JVM value or instance.
     *
     * @param value Any Kotlin/JVM value or instance to turn into a JsonElement.
     * @param requireTokenization If given, a function that returns whether the value must be tokenized even if it can be serialized.
     * @return Input value unchanged if value is already a JsonElement, otherwise JsonElement best representing value— Generally best effort to create JsonObject, JsonArray, JsonPrimitive, or JsonNull directly from value, and token string JsonPrimitive if best effort fails or tokenization is mandatory for the given value.
     */
    fun getJsonElement(value: Any?, requireTokenization: ((Any?) -> Boolean)? = null): JsonElement {
        if (value is JsonElement) {
            return value
        }
        if (requireTokenization == null || !requireTokenization(value)) {
            if (value is Map<*, *>) {
                return JsonObject( (value as Map<Any?, Any?>).entries.associate {
                    json.encodeToString(getJsonElement(it.key)) to getJsonElement(it.value)
                    // TODO: Currently, this means that string keys are encoded with quotes as part of the string.
                    // Serialized keys are additionally different from Kotlin/JVM keys exposed reflectively.
                    // Treating keys as their own JSON strings may be the only way to let all types of values be represented unambiguously in keys, though.
                    // Maybe just treat strings normally but stringify everything else? That would be consistent with Python behaviour and a superset of normal string-encoding behaviour.
                    // Collisions would be tricky— Probably just either throw an exception or make real strings always take precedence. It's probably not worth it to specify that JSON keys have to be encoded.
                    // TODO: More testing/documentation is needed.
                } )
            }
            if (value is Iterable<*>) {
                // Apparently ::class.java.isArray can be used to check for primitive arrays, but it breaks
                return JsonArray(value.map(::getJsonElement))
            }
            if (value is Sequence<*>) {
                return getJsonElement((value as Sequence<Any?>).toList())
            }
            if (value is String) {
                return JsonPrimitive(value as String)
            }
            if (value is Number) {//(value is Int || value is Long || value is Float || value is Double) {
                return JsonPrimitive(value as Number)
            }
            if (value is Boolean) { //TODO: Arrays and primitive arrays?
                return JsonPrimitive(value as Boolean)
            }
            if (value == null) {
                return JsonNull
            }
        }
        return JsonPrimitive(InstanceTokenizer.getToken(value))
    }

    /**
     * Get a real value or instance from any KotlinX JsonElement.
     *
     * @param value JsonElement to make into a real instance.
     * @return Detokenized instance from input value if input value represents a token string, direct conversion of input value into a Kotlin/JVM primitive or container otherwise.
     */
    fun getJsonReal(value: JsonElement): Any? {
        if (value is JsonNull || value == JsonNull) {
            return null
        }
        if (value is JsonArray) {
            return (value as List<JsonElement>).map(::getJsonReal)// as List<Any?>
        }
        if (value is JsonObject) {
            return (value as Map<String, JsonElement>).entries.associate {
                InstanceTokenizer.getReal(it.key) to getJsonReal(it.value)
            }// as Map<Any?, Any?>
        }
        if (value is JsonPrimitive) {
            val v = value as JsonPrimitive
            return if (v.isString) {
                InstanceTokenizer.getReal(v.content)
            } else {
                v.content.toIntOrNull()
                    ?: v.content.toLongOrNull()
                    ?: v.content.toFloatOrNull() // NOTE: This may prevent .toDoubleOrNull() from ever being used. I think the implicit number type conflation in FunctionDispatcher means that Floats can still be used where Doubles are expected, though.
                    ?: v.content.toDoubleOrNull()
                    ?: v.content.toBooleanStrictOrNull()
                    ?: v.content
            }
        }
        throw IllegalArgumentException("Unrecognized type of JsonElement: ${value::class}/${value}")
    }
}
