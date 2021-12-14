package com.unciv.scripting.sync

import com.unciv.scripting.ScriptingBackend
import com.unciv.scripting.ScriptingState
import com.unciv.scripting.utils.ScriptingDebugParameters
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// Does not prevent concurrent run attempts. Just tries to make them to error immediately instead of messing anything up.



// Lock to prevent multiple scripts from trying to run at the same time.
object ScriptingRunLock {

    private val isRunning = // Why is this separate from ScriptingRunThreader? Well, for one thing, ScriptingRunThreader doesn't let return values from scripts be used
        AtomicBoolean(false)

    var runningName: String? = null
        private set

    private var runningKey: String? = null // Unique key set each run to make sure

    // @param name An informative string to identify this acquisition and subsequent activities.
    // @throws IllegalStateException if lock is already in use.
    // @return A randomly generated string to pass to the release function.
    @Synchronized fun acquire(name: String? = null): String {
        // Different *threads* that try to run scripts concurrently should already queue up nicely without this due to the use of the Synchronized annotation on ScriptingState.exec(), I think.
        // But because of the risk of recursive script runs (which will deadlock if trying to acquire a lock that must be released by the same thread, and which break the already-in-use IPC loop for a backend if allowed to continue without a lock), the behaviour here if or when that fails is to throw an exception.
        if (!isRunning.compareAndSet(false, true)) throw IllegalStateException("Cannot acquire ${this::class.simpleName} for $name because it is already in use by $runningName.") // Prooobably don't translate?
        if (ScriptingDebugParameters.printLockAcquisition) {
            println("${this::class.simpleName} acquired by $name.")
        }
        val key = UUID.randomUUID().toString()
        runningKey = key
        runningName = name
        return key
    }
    // @param releaseKey The string previously returned by the immediately preceding successful acquire().
    // @throws IllegalArgumentException If given the incorrect releaseKey.
    // @throws IllegalStateException If not currently acquired.
    @Synchronized fun release(releaseKey: String) {
        if (releaseKey != runningKey) throw IllegalArgumentException("Invalid key given to release ${this::class.simpleName}.")
        if (isRunning.get()) {
            if (ScriptingDebugParameters.printLockAcquisition) {
                println("${this::class.simpleName} released by $runningName.")
            }
            runningName = null
            runningKey = null
            isRunning.set(false)
        } else {
            throw IllegalStateException("Cannot release ${this::class.simpleName} because it has not been acquired.")
        }
    }
}

fun makeScriptingRunName(executor: String?, backend: ScriptingBackend) = "$executor/${backend.metadata.displayName}@${ScriptingState.getIndexOfBackend(backend)}"
