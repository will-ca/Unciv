package com.unciv.ui.console

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.SplitPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.unciv.Constants
import com.unciv.console.ConsoleBackend
import com.unciv.console.ConsoleBackendType
import com.unciv.console.ConsoleState
import com.unciv.ui.utils.*
import com.unciv.ui.utils.AutoScrollPane as ScrollPane


class ConsoleScreen(val consoleState:ConsoleState, val closeAction: ()->Unit): CameraStageBaseScreen() {

    private val lineHeight = 30f

    private val layoutTable: Table = Table()
    
    private val topBar: Table = Table()
    private var backendsScroll: ScrollPane
    private val backendsAdders: Table = Table()
    private val closeButton: TextButton = Constants.close.toTextButton()
    
    private var middleSplit: SplitPane
    private var printScroll: ScrollPane
    private val printHistory: Table = Table()
    private val runningContainer: Table = Table()
    private val runningList: Table = Table()
    
    private val inputBar: Table = Table()
    private val inputField: TextField = TextField("", skin)
    
    private val inputControls: Table = Table()
    private val tabButton: TextButton = "TAB".toTextButton()
    private val upButton: Image = ImageGetter.getImage("OtherIcons/Up")
    private val downButton: Image = ImageGetter.getImage("OtherIcons/Down")
    private val runButton: TextButton = "ENTER".toTextButton()

    init {
        
        backendsAdders.add("Launch new backend:".toLabel())
        for (backendtype in ConsoleBackendType.values()) {
            var backendadder = backendtype.displayname.toTextButton()
            backendadder.onClick({
                echo(consoleState.spawnBackend(backendtype))
                updateRunning()
            })
            backendsAdders.add(backendadder)
        }
        backendsScroll = ScrollPane(backendsAdders)
        
        backendsAdders.left()
        
        topBar.add(backendsScroll).minWidth(stage.width - closeButton.getPrefWidth())
        topBar.add(closeButton)
        
        printHistory.left()
        printHistory.bottom()
        printScroll = ScrollPane(printHistory)
        
        runningContainer.add("Active Backends:".toLabel()).row()
        runningContainer.add(runningList)
        
        middleSplit = SplitPane(printScroll, runningContainer, false, skin)
        middleSplit.setSplitAmount(0.8f)
        
        inputControls.add(tabButton)
        inputControls.add(upButton.surroundWithCircle(40f))
        inputControls.add(downButton.surroundWithCircle(40f))
        inputControls.add(runButton)
        
        inputBar.add(inputField).minWidth(stage.width - inputControls.getPrefWidth())
        inputBar.add(inputControls)
        
        layoutTable.setSize(stage.width, stage.height)
        
        layoutTable.add(topBar).minWidth(stage.width).row()
        
        layoutTable.add(middleSplit).minWidth(stage.width).minHeight(stage.height - topBar.getPrefHeight() - inputBar.getPrefHeight()).row()
        
        layoutTable.add(inputBar)
        
        runButton.onClick({ run() })
        keyPressDispatcher[Input.Keys.ENTER] = { run() }
        keyPressDispatcher[Input.Keys.NUMPAD_ENTER] = { run() }
        
        tabButton.onClick({ autocomplete() })
        keyPressDispatcher[Input.Keys.TAB] = { autocomplete() }
        
        upButton.onClick({ navigateHistory(1) })
        keyPressDispatcher[Input.Keys.UP] = { navigateHistory(1) }
        downButton.onClick({ navigateHistory(-1) })
        keyPressDispatcher[Input.Keys.DOWN] = { navigateHistory(-1) }
        
        onBackButtonClicked({ closeConsole() })
        closeButton.onClick({ closeConsole() })
        
        stage.addActor(layoutTable)
        
        echoHistory()
        
        updateRunning()
    }
    
    fun openConsole() {
        game.setScreen(this)
        keyPressDispatcher.install(stage)
    }
    
    fun closeConsole() {
        closeAction()
        keyPressDispatcher.uninstall()
    }
    
    private fun updateRunning() {
        runningList.clearChildren()
        var i = 0
        for (backend in consoleState.consoleBackends) {
            var button = backend.displayname.toTextButton()
            val index = i
            runningList.add(button)
            if (i == consoleState.activeBackend) {
                button.color = Color.GREEN
            }
            button.onClick({
                consoleState.switchToBackend(index)
                updateRunning()
            })
            var termbutton = ImageGetter.getImage("OtherIcons/Stop")
            termbutton.onClick({
                consoleState.termBackend(index)
                updateRunning()
            })
            runningList.add(termbutton.surroundWithCircle(40f)).row()
            i += 1
        }
    }
    
    private fun clear() {
        printHistory.clearChildren()
    }
    
    private fun setText(text:String) {
        inputField.setText(text)
        inputField.setCursorPosition(inputField.text.length)
    }
    
    private fun echoHistory() {
        for (hist in consoleState.outputHistory) {
            echo(hist)
        }
    }
    
    private fun autocomplete() {
        var results = consoleState.getAutocomplete(inputField.text)
        if (results.isHelpText) {
            echo(results.helpText)
            return
        }
        if (results.matches.size < 1) {
            return
        } else if (results.matches.size == 1) {
            setText(results.matches[0])
        } else {
            for (m in results.matches) {
                echo(m)
            }
        }
    }
    
    private fun navigateHistory(increment:Int) {
        setText(consoleState.navigateHistory(increment))
    }
    
    private fun echo(text: String) {
        printHistory.add(Label(text, skin)).left().bottom().padLeft(15f).row()
        printScroll.scrollTo(0f,0f,1f,1f)
    }
    
    private fun run() {
        echo(consoleState.exec(inputField.text))
        setText("")
    }
}

// Screen, or widget? Screen would create whole new context for hotkeys and such, I think? Widget would be nice for 
