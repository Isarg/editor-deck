package io.github.isarg.editordeck.editor

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenEditorsServiceThreadingTest {
    @Test
    fun `preview editor state is read only on EDT`() {
        assertTrue(openEditorsShouldReadPreviewState(isDispatchThread = true))
        assertFalse(openEditorsShouldReadPreviewState(isDispatchThread = false))
    }
}
