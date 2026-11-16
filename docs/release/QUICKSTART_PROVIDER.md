<!-- SPDX-License-Identifier: Apache-2.0 -->

# Quickstart: offer a tool from your Android app

This guide turns an Android app into an ATP provider: an app that offers MCP tools to an agent app (a host) on the
same phone. It uses the provider kit at drop `atp-v0.24`, which implements ATP specification version 0.1.

The kit does the protocol work: the Binder service that hosts bind to, the consent screen in its own process, grants
per group of tools (on, ask each time, off), the question the person answers for a call in a group set to ask, the
Connected apps screen, and the channels that carry MCP messages. Once the drop is in your build (step 1), you write
three things:

1. `res/xml/atp_provider.xml`, which hosts read before they connect;
2. a tool source, which lists your tools and runs them;
3. one dependency line.

`samples/hello/` in this repository is the whole of it in 26 lines (a 15-line tool source, 7 lines of XML and 4 of
manifest, counted without comments or blank lines). This guide follows it and then shows what an app adds for more.

## What you need

- JDK 17, with `JAVA_HOME` pointing at it.
- The toolchain this repository builds with: Gradle 9.4.1, Android Gradle Plugin 9.2.1, Kotlin 2.4.0 and
  compileSdk 37. The kit's own code is compiled to Kotlin 2.1's language and API level and needs minSdk 24. Only this
  toolchain has been built; on an Android Gradle Plugin older than 9, apply the Kotlin Android plugin in the provider
  module's build file, since AGP 9's built-in Kotlin support is what compiles it here.
- PowerShell 7 (`pwsh`) to run the export script.
- To try it on a phone: an ATP host installed there, such as NuJan.

## 1. Vendor the drop

Apps take the kit as a drop: the tagged sources copied byte for byte into the app's repository. From the root of
this repository:

```sh
pwsh tools/export-drop.ps1 -Tag atp-v0.24 -Dest ../your-app/third_party/atp
```

The script accepts only a folder named `third_party/atp`, because it deletes that folder's old files before writing
the new ones. It writes `aidl/`, `fixtures/`, `wire/src/`, `provider/src/`, `provider/consumer-rules.pro`, `SPEC.md`,
`LICENSE`, `NOTICE`, a `README.md` naming the tag and commit, a `.gitattributes` that turns off line-ending
conversion, and `SHA256SUMS`, which a test in your build can check so that nobody edits the vendored files by
accident.

The two build files, `wire/build.gradle.kts` and `provider/build.gradle.kts`, are yours, and a later export leaves
them alone. Start from this repository's `atp/wire/build.gradle.kts` and `atp/provider/build.gradle.kts`. The
provider's build file compiles the drop's AIDL from the folder beside it:

```kotlin
// third_party/atp/provider/build.gradle.kts
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nujan_agent.atp.provider"
    compileSdk = 37
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { aidl = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

// The provider and host interfaces sit beside the module in every copy of the drop.
androidComponents {
    onVariants { variant -> variant.sources.aidl?.addStaticSourceDirectory(file("../aidl").absolutePath) }
}

tasks.withType<Test>().configureEach {
    systemProperty("atp.fixtures", file("../fixtures").absolutePath)
}

dependencies {
    api(project(":atp-wire"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
```

Include both modules in `settings.gradle.kts`:

```kotlin
include(":atp-wire")
project(":atp-wire").projectDir = file("third_party/atp/wire")
include(":atp-provider")
project(":atp-provider").projectDir = file("third_party/atp/provider")
```

Give your version catalog the entries the two build files name. These are the versions this repository uses:

```toml
[versions]
agp = "9.2.1"
kotlin = "2.4.0"
coroutines = "1.11.0"
serialization = "1.11.0"
junit = "4.13.2"

[libraries]
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

Declare the two plugins in your root build file with `apply false`, then add the dependency to your app module:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":atp-provider"))
}
```

## 2. Describe your app: `atp_provider.xml`

Put your own `app/src/main/res/xml/atp_provider.xml` in your app. It replaces the kit's placeholder, which has no
privacy policy and no contact and is reported as unfit to ship. The hello sample's file:

```xml
<atp-provider
    spec="1"
    privacyPolicy="https://example.com/privacy"
    contact="mailto:developer@example.com">
    <server id="main" mcpEras="2025-06-18" entry="com.nujan_agent.atp.sample.hello.HelloTools" />
</atp-provider>
```

- `spec="1"` is the version of this file's format.
- `privacyPolicy` (an `https:` URL) and `contact` (an `https:` or `mailto:` URL) are required and must be your own. A
  host shows both to the person before it connects. Names reserved for examples and tests, such as `example.com` or a
  name under `.invalid`, `.test` or `.localhost`, are reported as placeholders; the sample keeps them on purpose, and
  your app must not.
- `<server id="main">` is the server hosts open a channel to. `mcpEras` must include `2025-06-18`.
- `entry` names the class that lists the server's tools (step 3). Hosts ignore it; the kit reads it.

Tools that need the person's grant belong to a group, which the XML declares:

```xml
<atp-provider xmlns:android="http://schemas.android.com/apk/res/android"
    spec="1" privacyPolicy="https://example.com/privacy" contact="mailto:dev@example.com">
  <server id="main" mcpEras="2025-06-18" toolFamily="productivity" entry="com.example.notes.NotesTools" />
  <group id="notes" risk="low" kind="read write" grant="in-flow"
         android:title="@string/atp_group_notes" android:summary="@string/atp_group_notes_summary" />
</atp-provider>
```

| Attribute | Values |
|---|---|
| `id` | 1 to 32 of `a-z`, `0-9` and `-`, unique |
| `risk` | `low`, `medium`, `high` or `critical`; a high or critical group asks the person again before it turns on |
| `kind` | one or more of `read write exec egress install device` |
| `grant` | `in-flow`: the consent screen may grant it. `provider-ui`: only your app's Connected apps screen can turn it on |
| `android:title`, `android:summary` | at most 200 and 80 characters; shown as plain text |
| `toolFamily` (on `<server>`) | any of `files code device web media productivity`; advisory |

The file is capped at 64 KB, 16 servers and 32 groups. SPEC.md section 5.2 has the full rules.

## 3. Write the tool source

A tool source is a Kotlin object, or a class with a constructor taking your app's `Context` or nothing, that
implements `AtpToolSource`. The kit makes it from the class `entry` names, and its consumer ProGuard rules keep that
class through R8. The sample's tool source:

```kotlin
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
```

Each argument is declared once with `AtpParam`: `string`, `integer` (read as `Long`), `number` (`Double`), `boolean`,
`choice` (one of the values given) or `strings` (a list), each required unless you call `.optional()`. The parameters
make the tool's JSON Schema, and a call that misses a required argument or carries one of the wrong type is answered
with an error naming each fault before your handler runs. In the handler, `args.required(param)` reads a required
argument and `args[param]` an optional one (null when the call left it out).

Give every tool a `title`, the name a person reads: the kit's question about a call in a group set to ask names the
tool by it, and a host may show it. A tool without one is shown as its name made readable (`workspace_list` reads
"Workspace list").

A tool source with a group, hints and instructions:

```kotlin
class NotesTools(private val context: Context) : AtpToolSource {
    private val query = AtpParam.string("query", "Words to look for.")
    private val limit = AtpParam.integer("limit", "How many notes to return, 1 to 50.").optional()

    override fun tools(caller: AtpCaller) = listOf(
        atpTool(
            "notes_search", "Search the notes by the words they contain.", query, limit,
            group = "notes", annotations = """{"readOnlyHint":true}""", title = "Search notes",
        ) { args ->
            val count = (args[limit] ?: 10L).coerceIn(1L, 50L).toInt()
            val found = searchNotes(context, args.required(query), count) // your app's own code
            AtpResult.ok(found.joinToString("\n"))
        },
    )

    override fun instructions() = "notes_search returns one note per line, its id first."
}
```

- `group` names a group the XML declares. A tool with no group is open to every connected host.
- `annotations` carries MCP's hints as JSON text (`readOnlyHint`, `destructiveHint`, `idempotentHint`,
  `openWorldHint`).
- `instructions()` is sent to hosts with MCP's `initialize` result: plain text on how your tools fit together, at most
  8,000 characters (longer text is cut at the last line end that fits). A host may give it to its model as your text.

`tools(caller)` runs on every `tools/list` and `tools/call`, so it may list different tools for different callers or
over time. Call `AtpProvider.toolsChanged(context)` when the list changes, and connected hosts list again.

When a schema is more than the typed parameters can say, write the tool as an `AtpTool` with a hand-written JSON
Schema. Its handler receives the call's arguments as a JSON object's text in `call.arguments`:

```kotlin
AtpTool(
    name = "notes_attach",
    description = "Attach a file to a note. data is the file's bytes.",
    inputSchema = """{"type":"object","properties":{"id":{"type":"string"},
        "data":{"type":"string","contentEncoding":"base64"}},"required":["id","data"]}""",
    group = "notes",
    title = "Attach a file to a note",
) { call ->
    attach(call.arguments) // parse the JSON text with your own JSON library
    AtpResult.ok("Attached.")
}
```

Mark a string argument that carries a file's bytes with `"contentEncoding": "base64"`, as above. A host may then let
its model name a file the host holds, and send its bytes in the argument for you.

Handlers run on a background dispatcher and may block or suspend; a call the host cancels is cancelled as a
coroutine. Return failures as `AtpResult.error(text)`. An exception is caught and reaches the host as an error naming
it, but the text you write is better for the model that reads it.

Instead of `entry`, an app may set its tools in code with `AtpProvider.setTools(context, source)`, which takes the
place of the XML's class on every server.

## 4. Set the kit's options (optional)

Every option has a default. To change one, set it in your `Application.onCreate`:

```kotlin
class NotesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // End work the app started for a host when the person disconnects it, or it disconnects itself.
        AtpProvider.onDisconnected = { packageName -> cancelJobsFor(packageName) }
    }
}
```

| Option | Default | What it does |
|---|---|---|
| `AtpProvider.trustedHosts` | none pinned | Hosts you trust by package and SHA-256 of their signing certificate, as `TrustedHosts(pins, releaseBuild)`. A release build never treats a debug-signed caller as pinned. Pinning changes defaults only: the person is still asked. |
| `AtpProvider.admitsUntrusted` | `true` | `false` lets only pinned hosts connect. |
| `AtpProvider.initialGrant` | groups with `grant="in-flow"` on, the rest off | The group states a newly connected host starts with, given the caller and the declared groups. |
| `AtpProvider.onDisconnected` | nothing | Called when the person forgets a host or the host disconnects. |

For example, to start only pinned hosts with their in-flow groups on:

```kotlin
AtpProvider.trustedHosts = TrustedHosts(
    pins = setOf("com.example.agent" to "<sha-256 of its signing certificate, lowercase hex>"),
    releaseBuild = !BuildConfig.DEBUG,
)
AtpProvider.initialGrant = { caller, groups ->
    groups.associate { it.id to if (caller.pinned && it.grant == "in-flow") GroupState.ON else GroupState.OFF }
}
```

The consent screen offers the groups `initialGrant` turns on and names the rest as staying off until the person turns
them on in your app. In `atp-v0.24` it does not read the groups the host says it asks for.

## 5. Notifications for groups that ask

A call in a group the person set to ask each time is put to the person through a notification that opens the kit's
question screen (or the screen itself, while one of your app's windows is showing). The kit declares
`POST_NOTIFICATIONS`. On Android 13 and later that is a runtime permission: request it from your own screens if any
group may be set to ask. Without it, or with your app's notifications off, nobody can be asked and such calls are
refused with a message saying so.

## 6. Test without a phone

Check your XML against the specification in a plain unit test. An app's own file has no problems, so the test
expects an empty list:

```kotlin
class ProviderXmlTest {
    @Test
    fun `the provider file follows the spec`() {
        assertEquals(emptyList<String>(), AtpProviderXml.problems(File("src/main/res/xml/atp_provider.xml").readText()))
    }
}
```

`AtpProviderXml.problems` returns one sentence for each broken rule of SPEC.md section 5.2: the file format version,
the two addresses, ids, eras, families, risks, kinds, grants, and the caps on servers, groups, strings and size. A
resource reference such as `@string/title` is not resolved, so only literal strings' lengths are checked.

Call your tools directly, the way the sample's test does:

```kotlin
private val caller = AtpCaller("com.example.agent", "00", pinned = false)

@Test
fun `greet answers with the name and refuses a call without one`() = runBlocking {
    val greet = HelloTools.tools(caller).single()
    assertEquals("Hello, Ada!", greet.handler.call(AtpCall("greet", """{"name":"Ada"}""", caller)).text)
    assertTrue(greet.handler.call(AtpCall("greet", "{}", caller)).isError)
}
```

The sample's build adds `testImplementation(libs.junit)` and `testImplementation(libs.kotlinx.coroutines.android)`
for `runBlocking`.

## 7. Try it on a phone

Build and install your app, then open the host's list of apps that offer tools. In NuJan, connecting shows NuJan's own
sheet first and then your app's consent screen, which the kit provides. It names the host, its package and the start
of its signing key's digest, the purpose the host gave, what the host will be able to do, and Allow, Deny and Block;
Allow works only after the screen has been in front for a second. Once allowed, the host lists your tools under its
own names (`atp_hello_greet` for the sample's `greet`), and its agent can call them.

For the sample:

```sh
./gradlew :samples:hello:testDebugUnitTest :samples:hello:assembleDebug
adb install -r samples/hello/build/outputs/apk/debug/hello-debug.apk
```

## Going further

**The Connected apps screen.** `AtpConnectionsActivity` lists every host the person connected or blocked, with On,
Ask each time and Off for each group, and Disconnect. It is the only place a `provider-ui` group can be turned on,
and it asks again before a high or critical group turns on. It is exported for
`com.nujan_agent.atp.action.CONNECTED_APPS`, so a host can open it when the person asks to manage the connection.
Give it a way in from your own app too, such as a settings row. For your own screens, `AtpProvider.connectedApps`,
`AtpProvider.setGroup` and `AtpProvider.forget` expose the same records.

**Your app's screens during a call.** In a handler, `args.call.ui.show(intent, reason)` (or `call.ui.show` in an
`AtpTool` handler) brings up one of your own activities. With one of your windows on screen the kit starts it itself;
otherwise it asks the host, which shows it if the person is looking at the host, and waits up to a minute. It returns
`SHOWN`, `DECLINED` or `UNAVAILABLE`.

**Pictures and structured results.** `AtpResult(text, images = listOf(AtpImage("image/png", bytes)))` sends pictures
as MCP image content after the text, up to 8 MiB in all; pictures past that are left out with a line saying so.
`structured` takes MCP's `structuredContent` as a JSON object's text.

**Turns and Stop.** `call.turn` is the host's id for the turn the call belongs to, when the host names one. If your
app shows a sign while a host works (an overlay, a notification) and offers Stop on it, call
`AtpProvider.stopTurn(context, packageName, turn)` when the person presses it: the host is told at once and ends that
turn, and the connection stays open. Answer that turn's later calls with `AtpResult.stopped(text)`, which the host
ends the turn on and does not retry. Take the sign down when the turn ends, which the kit tells your tool source through
`AtpToolSource.turnEnded(caller, turn)`, and for a host that names no turns at most 30 seconds after the last call.

**Tools from an MCP server inside your app.** If your app runs MCP servers in its own sandbox, it may re-export their
tools: set `origin = "<server>"` on each `AtpTool`, and `ephemeral = true` when the tool exists only while that server
runs. Never re-export tools you got from another ATP provider.

**Long work.** A host gives a call 300 seconds. Answer within 240 seconds, or return a job handle and offer start,
status and cancel tools.

**Size.** One message carries at most 16 MiB; an answer larger than that is replaced by an error. Hand large files
over through a file rather than in a result.

## Rules your provider follows

The kit enforces most of SPEC.md for you. What remains is yours:

- Ship your own privacy policy and contact in the XML, and keep `AtpProviderXml.problems` empty in a test.
- A tool without a group is open to every connected host, so put each tool that reads or changes the person's data,
  runs code, reaches the network, installs anything or acts on the device in a group, with a `risk` and `kind` that
  say so. A host treats them as claims that can only raise its caution.
- Declare `grant="provider-ui"` for groups that must never be granted from the consent flow.
- Give each tool a `title`, and describe tools, instructions and results by your tools' wire names.
- Answer within 240 seconds or hand back a job; mark file bytes with `contentEncoding: "base64"`.
- Never re-export another ATP provider's tools.
- If you show a sign that a host is at work, take it down at most 30 seconds after the last call, and make Stop end
  the turn, not the connection.

## Known issues in `atp-v0.24`

- A result cannot carry an MCP `resource_link`, so the provider-to-host file handoff (SPEC.md 9.2) is not available.
- Tool code cannot send progress notifications, so long work needs a job handle.
- The typed parameters have no nested objects, no description per choice and no schema from a `@Serializable` class;
  use `AtpTool` with a hand-written schema for those.

## Where to read more

- `spec/SPEC.md`: the specification, version 0.1.
- `samples/hello/`: the sample this guide follows.
- `docs/release/GUIDE_HOST.md`: what a host does on the other side.
- `docs/release/SECURITY_MODEL.md`: the threat model and what ATP guarantees.

"Model Context Protocol" and "MCP" are trademarks of LF Projects, LLC; ATP is an independent protocol for use with MCP.
Android is a trademark of Google LLC.
