package com.vaultnote.core.common

import com.vaultnote.core.common.model.NoteBlock
import com.vaultnote.core.common.model.NoteBlockType
import com.vaultnote.core.common.model.NoteBodyDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object NoteBodyCodec {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
        allowSpecialFloatingPointValues = false
    }

    fun encode(document: NoteBodyDocument): String {
        require(document.version == NoteBodyDocument.CURRENT_VERSION)
        validate(document)
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("version", document.version)
                put(
                    "blocks",
                    buildJsonArray {
                        document.blocks.forEach { block ->
                            add(
                                buildJsonObject {
                                    put("id", block.id)
                                    put("type", block.type.name)
                                    put("text", block.text)
                                    put("checked", block.isChecked)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    fun decodeOrNull(value: String?): NoteBodyDocument? {
        if (value == null) return null
        return runCatching {
            val root = json.parseToJsonElement(value).jsonObject
            require(root.keys == setOf("version", "blocks"))
            val version = root.getValue("version").jsonPrimitive.int
            require(version == NoteBodyDocument.CURRENT_VERSION)
            val blocks = root.getValue("blocks").jsonArray.map { element ->
                val block = element.jsonObject
                require(block.keys == setOf("id", "type", "text", "checked"))
                NoteBlock(
                    id = block.getValue("id").jsonPrimitive.contentOrNull.orEmpty(),
                    type = enumValueOf(block.getValue("type").jsonPrimitive.content),
                    text = block.getValue("text").jsonPrimitive.content,
                    isChecked = block.getValue("checked").jsonPrimitive.boolean,
                )
            }
            NoteBodyDocument(version = version, blocks = blocks).also(::validate)
        }.getOrNull()
    }

    fun derivePlainText(document: NoteBodyDocument): String {
        validate(document)
        return document.blocks.joinToString(separator = "\n") { block ->
            when (block.type) {
                NoteBlockType.PARAGRAPH -> block.text
                NoteBlockType.CHECKLIST_ITEM ->
                    "${if (block.isChecked) "[x]" else "[ ]"} ${block.text}"
            }
        }
    }

    fun fromPlainText(text: String, idFactory: () -> String): NoteBodyDocument {
        val lines = text.split('\n')
        val blocks = lines.map { line ->
            NoteBlock(
                id = idFactory(),
                type = NoteBlockType.PARAGRAPH,
                text = line,
            )
        }.ifEmpty {
            listOf(NoteBlock(idFactory(), NoteBlockType.PARAGRAPH, ""))
        }
        return NoteBodyDocument(blocks = blocks)
    }

    private fun validate(document: NoteBodyDocument) {
        require(document.blocks.size <= MAX_BLOCKS)
        require(document.blocks.map(NoteBlock::id).toSet().size == document.blocks.size)
        var codePoints = 0L
        document.blocks.forEach { block ->
            require(block.id.isNotBlank() && block.id.length <= MAX_ID_UTF16_UNITS)
            require(block.text.none { it == '\u0000' })
            require(block.type == NoteBlockType.CHECKLIST_ITEM || !block.isChecked)
            codePoints += block.text.codePointCount(0, block.text.length)
        }
        require(codePoints <= VaultConstraints.MAX_NOTE_BODY_CHARACTERS)
    }

    private const val MAX_BLOCKS = 10_000
    private const val MAX_ID_UTF16_UNITS = 128
}
