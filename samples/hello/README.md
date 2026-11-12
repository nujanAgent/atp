<!-- SPDX-License-Identifier: Apache-2.0 -->

# Hello ATP

An Android app that offers one tool, `greet`, to agents on the same phone. It is the whole of what an app writes to
offer tools over ATP: the provider kit does the Binder service, the consent screen, grants and channels.

| File | Lines | What it says |
| --- | --- | --- |
| `src/main/kotlin/.../HelloTools.kt` | 15 | The tool: name, description, its argument (`AtpParam`), its title and the handler |
| `src/main/res/xml/atp_provider.xml` | 7 | Where the tools are listed (`entry`), and placeholders for the privacy policy and a contact |
| `src/main/AndroidManifest.xml` | 4 | The app's name; the kit merges in its service and screens |
| `build.gradle.kts` | 1 of 23 | `implementation(project(":atp-provider"))` |

Lines are counted without comments or blank lines. The argument is declared once with `AtpParam.string`: it makes the
schema, a call without it is answered with an error before the handler runs, and the handler reads it with
`args.required(name)`. The title is the name a person reads for the tool; without one the kit shows the tool's name
made readable. A tool can also be an `AtpTool` with a hand-written JSON Schema and the raw arguments.

## Build and try it

With `JAVA_HOME` at JDK 17, from the repository's root (`gradlew.bat` in place of `./gradlew` on Windows):

```sh
./gradlew :samples:hello:testDebugUnitTest :samples:hello:assembleDebug
adb install -r samples/hello/build/outputs/apk/debug/hello-debug.apk
```

An ATP host on the phone, such as NuJan, lists Hello ATP among the apps it can connect. Connecting shows the kit's
consent screen in Hello ATP; once allowed, the agent can call `greet` with `{"name": "Ada"}` and gets `Hello, Ada!`.

The unit test checks `atp_provider.xml` against the spec with `AtpProviderXml.problems`, which names the sample's
placeholder policy and contact under example.com and nothing else, and calls the tool directly, the way an app tests
its own tools without a phone. An app's file with its own policy and contact passes with no problems.

## What an app adds for more

- Groups: tools that need the person's grant name a `group`, and the XML declares it (`<group id="files" …>`,
  SPEC.md 6.4). A tool without one is open to every connected agent. A person may set a group to ask each time, and
  the kit's question about each call names the tool by its title.
- Instructions: `AtpToolSource.instructions()` tells an agent how the tools fit together.
- The app's own screens: in an `atpTool` handler, `args.call.ui.show(intent, reason)` brings up one of the app's
  activities while a call runs; an `AtpTool` handler reaches the same as `call.ui.show`.
