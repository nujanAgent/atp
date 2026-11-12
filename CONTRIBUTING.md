# Contributing

Bug reports, questions about the specification and pull requests are welcome.

## Issues

- Search the existing issues first.
- For a bug, give the version (an `atp-v0.N` tag or a commit), the Android version and API level, and a failing test
  or the steps that show the problem.
- Open an issue before a large change, a new public API in the kit, or a change to the specification, so that the
  approach is agreed before the work starts. Version 0.1 freezes the Binder interfaces, the wire values and the golden
  fixtures; changing those means a new version of the specification.
- Report security vulnerabilities privately, as [SECURITY.md](SECURITY.md) describes, not in an issue.

## Pull requests

- Keep a pull request to one change, and link the issue it addresses.
- Add tests with the change: a fix comes with a test that fails without it, and new behaviour with tests of its own.
- Run the checks before you open the pull request:

  ```sh
  ./gradlew :atp-wire:test :atp-provider:testDebugUnitTest :atp-provider:lintDebug :samples:hello:testDebugUnitTest
  ```

- When behaviour changes, update the specification or the guide that describes it, and add an entry to the
  [changelog](docs/release/CHANGELOG.md).
- Follow the Kotlin coding conventions (`kotlin.code.style=official`) and the style of the code around the change. New
  source and documentation files start with an `SPDX-License-Identifier: Apache-2.0` line, as the existing ones do.

## License

Unless you state otherwise, a contribution you submit for inclusion is licensed under the Apache License 2.0, as
section 5 of the [license](LICENSE) provides.
