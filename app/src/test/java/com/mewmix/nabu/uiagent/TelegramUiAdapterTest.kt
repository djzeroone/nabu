package com.mewmix.nabu.uiagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramUiAdapterTest {
    @Test
    fun identifiesUniqueSavedMessagesRowOnChatList() {
        val context = TelegramUiAdapter.inspect(chatList(savedRows = 1))!!

        assertEquals(TelegramSurface.CHAT_LIST, context.surface)
        assertEquals("Saved Messages", context.targets.single { it.kind == "chat" }.label)
        assertTrue(context.ambiguousKinds.isEmpty())
    }

    @Test
    fun refusesToExposeAmbiguousSavedMessagesRows() {
        val context = TelegramUiAdapter.inspect(chatList(savedRows = 2))!!

        assertTrue(context.targets.none { it.kind == "chat" })
        assertEquals(2, context.ambiguousKinds["chat:Saved Messages"])
    }

    @Test
    fun identifiesChatComposerAndSendControl() {
        val context = TelegramUiAdapter.inspect(
            UiTreeIndexer.parse(
                """
                <hierarchy>
                  <node package="org.telegram.messenger.web" class="android.widget.FrameLayout" bounds="[0,0][1080,2376]">
                    <node text="Saved Messages" class="android.widget.TextView" bounds="[100,80][700,180]" />
                    <node hint="Message" resource-id="org.telegram.messenger.web:id/chat_message_edit_text" class="android.widget.EditText" editable="true" clickable="true" bounds="[20,2100][850,2250]" />
                    <node content-desc="Send message" resource-id="org.telegram.messenger.web:id/chat_send_button" class="android.widget.ImageButton" clickable="true" bounds="[880,2100][1060,2250]" />
                  </node>
                </hierarchy>
                """.trimIndent()
            )
        )!!

        assertEquals(TelegramSurface.CHAT, context.surface)
        assertTrue(context.targets.any { it.kind == "composer" })
        assertTrue(context.targets.any { it.kind == "send" })
    }

    @Test
    fun ignoresOtherPackages() {
        assertNull(TelegramUiAdapter.inspect(chatList(savedRows = 1).copy(packageName = "com.example")))
    }

    private fun chatList(savedRows: Int): UiScreenState {
        val rows = (0 until savedRows).joinToString("\n") { index ->
            """
            <node class="android.widget.FrameLayout" clickable="true" bounds="[0,${300 + index * 180}][1080,${470 + index * 180}]">
              <node text="Saved Messages" class="android.widget.TextView" bounds="[120,${320 + index * 180}][700,${380 + index * 180}]" />
            </node>
            """.trimIndent()
        }
        return UiTreeIndexer.parse(
            """
            <hierarchy>
              <node package="org.telegram.messenger.web" class="android.widget.FrameLayout" bounds="[0,0][1080,2376]">
                <node content-desc="Search" class="android.widget.ImageButton" clickable="true" bounds="[900,80][1060,220]" />
                $rows
              </node>
            </hierarchy>
            """.trimIndent()
        )
    }
}
