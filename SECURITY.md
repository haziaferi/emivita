# Security policy

Emivita keeps medication records on the phone, encrypted (SQLCipher, key
wrapped by the Android Keystore), and declares no INTERNET permission. A flaw
that exposes those records, lets another app read or change them, or stops
a reminder from ringing is a security issue.

## Reporting a vulnerability

Please report it privately through GitHub: open the repository's
**Security** tab and choose **Report a vulnerability**. Do not open a public
issue for it. The report stays visible only to you and the maintainer until
a fix is published.

Include the app version, the Android version and device, and the steps that
show the problem.

## Supported versions

Only the latest commit on `master` and the latest build made from it are
supported; fixes are not back-ported.
