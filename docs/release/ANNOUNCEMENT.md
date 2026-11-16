<!-- SPDX-License-Identifier: Apache-2.0 -->

# ATP 0.1: MCP tools between Android apps on the same phone

This is the first public release of ATP, the App Tools Protocol: specification version 0.1, with the Android provider
kit at version `atp-v0.24`. Both are open source under the Apache License 2.0.

ATP lets an Android app offer tools to an agent app on the same phone. The tools are Model Context Protocol (MCP)
tools, and the messages between the two apps are MCP's messages, unchanged. ATP adds what Android needs and MCP does
not define: a transport between two sandboxed apps, a way to find the apps that offer tools, consent in both apps,
identity on every call, and rules for apps that the system freezes, kills or updates.

## Why

An agent app on a phone acts for its user, and much of what it could do lives in other apps: an editor's projects, a
terminal's Linux system, the files and devices an app was granted. Today an agent reaches those apps through intents
and deep links, which open a screen but describe no tool; through accessibility services, which act on the screen with
the reach of the person; or, on Android 16 and later, through AppFunctions, an experimental platform API for which,
during the experiment, only a limited number of apps and system agents can access the entire pipeline.

MCP already gives agents a common way to call tools, but its standard transports do not fit two apps on one phone: a
process an app starts runs under that app's own identity, and a loopback port is open to every app on the device. And
the app that offers a tool holds the person's data, so it must decide which agent apps may use it, and know who is
asking.

## How it works

- **Discovery.** A host reads a provider's `atp_provider.xml` through PackageManager, without running the provider's
  code: its servers, its groups of tools, its privacy policy and a contact.
- **Consent in both apps.** The host's own sheet comes first, saying what it will send and that results may reach its
  AI model. Then the provider's consent screen, in its own process and protected against overlays, offers Allow, Deny
  and Block.
- **Grants per group.** Each group of tools is on, asks each time, or off. Groups the provider reserves for its own
  screens, such as running commands, can be turned on only there. A grant unused for 90 days expires.
- **Transport.** The host hands the provider two pipes over Binder, and MCP's stdio framing runs on them. Every call
  is checked against what Android reports about the caller (uid, package, signing key), never what an app claims.
- **Turns and Stop.** Calls name the turn they belong to, so a provider's Stop button can end that turn without
  disconnecting the host.

## What is in the release

- `spec/SPEC.md`, version 0.1. It freezes the Binder interfaces (binding version 1), the wire values and the golden
  fixtures; anything else may change before 1.0.
- The AIDL and 16 golden JSON fixtures that both sides test against.
- `:atp-wire`, plain Kotlin shared by hosts and providers: framing, codecs, identity checks, nonces, tool naming.
- `:atp-provider`, the provider kit (an Android library, minSdk 24). An app adds its tools and its XML file, and gets
  the Binder service, the consent and Connected apps screens, grants per group and the channels. `AtpProviderXml`
  checks the XML in a unit test.
- `samples/hello/`, an app that offers one tool in 26 lines.
- A technical paper, a provider quickstart, a host guide, the security model and a changelog.

NuJan, an Android agent app, is the reference host.

## Testing

The repository holds 158 unit tests (54 for the wire module, 102 for the provider kit and 2 for the sample) and 16
golden fixtures that pin the wire values both sides test against. This release reports no performance measurements,
and there has been no independent security review. The paper lists what is not covered.

## Try it

To offer a tool from your app, follow `docs/release/QUICKSTART_PROVIDER.md`: export the drop into your app, add your
`atp_provider.xml` and a tool source, and check the XML with `AtpProviderXml.problems`. To see one work, build and
install the sample:

```sh
./gradlew :samples:hello:testDebugUnitTest :samples:hello:assembleDebug
adb install -r samples/hello/build/outputs/apk/debug/hello-debug.apk
```

With an ATP host such as NuJan on the phone, Hello ATP appears among the apps it can connect, and after the consent
screen its agent can call `greet`. To build a host, read `docs/release/GUIDE_HOST.md`; a host needs only the wire
module and the AIDL.

Source: https://github.com/nujanAgent/atp

## Open work

- In the kit: results cannot yet carry a `resource_link`, and tool code cannot yet send progress.
- Measurements of the transport and the kit on Android 14, 15 and 16, with Android's app freezer on.
- More MCP protocol versions; ATP 0.1 speaks MCP 2025-06-18 only.
- MCP resources and prompts, which version 0.1 does not cover.

If you build a provider or a host, say in an issue what was hard and what the specification leaves unclear.

Source: https://github.com/nujanAgent/atp.

"Model Context Protocol" and "MCP" are trademarks of LF Projects, LLC; ATP is an independent protocol for use with MCP.
Android is a trademark of Google LLC.
