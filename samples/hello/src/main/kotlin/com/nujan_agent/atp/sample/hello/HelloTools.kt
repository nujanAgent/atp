// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.sample.hello

import com.nujan_agent.atp.provider.AtpCaller
import com.nujan_agent.atp.provider.AtpParam
import com.nujan_agent.atp.provider.AtpResult
import com.nujan_agent.atp.provider.AtpToolSource
import com.nujan_agent.atp.provider.atpTool

/** The app's tools, as an MCP server lists them: a name, a description, its arguments, a title and a handler. */
object HelloTools : AtpToolSource {
    private val name = AtpParam.string("name", "Who to greet.")

    override fun tools(caller: AtpCaller) = listOf(
        atpTool("greet", "Greet a person by name.", name, title = "Greet a person") { args ->
            val who = args.required(name).trim()
            if (who.isEmpty()) AtpResult.error("greet needs a name.") else AtpResult.ok("Hello, $who!")
        },
    )
}
