# JustProxy icon alternatives

Open [index.html](index.html) or [preview.png](preview.png) to compare the four directions, including small, round, and themed treatments.

1. **Relay J:** a recognizable J with a forward arrow.
2. **Tunnel:** a gateway framing bidirectional traffic.
3. **Linked (selected and refined):** balanced interlocking loops with smooth curves and a gentle upward angle.
4. **Mobile Gateway:** a phone with an outgoing connection.

The refined Linked icon is applied to the Android launcher foreground, Android 13+ monochrome icon, in-app drawable, and notification icon. The notification version uses a larger white silhouette for small-size legibility. Existing adaptive backgrounds and launcher resource names are preserved.

Each SVG is scalable and self-contained. Matching 108dp Android foreground and monochrome vectors are in `android/`. Pair the foreground with the existing navy adaptive background. The SVG rounded square is a presentation background; Android supplies its own launcher mask. Themed previews illustrate a possible launcher palette.

Created directly as native vector paths using the existing navy (#081A2C), teal (#21D4B4), and off-white (#F4F8FB) palette. No raster assets are required by the app.

## UI polish and verification

The main screen now uses the Linked mark in a shorter header, sentence-case buttons that grow with their labels, distinct disabled-action colors, 48dp minimum touch targets, selectable public IP text, and labels associated with their settings fields. System-bar insets keep the content clear of the status and navigation bars.

Verified with Android resource compilation, lint (no errors; existing warning categories remain), and all 124 JVM unit tests. A debug APK was assembled using the unchanged cached native libraries. The comparison was rendered at desktop and mobile widths. Live emulator verification was unavailable because the configured AVD was already in use.
