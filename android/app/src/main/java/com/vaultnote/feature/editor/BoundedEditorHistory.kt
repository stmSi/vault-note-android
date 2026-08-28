package com.vaultnote.feature.editor

import com.vaultnote.core.common.model.NoteBodyDocument

/**
 * A bounded, in-memory history for the editable text fields of one open note.
 *
 * History is intentionally excluded from Room and saved-instance state: persisting every
 * keystroke would increase storage churn and could duplicate sensitive note text. The active
 * ViewModel retains this history across rotation, while process death keeps only the autosaved
 * current draft.
 */
internal class BoundedEditorHistory(
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
    private val maximumCharacterWeight: Int = DEFAULT_MAXIMUM_CHARACTER_WEIGHT,
) {
    private data class WeightedSnapshot(
        val snapshot: EditorHistorySnapshot,
        val characterWeight: Int,
    )

    private val undo = ArrayDeque<WeightedSnapshot>()
    private val redo = ArrayDeque<WeightedSnapshot>()
    private var undoCharacterWeight = 0
    private var redoCharacterWeight = 0

    init {
        require(maximumEntries > 0)
        require(maximumCharacterWeight > 0)
    }

    val canUndo: Boolean
        get() = undo.isNotEmpty()

    val canRedo: Boolean
        get() = redo.isNotEmpty()

    fun record(snapshot: EditorHistorySnapshot) {
        clearRedo()
        if (undo.lastOrNull()?.snapshot == snapshot) return
        undoCharacterWeight += undo.addBounded(snapshot)
        undoCharacterWeight = undo.trimToBounds(undoCharacterWeight)
    }

    fun undo(current: EditorHistorySnapshot): EditorHistorySnapshot? {
        val target = undo.removeLastOrNull() ?: return null
        undoCharacterWeight -= target.characterWeight
        redoCharacterWeight += redo.addBounded(current)
        redoCharacterWeight = redo.trimToBounds(redoCharacterWeight)
        return target.snapshot
    }

    fun redo(current: EditorHistorySnapshot): EditorHistorySnapshot? {
        val target = redo.removeLastOrNull() ?: return null
        redoCharacterWeight -= target.characterWeight
        undoCharacterWeight += undo.addBounded(current)
        undoCharacterWeight = undo.trimToBounds(undoCharacterWeight)
        return target.snapshot
    }

    fun clear() {
        undo.clear()
        undoCharacterWeight = 0
        clearRedo()
    }

    private fun clearRedo() {
        redo.clear()
        redoCharacterWeight = 0
    }

    private fun ArrayDeque<WeightedSnapshot>.addBounded(
        snapshot: EditorHistorySnapshot,
    ): Int {
        if (lastOrNull()?.snapshot == snapshot) return 0
        val weight = snapshot.characterWeight()
        addLast(WeightedSnapshot(snapshot, weight))
        return weight
    }

    private fun ArrayDeque<WeightedSnapshot>.trimToBounds(currentWeight: Int): Int {
        var weight = currentWeight
        while ((size > maximumEntries || weight > maximumCharacterWeight) && size > 1) {
            weight -= removeFirst().characterWeight
        }
        return weight
    }

    private fun EditorHistorySnapshot.characterWeight(): Int {
        val blockCharacters = bodyDocument.blocks.sumOf { block -> block.text.length.toLong() }
        return (title.length.toLong() + tagsText.length + blockCharacters)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private companion object {
        const val DEFAULT_MAXIMUM_ENTRIES = 50
        const val DEFAULT_MAXIMUM_CHARACTER_WEIGHT = 1_000_000
    }
}

internal data class EditorHistorySnapshot(
    val title: String,
    val bodyDocument: NoteBodyDocument,
    val tagsText: String,
)
