# Security policy

## Reporting a vulnerability

Please do not open a public issue for security problems. Use GitHub's private
[vulnerability reporting](../../security/advisories/new) for this repository and include the steps to
reproduce, the Brisa version and the Android and WebView versions shown in Settings > About.

## Scope

Brisa uses the system WebView, so rendering-engine vulnerabilities are fixed by updating
*Android System WebView*, not by updating Brisa. Reports about Brisa's own code are in scope: address-bar
handling, download and file handling, the blob download bridge, link and intent handling, and anything that
stores or sends user data.
