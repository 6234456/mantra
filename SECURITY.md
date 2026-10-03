# Security policy

Mantra is pre-1.0. Security fixes are applied to the current default branch; there are no maintained
release lines yet.

## Reporting a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/6234456/mantra/security/advisories/new)
if it is enabled. Do not include vulnerabilities, private case data or credentials in a public issue.
If private reporting is unavailable, contact the maintainer privately through a contact method listed
on the [maintainer's GitHub profile](https://github.com/6234456) to arrange a private report.

Include the Mantra and Normein commits, affected command or endpoint, a minimal fictional reproducer,
the observed impact and relevant Java/Node versions. Remove taxpayer, employee and customer data.

## Scope and deployment

The reference workbench is a local tool. It listens on loopback, validates the `Host` header, requires
a session token for POST requests and confines file access to the workspace. It is not intended for
multi-user or public network deployment. Do not expose it through a reverse proxy or share its session
token as a substitute for a deployment security model.

Treat `.mantra` documents and imported CSV, JSON and XLSX files as untrusted input. The kernel and
service enforce resource limits, but documents and imports should still be kept small and reviewed.
Run local demonstrations with fictional data. Keep JDK, Node.js and dependencies current; include
dependency changes in normal verification and preserve the pinned Normein contract.

Demonstration tax and accounting schemas do not guarantee complete or current domain rules. Domain
accuracy questions belong in ordinary issues unless they also expose a security vulnerability.
