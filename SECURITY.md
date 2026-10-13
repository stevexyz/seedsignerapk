# Security Policy

## Reporting a Vulnerability

Report security issues — and any bug that could affect the security of users' funds — as a **GitHub issue** on <https://github.com/stevexyz/seedsignerapk>.

The channel is a GitHub issue on purpose: **for transparency**, every reported issue is publicly visible to the community.

If the issue is delicate, **do not include sensitive details in the issue**. Describe it as briefly as safely possible and offer direct contact (e.g. an email address) so maintainers can reach you privately for the details.

## Scope

The binding security requirements are in [docs/spec.md](docs/spec.md): total isolation (only inputs touch + camera, only output screen), statelessness (secrets in process memory only), no external dependencies. Anything that breaks these properties (e.g. a secret written to disk or logcat, a permission beyond camera, a runtime channel out of the process) is in scope.
