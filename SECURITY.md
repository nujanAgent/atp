# Security policy

## Reporting a vulnerability

Please report a vulnerability privately, through GitHub's private vulnerability reporting: on this repository, open
the **Security and quality** tab and click **Report a vulnerability**, or go to
https://github.com/nujanAgent/atp/security/advisories/new. GitHub describes the form in
[Privately reporting a security vulnerability](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/report-privately).

Do not report a vulnerability in a public issue, discussion or pull request.

A useful report includes:

- the version (an `atp-v0.N` tag, or a commit) and the part affected: the specification, the wire module, the provider
  kit or the sample;
- the Android version and API level it was seen on;
- the steps, or a test, that show the problem;
- the impact.

A report is acknowledged within 7 days, and within 30 days it has a fix or a plan with a date. A fix is released as a
new tagged version with an entry in the [changelog](docs/release/CHANGELOG.md). An app that vendors the kit takes the
fix by exporting that version.

## Scope

The [security model](docs/release/SECURITY_MODEL.md) states what ATP protects, against whom, and what it leaves to
others. What it lists as out of scope, such as a rooted device or a person granting a dangerous group to a hostile
app, is not a vulnerability in ATP. A guarantee the specification states and the kit does not keep is.
