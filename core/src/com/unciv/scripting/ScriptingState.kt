package com.unciv.scripting

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.UncivGame
import com.unciv.ui.worldscreen.WorldScreen
import kotlin.collections.ArrayList
import kotlin.math.max
import kotlin.math.min

/*

The major classes involved in the scripting API are structured as follows. `UpperCamelCase()` and parentheses means a new instantiation of a class. `lowerCamelCase` means a reference to an already-existing instance. An asterisk at the start of an item means zero or multiple instances of that class may be held. A question mark at the start of an item means that it may not exist in all implementations of the parent base class/interface. A question mark at the end of an item means that it is nullable, or otherwise may not be available in all states.

```
UncivGame():
    ScriptingState(): // Persistent per UncivGame().
        ScriptingScope():
            civInfo? // These are set by WorldScreen init, and unset by MainMenuScreen.
            gameInfo?
            uncivGame
            worldScreen?
        *ScriptingBackend():
            scriptingScope
            ?ScriptingReplManager():
                Blackbox() // Common interface to wrap foreign interpreter with pipes, STDIN/STDOUT, queues, sockets, embedding, JNI, etc.
                scriptingScope
                ScriptingProtocol():
                    scriptingScope
            ?folderHandler: setupInterpreterEnvironment() // If used, a temporary directory with file structure copied from engine and shared folders in `assets/scripting`.
    ConsoleScreen(): // Persistent as long as window isn't resized. Recreates itself and restores most of its state from scriptingState if resized.
        scriptingState
WorldScreen():
    consoleScreen
    scriptingState // ScriptingState has getters and setters that wrap scriptingScope, which WorldScreen uses to update game info.
MainMenuScreen():
    consoleScreen
    scriptingState // Same as for worldScreen.
InstanceTokenizer() // Holds WeakRefs used by ScriptingProtocol. Unserializable objects get strings as placeholders, and then turned back into into objects if seen again.
Reflection() // Used by some hard-coded scripting backends, and essential to dynamic bindings in ScriptingProtocol().
SourceManager() // Source of the folderHandler and setupInterpreterEnvironment() above.
TokenizingJson() // Serializer and functions that use InstanceTokenizer.
```

*/

fun <T> ArrayList<T>.clipIndexToBounds(index: Int, extendsize: Int = 0): Int {
    return max(0, min(this.size-1+extendsize, index))
}

fun <T> ArrayList<T>.enforceValidIndex(index: Int) {
    // Doing all checks with the same function and error message is probably easier to debug than letting an array access fail.
    if (index < 0 || this.size <= index) {
        throw IndexOutOfBoundsException("Index {index} is out of range of ArrayList().")
    }
}

class ScriptingState(val scriptingScope: ScriptingScope, initialBackendType: ScriptingBackendType? = null){

    val scriptingBackends: ArrayList<ScriptingBackendBase> = ArrayList<ScriptingBackendBase>()

    private val outputHistory: ArrayList<String> = ArrayList<String>()
    private val commandHistory: ArrayList<String> = ArrayList<String>()

    var activeBackend: Int = 0

    val maxOutputHistory: Int = 511
    val maxCommandHistory: Int = 511

    var activeCommandHistory: Int = 0
    // Actually inverted, because history items are added to end of list and not start. 0 means nothing, 1 means most recent command at end of list.


    var civInfo: CivilizationInfo?
        get() = scriptingScope.civInfo
        set(value) { scriptingScope.civInfo = value }

    var gameInfo: GameInfo?
        get() = scriptingScope.gameInfo
        set(value) { scriptingScope.gameInfo = value }

    var uncivGame: UncivGame?
        get() = scriptingScope.uncivGame
        set(value) { scriptingScope.uncivGame = value }

    var worldScreen: WorldScreen?
        get() = scriptingScope.worldScreen
        set(value) { scriptingScope.worldScreen = value }


    init {
        if (initialBackendType != null) {
            echo(spawnBackend(initialBackendType))
        }
    }
    
    fun getOutputHistory() = outputHistory.toList()

    fun spawnBackend(backendtype: ScriptingBackendType): String {
        val backend:ScriptingBackendBase = SpawnNamedScriptingBackend(backendtype, scriptingScope)
        scriptingBackends.add(backend)
        activeBackend = scriptingBackends.size - 1
        val motd = backend.motd()
        echo(motd)
        return motd
    }

    fun switchToBackend(index: Int) {
        scriptingBackends.enforceValidIndex(index)
        activeBackend = index
    }
    
    fun switchToBackend(backend: ScriptingBackendBase) {
        for ((i, b) in scriptingBackends.withIndex()) {
            // TODO: Apparently there's a bunch of extensions like `.withIndex()`, `.indices`, and `.lastIndex` that I can use to replace a lot of stuff currently done with `.size`.
            if (b == backend) {
                switchToBackend(index = i)
            }
        }
        throw IllegalArgumentException("Could not find scripting backend base: ${backend}")
    }
    
    fun switchToBackend(displayname: String) {
    }

    fun termBackend(index: Int): Exception? {
        scriptingBackends.enforceValidIndex(index)
        val result = scriptingBackends[index].terminate()
        if (result == null) {
            scriptingBackends.removeAt(index)
            if (index < activeBackend) {
                activeBackend -= 1
            }
            activeBackend = scriptingBackends.clipIndexToBounds(activeBackend)
        }
        return result
    }

    fun hasBackend(): Boolean {
        return scriptingBackends.size > 0
    }

    fun getActiveBackend(): ScriptingBackendBase {
        return scriptingBackends[activeBackend]
    }

    fun echo(text: String) {
        outputHistory.add(text)
        while (outputHistory.size > maxOutputHistory) {
            outputHistory.removeAt(0)
            // If these are ArrayLists, performance will probably be `O(n)` relative ot maxOutputHistory.
            // But premature optimization would be bad.
        }
    }

    fun autocomplete(command: String, cursorPos: Int? = null): AutocompleteResults {
        // Deliberately not calling `echo()` to add into history because I consider autocompletion a protocol/API level feature
        if (!(hasBackend())) {
            return AutocompleteResults(listOf(), false, "")
        }
        return getActiveBackend().autocomplete(command, cursorPos)
    }

    fun navigateHistory(increment: Int): String {
        activeCommandHistory = commandHistory.clipIndexToBounds(activeCommandHistory + increment, extendsize = 1)
        if (activeCommandHistory <= 0) {
            return ""
        } else {
            return commandHistory[commandHistory.size - activeCommandHistory]
        }
    }

    fun exec(command: String): String {
        if (command.length > 0) {
            commandHistory.add(command)
            while (commandHistory.size > maxCommandHistory) {
                commandHistory.removeAt(0)
                // No need to restrict activeCommandHistory to valid indices here because it gets set to zero anyway.
                // Also O(n).
            }
        }
        activeCommandHistory = 0
        var out: String
        if (hasBackend()) {
            out = getActiveBackend().exec(command)
        } else {
            out = ""
        }
        echo(out)
        return out
    }
}
