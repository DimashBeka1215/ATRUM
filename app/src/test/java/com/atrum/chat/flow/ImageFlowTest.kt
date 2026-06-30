package com.atrum.chat.flow

import com.atrum.chat.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageFlowTest {

    @Test
    fun `test parsing external image link`() {
        val dc1 = 17.toChar() // DC1
        val rs = 30.toChar()  // RS
        val us = 31.toChar()  // US
        
        val timestamp = 1700000000000L
        val userId = "user123"
        val imageUrl = "https://example.com/image.jpg"
        val caption = "Check this out!"
        
        // Format: <US>timestamp<US><RS>userId<RS>Sender: <DC1>url<DC1>caption
        val rawDecrypted = "${us}${timestamp}${us}${rs}${userId}${rs}Alice: ${dc1}${imageUrl}${dc1}${caption}"
        
        val message = Message.fromDecrypted(
            decrypted = rawDecrypted,
            currentUserId = "user456",
            currentUserName = "Bob"
        )
        
        assertEquals("Alice", message.sender)
        assertEquals(caption, message.text)
        assertEquals(imageUrl, message.imageFileName)
        assertEquals(timestamp, message.timestampMs)
        assertEquals(userId, message.senderUserId)
        assertEquals(false, message.isSelf)
    }

    @Test
    fun `test parsing legacy img_ link`() {
        val dc1 = 17.toChar()
        val rawDecrypted = "Alice: ${dc1}img_123.txt${dc1}Hello"
        
        val message = Message.fromDecrypted(
            decrypted = rawDecrypted,
            currentUserId = "user456",
            currentUserName = "Bob"
        )
        
        assertEquals("Alice", message.sender)
        assertEquals("Hello", message.text)
        assertEquals("img_123.txt", message.imageFileName)
    }

    @Test
    fun `test composing and parsing external image link`() {
        val senderName = "Alice"
        val senderUserId = "user123"
        val text = "Check this out!"
        val imageUrl = "https://example.com/image.jpg"
        val timestamp = 1700000000000L

        val plaintext = Message.composePlaintext(
            senderName = senderName,
            senderUserId = senderUserId,
            text = text,
            imageFileName = imageUrl,
            timestampMs = timestamp
        )

        val parsed = Message.fromDecrypted(
            decrypted = plaintext,
            currentUserId = "user456",
            currentUserName = "Bob"
        )

        assertEquals(senderName, parsed.sender)
        assertEquals(text, parsed.text)
        assertEquals(imageUrl, parsed.imageFileName)
        assertEquals(senderUserId, parsed.senderUserId)
        assertEquals(timestamp, parsed.timestampMs)
    }
}
