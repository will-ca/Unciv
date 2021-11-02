package com.unciv.scripting

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.UncivGame

class ScriptingScope(var civInfo: CivilizationInfo?, var gameInfo: GameInfo?, var uncivGame: UncivGame?) {
    val isInGame: Boolean
        get() = (civInfo != null && gameInfo != null && uncivGame != null)
    // Holds references to all internal game data that the console has access to.
    // Mostly `.civInfo`/.`gameInfo`, but could be cool to E.G. allow loading and making saves through CLI/API too.
    // Also where to put any `PlayerAPI`, `CheatAPI`, `ModAPI`, etc.
    // For `LuaScriptingBackend`, `UpyScriptingBackend`, `QjsScriptingBackend`, etc, this should probably directly mirror the wrappers exposed to the scripting language.
}
