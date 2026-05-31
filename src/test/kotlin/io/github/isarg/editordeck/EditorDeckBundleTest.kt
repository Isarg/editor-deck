package io.github.isarg.editordeck

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.ResourceBundle

class EditorDeckBundleTest {
    @Test
    fun `simplified chinese locale uses chinese bundle`() {
        val bundle = ResourceBundle.getBundle("messages.EditorDeckBundle", Locale.SIMPLIFIED_CHINESE)

        assertEquals("新建分组", bundle.getString("action.new.group.text"))
    }
}
