package com.unciv.ui.consolescreen

import com.badlogic.gdx.Input
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.scripting.ScriptingState
import com.unciv.ui.consolescreen.ConsoleScreen
import com.unciv.ui.utils.CameraStageBaseScreen
import com.unciv.ui.worldscreen.WorldScreen
import com.unciv.ui.mapeditor.MapEditorScreen

//Interface that extends CameraStageBaseScreen with methods for exposing the global ConsoleScreen.
interface IConsoleScreenAccessible {

    val CameraStageBaseScreen.consoleScreen: ConsoleScreen
        get() = this.game.consoleScreen

    val CameraStageBaseScreen.scriptingState: ScriptingState
        get() = this.game.scriptingState


    //Set the console screen tilde hotkey.
    fun CameraStageBaseScreen.setOpenConsoleScreenHotkey() {
        this.keyPressDispatcher[Input.Keys.GRAVE] = { this.game.setConsoleScreen() }
    }

    //Set the console screen to return to the right screen when closed.

    //Defaults to setting the game's screen to this instance. Can also use a lambda, for E.G. WorldScreen and UncivGame.setWorldScreen().
    fun CameraStageBaseScreen.setConsoleScreenCloseAction(closeAction: (() -> Unit)? = null) {
        this.consoleScreen.closeAction = closeAction ?: { this.game.setScreen(this) }
    }

    //Extension method to update scripting API scope variables that are expected to change over the lifetime of a ScriptingState.

    //Unprovided arguments default to null. This way, screens inheriting this interface don't need to explicitly clear everything they don't have. They only need to provide what they do have.

    //@param gameInfo Active GameInfo.
    //@param civInfo Active CivilizationInfo.
    //@param worldScreen Active WorldScreen.
    fun CameraStageBaseScreen.updateScriptingState(
        gameInfo: GameInfo? = null,
        civInfo: CivilizationInfo? = null,
        worldScreen: WorldScreen? = null,
        mapEditorScreen: MapEditorScreen? = null
    ) {
        this.scriptingState.scriptingScope.also {
            it.gameInfo = gameInfo
            it.civInfo = civInfo
            it.worldScreen = worldScreen
            it.mapEditorScreen = mapEditorScreen
        } // .apply errors on compile with "val cannot be reassigned".
    }
}
