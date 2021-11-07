package com.unciv.scripting.protocol

import kotlin.collections.MutableMap
import java.lang.ref.WeakReference
import java.util.UUID


object ScriptingObjectIndex {

    private val objs = mutableMapOf<String, WeakReference<Any>>()

    private val kotlinObjectIdPrefix = "_unciv-kt-obj@" // load from ScriptAPIConstants.json

    private fun idKotlinObject(value: Any?): String {
        //Don't depend on parsing this for extracting information.
        //It's basically a readable/dumb UUID.
        //Actually, it might be better to just use a UUID. I think that was actually my original plan.
        return "${kotlinObjectIdPrefix}${System.identityHashCode(value)}:${if (value == null) "null" else value::class.qualifiedName}/${value.toString()}_${UUID.randomUUID().toString()}"
    }
    
    private fun isKotlinToken(value: Any?): Boolean {
        return value is String && value.startsWith(kotlinObjectIdPrefix)
    }
    
    fun clean(): Unit {
        for ((t, o) in objs) {
            if (o.get() == null) {
                objs.remove(t)
            }
        }
    }
    
    fun getToken(obj: Any?): String {
        clean()
        val token = idKotlinObject(obj)
        objs[token] = WeakReference(obj)
        return token
    }
    
    fun getReal(token: Any?): Any? {
        clean()
        if (isKotlinToken(token)) {
            return objs[token]!!.get()
        } else {
            return token
        }
    }
    
}
