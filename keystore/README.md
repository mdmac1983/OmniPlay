# OmniPlay signing keystore

`release.keystore` signs every build (debug and release) so the app's self-integrity check passes and updates install
over each other. Its passwords live in `keystore.properties` next to it (or as environment variables / GitHub Actions
secrets with the same names: `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Both files are committed by the owner —
this repository is private.

- The certificate SHA-256 is pinned in `app/src/main/java/app/orionmd/omniplay/Guard.kt` (`EXPECTED`).
- If you ever regenerate the keystore, update `EXPECTED` to the new certificate's SHA-256 (uppercase, no colons), from
  `keytool -list -v -keystore keystore/release.keystore`.
