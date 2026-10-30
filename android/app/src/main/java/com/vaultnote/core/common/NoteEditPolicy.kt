package com.vaultnote.core.common

import com.vaultnote.core.common.model.NoteBodyDocument

/** Content writes cannot change protection settings or remove/alter protected lines. */
object NoteEditPolicy {
    fun allowsBodyChange(current: NoteBodyDocument, next: NoteBodyDocument): Boolean {
        if (current.isReadOnly) return current == next
        if (next.isReadOnly) return false
        val protected = current.blocks.filter { it.isReadOnly }
        // Equality includes text, type, checklist state, stable identity and protection.
        return next.blocks.filter { it.isReadOnly } == protected
    }
}
