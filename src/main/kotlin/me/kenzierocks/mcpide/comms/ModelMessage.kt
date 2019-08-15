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

package me.kenzierocks.mcpide.comms

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import me.kenzierocks.mcpide.SrgMapping
import java.nio.file.Path
import kotlin.coroutines.coroutineContext

sealed class ModelMessage

data class LoadProject(val projectDirectory: Path) : ModelMessage()

data class OpenFile(val file: Path) : ModelMessage()

object ExportMappings : ModelMessage()

data class DecompileMinecraft(val mcpConfigZip: Path) : ModelMessage()

data class SetInitialMappings(val srgMappingsZip: Path) : ModelMessage()

data class Rename(val file: Path, val old: String, val new: String) : ModelMessage()

class RetrieveMappings(parent: Job? = null) : ModelMessage() {
    val result = CompletableDeferred<Map<String, SrgMapping>>(parent = parent)
}

suspend fun SendChannel<ModelMessage>.retrieveMappings(): Map<String, SrgMapping> {
    return RetrieveMappings(coroutineContext[Job]).let { msg ->
        send(msg)
        msg.result.await()
    }
}
