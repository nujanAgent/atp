# ATP: App Tools Protocol for Android

ATP lets an Android app offer tools to an agent app on the same phone. The tools are Model Context Protocol (MCP)
tools, and the two apps exchange MCP's messages unchanged, over pipes handed across Binder. ATP adds what Android needs
and MCP does not define: discovery of the apps that offer tools without running their code, consent in both apps,
grants per group of tools, the caller's identity from the platform on every call, and lifecycle rules for apps the
system freezes, kills or updates.

This repository holds the specification, the Binder interfaces and golden fixtures, a Kotlin wire module for hosts and
providers, an Android provider kit, and a sample provider.

## Status

- The specification is a draft at version 0.1. It freezes the Binder interfaces (binding version 1), the wire values
  and the golden fixtures; anything else may change before 1.0. It covers MCP tools at protocol version `2025-06-18`;
  resources and prompts are not covered yet.
- The provider kit is at version `atp-v0.22`. Its known issues are listed at the end of the
  [provider quickstart](docs/release/QUICKSTART_PROVIDER.md).
- NuJan, an Android agent app, is the reference host.

## Getting started

To offer a tool from your app, follow the [provider quickstart](docs/release/QUICKSTART_PROVIDER.md). In short: vendor
the kit into your build with `tools/export-drop.ps1`, describe your app in `res/xml/atp_provider.xml`, and write a tool
source. The kit provides the Binder service, the consent screen, the grants and the channels. The sample's tool source,
without its imports:

```kotlin
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

[`samples/hello/`](samples/hello) is the complete provider. To build an agent app that calls such tools, read the
[host guide](docs/release/GUIDE_HOST.md); a host needs only the wire module and the AIDL.

## Documentation

| Document | Contents |
|---|---|
| [Specification](spec/SPEC.md) | ATP version 0.1, the normative text |
| [Provider quickstart](docs/release/QUICKSTART_PROVIDER.md) | Offering a first tool from an Android app with the kit |
| [Host guide](docs/release/GUIDE_HOST.md) | What an agent app implements to use providers |
| [Security model](docs/release/SECURITY_MODEL.md) | What ATP protects, against whom, and what it leaves to others |
| [Paper](docs/release/PAPER.md) | The design, its rationale, related work and open questions |
| [Announcement](docs/release/ANNOUNCEMENT.md) | The release on one page |
| [Changelog](docs/release/CHANGELOG.md) | Changes by version |

## Repository layout

| Path | Contents |
|---|---|
| `spec/` | The specification |
| `atp/aidl/` | The provider and host Binder interfaces (`com.nujan_agent.atp.v1`), frozen at binding version 1 |
| `atp/fixtures/` | Golden JSON values that implementations test against |
| `atp/wire/` | `:atp-wire`: framing, codecs, identity checks, nonces and tool naming; plain Kotlin, no Android dependency |
| `atp/provider/` | `:atp-provider`: the provider kit, an Android library (minSdk 24) |
| `samples/hello/` | A provider app that offers one tool |
| `tools/export-drop.ps1` | Copies a tagged version of the kit into an app's `third_party/atp`, with `SHA256SUMS` |
| `docs/release/` | The guides, the security model, the paper and the changelog |

## Building

You need JDK 17, with `JAVA_HOME` pointing at it, and the Android SDK, with `ANDROID_HOME` pointing at it; the modules
compile against API 37. The build uses Gradle 9.4.1 through the wrapper, the Android Gradle Plugin 9.2.1 and Kotlin
2.4.0. The kit compiles to Kotlin 2.1's language and API level, so apps on older Kotlin toolchains can use it.

From the repository's root (`gradlew.bat` in place of `./gradlew` on Windows):

```sh
./gradlew :atp-wire:test :atp-provider:testDebugUnitTest :atp-provider:lintDebug :atp-provider:assembleRelease
./gradlew :samples:hello:testDebugUnitTest :samples:hello:assembleDebug
```

Exporting the kit into an app uses PowerShell 7 (`pwsh`); the quickstart shows how.

## Contributing

Issues and pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). Report security vulnerabilities
privately, as [SECURITY.md](SECURITY.md) describes.

## License

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).

"Model Context Protocol" and "MCP" are trademarks of LF Projects, LLC; ATP is an independent protocol for use with MCP.
Android is a trademark of Google LLC.
