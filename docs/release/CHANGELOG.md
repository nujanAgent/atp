<!-- SPDX-License-Identifier: Apache-2.0 -->

# Changelog

ATP ships as drops: tagged commits of this repository that `tools/export-drop.ps1` copies byte for byte into a
consuming app's `third_party/atp`. A drop carries the specification, the AIDL, the golden fixtures, the wire module
and, from `atp-v0.3`, the provider kit.

Every drop below has specification version `0.1` (`SPEC_VERSION`), binding version 1 and MCP era `2025-06-18`. The
specification's text grew across drops within version 0.1. The Binder interfaces changed once, in `atp-v0.2`; from
then on the AIDL and its transaction table are unchanged, and later drops only add entries to `fixtures/names.json`.

The first public release is the specification at version 0.1 with the provider kit at `atp-v0.22`. Earlier versions
were not published as tags in this repository; their entries are kept as the record of changes.

## atp-v0.22 (2026-11-02)

The specification says what the kit does where the two disagreed. A file travels by handoff, while pictures in
results and file arguments travel as base64 within the line limit. `IDLE` and `IDENTITY_CHANGED` are reserved:
nothing sends them, since no idle period is defined and a signer changes only by reinstalling, which ends the channel
first; a changed signer's next `open` is refused with `NOT_GRANTED` until the person consents again. The stall clock counts wall time, and the freezer rule is why no pause is needed. Text from
another app keeps tabs as well as line breaks. The signer and launcher checks are defined from the kit's lowest API
(below 28, below 34). `SHUTTING_DOWN` is something a side may send. The fixtures are named where they sit in the
repository and in a drop. No code changed apart from a KDoc in `ToolNaming`.

## atp-v0.21 (2026-11-02)

Fixed: a host's `turn_ended` reaches the tool source over a channel. The channel passed on only
`notifications/cancelled` and dropped every other notification, so `AtpToolSource.turnEnded` was not called, and a
provider that clears an in-use sign at the end of the host's turn kept it up until its own timeout. Every notification
other than a cancellation now goes to the dispatcher, which acts on those it knows and ignores the rest. A tool source
that throws on a turn's end is logged, and the channel goes on.

## atp-v0.20 (2026-11-02)

Turns and Stop (SPEC 7.5). A host names the turn each `tools/call` belongs to (`_meta`
`com.nujan-agent.atp/turn`) and sends `notifications/com.nujan-agent.atp/turn_ended` when it ends. A provider's Stop
sends `notifications/com.nujan-agent.atp/turn_stopped` and answers that turn's later calls with an error marked
`com.nujan-agent.atp/stopped`; the connection stays open. Kit: `AtpCall.turn`, `AtpResult.stopped`,
`AtpToolSource.turnEnded`, `AtpProvider.stopTurn`, and a write-queue kind for notices that are neither coalesced nor
dropped. SPEC 10: a host that renames a provider's tools writes its own names in place of the wire names in what its
model reads.

Known issue, fixed in `atp-v0.21`: the channel did not pass `turn_ended` on to the tool source, so
`AtpToolSource.turnEnded` was not called.

## atp-v0.19 (2026-11-01)

Tool names in a host: a last package segment that only says what the package is (`atp`, `app`, `android`, `mobile`)
passes to the segment before it. SPEC 10 says a host treats every provider alike and names none in its own code.

## atp-v0.18 (2026-10-30)

A host is told while the person is asked about its call: for a call that carries a progress token, the provider sends
`notifications/progress` with `_meta` `com.nujan-agent.atp/asking` true once the question is in front of the person,
and false when it is over (SPEC 6.4 item 2), so the host can clear its own overlay. Every notification is held to the
60-second notification stall time.

## atp-v0.17 (2026-10-30)

The consent, question and Connected apps screens say why they ignore a touch while another app's overlay covers them
below Android 12. SPEC 6.3: a consent screen should say so, and a host should hide its own overlay while a consent
screen it started is in front. `NOTICE` names the copyright holder, and every drop carries it.

## atp-v0.16 (2026-10-29)

`samples/hello`: an app that offers one tool. Typed parameters: `AtpParam` (string, integer, number, boolean, choice,
strings, each with `optional()`) and `atpTool(...)` build a tool's JSON Schema and check each call before the handler
runs. The question about a call names the tool by its `title`, or its name made readable, and group names are quoted
in the kit's sentences. `AtpProviderXml.problems` reports a missing privacy policy or contact, and addresses at names
reserved by RFC 2606 and RFC 6761.

## atp-v0.15 (2026-10-29)

Text another app supplies, such as its label and its stated purpose, is cleaned of characters that hide or reorder
words and isolated on the consent, question and Connected apps screens (`AppText`, SPEC 6.3).

## atp-v0.14 (2026-10-28)

A grant whose host has not opened a channel for 90 days expires; a block does not (SPEC 6.4 item 8). A string
argument that carries a file's bytes is marked `contentEncoding: "base64"`, and a host may fill it from a file it holds,
for a provider on the same phone (SPEC 8 item 7).

## atp-v0.13 (2026-10-27)

`AtpProviderXml.problems(xml)` checks a provider's `atp_provider.xml` against SPEC 5.2, one sentence per broken rule,
for use in a plain unit test.

## atp-v0.12 (2026-10-27)

A question about a call in a group set to ask waits two minutes instead of one (SPEC 6.4 item 2), since opening the
notification, reading the question and answering can take more than a minute.

## atp-v0.11 (2026-10-27)

Groups set to ask each time work: each call is put to the person in a notification that opens the provider's question
screen, answered there by a tap. A call refused, left unanswered or impossible to ask about is an `isError` result
that names no missing group. The Connected apps screen offers On, Ask each time and Off for each group, and the three
guarded screens share one set of protections. The README and SPEC 11 describe the protocol as implemented and say
that not every MUST has a test of its own yet.

## atp-v0.10 (2026-10-27)

A tool source may send `instructions()` with the `initialize` result, at most 8,000 characters (SPEC 8 item 5). The
Gradle wrapper checks its download against the published SHA-256.

## atp-v0.9 (2026-10-27)

Results carry pictures as MCP image content after the text (`AtpImage`), up to 8 MiB per result; pictures past that
are left out with a line saying so (SPEC 8 item 4).

## atp-v0.8 (2026-10-27)

The Connected apps screen (`AtpConnectionsActivity`, exported for `com.nujan_agent.atp.action.CONNECTED_APPS`): every
connected or blocked app, a choice per group, and Disconnect. It is the only place a `provider-ui` group is turned on,
and it asks again before a high or critical group turns on. A connected app's name is kept for that screen and
refreshed whenever the app opens a channel.

## atp-v0.7 (2026-10-27)

UI hand-off (SPEC 9.1): `AtpCall.ui.show(intent, reason)` brings up one of the app's own activities during a call,
directly when one of its windows is on screen and otherwise through the host (`onUiRequest`, `uiResult`), waiting up
to 60 seconds.

## atp-v0.6 (2026-10-27)

The consent screen keeps clear of the system bars in apps that target Android 15 or later.

## atp-v0.5 (2026-10-27)

An `entry` class may take the app's `Context`. SPEC 5.2 names `entry` and says a host ignores attributes and elements
it does not name.

## atp-v0.4 (2026-10-27)

`AtpProvider.connectedApps` is a live flow of the connected apps. The consumer ProGuard rules keep a Kotlin object
that `entry` names.

## atp-v0.3 (2026-10-27)

The provider kit, `:atp-provider`, with which any app can offer tools: the exported provider service, the consent
screen in its own process, the bridge between them, host records and grants, and the channel server. Drops carry the
kit with its tests and consumer rules.

## atp-v0.2 (2026-10-23)

Identity and bookkeeping in the wire module: `CallerChecks` with the signer ratchet, `TrustedHosts`, `NonceBook`,
JSON-RPC message kinds and a book of requests in flight. `getInterfaceVersion` is removed from `IAtpProvider`,
because the AIDL compiler reserves that name; the binding version travels in `hello`, and the transaction codes in
`fixtures/aidl-table.json` changed with it.

## atp-v0.1 (2026-10-23)

The first drop: SPEC.md v0 (version 0.1), the AIDL (`com.nujan_agent.atp.v1`), the golden fixtures, and the wire
module (names and limits, codes, `hello` and `openJson` codecs, the `ndjson` line reader and writer, the write queue,
tool naming). `tools/export-drop.ps1` writes identical drops with `SHA256SUMS`.
