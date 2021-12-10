package com.unciv.scripting.reflection

import com.unciv.scripting.serialization.TokenizingJson
import kotlin.collections.ArrayList
import kotlinx.serialization.Serializable
import kotlin.reflect.*

// I've noticed that the first time running a script is significantly slower than any subsequent times. Takes 50% longer to run the Python test suite the first time than the second time, and simple functions go from incurring a noticeable delay to being visually instant.
// I don't think anything either can or needs to be done about that, but I assume it's the JVM JIT'ing.

// TODO: Show warning on accessing deprecated property?

object Reflection {

    @Suppress("UNCHECKED_CAST")
    fun <R> readClassProperty(cls: KClass<*>, propertyName: String)
        = (cls.members.first { it.name == propertyName } as KProperty0<*>).get() as R?

    @Suppress("UNCHECKED_CAST")
    fun <R> readInstanceProperty(instance: Any, propertyName: String): R? {
        // From https://stackoverflow.com/a/35539628/12260302
        val kprop = (instance::class.members.first { it.name == propertyName } as KProperty1<Any, *>) // Memoization candidates? I already have LazyMap, which should work for this.
        return (if (kprop.isConst)
                kprop.getter.call()
            else
                kprop.get(instance)) as R?
    // KProperty1().get(instance) Fails for consts: apiHelpers.Jvm.kotlinSingletonByQualname["com.unciv.Constants"].close
    // m=next(m for m in apiHelpers.Jvm.kotlinClassByQualname["com.unciv.Constants"].members if m.name == 'close')
    // object o {val a=1; const val b=2}
    // (o::class.members.first{it.name == "a"} as KProperty1<Any, *>).get(o)
    // (o::class.members.first{it.name == "b"} as KProperty1<Any, *>).getter.call()
    }

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
            functions = instance::class.members.filter { it is KFunction<*> && it.name == methodName },
            // TODO: .functions? Choose one that includes superclasses but excludes extensions.
            // FIXME: Right. Cell.row is an example of a name used as both a property and a function.
            //  p=apiHelpers.Jvm.constructorByQualname["com.unciv.ui.utils.Popup"](uncivGame.consoleScreen); disp=p.add(apiHelpers.Jvm.functionByQualClassAndName["com.unciv.ui.utils.ExtensionFunctionsKt"]["toLabel"]("Test Text.")).row
            //  [apiHelpers.Jvm.kotlinClassByInstance[f] for f in disp.functions]
            //  KFunctionImpl vs KMutableProperty1Impl, apparently.
            //  Adding `is Function` to the filter should do it, I think?
            // apiHelpers.Jvm.kotlinClassByQualname["com.badlogic.gdx.scenes.scene2d.ui.Cell"].members
            // apiHelpers.Jvm.kotlinClassByQualname["com.badlogic.gdx.scenes.scene2d.ui.Cell"]
            // apiHelpers.Jvm.functionByQualClassAndName["kotlin.reflect.full.KClasses"]["getFunctions"](apiHelpers.Jvm.kotlinClassByQualname["com.badlogic.gdx.scenes.scene2d.ui.Cell"])
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


    fun readInstanceItem(instance: Any, keyOrIndex: Any): Any? { // TODO: Unify type param/casting behaviour across this, readInstanceProperty, and FunctionDispatcher.call.
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


    fun stringifyKotlinPath(path: List<PathElement>): String {
        val components = ArrayList<String>()
        for (element in path) { // TODO: Encoded strings.
            components.add( when (element.type) {
                PathElementType.Property -> ".${element.name}"
                PathElementType.Key -> "[${if (element.doEval) element.name else element.params[0]!!}]"
                PathElementType.Call -> "(${if (element.doEval) element.name else element.params.joinToString(", ")})"
            })
        }
        return components.joinToString()
    }

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
        return if (code.isBlank()) listOf() else code.split(',').map { it.trim(' ') }
        var segs = ArrayList<String>()
        val bracketdepths = mutableMapOf<Char, Int>(
            *brackettypes.keys.map { it to 0 }.toTypedArray()
        )
        //TODO: Actually try to parse for parenthesization, strings, etc.
    }


    fun resolveInstancePath(instance: Any?, path: List<PathElement>): Any? {
        //TODO: Allow passing an ((Any?)->Unit)? (or maybe Boolean) function as a parameter that gets called at every stage of resolution, to let exceptions be thrown if accessing something not whitelisted.
        var obj: Any? = instance
        for (element in path) {
            try {
                obj = when (element.type) {
                    PathElementType.Property -> {
                        try {
                            readInstanceProperty<Any?>(obj!!, element.name) // Not explicitly typing the function call makes it always fail an implicit cast or something.
                            // TODO: Consider a LBYL instead of AFP here.
                        } catch (e: ClassCastException) {
                            makeInstanceMethodDispatcher(
                                obj!!,
                                element.name
                            )
                        }
                    }
                    PathElementType.Key -> {
                        readInstanceItem(
                            obj!!,
                            if (element.doEval)
                                evalKotlinString(instance!!, element.name)!!
                            else
                                element.params[0]!!
                        )
                    }
                    PathElementType.Call -> {
                        // TODO: Handle invoke operator. Easy enough, just recurse to access the .invoke.
                        // object test{operator fun invoke(a:Any?,b:Any?){println("$a $b")}}; test(1,2)
                        // Test in Python: apiHelpers.Jvm.constructorByQualname('com.unciv.UncivGame'). Also do an object for multi-arg testing, I guess?
                        // Maybe TODO: Handle lambdas. E.G. WorldScreen.nextTurnAction. But honestly, it may be better to just expose wrapping non-lambdas.
                        if (obj is FunctionDispatcher) {
                            // Undocumented implicit behaviour: Using the last object means that this should work with explicitly created FunctionDispatcher()s.
                            (obj).call(
                                (
                                    if (element.doEval)
                                        splitToplevelExprs(element.name).map { evalKotlinString(instance!!, it) }
                                    else
                                        element.params
                                ).toTypedArray()
                            )
                        } else {
                            throw UnsupportedOperationException()
                            resolveInstancePath( // TODO: Test this.
                                obj,
                                listOf(
                                    PathElement(
                                        type = PathElementType.Property,
                                        name = "invoke"
                                    ),
                                    element
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                throw IllegalAccessException("Cannot access $element on $obj:\n${e.toString().prependIndent("\t")}")
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
        } // TODO: Allow single-quoted strings?
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


    fun setInstancePath(instance: Any?, path: List<PathElement>, value: Any?) {
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
        }
    }

    fun removeInstancePath(instance: Any?, path: List<PathElement>) {
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
        }
    }
}
