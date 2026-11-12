// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.sample.hello

import com.nujan_agent.atp.provider.AtpCall
import com.nujan_agent.atp.provider.AtpCaller
import com.nujan_agent.atp.provider.AtpProviderXml
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HelloToolsTest {
    private val caller = AtpCaller("com.example.agent", "00", pinned = false)

    /**
     * A host that reads a provider file breaking SPEC.md 5.2 treats the app with more caution or not at all. The sample's
     * privacy policy and contact are placeholders under example.com, which the check names; an app's own file has no
     * problems at all, so its test expects an empty list.
     */
    @Test
    fun `the provider file follows the spec, apart from its placeholder addresses`() {
        assertEquals(
            listOf(
                "privacyPolicy is a placeholder: example.com is a reserved name (RFC 2606, RFC 6761).",
                "contact is a placeholder: example.com is a reserved name (RFC 2606, RFC 6761).",
            ),
            AtpProviderXml.problems(File("src/main/res/xml/atp_provider.xml").readText()),
        )
    }

    @Test
    fun `greet answers with the name and refuses a call without one`() = runBlocking {
        val greet = HelloTools.tools(caller).single()

        assertEquals("Hello, Ada!", greet.handler.call(AtpCall("greet", """{"name":"Ada"}""", caller)).text)
        assertTrue(greet.handler.call(AtpCall("greet", "{}", caller)).isError)
    }
}
