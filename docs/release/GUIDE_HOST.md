<!-- SPDX-License-Identifier: Apache-2.0 -->

# Guide: building an ATP host

A host is an Android app that runs an agent and calls tools that other apps on the same phone offer over ATP. This
guide walks through what a host implements, in the order it happens, for ATP specification version 0.1 (binding
version 1, MCP era `2025-06-18`). `spec/SPEC.md` is normative; section numbers below (§) refer to it. Where this guide
says MUST or MUST NOT it repeats the specification; elsewhere it describes what the specification states as the
procedure, and notes how the reference host, NuJan, does it.

A host needs no provider kit. It uses the wire module (`:atp-wire`, plain Kotlin) and the AIDL from the drop:
`HelloRequest`, `HelloResponse`, `OpenRequest`, `OpenResult`, `CloseReason`, `ConsentOutcome`, `CallerChecks`,
`LineReader`, `LineWriter`, `JsonRpc`, `AtpToolNaming`, `AppText` and the names in `Atp`. Vendor the drop with
`tools/export-drop.ps1` as a provider does, include `:atp-wire`, and compile the AIDL stubs from the drop's `aidl/`
folder into the host app (NuJan adds that folder as an AIDL source directory of its app module).

## 1. The host's manifest

A host MUST declare that it queries the provider action (§5.3). That is necessary and sufficient to see providers;
`QUERY_ALL_PACKAGES` is not needed.

```xml
<queries>
    <intent>
        <action android:name="com.nujan_agent.atp.action.PROVIDER" />
    </intent>
</queries>
```

A host MAY also export an activity for `com.nujan_agent.atp.action.REQUEST_CONNECT`, which a provider starts for a
result to ask the host to connect. The host verifies that caller by the consent rules of section 4 below, rate-limits
it, and opens its own sheet (§6.3 item 8).

## 2. Discovery

Find providers with `queryIntentServices` and read each one's `res/xml/atp_provider.xml` through PackageManager, so
that none of the provider's code runs (§5.3):

```kotlin
val pm = context.packageManager
val services = pm.queryIntentServices(Intent(Atp.ACTION_PROVIDER), PackageManager.GET_META_DATA)
    .mapNotNull { it.serviceInfo }
    .filter { it.exported && it.packageName != context.packageName }

for (service in services) {
    val resources = pm.getResourcesForApplication(service.applicationInfo)
    service.loadXmlMetaData(pm, Atp.META_DATA_PROVIDER)?.use { parser ->
        // Read <atp-provider>, <server> and <group> as SPEC.md 5.2 names them, resolving
        // android:title and android:summary through `resources`. Ignore anything else.
    }
}
```

Rules for reading the XML (§5.2):

- Read only the attributes and elements §5.2 names, and ignore the rest, including `entry`, which is for the
  provider's own kit.
- Enforce the caps: 64 KB, 16 servers, 32 groups, strings of at most 200 characters and summaries of at most 80.
  Render everything as plain text.
- `risk` and `kind` are the provider's claims. They may only raise your caution. Treat a group you do not recognise,
  or one without a valid `risk`, as high risk, and a group whose `grant` is not `in-flow` as one only the provider can
  turn on.
- Of the XML's URLs, render only `privacyPolicy` and `contact`.

Rescan when your provider screen opens, at launch, and on `PACKAGE_ADDED`, `PACKAGE_REPLACED` and
`PACKAGE_REMOVED` (§5.3). Your `<queries>` entry is what lets you receive those broadcasts for providers.

### Show platform facts, not claims

When you list a provider, show what the platform says about it (§5.3 item 4): its label and icon from PackageManager,
its package, a short hash of its signer, its installer (as neutral text), how long ago it was installed, and its
version. Never render an icon or image the provider supplies. Clean text that comes from another app before you put
it in a sentence of your own: `AppText.clean` removes the characters that can hide or reorder words, and
`AppText.isolate` also wraps the text in a first-strong isolate.

Refer to a provider in configuration and skills as `atp:PACKAGE[/SERVER_ID]`, for example `atp:com.example.toolbox/main`
(§5.4).

## 3. Binding

Bind the provider's service with `BIND_AUTO_CREATE`, and hold the binding for the whole life of your channels
(§7.1). A host MUST NOT pass `BIND_WAIVE_PRIORITY`, `BIND_NOT_PERCEPTIBLE`, `BIND_ALLOW_ACTIVITY_STARTS` or
`BIND_INCLUDE_CAPABILITIES`.

```kotlin
val intent = Intent(Atp.ACTION_PROVIDER).setClassName(providerPackage, serviceClass)
context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
```

- **Freezer rule.** While a channel is open, the host MUST be in an active state: visible, running a foreground
  service, a running job or a running broadcast. When it leaves that state it closes its channels and unbinds (§7.1
  item 2), since a frozen host can neither read its pipes nor take its callbacks.
- **`onServiceDisconnected`:** keep the binding. The system restarts the provider and calls `onServiceConnected`
  again; reopen your channels then.
- **`onBindingDied`:** unbind, then rebind after a back-off starting at 500 ms, or when `PACKAGE_REPLACED` arrives,
  and discover again.

Before you bind, and again before each open, compare the provider's current signer with the one you recorded when the
person connected it (§6.2), as described in section 4.

## 4. Consent

The person connects a provider through two screens: yours, then the provider's. Nothing is bound for consent before
the person confirms yours.

### 4.1 Your own sheet first

Show a sheet that names what your host will send, to which app, and that results may reach your AI model, and that
links the provider's `privacyPolicy` (§6.3 item 1). Back or dismiss is never consent. NuJan's sheet says that it will
send the app what the person asks it to do with the app's tools, that what the app sends back joins the session where
the person's AI model may read it, and that the app asks next on its own screen.

### 4.2 Ask for a nonce

Call `hello` with `wantNonce` and the groups you intend to ask for (§4.7, §6.3 item 2):

```kotlin
val answer = HelloResponse.parse(provider.hello(HelloRequest(wantNonce = true, groups = groupIds).toJson()))
when {
    answer == null -> { /* unreadable: treat the provider as unavailable */ }
    answer.grant.state == GrantView.State.BLOCKED -> { /* the person blocked you in the provider; say so */ }
    answer.nonce == null -> { /* no nonce offered; the provider will not ask the person now */ }
}
```

`hello` never throws for an ungranted caller and reveals only your own grant state, the provider's static servers and
its limits. The nonce is 32 lowercase hex digits, single use, bound to your uid, package, signer and groups, and valid
for five minutes (`nonceTtlMs`).

### 4.3 Start the provider's consent screen for a result

Start the provider's `CONNECT` activity with an explicit intent, from a live activity that is not finishing, with a
request code of 0 or more, without `FLAG_ACTIVITY_NEW_TASK`, `FLAG_ACTIVITY_NEW_DOCUMENT` or
`FLAG_ACTIVITY_MULTIPLE_TASK`, and on API 34 and later with `ActivityOptions.setShareIdentityEnabled(true)` (§6.3
item 3):

```kotlin
val connect = Intent(Atp.ACTION_CONNECT).setPackage(providerPackage)
val target = pm.queryIntentActivities(connect, 0).firstOrNull()?.activityInfo ?: return
connect.setClassName(target.packageName, target.name)
    .putExtra(Atp.EXTRA_NONCE, nonce)
    .putExtra(Atp.EXTRA_GROUPS, groupIds.toTypedArray())
    .putExtra(Atp.EXTRA_PURPOSE, purpose.take(Atp.PURPOSE_MAX_CHARS))

val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
} else {
    null
}
activity.startActivityForResult(connect, REQUEST_CONNECT, options)
```

The purpose is plain text of at most 120 characters, shown to the person on the provider's screen. The provider checks
who started it: on API 34 and later, `getLaunchedFromUid()` must be the nonce's uid, which requires your
`setShareIdentityEnabled(true)`; below that, `getCallingPackage()`, which a start for a result supplies. A plain
`startActivity` carries no identity and is refused. A provider never starts consent from an IPC call, and you never
bind with `BIND_ALLOW_ACTIVITY_STARTS` (§6.3 item 7).

If your host draws an overlay of its own, such as a floating bubble, hide it while the provider's consent screen is in
front (§6.3). Below API 31 the provider cannot hide other apps' overlays, and its screen rejects every touch while
one shows.

### 4.4 Read the outcome

The outcome is explicit (§6.3 item 5):

```kotlin
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode != REQUEST_CONNECT) return
    val outcome = if (resultCode == Atp.CONSENT_RESULT_CODE) {
        ConsentOutcome.of(data?.getStringExtra(Atp.EXTRA_OUTCOME))
    } else {
        null // a bare RESULT_CANCELED: the flow did not complete, which includes a failed launch
    }
    // GRANTED: confirm with hello. DENIED: not connected. IDENTITY_REFUSED: the provider could not
    // confirm who asked. ERROR or null: the provider did not finish asking.
}
```

`Atp.CONSENT_RESULT_CODE` is `RESULT_FIRST_USER` (1). The provider limits how often it shows its screen: at most three
times per host per 24 hours, with a cool-down after two denials, and the person can Block your host there. A provider
built on the kit answers `denied` for Deny, for Block, and when its limits keep the screen from showing; a later
`hello` reports `blocked` when the person blocked you.

### 4.5 Confirm, and name the tools

Call `hello` again, without a nonce, and go on only if `grant.state` is `granted` (§6.3 item 6). Record the provider's
package, its signer (`CallerChecks.signerOf` over the SHA-256 digests of its signing certificates) and its tool slug,
which you derive now and never again (section 10).

### 4.6 The signer check before each open

Before each open, read the provider's signers from PackageManager (on API 28 and later, the last entry of
`SigningInfo.getSigningCertificateHistory()` and the earlier entries as its rotation history, or the exact
`apkContentsSigners` set when there are several; below API 28, the exact `GET_SIGNATURES` set) and compare them with
the recorded signer. A current signer equal to the recorded one, or one the recorded signer rotated to, is the same
app, and you move your record forward to it. Anything else is a different app under the same package name: do not
open a channel, and show it to the person as a new app to connect. `CallerChecks.check` in the wire module applies
this rule over `CallerFacts`; NuJan uses it with the provider's facts in place of a caller's.

## 5. Opening a channel

Create both pipes yourself with `ParcelFileDescriptor.createPipe()`, hand the provider its two ends in `openChannel`,
and close your copies of those ends as soon as `openChannel` returns (§3.2 items 1 and 2). If you keep them, the
provider never sees end-of-stream.

```kotlin
val toProvider = ParcelFileDescriptor.createPipe()   // [0] read end: the provider's; [1] write end: yours
val fromProvider = ParcelFileDescriptor.createPipe() // [0] read end: yours; [1] write end: the provider's
val status = try {
    provider.openChannel(serverId, toProvider[0], fromProvider[1], hostCallback, OpenRequest().toJson())
} catch (gone: RemoteException) {
    null
} finally {
    toProvider[0].close()
    fromProvider[1].close()
}
val result = status?.let(OpenResult::parse)
if (result == null || !result.ok) {
    toProvider[1].close()
    fromProvider[0].close()
    // act on result?.status (table below)
    return
}
val input = ParcelFileDescriptor.AutoCloseInputStream(fromProvider[0])
val output = ParcelFileDescriptor.AutoCloseOutputStream(toProvider[1])
```

`OpenRequest()` writes `{"framing":"ndjson","binding":1,"spec":"0.1","era":"2025-06-18"}` (§4.8). The descriptors are
bearer capabilities: a host MUST NOT pass them on (§4.3 item 3).

What to do with each status (§4.4):

| Status | What the host does |
|---|---|
| `OK` | Start MCP on the channel (section 6). |
| `NOT_GRANTED` | You have no grant, or it expired, or the provider's record of you was deleted: run consent again. |
| `BLOCKED` | The person blocked you in the provider. Say so; only the person can unblock you there. |
| `UNKNOWN_SERVER` | Discover again; the provider no longer declares that server. |
| `TOO_MANY_CHANNELS` | You already hold 8 channels with this provider. Reuse one. |
| `UNSUPPORTED_VERSION` | The provider does not serve this framing, binding or era. |
| `NOT_READY` | The provider needs set-up first; tell the person to open the provider app. |
| `TRANSPORT_UNAVAILABLE` | The provider cannot use the descriptors on this device. |
| `BAD_STREAM` | A descriptor was not a pipe: a bug in the host. |
| `RATE_LIMITED` | More than 10 opens in the last minute. Wait before trying again. |
| `IDENTITY_REFUSED` | The provider could not identify your app (for example a shared uid). |

A status code you do not know (`OpenResult.status` is null) is a refusal.

### 5.1 The host callback

The `IAtpHost` you pass in `openChannel` receives the provider's oneway callbacks. On every callback, check that the
caller is the provider (§4.3 item 2) and ignore anything else:

```kotlin
val providerUid = pm.getPackageUid(providerPackage, 0)

val hostCallback = object : IAtpHost.Stub() {
    override fun onClosed(reason: Int, detailJson: ByteArray?) {
        if (Binder.getCallingUid() != providerUid) return
        onChannelClosed(CloseReason.of(reason)) // section 8
    }

    override fun onUiRequest(requestId: Int, intent: PendingIntent?, reasonJson: ByteArray?) {
        if (Binder.getCallingUid() != providerUid) return
        handleUiRequest(requestId, intent, reasonJson) // section 9
    }

    override fun onServersChanged() {
        if (Binder.getCallingUid() != providerUid) return
        rediscover(providerPackage)
    }
}
```

Close reasons travel only here, never in-band (§3.2 item 7).

## 6. MCP on the channel

The channel carries MCP's stdio framing, `ndjson`: one UTF-8 JSON-RPC message per line, with no raw newline inside a
message (§3.2 item 4). `LineWriter` writes a message and its newline and refuses one that breaks the rule;
`LineReader` reads lines strictly as UTF-8 and is bounded. You MUST accept lines up to 16 MiB; you MAY close the
channel with `PROTOCOL_ERROR` above 64 MiB (§3.2 item 5). Each side has exactly one writer, so serialize your writes.

After `openChannel` returns OK, send `initialize` with protocol `2025-06-18`, then `notifications/initialized`, then
`tools/list` (§7.2):

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"your-host","version":"1.0"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
```

- **Capabilities.** Client features are your policy (§8 item 5): you may offer no sampling, elicitation or roots. A
  provider in version 0.1 sends no requests to the host; answer any that arrives with JSON-RPC `-32601`, as NuJan does.
- **Instructions.** The `initialize` result may carry `instructions`, plain text of at most 8,000 characters on how
  the provider's tools fit together. You MAY give them to your model, MUST present them as the provider's text rather
  than the person's or yours, and MAY ignore them (§8 item 5).
- **The tool list** is one page; ignore `nextCursor` (§8 item 2). It is already filtered by your grant. Tools carry MCP
  `annotations`, a `title` for people, and `_meta` keys: `com.nujan-agent.atp/group` for the group, and
  `com.nujan-agent.atp/origin` and `com.nujan-agent.atp/ephemeral` for tools re-exported from an MCP server inside the
  provider. Annotations are the provider's claims.
- **List changes.** The provider declares `tools.listChanged: true` and sends `notifications/tools/list_changed` when
  your grant or its tools change. List again.

The limits per provider are 8 channels, 32 requests in flight per channel and 10 opens per minute (§7.2). A request
past 32 in flight is answered with JSON-RPC error `-31000` (`TOO_MANY_REQUESTS`). `-31001` (`SHUTTING_DOWN`) means the
channel is closing and the provider did not start the request.

### 6.1 Calling a tool

Call a tool by its wire name. Give every call a progress token and, when you can, the id of the turn it belongs to:

```json
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"greet","arguments":{"name":"Ada"},"_meta":{"progressToken":7,"com.nujan-agent.atp/turn":"t-41"}}}
```

NuJan uses each request's id as its progress token. Results follow MCP (§8 item 4): text content and `isError`, then
any pictures as MCP image content, together under 8 MiB of image data. You MAY show pictures, pass them to a model
that reads images under the person's data settings, or only name them. A result may also carry `structuredContent`.

Three kinds of error result need care:

- **Missing groups.** A call to a tool your grant does not allow is not a JSON-RPC error. It is a result with
  `isError: true`, readable text, and `_meta {"com.nujan-agent.atp/missingGroups": ["exec"]}` (§4.6). Tell the person
  which group to turn on in the provider; the text names where.
- **Refused by the person.** For a tool in a group set to ask each time, the provider asks the person about each call.
  A call refused, or not answered within two minutes, is an `isError` result that names no missing group (§6.4 item
  2). Do not send the person to turn a group on.
- **Stopped.** A result with `_meta {"com.nujan-agent.atp/stopped": true}` means the person stopped your turn on the
  provider's screen (section 7).

An unknown tool is JSON-RPC `-32602`, as in MCP.

### 6.2 Timeouts, cancellation and retries

A tool call gets 300 seconds (§7.3 item 2). A provider answers within 240 seconds or returns a job handle (start,
status and cancel tools), and progress notifications for the call extend the deadline. When you give up on a call,
send MCP's cancellation for it (§7.3 item 3); the provider stops it and never answers it:

```json
{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":7,"reason":"no answer in time"}}
```

Retry safety (§7.3 item 4): a request line you did not finish writing MAY be retried once. A request you wrote in full
MUST NOT be retried automatically, because the provider may have acted on it. Keep the two apart in your error
handling, as NuJan does by reporting whether a failed call may have executed.

Stalls (§7.3 item 1): a side closes a channel with `STALLED` when the other leaves a response it owes unwritten for 10
minutes, or a notification for 60 seconds. Read your channels promptly, and keep the freezer rule of section 3.

### 6.3 While the provider asks the person

When a call in an ask group carries a progress token, the provider sends MCP progress notifications for that token
once its question is in front of the person, and again when it is answered or given up (§6.4 item 2):

```json
{"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":9,"progress":0,"message":"Waiting for you to answer Notes about “Search notes”.","_meta":{"com.nujan-agent.atp/asking":true}}}
```

The second carries `"com.nujan-agent.atp/asking": false`. A host that draws an overlay of its own SHOULD hide it from
the first notice until the second, or until the call's result: below API 31 the question's screen refuses every touch
while another app's overlay shows. NuJan clears its floating panel for that time and restores it afterwards.

## 7. Turns and Stop

A turn is the work your agent does on one request from the person: every call it makes until it answers, stops or is
stopped (§7.5).

1. **Name the turn.** A host SHOULD put the turn's id in each call's `_meta` as `com.nujan-agent.atp/turn`: an opaque
   string of at most 64 characters (`Atp.TURN_MAX_CHARS`), the same for every call of the turn and new for the next.
   Providers treat it as opaque and do not require it.
2. **Say when it ends.** When a turn ends, however it ends, a host SHOULD send on each channel that carried a call of
   it:

   ```json
   {"jsonrpc":"2.0","method":"notifications/com.nujan-agent.atp/turn_ended","params":{"turn":"t-41"}}
   ```

   A provider that shows the person a sign that you are at work takes it down then, and otherwise after an idle time
   of its own, at most 30 seconds after your last call.
3. **Honour the provider's Stop.** A provider may offer the person a Stop on that sign. It stops your turn, not your
   connection, and sends at once:

   ```json
   {"jsonrpc":"2.0","method":"notifications/com.nujan-agent.atp/turn_stopped","params":{"turn":"t-41"}}
   ```

   It answers later calls carrying that turn's id with an `isError` result marked
   `_meta {"com.nujan-agent.atp/stopped": true}`, and may end that turn's calls in flight the same way. A call with no
   turn id is answered the same way for as long as the sign would have shown.
4. **End the turn.** On a `turn_stopped` for a turn in progress, or a `stopped` result, end that turn as your own Stop
   would, including the model request in flight. You MUST NOT repeat the call or try the same work another way. Tell
   the person the turn was stopped on the provider's screen. Ignore a `turn_stopped` for a turn that has already
   ended. The next turn runs as usual.

NuJan keeps one id per turn, sends `turn_ended` once per channel that carried a call of the turn, and stops only a turn
that called the app that sent `turn_stopped`, showing "Stopped in" and the app's name in the chat.

## 8. Closing, revocation and disconnect

A channel is a transport connection, and no MCP state may depend on it staying open (§2 item 6). When it closes,
`onClosed` tells you why (§4.5):

| Reason | What the host does |
|---|---|
| `NORMAL`, `PROVIDER_SHUTDOWN`, `IDLE` | Nothing is wrong. Open a new channel on next use. |
| `STALLED` | One side stopped reading. Open a new channel on next use, and check that you read promptly. |
| `PROTOCOL_ERROR` | Bad framing, an oversized line or invalid JSON-RPC. Treat it as a bug. |
| `REVOKED` | The person revoked your grant in the provider. Remove its tools and forget the connection. |
| `BLOCKED` | The person blocked you. Remove its tools and forget the connection. |
| `IDENTITY_CHANGED` | A signer changed. Treat the provider as a new app. |
| `EXPIRED` | Your grant expired after 90 days without a channel. Remove its tools; connecting again asks the person. |

On revocation the provider sends `onClosed(REVOKED)` and ends the stream within one second, and your jobs and servers
with it stop (§7.4 item 1).

When the person disconnects a provider in your app (§7.4 item 2): close the channel, call `releaseGrant()`, which
deletes your grant in the provider, unbind, and delete your record.

To let the person manage what your host may do in a provider, start the provider's Connected apps screen, and only
when the person asks to (§6.4 item 3):

```kotlin
startActivity(Intent(Atp.ACTION_CONNECTED_APPS).setPackage(providerPackage))
```

There the person turns groups on, sets them to ask each time, or turns them off. Groups declared
`grant="provider-ui"` can be turned on only there.

## 9. Handoffs

### 9.1 The provider's screens

During a call, a provider may ask you to show one of its activities with `onUiRequest(requestId, intent, reasonJson)`
(§9 item 1). It sends one only during a call you have in flight to it, at most one per channel, as an immutable,
explicit activity `PendingIntent` that expires after 60 seconds. `reasonJson` is
`{"reason":"<text, at most 200 characters>"}`. Before you send the intent, check that:

- `intent.creatorUid` is the provider's uid;
- `intent.isActivity` is true. That method exists from API 31; below 31, decline;
- your host is visible to the person.

Send it at once when the person already approved the call that asked, and otherwise after a card of your own. Answer
with `uiResult(serverId, requestId, RESULT_OK)` when you sent it and `RESULT_CANCELED` when you did not. A provider
with one of its own windows on screen starts its activity itself and sends no request. For a host that targets API 34
or later, the activity start can use the host's own right to start activities only if the `ActivityOptions` passed to
`send` say so: NuJan passes `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` on API 34 and 35, and
`MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE` from API 36.

### 9.2 Files from the provider

A provider hands you a file as a content URI in an MCP `resource_link` (§9 item 2). You MUST verify that the URI's
authority belongs to the provider's package (`pm.resolveContentProvider(authority, 0)?.packageName`). Open it only
through `ContentResolver`, cap its size, and treat what you read as untrusted. Without the authority check, a provider
could hand you a URI into your own content provider and have you read your own files.

### 9.3 Files to the provider

Give a provider only files the person picked for that call, read-only, single documents, as a per-call grant you
revoke after the call (§9 item 3).

A string argument marked with JSON Schema's `contentEncoding: "base64"` carries a file's bytes (§8 item 7). You MAY
let your model name a file your host holds there and send the file's bytes in its place; the provider receives base64
either way. Do this only for a provider on the same phone, since your checks on what leaves the phone read the
arguments your model wrote.

## 10. Tool names in the host

Name a provider's tools `atp_<slug>_<tool>` (§10):

```kotlin
// At first consent; store the slug with the connection and never derive it again.
val slug = AtpToolNaming.slugFor(providerPackage, taken = slugsAlreadyStored)

// For each tool, the name your model sees. Calls still use the provider's wire name.
val hostName = AtpToolNaming.toolName(slug, wireName) // "greet" from com.example.hello -> atp_hello_greet
```

- `<tool>` is the wire name lowercased, with anything outside `[a-z0-9_]` mapped to `_`.
- `<slug>` is the package's last segment reduced to `[a-z0-9]`, or the segment before it when the last only says what
  the package is (`atp`, `app`, `android`, `mobile`). A collision appends `2`, `3` and so on in order of first consent.
- The slug is stored at first consent and never derived again, so the names that skills refer to survive a reinstall.

| Package | Wire name | Name in the host |
|---|---|---|
| `com.example.toolbox` | `run_command` | `atp_toolbox_run_command` |
| `com.example.Notes_App` | `Search.Notes` | `atp_notesapp_search_notes` |
| `com.example.notes.atp` | `search` | `atp_notes_search` |

Treat every provider alike: find providers through this protocol, and name none in your own code.

A provider's descriptions, instructions and results name its tools by their wire names. If your model sees other
names, you SHOULD write your own name for each of that provider's tools in their place in what your model reads, so
the model calls a tool by a name you know. NuJan rewrites a server's tool names in its tools' descriptions and argument
help, and notes the names to call after a result that mentions one, while keeping the result itself unchanged; it
leaves a one-word name such as `search` alone, since it is also a word in the provider's prose.

## 11. Testing a host

- The drop's `fixtures/` hold the golden values: `hello` requests and responses, `openJson`, the status, close-reason
  and error tables, the consent extras, an ungranted-call result and the AIDL table. The wire module's own tests read
  them; point the `atp.fixtures` system property of your test task at the folder, as the drop's build files do.
- Test the channel against a fake provider over a pair of pipes or streams: framing, request ids, cancellation, the
  asking notices, turn ids, `turn_ended`, `turn_stopped` and stopped results. NuJan's host tests do this in its own
  repository.
- Test refusals as well as successes: every open status, every close reason, an unknown tool, a missing group, a
  refused question.
- On a phone, run the consent flow end to end with a provider built on the kit, such as `samples/hello`.

## 12. Checklist

| § | Requirement |
|---|---|
| 3.2 | MUST create both pipes with `createPipe()`, and close your copies of the handed-over ends right after `openChannel`. |
| 3.2 | One writer per side; `ndjson`; accept lines up to 16 MiB; close reasons only from `onClosed`. |
| 4.3 | MUST check the calling uid on every `IAtpHost` callback; MUST NOT pass the descriptors on. |
| 5.2 | Read only the named attributes; enforce the caps; plain text only; unknown groups are high risk. |
| 5.3 | MUST declare `<queries>` for the provider action; read manifests without running provider code; rescan on launch, screen open and package changes; show platform facts, never a provider's icon. |
| 6.2 | Compare the provider's signer with the recorded one before binding and before each open. |
| 6.3 | Your own sheet first; `hello` with `wantNonce`; start `CONNECT` for a result as described; read the explicit outcome; confirm with `hello`. MUST NOT bind with `BIND_ALLOW_ACTIVITY_STARTS`. SHOULD hide your own overlay while the provider's screen is in front. |
| 6.4 | Tell the person which group is missing from a `missingGroups` result; SHOULD hide your overlay while the provider asks about a call; open the Connected apps screen only when the person asks. |
| 7.1 | `BIND_AUTO_CREATE` only, none of the four forbidden flags; MUST stay in an active state while a channel is open; keep the binding on `onServiceDisconnected`; back off and rebind on `onBindingDied`. |
| 7.2 | `initialize` (2025-06-18), `notifications/initialized`, `tools/list`; respect 8 channels, 32 in flight, 10 opens a minute. |
| 7.3 | 300 s per call; send `notifications/cancelled` when you give up; MUST NOT retry a fully written request automatically. |
| 7.4 | Disconnect: close, `releaseGrant()`, unbind, delete the record. |
| 7.5 | SHOULD name turns and send `turn_ended`; on `turn_stopped` or a stopped result end the turn, MUST NOT repeat the call or try the work another way, and tell the person. |
| 8 | One era; one page of tools; MUST present `instructions` as the provider's text; client features are your policy. |
| 9 | UI handoffs: creator uid, `isActivity` (API 31+), only while visible, `uiResult`. Files: MUST verify the `resource_link` authority; only person-picked, read-only, per-call files to the provider. |
| 10 | `atp_<slug>_<tool>`, slug stored at first consent; name no provider in your code; SHOULD write your names in place of wire names in what your model reads. |

"Model Context Protocol" and "MCP" are trademarks of LF Projects, LLC; ATP is an independent protocol for use with MCP.
Android is a trademark of Google LLC.
