/*
 * This file is part of MCPIDE, licensed under the MIT License (MIT).
 *
 * Copyright (c) kenzierocks <https://kenzierocks.me>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package me.kenzierocks.mcpide.fx

import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import me.kenzierocks.mcpide.comms.PublishComms
import me.kenzierocks.mcpide.comms.Rename
import me.kenzierocks.mcpide.comms.StatusUpdate
import me.kenzierocks.mcpide.comms.retrieveMappings
import me.kenzierocks.mcpide.util.openErrorDialog
import me.kenzierocks.mcpide.util.setPrefSizeFromContent
import me.kenzierocks.mcpide.util.showAndSuspend
import me.kenzierocks.mcpide.util.suspendUntilEqual
import mu.KotlinLogging
import net.octyl.aptcreator.GenerateCreator
import net.octyl.aptcreator.Provided
import org.fxmisc.richtext.LineNumberFactory
import java.nio.file.Path

@GenerateCreator
class JavaEditorArea(
    var path: Path,
    @Provided
    private val publishComms: PublishComms
) : MappingTextArea() {
    private val logger = KotlinLogging.logger { }
    private val updatesChannel = Channel<String>(Channel.BUFFERED)

    init {
        isEditable = false
        paragraphGraphicFactory = LineNumberFactory.get(this)
        val highlightFlow = updatesChannel.consumeAsFlow()
            // If new edits while highlighting, toss out highlight result
            .mapLatest {
                try {
                    computeHighlighting(it)
                } catch (e: Exception) {
                    logger.warn(e) { "Highlighting error" }
                    e.openErrorDialog(
                        title = "Highlighting Error",
                        header = "An error occurred while computing highlighting"
                    )
                    null
                }
            }
            .flowOn(Dispatchers.Default + CoroutineName("Highlighting"))
        CoroutineScope(Dispatchers.JavaFx + CoroutineName("HighlightApplication")).launch {
            highlightFlow.collect { highlighting ->
                // Verify spans are valid, then apply.
                if (highlighting != null) {
                    this@JavaEditorArea.replaceText(highlighting.newText)
                    setStyleSpans(0, highlighting.spans)
                }
            }
        }
    }

    private suspend fun computeHighlighting(text: String): Highlighting? {
        publishComms.viewChannel.send(StatusUpdate("Highlighting", "In Progress..."))
        try {
            val mappings = publishComms.modelChannel.retrieveMappings()
            return provideHighlighting(text, mappings)
        } finally {
            publishComms.viewChannel.send(StatusUpdate("Highlighting", ""))
        }
    }

    suspend fun updateText(text: String) {
        updatesChannel.send(text)
    }

    suspend fun startRename() {
        val sel = trySpecialSelectWord() ?: return
        caretSelectionBind.selectRange(sel.first, sel.last)
        val srgName = getStyleSpans(sel.first, sel.last)
            .single().style.srgName ?: return
        val renameDialog = RenameDialog.create()
        val selBounds = selectionBounds.orElse(null) ?: throw IllegalStateException("Expected bounds")
        renameDialog.popup.show(this, selBounds.minX, selBounds.maxY)
        renameDialog.textField.requestFocus()
        renameDialog.popup.showingProperty().suspendUntilEqual(false)
        val text = renameDialog.textField.text
        if (text.isEmpty() || !text.all { it.isJavaIdentifierPart() } || !text[0].isJavaIdentifierStart()) {
            return
        }
        val mappings = publishComms.modelChannel.retrieveMappings()
        if (srgName in mappings && !askProceedRename()) {
            return
        }
        publishComms.modelChannel.send(Rename(path, srgName, text))
    }

    private suspend fun askProceedRename(): Boolean {
        val d = Alert(Alert.AlertType.CONFIRMATION)
        d.title = "Overwrite Confirmation"
        d.contentText = "Are you sure you want to overwrite an existing mapping?"
        d.isResizable = true
        d.dialogPane.setPrefSizeFromContent()
        return when (d.showAndSuspend()) {
            null, ButtonType.CANCEL -> false
            else -> true
        }
    }

    override fun selectWord() {
        when (val sel = trySpecialSelectWord()) {
            null -> super.selectWord()
            else -> caretSelectionBind.selectRange(sel.first, sel.last)
        }
    }

    private fun trySpecialSelectWord(): IntRange? {
        val afterCaret = caretPosition
        if (afterCaret !in (0 until length)) {
            // Don't really know how to handle out-of-range.
            // Let the superclass decide.
            return null
        }
        val doc = text
        if (doc[afterCaret].isJavaIdentifierPart()) {
            val start = doc.iterIds(afterCaret, -1)
            val end = doc.iterIds(afterCaret, 1) + 1
            check(start < end) { "No selection made." }
            return start..end
        }
        // try starting before the caret
        val beforeCaret = afterCaret - 1
        if (beforeCaret < 0) {
            return null
        }
        val start = doc.iterIds(beforeCaret, -1)
        // the caret is implicitly at the end, since we can't iterate forwards
        if (start < afterCaret) {
            return start..afterCaret
        }
        // Nothing found.
        return null
    }

    private fun String.iterIds(index: Int, step: Int): Int {
        var newIndex = index
        var validIndex = index
        while (newIndex in (0 until length) && this[newIndex].isJavaIdentifierPart()) {
            validIndex = newIndex
            newIndex += step
        }
        return validIndex
    }

}
