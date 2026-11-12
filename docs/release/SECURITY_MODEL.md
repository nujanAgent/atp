<!-- SPDX-License-Identifier: Apache-2.0 -->

# ATP security model

This document states what ATP protects, against whom, how, and what it leaves to others. It covers ATP specification
version 0.1 (`spec/SPEC.md`, which is normative; § numbers refer to it) and the provider kit at drop `atp-v0.22`.

ATP lets an app (the provider) offer MCP tools to an agent app (the host) on the same Android phone. Its security rests
on one rule: every trust anchor is something the platform enforces, never what a peer says about itself (§2 item 3).
And it authorizes every call twice, in the provider and in the host, independently of any model (§2 item 4).

## 1. Parties, assets and assumptions

| Party | Trusted? | Notes |
|---|---|---|
| The platform: kernel, Binder, PackageManager, activity and window managers | Yes | The source of every fact ATP relies on. |
| The person | Yes, for decisions | Decides on the consent, question and Connected apps screens. |
| The provider | No, by the host | May be hostile, or may pose as another app. |
| The host | No, by the provider | May be hostile, or may pose as another app. |
| The host's model | No, by either | May follow instructions planted in text it reads. |

**Assets.** The provider's capabilities: its data, its permissions, and what its tools do, which may include running
commands, installing apps or acting on the device. The host's data: what its model writes into arguments, its own
files, and the person's session. The person's attention and trust on consent screens.

**Assumptions.** The device's operating system is intact (not rooted, no malicious system image). Each app runs in its
own sandbox under its own uid. Both apps follow the specification, except where a threat below says otherwise.

## 2. Trust anchors

| Anchor (platform-enforced) | Where ATP uses it |
|---|---|
| The kernel uid of a Binder caller (`Binder.getCallingUid()`) | The provider checks it on every call into `IAtpProvider` (§4.3, §6.1); the host checks it on every `IAtpHost` callback (§4.3, §6.2). |
| The package and signer PackageManager reports for a uid, with the key-rotation lineage | Grants are keyed by package and signer; a stored signer moves forward only along the lineage (§6.1 item 4); the host records the provider's signer at consent (§6.2). |
| Package visibility (`<queries>`) | A host sees providers only by declaring the provider action (§5.3). |
| File descriptors held by two processes | The channel: pipes the host creates and hands over (§3). |
| The identity of the app that started an activity for a result | The provider's consent screen checks it (`getLaunchedFromUid()` on API 34+, `getCallingPackage()` below) against the nonce (§6.3 item 4). |
| The creator of a `PendingIntent` | The host checks it before it shows a provider's screen (§9 item 1). |
| Content provider authorities and system URI grants | The host checks a file's authority; files go to a provider only as per-call grants (§9 items 2, 3). |
| The window manager's knowledge of overlays | Consent screens reject obscured touches and hide overlays where the platform allows (§6.3). |

**Never an anchor:** MCP's `clientInfo`, intent extras, app names and labels, the provider XML's `risk` and `kind`,
tool annotations, a provider's `instructions`, and anything else a peer says about itself (§6.1 item 5).

## 3. Guarantees

When both apps follow the specification and the platform is intact:

| # | Guarantee | Mechanism |
|---|---|---|
| G1 | A provider serves a channel only to an app the person connected in the provider's own consent screen, identified by uid, package and signer. The same package signed by a key outside the recorded lineage is a different app and is asked about again. | §4.3, §6.1 |
| G2 | A grant comes only from a completed consent in the provider. A host's request alone never creates one, and a nonce cannot be replayed or used by another app: it is single use, valid for five minutes, and bound to the uid, package, signer and groups that asked. | §4.7, §6.3 |
| G3 | A group declared `grant="provider-ui"` can be turned on only inside the provider, on a screen with the consent screen's protections, which asks again before it turns on a high or critical group. | §6.4 item 3 |
| G4 | A call to a tool outside the grant does not run: it is an `isError` result naming the missing groups. A call in a group set to ask runs only after the person allows it with a tap in the provider's own activity; unanswered within two minutes, or with the provider's notifications blocked, it is refused. | §4.6, §6.4 items 2, 4 |
| G5 | The person can end a host's access at any time: revocation closes its channels with `REVOKED` and ends the stream within one second. A block persists. A grant unused for 90 days expires. | §6.4 items 6, 8, §7.4 |
| G6 | Text another app supplies (its label, its stated purpose) cannot hide words or reorder the sentence the person decides on. | §6.3 |
| G7 | A host accepts callbacks only from the provider's uid, and opens a channel only while the provider's signer is the one recorded at consent or its successor in the lineage. | §4.3, §6.2 |
| G8 | Channel descriptors are pipes, kept out of child processes, and never passed on. | §3.2 items 1 to 3, §4.3 item 3 |
| G9 | A peer that stops reading blocks only its own channel, and is closed as stalled. Lines, queues, channels, requests in flight and opens are bounded. | §3.2 items 5, 8, §7.2, §7.3 |
| G10 | When the person presses Stop on a provider's screen, the host ends that turn and does not repeat the call or try the same work another way. | §7.5 |
| G11 | A provider's instructions reach a model, if at all, as the provider's text, never as the person's or the host's. | §8 item 5 |
| G12 | A host opens a file a provider hands over only if its authority belongs to that provider, and a provider receives only files the person picked for that call, read-only, for that call. | §9 items 2, 3 |

## 4. Threats and defences

### 4.1 A rogue host

A hostile app, or a legitimate host misused, wants to use a provider's tools without the person's consent, or more of
them than the person granted.

- **Calling without consent.** The provider service is exported without a permission, so any app can bind it, but
  binding gains nothing: every call is answered from the platform's facts about the caller, and a channel opens only
  for a recorded grant (G1).
- **Pretending to be a trusted host.** Identity is the uid's single package and its current signer, never a name. Pins
  match an exact package and signer, and a release build of a provider never treats a debug-signed caller as pinned
  (§6.4 item 7). Pinning changes defaults; it never skips consent.
- **Borrowing another app's consent.** A nonce is bound to the uid that asked for it and consumed on first use,
  whatever the outcome, and the consent screen checks who launched it (G2). A plain `startActivity` carries no
  identity, so consent requires a start for a result.
- **Raising the consent screen at a moment of its choosing.** A provider never starts consent from an IPC call, a host
  never binds with `BIND_ALLOW_ACTIVITY_STARTS`, and the screen runs in the provider's own process (§5.1, §6.3 item 7).
- **Wearing the person down.** The consent screen shows at most three times per host per 24 hours, cools down after
  two denials, and offers Block (§6.3).
- **Escalating to dangerous groups.** `provider-ui` groups cannot be granted from the consent flow (G3). Groups set to
  ask put each call to the person (G4).
- **Probing.** `hello` reveals only the caller's own grant state, the provider's static servers and its limits, and
  does not throw for an ungranted caller (§4.7).
- **Flooding.** At most 8 channels per host, 32 requests in flight per channel and 10 opens per minute; a line may be
  refused above 64 MiB, and each channel's write queue is bounded at 32 MiB (§3.2, §7.2).

**Residual.** A host the person connected and granted a group controls what that group allows. A person may grant a
dangerous group to a hostile app.

### 4.2 A rogue provider

A hostile app wants to pass as another app, take data from the host, or make the host act against the person.

- **Posing as another app.** Hosts show platform facts (label and icon from PackageManager, package, a short signer
  hash, installer, install age, version) and never an icon the provider supplies (§5.3). `risk` and `kind` are claims
  that may only raise a host's caution, and a group a host does not recognise is high risk (§5.2).
- **Signer swap.** The host compares the provider's signer with the recorded one before binding and before each open,
  and shows a change as a different app (G7).
- **Forged callbacks.** The host ignores `IAtpHost` calls from any uid but the provider's (G7).
- **Taking the host's data.** What the host's model writes into arguments goes to the provider. The host's own sheet
  says so before anything is bound (§6.3 item 1), and the host's own policy decides what its model may send. A host
  fills a `contentEncoding: "base64"` argument from its own files only for a provider on the same phone (§8 item 7).
  Files go to a provider only when the person picked them for that call (G12).
- **Confused deputy through files.** A provider could hand the host a URI into the host's own content provider and
  have the host read its own files. The authority check prevents this (G12).
- **Taking over the screen.** A provider can ask the host to show one of its activities only with an immutable,
  explicit activity `PendingIntent` it created, during a call from that host, at most one per channel, expiring after 60
  seconds. The host checks the creator and that it opens an activity, and sends it only while visible, after its own
  card unless the person already approved the call (§9 item 1).
- **Steering the host's model.** See 4.3.
- **Exhausting the host.** Lines are bounded, pictures in a result are capped at 8 MiB together, and instructions at
  8,000 characters (§3.2, §8).
- **Laundering another provider's tools.** A provider must not re-export tools it got from another ATP provider (§8
  item 6). This is a rule, not something a host can check.

**Residual.** A provider receives what the host sends it; what it does with that is governed by its privacy policy,
which the host shows before connecting. `kind="egress"` is a claim; ATP does not contain a provider's network access.

### 4.3 Prompt injection through tool text

Tool names, titles, descriptions, annotations, a provider's instructions, results, pictures and file contents all come
from another app, and any of them may carry instructions aimed at the host's model.

ATP's defences are structural rather than filters:

- Authorization happens in the provider and in the host, independently of the model (§2 item 4). A misled model
  cannot widen a grant, answer the provider's question for the person, or turn on a `provider-ui` group.
- A provider's instructions are presented as the provider's text, and a host may ignore them (G11).
- Annotations and XML claims are treated as claims. MCP itself says clients must consider tool annotations untrusted
  unless they come from trusted servers.
- A stopped turn is not retried another way (G10).
- The host's own sheet tells the person that results may reach the host's model (§6.3 item 1).

ATP does not clean results for a model, and it cannot stop a model from misusing tools within what the person granted.
That is a residual risk, which hosts reduce with their own policy: per-call approvals, how device data is treated, what
leaves the phone. NuJan, for example, asks the person before an app's tool runs and treats results as data from the
device.

### 4.4 Deceiving the person

- **Overlays and tapjacking.** Consent, question and Connected apps screens filter touches when the window is obscured,
  reject partly obscured touches, hide other apps' overlays (API 31 and later, with `HIDE_OVERLAY_WINDOWS`), mark Allow
  as sensitive to accessibility services (API 34 and later), and arm Allow only after about one second of focus
  (§6.3). Below API 31 overlays cannot be hidden, so every touch is rejected while one shows; the screen should say why,
  and a host should hide its own overlay while a consent screen it started, or a question about its call, is in front.
- **Text that changes the sentence.** Another app's label and purpose are shown without control characters other than
  line breaks, bidirectional controls, zero-width characters, variation selectors and Unicode Tags, and inside a
  sentence between U+2068 FIRST STRONG ISOLATE and U+2069 POP DIRECTIONAL ISOLATE; a label with nothing left is shown
  as the package name (G6).
- **Imitated notifications.** A question about a call is answered in the provider's own activity by a tap, never
  through notification actions, which another app's notification could imitate (§6.4 item 2).
- **Hidden consent.** The consent activity runs in its own process with `launchMode="standard"`, started for a result
  without new-task flags, so it appears over the host the person was using.

### 4.5 Transport abuse

- **Descriptor leaks.** The host creates both pipes and closes its copies of the ends it hands over; the provider
  accepts only FIFOs, sets `FD_CLOEXEC` and keeps descriptors out of child processes; descriptors are bearer
  capabilities and never passed on (G8).
- **Exposed endpoints.** ATP rejects loopback HTTP, which any app on the device can reach, abstract-namespace sockets
  and oneway AIDL messages as a data plane (§3.2).
- **Stalls and floods.** One writer and one bounded queue per channel; list changes coalesce, progress keeps the
  latest per token, logs drop when the queue is half full; a peer that leaves a response unwritten for 10 minutes, or a
  notification for 60 seconds, is closed as stalled (G9).
- **Frozen processes.** A process that might be frozen is never called synchronously, and a host stays in an active
  state while it holds a channel (§2 item 5, §7.1 item 2).

### 4.6 Identity over time

- **Key rotation.** A stored signer is accepted when it is the current signer or in the current lineage, and moves
  forward to the current one; a signer outside the lineage is a different app (§6.1 item 4).
- **Shared and own uids.** A uid with several packages is refused, and so is the provider's own uid, so code running in
  the provider's sandbox cannot act as a host to it (§6.1 items 2, 3).
- **Stale grants.** A grant whose host has not opened a channel for 90 days expires, as Android resets the permissions
  of an app left unused; a block does not expire (§6.4 item 8). Disconnect in the host deletes the grant in the
  provider (`releaseGrant()`, §7.4 item 2).

## 5. Who does what

| Concern | Platform | ATP specification | Provider kit (`atp-v0.22`) | Host policy |
|---|---|---|---|---|
| Who is calling | uid, package, signer | Checks on every call; signer ratchet | `CallerChecks`, run on every call | Checks the provider's uid and signer |
| Consent | Caller identity of an activity result | Two sheets, nonce, explicit outcome, limits | Consent screen, nonce book, limits | Its own sheet; when to ask |
| What may run | | Groups ON / ASK / OFF; `provider-ui` | Grants, the question screen, Connected apps | Approvals before calls |
| Screens | Overlay hiding, obscured touches | Required protections, text rules | `ScreenGuard`, `AppText` | Hides its own overlay |
| Transport | Descriptors over Binder | Pipes, framing, bounds, close reasons | Channel server, write queue | Channel client |
| Model safety | | Instructions as the provider's text; Stop not retried | | How results reach the model |

## 6. The provider kit at `atp-v0.22`

The kit implements the provider's side of everything above. Points that bear on security, including known gaps:

- `hello`, `openChannel` and `releaseGrant` run the full caller checks over the platform's facts, and `closeChannel`
  and `uiResult` act only on channels the calling uid opened. The consent screen runs in its own process and reaches
  the main process only through a non-exported content provider, which writes a grant only against a ticket issued
  after the nonce and the caller's identity checked out.
- `AtpProvider.trustedHosts` defaults to no pins and to release-build behaviour, so a debug-signed caller is never
  treated as pinned.
- The consent screen offers the groups `AtpProvider.initialGrant` turns on (by default, every `in-flow` group) and
  does not read the groups the host asked for (`EXTRA_GROUPS`), nor compare them with the groups bound to the nonce.
  The screen lists exactly what it grants, so nothing is granted unseen, but a host asking for less can be offered
  more.
- Inside the kit, a group without a `risk` reads as `low` and a group without a `grant` as `in-flow`.
  `AtpProviderXml.problems` reports both as errors; keep it empty in a test.
- The stall watchdog uses wall-clock time and does not pause while the host is frozen. A host that follows the
  freezer rule is not frozen while it holds a channel.
- Results cannot carry a `resource_link`, so providers built on the kit do not use the file handoff of §9 item 2.

## 7. Out of scope

- A compromised or rooted device, a malicious system image, or an attacker with the unlocked phone in hand.
- A person who grants a dangerous group to a hostile app. ATP makes the decision explicit and reversible; it does not
  make it for the person.
- What a provider does with data it receives, and its network access.
- A model that follows injected text within the tools it was granted.
- Side channels between apps on the same device.
- The authenticity of a drop. `SHA256SUMS` lets a consumer check that its copy matches the tagged files; it does not
  say who tagged them.
- Platform differences ATP cannot remove: below API 31 overlays cannot be hidden, and below API 34 the provider learns
  who started its consent screen from `getCallingPackage()` rather than `getLaunchedFromUid()`.

## 8. Testing and review

- **Unit tests in the repository at `atp-v0.22`.** 54 in the wire module and 102 in the provider kit, including the
  caller checks, nonces, the provider's policy for `hello`, `openChannel`, expiry and release, consent limits, text
  cleaning, and the channel server's bounds and stall handling. The specification says that not every MUST has a test of
  its own yet (§11).
- **Not done.** There has been no independent security review, no fuzz testing and no systematic adversarial testing of
  a host or a provider.

## 9. Reporting a vulnerability

Report a vulnerability privately, as `SECURITY.md` at the root of the repository describes, not in a public issue.
Include the version or commit, the Android version, and the steps that show the problem. A fix is released as a new
tagged version, `atp-v0.N`, with an entry in `docs/release/CHANGELOG.md`, so a consuming app takes it by exporting that
version.

"Model Context Protocol" and "MCP" are trademarks of LF Projects, LLC; ATP is an independent protocol for use with MCP.
Android is a trademark of Google LLC.
