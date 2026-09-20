package com.natan.mediaportal

import org.junit.Assert.*
import org.junit.Test

class TelegramErrorTest {
    @Test fun unknownErrorsKeepTelegramsReasonVisible() {
        val error = TelegramError(400, "USER_DEACTIVATED_BAN")
        assertTrue(error.message!!.contains("USER_DEACTIVATED_BAN"))
        assertFalse(error.chatUnavailable)
    }
    @Test fun missingChatsAreRecognisedSoSavedMessagesCanTakeOver() {
        listOf("Chat not found", "CHANNEL_PRIVATE", "Can't access the chat", "CHAT_ID_INVALID").forEach { assertTrue(it, TelegramError(400, it).chatUnavailable) }
        assertEquals("That chat is no longer available. Choose another chat or use Saved Messages.", TelegramError(400, "Chat not found").message)
        assertFalse(TelegramError(400, "Message not found").chatUnavailable)
    }
    @Test fun signInAndConnectionErrorsStayFriendly() {
        assertEquals("Check your phone number, including its country code.", TelegramError(400, "PHONE_NUMBER_INVALID").message)
        assertEquals("That code is invalid or expired. Please try again.", TelegramError(400, "PHONE_CODE_EXPIRED").message)
        assertEquals("Telegram has asked you to wait before trying again.", TelegramError(429, "Too Many Requests: retry after 30").message)
        assertEquals("Telegram couldn’t reach its servers. Check your connection and try again.", TelegramError(500, "Network is unreachable").message)
        assertEquals("This file is no longer in Telegram.", TelegramError(400, "Message not found").message)
    }
}
