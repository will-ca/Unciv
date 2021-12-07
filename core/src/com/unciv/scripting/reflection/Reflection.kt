package com.unciv.scripting.reflection

import com.unciv.scripting.utils.TokenizingJson
import kotlin.collections.ArrayList
import kotlinx.serialization.Serializable
import kotlin.reflect.*


object Reflection {

    @Suppress("UNCHECKED_CAST")
    fun <R> readClassProperty(cls: KClass<*>, propertyName: String)
        = (cls.members.first { it.name == propertyName } as KProperty0<*>).get() as R?

    @Suppress("UNCHECKED_CAST")
    fun <R> readInstanceProperty(instance: Any, propertyName: String)
        // From https://stackoverflow.com/a/35539628/12260302
        = (instance::class.members.first { it.name == propertyName } as KProperty1<Any, *>).get(instance) as R?
            // If scripting member access performance becomes an issue, memoizing this could be a potential first step.
            // TODO: Throw more helpful error on failure.

    // Return an [InstanceMethodDispatcher]() with consistent settings for the scripting API.
    fun makeInstanceMethodDispatcher(instance: Any, methodName: String) = InstanceMethodDispatcher(
        instance = instance,
        methodName = methodName,
        matchNumbersLeniently = true,
        matchClassesQualnames = false,
        resolveAmbiguousSpecificity = true
    )

    /**
     * Dynamic multiple dispatch for Any Kotlin instances by methodName.
     *
     * Uses reflection to first find all members matching the expected method name, and then to call the correct method for given arguments.
     *
     * See the [FunctionDispatcher] superclass for details on the method resolution strategy and configuration parameters.
     *
     * @property instance The receiver on which to find and call a method.
     * @property methodName The name of the method to resolve and call.
     */
    class InstanceMethodDispatcher(
        val instance: Any,
        val methodName: String,
        matchNumbersLeniently: Boolean = false,
        matchClassesQualnames: Boolean = false,
        resolveAmbiguousSpecificity: Boolean = false
        ) : FunctionDispatcher(
            functions = instance::class.members.filter { it.name == methodName },
            // TODO: .functions? Choose one that includes superclasses but excludes extensions.
            matchNumbersLeniently = matchNumbersLeniently,
            matchClassesQualnames = matchClassesQualnames,
            resolveAmbiguousSpecificity = resolveAmbiguousSpecificity
        ) {

        // This isn't just a nice-to-have feature. Before I implemented it, identical calls from demo scripts to methods with multiple versions (E.G. ArrayList().add()) would rarely but randomly fail because the member/signature that was found would change between runs or compilations.

        // TODO: This is going to need unit tests.

        /**
         * @return Helpful representative text.
         */
        override fun toString() = """${this::class.simpleName}(instance=${this.instance::class.simpleName}(), methodName="${this.methodName}") with ${this.functions.size} dispatch candidates"""
        // Used by "docstring" packet action in ScriptingProtocol, which is in turn exposed in interpeters as help text. TODO: Could move to an extension method in ScriptingProtocol, I suppose.

        override fun call(arguments: Array<Any?>): Any? {
            return super.call(arrayOf<Any?>(instance, *arguments))
            // Add receiver to arguments.
        }

        override fun nounifyFunctions() = "${instance::class?.simpleName}.${methodName}"
    }


    fun readInstanceItem(instance: Any, keyOrIndex: Any): Any? {
        // TODO: Make this work with operator overloading. Though Map is already an interface that anything can implement, so maybe not.
        if (keyOrIndex is Int) {
            return try { (instance as List<Any?>)[keyOrIndex] }
                catch (e: ClassCastException) { (instance as Array<Any?>)[keyOrIndex] }
        } else {
            return (instance as Map<Any, Any?>)[keyOrIndex]
        }
    }


    fun <T> setInstanceProperty(instance: Any, propertyName: String, value: T?) {
        val property = instance::class.members
            .first { it.name == propertyName } as KMutableProperty1<Any, T?>
        property.set(instance, value)
    }

    fun setInstanceItem(instance: Any, keyOrIndex: Any, value: Any?) {
        if (keyOrIndex is Int) {
            (instance as MutableList<Any?>)[keyOrIndex] = value
        } else {
            (instance as MutableMap<Any, Any?>)[keyOrIndex] = value
        }
    }

    fun removeInstanceItem(instance: Any, keyOrIndex: Any) {
        if (keyOrIndex is Int) {
            (instance as MutableList<Any?>).removeAt(keyOrIndex)
        } else {
            (instance as MutableMap<Any, Any?>).remove(keyOrIndex)
        }
    }


    enum class PathElementType {
        Property,
        Key,
        Call
    }

    @Serializable
    data class PathElement(
        val type: PathElementType,
        val name: String,
        /**
         * For key and index accesses, and function calls, whether to evaluate name instead of using params for arguments/key.
         * This lets simple parsers be written and used, that can simply break up a common subset of many programming languages into string components without themselves having to analyze or understand any more complex semantics.
         *
         * Default should be false, so deserialized JSON path lists are configured correctly in ScriptingProtocol.kt.
         */
        val doEval: Boolean = false,
        val params: List<@Serializable(with=TokenizingJson.TokenizingSerializer::class) Any?> = listOf()
        //val namedParams
        //Probably not worth it. But if you want to add support for named arguments in calls (which will also require changing InstanceMethodDispatcher's multiple dispatch resolution, and which respect default arguments), then it will probably have to be in a new field.
    )


    private val brackettypes: Map<Char, String> = mapOf(
        '[' to "[]",
        '(' to "()"
    )

    private val bracketmeanings: Map<String, PathElementType> = mapOf(
        "[]" to PathElementType.Key,
        "()" to PathElementType.Call
    )

    fun parseKotlinPath(code: String): List<PathElement> {
        var path: MutableList<PathElement> = ArrayList<PathElement>()
        //var curr_type = PathElementType.Property
        var curr_name = ArrayList<Char>()
        var curr_brackets = ""
        var curr_bracketdepth = 0
        var just_closed_brackets = true
        for (char in code) {
            if (curr_bracketdepth == 0) {
                if (char == '.') {
                    if (!just_closed_brackets) {
                        path.add(PathElement(
                            PathElementType.Property,
                            curr_name.joinToString("")
                        ))
                    }
                    curr_name.clear()
                    just_closed_brackets = false
                    continue
                }
                if (char in brackettypes) {
                    if (!just_closed_brackets) {
                        path.add(PathElement(
                            PathElementType.Property,
                            curr_name.joinToString("")
                        ))
                    }
                    curr_name.clear()
                    curr_brackets = brackettypes[char]!!
                    curr_bracketdepth += 1
                    just_closed_brackets = false
                    continue
                }
                curr_name.add(char)
            }
            just_closed_brackets = false
            if (curr_bracketdepth > 0) {
                if (char == curr_brackets[1]) {
                    curr_bracketdepth -= 1
                    if (curr_bracketdepth == 0) {
                        path.add(PathElement(
                            bracketmeanings[curr_brackets]!!,
                            curr_name.joinToString(""),
                            true
                        ))
                        curr_brackets = ""
                        curr_name.clear()
                        just_closed_brackets = true
                        continue
                    }
                } else if (char == curr_brackets[0]) {
                    curr_bracketdepth += 1
                }
                curr_name.add(char)
            }
        }
        if (!just_closed_brackets && curr_bracketdepth == 0) {
            path.add(PathElement(
                PathElementType.Property,
                curr_name.joinToString("")
            ))
            curr_name.clear()
        }
        if (curr_bracketdepth > 0) {
            throw IllegalArgumentException("Unclosed parentheses.")
        }
        return path
    }


//    fun stringifyKotlinPath() {
//    }

//    private val closingbrackets = null

//    data class OpenBracket(
//        val char: Char,
//        var offset: Int
//    )

    //class OpenBracketIterator() {
    //}


    //fun getOpenBracketStack() {
    //}

    fun splitToplevelExprs(code: String, delimiters: String = ","): List<String> {
        return code.split(',').map { it.trim(' ') }
        var segs = ArrayList<String>()
        val bracketdepths = mutableMapOf<Char, Int>(
            *brackettypes.keys.map { it to 0 }.toTypedArray()
        )
        //TODO: Actually try to parse for parenthesization, strings, etc.
    }


    fun resolveInstancePath(instance: Any, path: List<PathElement>): Any? {
        //TODO: Allow passing an ((Any?)->Unit)? (or maybe Boolean) function as a parameter that gets called at every stage of resolution, to let exceptions be thrown if accessing something not whitelisted.
        var obj: Any? = instance
        for (element in path) {
            when (element.type) {
                PathElementType.Property -> {
                    try {
                        obj = readInstanceProperty(obj!!, element.name)
                        // TODO: Consider a LBYL instead of AFP here.
                    } catch (e: ClassCastException) {
                        obj = makeInstanceMethodDispatcher(
                            obj!!,
                            element.name
                        )
                    }
                }
                PathElementType.Key -> {
                    obj = readInstanceItem(
                        obj!!,
                        if (element.doEval)
                            evalKotlinString(instance!!, element.name)!!
                        else
                            element.params[0]!!
                    )
                }
                PathElementType.Call -> {
                    // TODO: Handle invoke operator. Easy enough, just recurse to access the .invoke.
                    // Test in Python: apiHelpers.Factories.constructorByQualname.invoke('com.unciv.UncivGame'). Also do an object for multi-arg testing, I guess?
                    // Maybe TODO: Handle lambdas. E.G. WorldScreen.nextTurnAction. But honestly, it may be better to just expose wrapping non-lambdas.
                    obj = (obj as FunctionDispatcher).call(
                        // Undocumented implicit behaviour: Using the last object means that this should work with explicitly created FunctionDispatcher()s.
                        (
                            if (element.doEval)
                                splitToplevelExprs(element.name).map { evalKotlinString(instance!!, it) }
                            else
                                element.params
                        ).toTypedArray()
                    )
                }
//                else -> {
//                    throw UnsupportedOperationException("Unknown path element type: ${element.type}")
//                }
            }
        }
        return obj
    }


    fun evalKotlinString(scope: Any?, string: String): Any? {
        val trimmed = string.trim(' ')
        if (trimmed == "null") {
            return null
        }
        if (trimmed == "true") {
            return true
        }
        if (trimmed == "false") {
            return false
        }
        if (trimmed.length > 1 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
            return trimmed.slice(1..trimmed.length-2)
        }
        val asint = trimmed.toIntOrNull()
        if (asint != null) {
            return asint
        }
        val asfloat = trimmed.toFloatOrNull()
        if (asfloat != null) {
            return asfloat
        }
        return resolveInstancePath(scope!!, parseKotlinPath(trimmed))
    }


    fun setInstancePath(instance: Any, path: List<PathElement>, value: Any?) {
        val leafobj = resolveInstancePath(instance, path.slice(0..path.size-2))
        val leafelement = path[path.size - 1]
        when (leafelement.type) {
            PathElementType.Property -> {
                setInstanceProperty(leafobj!!, leafelement.name, value)
            }
            PathElementType.Key -> {
                setInstanceItem(
                    leafobj!!,
                    if (leafelement.doEval)
                        evalKotlinString(instance, leafelement.name)!!
                    else
                        leafelement.params[0]!!,
                    value
                )
            }
            PathElementType.Call -> {
                throw UnsupportedOperationException("Cannot assign to function call.")
            }
//            else -> {
//                throw UnsupportedOperationException("Unknown path element type: ${leafelement.type}")
//            }
        }
    }

    fun removeInstancePath(instance: Any, path: List<PathElement>) {
        val leafobj = resolveInstancePath(instance, path.slice(0..path.size-2))
        val leafelement = path[path.size - 1]
        when (leafelement.type) {
            PathElementType.Property -> {
                throw UnsupportedOperationException("Cannot remove instance property.")
            }
            PathElementType.Key -> {
                removeInstanceItem(
                    leafobj!!,
                    if (leafelement.doEval)
                        evalKotlinString(instance, leafelement.name)!!
                    else
                        leafelement.params[0]!!
                )
            }
            PathElementType.Call -> {
                throw UnsupportedOperationException("Cannot remove function call.")
            }
//            else -> {
//                throw UnsupportedOperationException("Unknown path element type: ${leafelement.type}")
//            }
        }
    }
}
