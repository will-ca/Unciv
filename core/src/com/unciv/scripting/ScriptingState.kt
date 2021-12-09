package com.unciv.scripting

import com.unciv.scripting.api.ScriptingScope
import com.unciv.ui.utils.clipIndexToBounds
import com.unciv.ui.utils.enforceValidIndex
import kotlin.collections.ArrayList

// TODO: Check for places to use Sequences.
// Hm. It seems that Sequence performance isn't even a simple question of number of loops, and is also affected by boxed types and who know what else.
// Premature optimization and such. Clearly long chains of loops can be rewritten as sequences.

// TODO: Replace Exception types with Throwable? Wait, no. Apparently that just includes "serious problems that a reasonable application should not try to catch."

// TODO: There's probably some public vars that can/should be private set.

// TODO: Mods blacklist.

// See https://github.com/yairm210/Unciv/pull/5592/commits/a1f51e08ab782ab46bda220e0c4aaae2e8ba21a4 for example of running locking operation in separate thread.

/**
 * Self-contained instance of scripting API use.
 *
 * Abstracts available scope, running backends, command history
 * Should be unique per isolated use of scripting. E.G. One for the [~]-key console screen, one for each mod/all mods per save file (or whatever works best), etc.
 *
 * @property scriptingScope ScriptingScope instance at the root of all scripting API.
 */
//TODO: Actually, probably should be only one instance in game, since various context changes set various ScriptingScope properties through it, and being able to view mod command history will also be useful.
class ScriptingState(val scriptingScope: ScriptingScope) {

    val scriptingBackends = ArrayList<ScriptingBackendBase>()

    private val outputHistory = ArrayList<String>()
    private val commandHistory = ArrayList<String>()

    var activeBackend: Int = 0

    val maxOutputHistory: Int = 511
    val maxCommandHistory: Int = 511

    var activeCommandHistory: Int = 0
    // Actually inverted, because history items are added to end of list and not start. 0 means nothing, 1 means most recent command at end of list.

    fun getOutputHistory() = outputHistory.toList()

    data class BackendSpawnResult(val backend: ScriptingBackendBase, val motd: String)

    fun spawnBackend(backendtype: ScriptingBackendType): BackendSpawnResult {
        val backend: ScriptingBackendBase = backendtype.metadata.new(scriptingScope)
        scriptingBackends.add(backend)
        activeBackend = scriptingBackends.size - 1
        val motd = backend.motd()
        echo(motd)
        return BackendSpawnResult(backend, motd)
    }

    fun switchToBackend(index: Int) {
        scriptingBackends.enforceValidIndex(index)
        activeBackend = index
    }

    fun switchToBackend(backend: ScriptingBackendBase) {
        // TODO: Apparently there's a bunch of extensions like .withIndex(), .indices, and .lastIndex that I can use to replace a lot of stuff currently done with .size.
        val index = scriptingBackends.indexOf(backend)
        if (index >= 0)
            return switchToBackend(index = index)
//        for ((i, b) in scriptingBackends.withIndex()) {
//            if (b == backend) {
//                return switchToBackend(index = i)
//            }
//        }
        throw IllegalArgumentException("Could not find scripting backend base: ${backend}")
    }

//    fun switchToBackend(displayName: String) {
//        // Are these really necessary?
//    }
//
//    fun switchToBackend(backendType: ScriptingBackendType) {
//    }

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
        return scriptingBackends.isNotEmpty()
    }

    fun getActiveBackend(): ScriptingBackendBase {
        return scriptingBackends[activeBackend]
    }

    fun echo(text: String) {
        outputHistory.add(text)
        while (outputHistory.size > maxOutputHistory) {
            outputHistory.removeAt(0)
            // If these are ArrayLists, performance will probably be O(n) relative to maxOutputHistory.
            // But premature optimization would be bad.
        }
    }

    fun autocomplete(command: String, cursorPos: Int? = null): AutocompleteResults {
        // Deliberately not calling echo() to add into history because I consider autocompletion a protocol/API/UI level feature
        if (!(hasBackend())) {
            return AutocompleteResults(listOf(), false, "")
        }
        return getActiveBackend().autocomplete(command, cursorPos)
    }

    fun navigateHistory(increment: Int): String {
        activeCommandHistory = commandHistory.clipIndexToBounds(activeCommandHistory + increment, extendEnd = 1)
        if (activeCommandHistory <= 0) {
            return ""
        } else {
            return commandHistory[commandHistory.size - activeCommandHistory]
        }
    }

    fun exec(command: String): String { // TODO: Allow "passing" args that get assigned to something under ScriptingScope here.
        //scriptingScope.scriptingBackend =
        if (command.length > 0) {
            if (command != commandHistory.lastOrNull())
                commandHistory.add(command)
            while (commandHistory.size > maxCommandHistory) {
                commandHistory.removeAt(0)
                // No need to restrict activeCommandHistory to valid indices here because it gets set to zero anyway.
                // Also probably O(n) to remove from start..
            }
        }
        activeCommandHistory = 0
        var out = if (hasBackend()) getActiveBackend().exec(command) else ""
        echo(out)
        //scriptingScope.scriptingBackend = null // TODO
        return out
    }

//    fun acquireScriptLock() {
//        scriptingScope.worldScreen?.isPlayersTurn = false
        //TODO: Move to ScriptingLock.
        //Not perfect. I think scriptingScope also exposes mutating the GUI itself, and many things that aren't protected by this? Then again, a script that *wants* to cause a crash/ANR will always be able to do so by just assigning an invalid value or deleting a required node somewhere. Could make mod handlers outside of worldScreen blocking, with written stipulations on (dis)recommended size, and then
        //https://github.com/yairm210/Unciv/pull/5592/commits/a1f51e08ab782ab46bda220e0c4aaae2e8ba21a4
//    }

//    fun releaseScriptLock() {
//        scriptingScope.worldScreen?.isPlayersTurn = true
        //Hm. Should return to original value, not necessarily true. That means keeping a property, which means I'd rather put this in its own class.
//    }
}
