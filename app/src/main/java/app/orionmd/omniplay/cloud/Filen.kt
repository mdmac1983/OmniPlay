package app.orionmd.omniplay.cloud

/**
 * Filen (filen.io) — end-to-end encrypted. OmniPlay connects to it through Filen's own WebDAV server so the decryption is
 * handled by Filen's official tools and nothing unencrypted leaves your machine:
 *   • Desktop app: Settings → WebDAV → enable it (choose a username, password and port), or
 *   • Filen CLI:  filen webdav --username you --password secret --port 8080
 * Then enter that URL (e.g. http://your-computer:8080), username and password here. For offline-only use you can instead
 * add your Filen sync folder as a Local folder.
 */
object Filen : WebDavBase("filen", "Filen (via WebDAV)",
    "Filen is end-to-end encrypted, so connect through its WebDAV server. In the Filen desktop app turn on Settings → WebDAV (set a username, password and port), or run the Filen CLI:  filen webdav --username you --password secret --port 8080 . Then enter that address, username and password here.",
    "https://docs.filen.io/docs/cli/webdav")
