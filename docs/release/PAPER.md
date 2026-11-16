<!-- SPDX-License-Identifier: Apache-2.0 -->

# ATP: MCP Tools Between Android Apps on One Phone

**The App Tools Protocol, specification version 0.1**

NuJan, https://nujan-agent.com

Draft for review, November 2026. The specification, the wire module and the provider kit are licensed under Apache-2.0.

## Abstract

An agent app on a phone acts for its user, and much of what it could do lives in other apps, each in its own sandbox.
Agents reach those apps today through intents and deep links, which open a screen but describe no tool; through
accessibility services, which act on the screen with the reach of the person; or, on Android 16 and later, through
AppFunctions, an experimental platform API for which, during the experiment, only a limited number of apps and system
agents can access the entire pipeline. The Model Context Protocol (MCP) gives agents a common way to call tools, but
its standard transports do not fit two apps on one phone: a process an app starts runs under that app's identity, and
a loopback port is open to every app on the device. MCP also leaves consent to the client, and a client's name for
itself is a claim.

ATP, the App Tools Protocol, carries MCP's data layer unchanged and specifies only what Android lacks: a transport of
two pipes that the host creates and hands over Binder, discovery from the provider's manifest without running its
code, identity from the platform on every call, consent in both apps with grants per group of tools (on, ask each
time, off), and lifecycle rules for processes that are frozen, killed or updated. We describe version 0.1 of the
specification, its security model, a provider kit with which an Android app offers a tool in 26 lines and a reference
host, and we state what the implementation's tests cover and what has not been measured.

## 1 Introduction

An agent app runs a language model, on the phone or in the cloud, and turns a person's request into actions. Many
useful actions belong to other apps: a code editor's projects, a terminal's Linux system, the files and devices an app
was granted. Android keeps apps apart: each runs under its own Linux uid, the system starts it, and another app reaches
it only through the components it exports.

Agents act on other apps in three ways today. Intents and deep links start another app's screen or hand it data [9],
but describe no tool and return at most an activity result. An accessibility service [10] reads the screen and
performs gestures, acting through an interface designed for people. On Android 16 and later, AppFunctions [5] lets
apps expose functions that callers holding the `EXECUTE_APP_FUNCTIONS` permission discover and execute. Google
describes it as "the mobile equivalent of tools within the Model Context Protocol (MCP)", and says that during the
experimental phase "only a limited number of apps and system agents can access the entire pipeline" [5].

The Model Context Protocol [1, 2] gives agents a common way to discover and call tools, as JSON-RPC 2.0 messages [4]
over stdio or Streamable HTTP. Neither transport fits two apps on one phone. A process an app starts runs under that
app's uid, so stdio cannot reach another app's code with that app's data and permissions. A loopback port is reachable
by every app on the device. MCP also leaves the user interaction model to the client [2], and the `clientInfo` a client
sends is its own claim. On a phone, the app that offers a tool holds the person's data, so it too must decide which
agent apps may use it, and it must be able to tell who is asking.

ATP fills this gap. It carries MCP's data layer verbatim and specifies only what Android lacks [18, §2]: a same-device
transport, discovery, two-sided consent, identity on every call, and lifecycle rules. One rule shapes the design:
every trust anchor is something the platform enforces, such as the kernel uid of a Binder caller or the signer that
PackageManager reports, and never what a peer says about itself.

This paper makes four contributions:

1. A specification that maps MCP onto Android's process model: transport, Binder interfaces, discovery, identity,
   consent and grants, lifecycle, turns and Stop, handoffs and tool naming (Section 4).
2. A security model in which both apps authorize every call independently of any model, and every anchor is
   platform-enforced (Section 5).
3. An open-source implementation: a platform-independent wire module, an Android provider kit and golden fixtures,
   used by a reference host (Section 6).
4. An account of what the implementation's tests cover and of what has not yet been measured (Section 7).

This is the first public release of the ATP specification, version 0.1. The wire value `SPEC_VERSION` is `"0.1"`, the
Binder interfaces are frozen at binding version 1, and channels speak MCP protocol version `2025-06-18`. The provider
kit described here is the drop tagged `atp-v0.24`. The specification's status is v0: it freezes the Binder surface,
the wire values and the fixtures, and everything else may still change before 1.0 [18].

## 2 Background and related work

### 2.1 The Model Context Protocol

Anthropic introduced MCP in November 2024 [1]. Its revisions are named by date; this work builds on 2024-11-05,
2025-03-26 and 2025-06-18, and ATP 0.1 speaks 2025-06-18 only. A session opens with `initialize`, which the server
answers with its capabilities, server information and optional `instructions`; the client confirms with
`notifications/initialized` [2]. Servers offer tools, resources and prompts; clients may offer sampling, roots and
elicitation.

A tool has a `name`, an optional display `title`, a `description`, a JSON Schema `inputSchema`, an optional
`outputSchema` and optional `annotations`. A result carries content items (text, image, audio, resource links,
embedded resources), optional `structuredContent`, and `isError` for a failure of the tool rather than of the protocol.
Clients must consider annotations untrusted unless they come from trusted servers, and there should always be a human
in the loop with the ability to deny tool invocations [2].

MCP's two standard transports are stdio, with one message per line and no embedded newlines, and Streamable HTTP,
which replaced the HTTP+SSE transport of 2024-11-05. Custom transports are allowed if they preserve the JSON-RPC
message format and the lifecycle requirements [2]. ATP is such a transport, with the Android rules around it, and it
reuses the stdio framing unchanged.

### 2.2 Android facilities ATP relies on

- **Binder** tells a service its caller's uid from the kernel (`Binder.getCallingUid()`), whatever the caller claims.
  Synchronous transactions are limited in size; oneway transactions do not block the caller.
- **PackageManager** reports a package's signing certificates and, from API 28, its key-rotation history.
- **Package visibility.** For apps that target Android 11 or later, the system filters which other apps an app can
  see; an app declares the apps it needs with `<queries>` [6].
- **Platform objects.** A `ParcelFileDescriptor` travels over Binder, after which both processes hold it. The system
  records which app created a `PendingIntent`. An activity started for a result can read its caller's package, and
  from API 34 `getLaunchedFromUid()` returns the launcher's uid when the launcher opted in with
  `ActivityOptions.setShareIdentityEnabled(true)`.
- **Freezer and hibernation.** From Android 11 the cached apps freezer stops execution of cached processes [7]. Apps
  that target Android 11 or later and go unused for a few months enter hibernation, and their runtime permissions are
  reset [8].
- **Overlays.** From API 31 a window can hide other apps' overlays (`setHideOverlayWindows`, with the
  `HIDE_OVERLAY_WINDOWS` permission); a view can ignore touches that passed through another window
  (`filterTouchesWhenObscured`).

### 2.3 How agents act on other apps

**Intents and deep links** start a component of another app, optionally for a result [9]. They suit "open this
screen" or "share this file"; they carry no schema an agent can plan with, no structured result and no notion of a
grant beyond what the receiving app does on its own screen.

**Accessibility services** receive the content of the screen and can perform gestures [10]. An agent built on one
uses apps as a person would, without an interface the app designed for it, and with the reach of the person: the
service sees and acts on whatever is on screen.

**AppFunctions** is the closest related work. An app annotates functions; a Jetpack library generates an XML schema
the OS indexes; callers with `EXECUTE_APP_FUNCTIONS` discover and execute them, on devices running Android 16 or
higher [5]. It is a platform registry, experimental at the time of writing, with end-to-end access limited during the
experiment [5]. From its published description, ATP differs in two respects. It is a protocol between apps rather
than a platform service, so any app can act as a host, on the Android versions the kit supports (its minimum SDK is
24). And it carries MCP's messages unchanged, so a host reads tools, results and notifications as any MCP client does.
In ATP the app that offers a tool also decides, with the person, which hosts may use it; we do not compare this with
AppFunctions' access model, which is still experimental. ATP does not depend on AppFunctions, and nothing prevents an
app from offering the same functions through both.

### 2.4 Attacks that shaped the design

Three known classes of attack shaped the consent and text rules. Fratantonio et al. showed that an app with the
overlay and accessibility permissions can take complete control of the UI feedback loop [11]; consent screens are an
obvious target. Boucher and Anderson showed that bidirectional control characters make text display in an order
different from the order in which it is read by software [12]; an app's label or a stated purpose placed inside a
consent sentence is exposed to the same effect. Greshake et al. showed that instructions planted in data an LLM
application retrieves can steer it [13]; for an agent, a tool's description, its provider's instructions and its
results are such data.

## 3 Design goals and principles

ATP's six principles [18, §2] are the design goals. Each answers a failure mode of the alternatives.

1. **The MCP data layer travels verbatim.** Tools, schemas, results and notifications are MCP's own, so an MCP server
   or client needs a new transport, not a new protocol.
2. **ATP specifies only what Android lacks.** It adds transport, discovery, consent, identity and lifecycle, and
   nothing to MCP's tool model.
3. **Every trust anchor is platform-enforced:** the kernel uid of a Binder caller, the signer PackageManager reports,
   file descriptors held by two processes, system URI grants and the PendingIntent creator. A peer's claims about
   itself, such as intent extras, `clientInfo` or names, are never a trust anchor.
4. **Authorization happens twice, independently of any model:** the provider checks the host's grant for each tool's
   group, and the host applies its own policy. A model that follows injected text cannot widen either.
5. **A process that might be frozen is never called synchronously, and no binder thread is ever pinned.** Every call
   from provider to host is oneway, and data travels on pipes, never on Binder threads. The host's synchronous calls
   go to a provider it holds bound while it is itself in an active state (Section 4.8).
6. **A channel is a transport connection.** No MCP state may depend on it staying open: channels close on revocation,
   stalls, updates and when the host leaves an active state, and the host opens and initializes a new one.

ATP has explicit non-goals. It does not define a tool model, a registry or a remote transport; it works between apps
on one phone. It does not replace a host's own approval of a model's actions, and it does not judge what a tool does.

## 4 The protocol

This section summarizes the specification [18], which uses the key words MUST, SHOULD and MAY as RFC 2119 defines
them [14]; the specification is normative where the two differ.

### 4.1 Roles and terms

| Term | Meaning |
|---|---|
| Host | An app that runs an agent and calls tools. |
| Provider | An app that serves MCP tools over ATP. |
| Channel | One byte-stream pair per (host uid, provider package, `serverId`). It has no MCP-session meaning. |
| Group | A named set of a provider's tools that is granted or refused together (`files`, `exec`, ...). |
| Grant | A provider's record of what one host may use: groups, each ON, ASK or OFF. |
| Era | The MCP protocol version a channel speaks. Version 0.1 uses `2025-06-18` only. |

Identifiers use the namespace `com.nujan_agent.atp` on Android and `com.nujan-agent.atp` in MCP `_meta` keys.

### 4.2 Transport

ATP separates a control plane from a data plane.

| Plane | Carried by | Purpose |
|---|---|---|
| Control | Binder | identity, lifecycle, death and freeze signals, platform objects |
| Data | two pipes the host creates and hands over in `openChannel` | MCP stdio framing |

The host creates both pipes with `ParcelFileDescriptor.createPipe()`: `toProvider` (the host writes, the provider
reads) and `fromProvider` (the reverse). Immediately after `openChannel` returns, the host closes its copies of the
two ends it handed over, or end-of-stream never arrives. The provider checks each received descriptor with `fstat()`,
accepts only a FIFO and refuses anything else with `BAD_STREAM`; it sets `FD_CLOEXEC` and keeps channel descriptors out
of every child process.

Framing is `ndjson`, MCP's stdio framing: one UTF-8 JSON-RPC message per line, with no raw newline inside. Lines up to
16 MiB must be accepted; a receiver may close the channel with `PROTOCOL_ERROR` above 64 MiB and may do either in
between. Each channel has exactly one writer per side and a bounded write queue of 32 MiB. A writer that cannot drain
coalesces list-change notifications, keeps only the latest progress per token, and drops log messages once the queue
is half full. Close reasons travel on Binder (`IAtpHost.onClosed`), never in-band.

The specification rejects three alternatives: oneway AIDL messages as the data plane, which can drop messages and cap
their size; abstract-namespace sockets; and loopback HTTP, which any app on the device can reach.

### 4.3 Binder surface

The AIDL is frozen at binding version 1 in package `com.nujan_agent.atp.v1`:

```aidl
interface IAtpProvider {
    byte[] hello(in byte[] helloJson);
    byte[] openChannel(String serverId, in ParcelFileDescriptor toProvider,
                       in ParcelFileDescriptor fromProvider, IAtpHost host, in byte[] openJson);
    oneway void closeChannel(String serverId, int reason);
    oneway void uiResult(String serverId, int requestId, int resultCode);
    void releaseGrant();
}

interface IAtpHost {
    oneway void onUiRequest(int requestId, in PendingIntent intent, in byte[] reasonJson);
    oneway void onClosed(int reason, in byte[] detailJson);
    oneway void onServersChanged();
}
```

Parameters are only `int`, `long`, `String`, `byte[]`, `ParcelFileDescriptor`, `PendingIntent` and binders; JSON
travels as UTF-8 bytes, and receivers ignore unknown keys. Errors come back as status JSON, never thrown, because
`ServiceSpecificException` is not in the public SDK and Binder carries only a fixed set of exception types from app
code. A fixture pins the interface descriptors and transaction codes, and the binding version travels in `hello`'s
answer.

`openChannel` returns `{"status":0}` on success and otherwise `{"status":<code>,"name":"<NAME>","detail":"<text>"}`:

| Code | Name | Meaning |
|---|---|---|
| 0 | OK | The channel is open. |
| 1 | NOT_GRANTED | This host has no grant; run consent. |
| 2 | BLOCKED | The person blocked this host in the provider. |
| 3 | UNKNOWN_SERVER | No server with that id. |
| 4 | TOO_MANY_CHANNELS | The host already holds 8 channels with this provider. |
| 5 | UNSUPPORTED_VERSION | The binding or spec version in `openJson` is not served. |
| 6 | NOT_READY | The provider needs set-up first. |
| 7 | TRANSPORT_UNAVAILABLE | The provider cannot use the descriptors on this device. |
| 8 | BAD_STREAM | A descriptor is not a pipe. |
| 9 | RATE_LIMITED | More than 10 opens in the last minute. |
| 10 | IDENTITY_REFUSED | The caller failed the identity checks. |

`onClosed` and `closeChannel` carry a close reason:

| Value | Name | Meaning |
|---|---|---|
| 0 | NORMAL | Closed on purpose, nothing wrong. |
| 1 | REVOKED | The person revoked this host's grant. |
| 2 | BLOCKED | The person blocked this host. |
| 3 | PROVIDER_SHUTDOWN | The provider is stopping. |
| 4 | STALLED | The peer stopped reading. |
| 5 | PROTOCOL_ERROR | Bad framing, an oversized line or invalid JSON-RPC. |
| 6 | IDLE | Reserved; version 0.1 defines no idle period and nothing sends it. |
| 7 | IDENTITY_CHANGED | Reserved; a signer changes only by reinstalling, which ends the channel first. |
| 8 | EXPIRED | The grant expired from disuse. |

ATP's own JSON-RPC errors use codes outside the range JSON-RPC reserves (-32768 to -32000):

| Code | Name | When |
|---|---|---|
| -31000 | TOO_MANY_REQUESTS | More than 32 requests in flight on the channel. |
| -31001 | SHUTTING_DOWN | The channel is closing and the request was not started. |

An unknown tool is JSON-RPC `-32602`, as in MCP. A call to a tool the host is not granted is not a JSON-RPC error: it
is a `tools/call` result with `isError: true`, readable text, and `_meta {"com.nujan-agent.atp/missingGroups": [...]}`,
so the host can tell the person which group to turn on.

`hello` reveals only the caller's own grant state, the provider's static servers and its limits, and it must not throw
for an ungranted caller. On request it also returns a consent nonce:

```json
{"binding": 1, "spec": "0.1",
 "grant": {"state": "granted", "groups": {"files": "on", "exec": "ask", "mcp": "off"}},
 "servers": [{"id": "main", "mcpEras": ["2025-06-18"]}],
 "limits": {"channels": 8, "inFlight": 32, "opensPerMinute": 10, "maxLineBytes": 16777216},
 "nonce": "9f2c4e1a7b3d5f60a8c2e4d6f1b3a5c7", "nonceTtlMs": 300000}
```

`openJson` names what the host speaks: `{"framing": "ndjson", "binding": 1, "spec": "0.1", "era": "2025-06-18"}`.
A provider that does not serve the framing, binding or era answers `UNSUPPORTED_VERSION`.

### 4.4 Discovery

A provider declares an exported service for `com.nujan_agent.atp.action.PROVIDER` with no `android:permission` and a
`<meta-data>` entry pointing at `res/xml/atp_provider.xml`; an exported consent activity for
`com.nujan_agent.atp.action.CONNECT` in its own `android:process`, so the service process never shows UI; the
`HIDE_OVERLAY_WINDOWS` permission; and optionally a connected-apps activity for
`com.nujan_agent.atp.action.CONNECTED_APPS`. The XML describes servers and groups:

```xml
<atp-provider xmlns:android="http://schemas.android.com/apk/res/android"
    spec="1" privacyPolicy="https://example.com/privacy" contact="mailto:dev@example.com">
  <server id="main" mcpEras="2025-06-18" toolFamily="code files" android:title="@string/server_title" />
  <group id="files" risk="low" kind="read write" grant="in-flow"
         android:title="@string/g_files" android:summary="@string/g_files_s" />
  <group id="exec" risk="critical" kind="exec egress" grant="provider-ui"
         android:title="@string/g_exec" android:summary="@string/g_exec_s" />
</atp-provider>
```

| Attribute | Rule |
|---|---|
| `spec` | `1` for this version (the version of the file format). |
| `privacyPolicy`, `contact` | Required and the provider's own: an `https:` URL, and an `https:` or `mailto:` URL, neither at a name reserved by RFC 2606 or RFC 6761 [15, 16]. |
| `server@id`, `group@id` | `[a-z0-9-]{1,32}`, unique. |
| `mcpEras` | Space-separated MCP versions; version 0.1 requires `2025-06-18`. |
| `toolFamily` | From `files code device web media productivity`; advisory only. |
| `risk` | `low`, `medium`, `high` or `critical`. |
| `kind` | From `read write exec egress install device`. |
| `grant` | `in-flow` (may be granted on the consent screen) or `provider-ui` (only inside the provider). |
| `server@entry` | Optional: the class the provider's own kit uses to list the server's tools. Hosts ignore it. |

The file is capped at 64 KB, 16 servers and 32 groups, strings at 200 characters and summaries at 80, and it renders
as plain text. `risk` and `kind` may only raise a host's caution, and a host treats a group it does not recognise as
high risk.

A host declares `<queries>` for the provider action, which is necessary and sufficient to see providers, finds them
with `queryIntentServices`, and reads the XML with `ServiceInfo.loadXmlMetaData` and `getResourcesForApplication`, so
no provider code runs. It rescans at launch, when its provider screen opens and when packages are added, replaced or
removed. It shows platform facts about a provider (label and icon from PackageManager, package, a short signer hash,
installer, install age and version), never a provider-supplied icon, and of the XML's URLs only the privacy policy and
the contact. Configuration and skills address a provider as `atp:PACKAGE[/SERVER_ID]`, such as
`atp:com.example.toolbox/main`.

### 4.5 Identity on every call

A provider checks `Binder.getCallingUid()` on every call into `IAtpProvider`. It requires exactly one package for the
uid (a shared uid is refused), refuses its own uid, and reads the caller's current signer: on API 28 and later the last
entry of the signing history, or the exact set for an app with several signers; below API 28 the exact set of
signatures. A stored digest is accepted only if it is the current signer or in its lineage, and then moves forward to
the current signer. A digest outside the lineage is a different app under the same name, and the person is asked
again. Each channel is bound to the uid that opened it.

A host checks on every `IAtpHost` callback that the calling uid is the provider's. Before binding, and again before
each open, it compares the provider's current signer with the one recorded at consent, and shows a change as a
different app. Handed-over descriptors are bearer capabilities: a host never passes them on.

### 4.6 Consent

Consent runs in both apps, and the provider's decision is the one that creates a grant.

1. **The host's own sheet comes first.** It names what the host will send, to which app, and that results may reach
   the host's AI model, and it links the provider's privacy policy. Dismissing it is never consent.
2. **The host calls `hello` with `wantNonce`** and receives a single-use nonce bound to the caller's uid, package,
   signer and groups, valid for 5 minutes.
3. **The host starts the provider's CONNECT activity for a result,** from a live activity, without new-task flags, and
   on API 34 and later with `setShareIdentityEnabled(true)`. The extras carry the nonce, the groups asked for and a
   purpose of at most 120 characters.
4. **The provider checks who asked:** on API 34 and later `getLaunchedFromUid()` must equal the nonce's uid; below
   API 34, `getCallingPackage()` must belong to it. The nonce is consumed whatever the outcome.
5. **The outcome is explicit:** `RESULT_FIRST_USER` with an outcome extra of `granted`, `denied`, `identity_refused` or
   `error`. A bare `RESULT_CANCELED` means the flow did not complete, because the system also sends it when a launch
   fails.
6. **The host calls `hello` again** to confirm the grant before it opens a channel.

A provider never starts consent from an IPC call, and a host never binds with `BIND_ALLOW_ACTIVITY_STARTS`, so a host
cannot raise the provider's screen except through an activity start the platform attributes to it.

Consent screens use the platform's protections wherever they exist: obscured and partly obscured touches rejected,
other apps' overlays hidden, Allow marked sensitive to accessibility services and armed only after about a second of
focus. A provider shows its consent screen at most three times per host per 24 hours, cools down after two denials,
and offers Block. Below API 31 overlays cannot be hidden and every touch is rejected while one shows, so a consent
screen should say why it ignored a touch, and a host should hide its own overlay while a consent screen it started is
in front.

Text another app supplies, such as its label and the stated purpose, is shown without control characters other than
line breaks, bidirectional controls, zero-width characters, variation selectors and Unicode Tags, and inside a
sentence between U+2068 FIRST STRONG ISOLATE and U+2069 POP DIRECTIONAL ISOLATE [17], so it can neither hide words nor
reorder the sentence the person decides on [12]. A label with nothing left is shown as the package name.

### 4.7 Groups and grants

Every tool belongs to at most one group, named in `_meta {"com.nujan-agent.atp/group": "<id>"}`. A tool without
a group is implicit and available to any granted host, which suits tools such as a status query. A grant holds one
state per group:

- **On:** calls run.
- **Ask:** each call is put to the person on its own, one question at a time, in the provider's own activity, by a tap
  and never through notification actions. The question names the tool by its MCP `title`. A call refused, or not
  answered within two minutes, is an `isError` result that names no missing group. With the provider's notifications
  blocked, ask denies. When the call carries a progress token, the provider sends MCP progress notifications with
  `_meta {"com.nujan-agent.atp/asking": true}` while the question is in front of the person and `false` when it is
  over, so the host can clear its own overlay meanwhile.
- **Off:** the tool is not listed, and a call to it gets the `missingGroups` result.

Groups declared `grant="provider-ui"` can be turned on only inside the provider, on a screen with the consent screen's
protections that asks again before it turns on a high or critical group. `tools/list` is filtered by the grant, and a
grant change reaches the host as `notifications/tools/list_changed`. `releaseGrant()` deletes the caller's grant.

A provider may pin trusted hosts as (package, SHA-256 of signing certificate) pairs in its source. Pinned hosts get
the publisher's defaults, but consent is never skipped, and a release build never treats a debug-signed caller as
pinned. A grant expires when its host has not opened a channel for 90 days, as Android resets an unused app's
permissions [8]; a block does not expire.

### 4.8 Lifecycle

A host holds a `BIND_AUTO_CREATE` binding for the life of its channels and passes none of `BIND_WAIVE_PRIORITY`,
`BIND_NOT_PERCEPTIBLE`, `BIND_ALLOW_ACTIVITY_STARTS` or `BIND_INCLUDE_CAPABILITIES`. The freezer rule follows from
principle 5: while a channel is open, the host must be in an active state (visible, running a foreground service, a
job or a broadcast), and it closes its channels and unbinds when it leaves that state. On `onServiceDisconnected` the
host keeps the binding, since the system restarts the provider, and reopens its channels; on `onBindingDied` it
unbinds, rebinds after a back-off starting at 500 ms or on the package-replaced broadcast, and discovers again.

After `openChannel` returns OK, the host sends MCP `initialize` with protocol `2025-06-18`, then
`notifications/initialized`, then `tools/list`. The limits per host uid and provider are 8 channels, 32 requests in
flight per channel and 10 opens per minute.

A side closes a channel with `STALLED` after 10 minutes with a response it owes still unwritten, or 60 seconds with a
notification still unwritten, counted in wall time; the freezer rule keeps the host, and the provider it binds, active
while a channel is open. Tool calls get 300 seconds; a provider
answers within 240 seconds or returns a job handle (start, status and cancel tools), and progress notifications
extend the deadline. A host that gives up on a call sends `notifications/cancelled`. A request line not fully written
may be retried once; a fully written one is never retried automatically, because the provider may have acted on it.

When the person revokes a host in the provider, the host receives `onClosed(REVOKED)` and end-of-stream within one
second, and its jobs and servers stop. When the person disconnects in the host, the host closes the channel, calls
`releaseGrant()`, unbinds and deletes its record.

### 4.9 Turns and Stop

A turn is the work a host's agent does on one request from the person: every call it makes until it answers, stops or
is stopped. A provider that shows a sign while a host is at work (an overlay, a notification) needs two things: to know
when the work is over, and to let the person stop it without cutting the connection.

A host names the turn each `tools/call` belongs to in `_meta {"com.nujan-agent.atp/turn": "<id>"}`, an opaque string
of at most 64 characters, the same for every call of a turn and new for the next; a provider must not require it. When
a turn ends, however it ends, the host sends `notifications/com.nujan-agent.atp/turn_ended` with `{"turn": "<id>"}` on
each channel that carried a call of it, and a provider's sign comes down then, or otherwise at most 30 seconds after
the last call.

A provider that offers Stop on its sign stops that turn, not the connection. It sends
`notifications/com.nujan-agent.atp/turn_stopped` at once on the caller's channels, so the host learns of it without
making another call, and answers later calls carrying that turn's id with an `isError` result marked
`_meta {"com.nujan-agent.atp/stopped": true}`. A host that receives either ends the turn as its own Stop would,
including the model request in flight; it must not repeat the call or try the same work another way, and it tells the
person the turn was stopped on the provider's screen. The next turn runs as usual. Turning a provider's tools off for
every turn is a setting in the provider, not Stop.

### 4.10 MCP in version 0.1

Both sides speak era `2025-06-18`, with no era probe. `tools/list` is one page, and hosts ignore `nextCursor`. Tools
carry MCP `annotations` and the `_meta` keys above, and should carry a `title`. A provider declares
`tools.listChanged: true` and pushes `notifications/tools/list_changed`. Results are text content with `isError`,
followed by any pictures as MCP image content, together under 8 MiB of image data; a picture over that is left out
with a line saying so.

Client features are host policy: a host may offer no sampling, elicitation or roots, and a provider in version 0.1
sends no server-to-client requests. A provider may send `instructions` of at most 8,000 characters on how its tools fit
together; a host may give them to its model, must present them as the provider's text rather than the person's or the
host's, and may ignore them. A provider must not re-export tools it got from another ATP provider, but it may
re-export MCP servers it runs in its own sandbox, tagging each tool with `origin` and, when the tool exists only while
that server runs, `ephemeral`. A string argument that carries a file's bytes is marked with JSON Schema's
`contentEncoding: "base64"`; a host may let its model name a file the host holds there and send the bytes in its
place, and only to a provider on the same phone, since its checks on what leaves the phone read what its model wrote.

### 4.11 Handoffs

**UI.** A provider asks the host to show one of its activities with `onUiRequest`, passing an immutable, explicit
activity `PendingIntent` during an in-flight call from that host, at most one per channel, expiring after 60 seconds.
The host checks the creator uid and that the intent opens an activity (API 31 and later; below 31 it declines), and
sends it only while it is visible: at once if the person approved the call that asked, otherwise after its own card.
It answers with `uiResult`. A provider with one of its own windows on screen starts the activity itself.

**Provider to host files** travel as a content URI in an MCP `resource_link`. The host verifies that the URI's
authority belongs to the provider package, opens it only through `ContentResolver`, caps its size and treats it as
untrusted. Without the authority check, a provider could hand the host a URI into the host's own content provider.

**Host to provider files** are only files the person picked for that call: read-only, single documents, granted for
the call and revoked after it.

### 4.12 Tool names in a host

A host names a provider's tool `atp_<slug>_<tool>`. `<tool>` is the wire name lowercased, with anything outside
`[a-z0-9_]` mapped to `_`. `<slug>` is the provider package's last segment reduced to `[a-z0-9]`, or the segment before
it when the last only says what the package is (`atp`, `app`, `android`, `mobile`); it is stored at first consent and
never derived again, so names that skills refer to survive a reinstall. Collisions append `2`, `3` and so on in order
of first consent. Calls use the provider's wire name. The fixtures pin examples:

| Package | Wire name | Name in the host |
|---|---|---|
| `com.example.toolbox` | `fs_read` | `atp_toolbox_fs_read` |
| `com.example.toolbox` | `mcp_everything_longRunningOperation` | `atp_toolbox_mcp_everything_longrunningoperation` |
| `com.example.Notes_App` | `Search.Notes` | `atp_notesapp_search_notes` |
| `com.example.notes.atp` | `search` | `atp_notes_search` |

A host treats every provider alike: it finds providers through the protocol and names none in its own code. Because a
provider's descriptions, instructions and results name its tools by wire names, a host that renames them should write
its own name for each tool in their place in what its model reads, so the model calls a name the host knows.

### 4.13 Conformance

Golden fixtures hold the values both sides test against: `hello` requests and responses, `openJson`, the status,
close-reason and error tables, the consent extras, an ungranted-call result and the AIDL table. The provider kit's
tests cover the provider side and the reference host's tests the host side. The specification says plainly that not
every MUST has a test of its own yet [18, §11].

## 5 Security model

### 5.1 Parties and assets

The parties are the person, the host, the provider and the platform. The assets are the provider's capabilities (its
data, its permissions, what its tools can do, which may include running commands), the host's data (what its model
sends in arguments, and its own files), and the person's attention on consent screens. The platform, meaning the
kernel, Binder, PackageManager and the activity manager, is trusted. Neither app trusts the other, and neither trusts
the model.

### 5.2 Threats

**A rogue host** wants a provider's tools without the person's consent, or more of them than granted. Any app can bind
the exported service, but the provider answers every call from the platform's facts about the caller (uid, single
package, current signer), and channels open only for a recorded grant. A grant is written only when the provider's own
consent screen, in its own process, completes with a nonce that is single use and bound to the uid, package and signer
that asked. A host cannot raise that screen from IPC or wear the person down (three showings a day, a cool-down after
two denials, Block), and no host request can turn on a `provider-ui` group. Rate limits bound channels, requests in
flight and opens.

**A rogue provider** wants to pass as another app, take data from the host, or make the host act against the person.
Hosts show platform facts rather than provider-supplied icons, treat `risk` and `kind` as claims that only raise
caution, check the uid on every callback and the signer before each open, accept UI handoffs only as immutable activity
intents the provider created during a call, and open files only from the provider's own authority. Its instructions are
presented as its text, and a host may ignore them.

**Prompt injection through tool text.** Descriptions, instructions and results come from another app and may contain
instructions aimed at the host's model [13]. ATP's defence is structural rather than a filter: both apps authorize every
call independently of the model (principle 4), a host's sheet says that results may reach its model, a stopped turn
may not be retried another way, and a provider's text is marked as the provider's. ATP does not prevent a model from
being misled within what the person granted; that remains a residual risk, as MCP's own guidance on human oversight
acknowledges [2].

**UI deception.** Overlays [11] are hidden where the platform allows and otherwise make the screen refuse touches;
Allow is armed after a second of focus; text from other apps is cleaned and isolated [12, 17]; questions are answered
in the provider's activity, never by notification actions that another app could imitate.

**Transport abuse.** Descriptors must be FIFOs, kept out of child processes, and never passed on. Lines, write queues
and requests in flight are bounded, and a peer that stops reading is closed as stalled rather than allowed to block
other channels. A loopback port, reachable by every app, is not part of ATP.

### 5.3 What the platform enforces and what ATP adds

The platform provides the facts: the caller's uid, that uid's package and signer, the creator of a `PendingIntent`,
the app that started an activity for a result, the processes holding a descriptor, and the windows covering a screen.
ATP adds the rules that use them: the consent flow and its nonce, grants per group, the provider-only path for
dangerous groups, rate limits, the text rules, the handoff checks, stall, close, turn and Stop semantics, and naming.
A host adds its own policy, such as per-call approvals and how results reach a model; NuJan asks the person before an
app's tool runs and treats results as data from the device.

### 5.4 Out of scope

ATP does not protect against a compromised operating system or a rooted device, a person who grants a dangerous group
to a hostile app, what a provider does with data it receives (its privacy policy governs that), network egress by a
provider (`kind="egress"` is a claim), or a model that follows injected text within its grants. A host holding a
provider's `exec` group controls what that group allows. The repository records no independent security review of the
provider kit.

## 6 Implementation

All components are Kotlin and licensed under Apache-2.0.

### 6.1 Wire module

`:atp-wire` is plain Kotlin for the JVM with one dependency (`kotlinx-serialization-json`) and no Android code, so the
same files compile into providers, hosts and tests. It holds the names and limits (`Atp`), the status, close-reason,
error and consent codes, codecs for `hello` and `openJson` that never throw on a peer's malformed input, a strict
UTF-8 line reader bounded at 16 MiB (configurable up to 64 MiB) and a line writer, the coalescing write queue, the
caller checks over platform-supplied facts with the signer ratchet, pinned trusted hosts, the consent nonce book, JSON-RPC
bookkeeping for requests in flight, the cleaning and isolation of text from other apps, and tool naming. Its main
sources are 800 lines, comments included.

### 6.2 Provider kit

`:atp-provider` is an Android library (minimum SDK 24, compiled to Kotlin 2.1's language and API level so that apps on
older toolchains can use it) that turns an app into a provider. Its manifest merges into the app an exported provider
service, the consent activity in its own process, the connected-apps activity, a non-exported activity for questions
about calls in ask groups, and a non-exported content provider that carries the consent screen's requests into the
main process, where nonces and grants live. The app supplies only its `atp_provider.xml` and its tools.

Tools are declared in MCP's terms. An `AtpToolSource` lists `AtpTool`s for a given caller and may supply instructions.
`atpTool` with typed `AtpParam` arguments (string, integer, number, boolean, choice and list of strings, each optional
or required) builds the JSON Schema from the arguments and checks every call against them before the handler runs. A
handler returns an `AtpResult` with text, an error flag, optional structured content, optional images, or a stopped
marker. During a call, `AtpCall.ui` brings up one of the app's activities through the UI handoff, and `AtpCall.turn`
carries the host's turn id. `AtpProviderXml.problems` checks an app's XML against the specification's rules in a plain
unit test.

Inside, one read thread and one write thread serve each channel; requests run as coroutines, at most 32 at once; a
cancelled call is stopped and never answered; an answer over 16 MiB is replaced by an error; and a watchdog closes a
channel whose host stops reading. The grant is read on every request, so a change in the connected-apps screen applies
to the next call. The kit's main Kotlin sources are 2,990 lines, comments included.

### 6.3 Distribution as drops

Consumers vendor the kit as a drop: `tools/export-drop.ps1` copies the AIDL, the wire module, the kit, the fixtures,
the specification, LICENSE and NOTICE byte for byte from a tagged commit, and writes a provenance README and a
`SHA256SUMS` file, which the consuming repositories check in their own tests. The build files for the vendored modules
belong to the consumer. Drops are named by their tags, `atp-v0.N`; this paper describes `atp-v0.24`. The Binder
interfaces changed once, in `atp-v0.2`, when `getInterfaceVersion` was removed; from then on the AIDL and its
transaction table are unchanged, and later drops only add entries to the fixture of names.

### 6.4 Reference host: NuJan

NuJan (`com.nujan.app`) is an Android agent app and the reference host. It discovers providers from their manifests,
shows its own sheet before asking a provider for consent, starts the provider's consent screen for a result, confirms
the grant with a second `hello`, and assigns the tool slug at first consent. Before each open it compares the
provider's signer with the one recorded at consent using the wire module's caller checks, and it accepts callbacks only
from the provider's uid. It speaks MCP over the channel with its own client, gives tool calls 300 seconds, sends
cancellation on time-out, uses each call's request id as its progress token, clears its floating overlay while a
provider asks the person about one of its calls, and reconnects a provider when its package is replaced. It names
turns and handles Stop as Section 4.9 describes, and writes its own tool names in what the model reads (Section 4.12).

### 6.5 Providers

A provider takes the kit as a drop (Section 6.3) and declares its groups in its XML. Beyond the basic flow, the kit
gives a provider an entry class that receives the app's Context, so its tool definitions can live in an asset; the UI
handoff, to bring its own screen to the front during a call; images in results; the connected-apps screen, where a
group is turned on; and the `contentEncoding` rule for file arguments. `samples/hello` is the smallest provider: one
tool in 26 lines.

## 7 Evaluation

### 7.1 Tests and fixtures

At `atp-v0.24` the repository holds 54 unit tests for the wire module, 102 for the provider kit and 2 for the sample,
and 16 golden fixtures. The wire module's tests read every fixture, and fail when a code, a name or a table differs
from it or when a fixture is added that no test reads. The kit's tests cover the caller checks and the signer ratchet,
nonces, the provider's policy for `hello`, `openChannel`, expiry and release, consent limits, text cleaning, groups set
to ask, the UI handoff, and the channel server's bounds and stall handling. Channel tests send requests and
notifications, a turn's end among them, over a channel rather than calling the dispatcher directly. The specification
states that not every MUST has a test of its own yet [18, §11].

### 7.2 Developer effort

The sample provider, `samples/hello`, offers one tool in 26 lines without comments or blank lines: a 15-line tool
source, 7 lines of XML and 4 of manifest, plus one dependency line. The tool is declared as an MCP server declares one,
by name, description, typed arguments, title and handler, and the kit checks each call against the arguments before the
handler runs. The required privacy policy and contact have no MCP equivalent; a host shows them before it connects.
The time a new developer needs to offer a first tool has not been measured.

### 7.3 What has not been measured

- No performance measurements accompany this release: the latency and throughput of a channel and of the kit, and the
  time a close reason takes to reach the host, are not reported.
- The lifecycle rules have not been tested systematically with Android's cached apps freezer on or under OEM battery
  policies.
- There has been no independent security review, no fuzz testing and no systematic adversarial testing of a host or a
  provider.
- Whether agents use ATP tools well is a property of hosts and models and is outside this evaluation.

## 8 Limitations and future work

**Tools only.** Version 0.1 covers tools. MCP resources and prompts are not in it, and neither are server-to-client
requests (sampling, elicitation, roots) or pagination of `tools/list`.

**One MCP era.** Channels speak 2025-06-18 only, with no era probe. MCP has moved on: its versioning page names
2026-07-28 as the current version and describes 2025-11-25 and earlier as handshake-based revisions; in 2026-07-28
each request declares its protocol version in `_meta`, and a `server/discover` call returns a server's versions,
capabilities and identity [3]. ATP's channel start is built on the `initialize` handshake. The `era` field in
`openJson` and `mcpEras` in `hello` leave room for more eras, but the rules for a second era are future work.

**Android only.** ATP is defined on Android's Binder, PackageManager and activity manager.

**Binary data.** Pictures and file arguments travel as base64 inside JSON, which adds a third to their size and costs
an encode and a decode. A stream handle for large payloads, such as a descriptor handed over Binder, is a candidate
extension.

**Specification gaps.** Some rules are stated without a test of their own (Section 4.13). Two close reasons, `IDLE`
and `IDENTITY_CHANGED`, are reserved values that nothing sends yet, and the consent screen does not yet narrow its
offer to the groups the host asked for. Changes to the specification are proposed as issues in its repository.

**Kit gaps in `atp-v0.24`.** Results cannot yet carry a `resource_link`, so the
provider-to-host file handoff is not available to kit users. Tool code has no way to send progress notifications, so
long work needs a job handle. The typed parameters lack nested objects, per-value descriptions of choices and schemas
from `@Serializable` classes.

**Evaluation gaps.** Section 7.3 lists what has not been measured. The next steps are measurements of the channel and
the kit on Android 14 to 16 with the cached apps freezer on, including the time `onClosed` takes to arrive.

## 9 Conclusion

ATP shows that MCP's tools can cross from one Android app to another on the same phone without changing MCP: two
pipes handed over Binder carry its messages, and Binder carries what Android adds, namely identity, lifecycle and
platform objects. The protocol's security comes from the platform's facts rather than from what apps say about
themselves, and from consent in both apps that no model can widen. Version 0.1 is small enough to implement in a
library: with the provider kit, an app offers a tool in 26 lines and gets the consent screen, the grants and the
channels. Its implementations come from one project, so the next steps are measurements on more devices, more MCP
eras, and providers and hosts built by others.

## References

1. Anthropic. "Introducing the Model Context Protocol." 25 November 2024.
   https://www.anthropic.com/news/model-context-protocol
2. Model Context Protocol. Specification, revision 2025-06-18: Transports, Lifecycle and Tools.
   https://modelcontextprotocol.io/specification/2025-06-18
3. Model Context Protocol. Versioning. https://modelcontextprotocol.io/specification/versioning (read 2 November
   2026).
4. JSON-RPC Working Group. JSON-RPC 2.0 Specification. https://www.jsonrpc.org/specification
5. Android Developers. AppFunctions. https://developer.android.com/ai/appfunctions (read 2 November 2026).
6. Android Developers. Package visibility filtering on Android. https://developer.android.com/training/package-visibility
7. Android Open Source Project. Cached apps freezer. https://source.android.com/docs/core/perf/cached-apps-freezer
8. Android Developers. App hibernation. https://developer.android.com/topic/performance/app-hibernation
9. Android Developers. Intents and intent filters. https://developer.android.com/guide/components/intents-filters
10. Android Developers. AccessibilityService.
    https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
11. Y. Fratantonio, C. Qian, S. P. Chung, W. Lee. "Cloak and Dagger: From Two Permissions to Complete Control of the
    UI Feedback Loop." IEEE Symposium on Security and Privacy, 2017, pp. 1041-1057. doi:10.1109/SP.2017.39
12. N. Boucher, R. Anderson. "Trojan Source: Invisible Vulnerabilities." 32nd USENIX Security Symposium, 2023,
    pp. 6507-6524. arXiv:2111.00169
13. K. Greshake, S. Abdelnabi, S. Mishra, C. Endres, T. Holz, M. Fritz. "Not what you've signed up for: Compromising
    Real-World LLM-Integrated Applications with Indirect Prompt Injection." arXiv:2302.12173, 2023.
14. S. Bradner. "Key words for use in RFCs to Indicate Requirement Levels." RFC 2119, 1997.
15. D. Eastlake, A. Panitz. "Reserved Top Level DNS Names." RFC 2606, 1999.
16. S. Cheshire, M. Krochmal. "Special-Use Domain Names." RFC 6761, 2013.
17. Unicode Consortium. Unicode Standard Annex #9: Unicode Bidirectional Algorithm. https://www.unicode.org/reports/tr9/
18. App Tools Protocol (ATP) for Android, specification v0, version 0.1. `spec/SPEC.md` in this repository.

"Model Context Protocol" and "MCP" are trademarks of LF Projects, LLC; ATP is an independent protocol for use with MCP.
Android is a trademark of Google LLC.
