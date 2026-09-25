# Third-party sources

This repository retains the upstream GPL-3.0 license and Morphe NOTICE.
The following revisions document the source used for the first milestone.

## Morphe patch template

The repository began with
[MorpheApp/morphe-patches-template](https://github.com/MorpheApp/morphe-patches-template/tree/f99b2938bd25b202a6185a774d487a07d1061915)
at `f99b2938bd25b202a6185a774d487a07d1061915`, under GPL-3.0 and its NOTICE.
Build files, the Gradle wrapper, and release tooling originate there.
September 17, 2026 modifications replace example patches, project metadata,
documentation, and issue forms, and add tests and release verification gates.

## Historical Spotify implementations

[anddea/revanced-patches](https://github.com/anddea/revanced-patches/tree/3174510163d9787571bd93275b54932b575f82ed)
at `3174510163d9787571bd93275b54932b575f82ed` provides the GPL-3.0 historical
reference for these features:

- `patches/src/main/kotlin/app/revanced/patches/spotify/layout/theme/CustomThemePatch.kt`
- `patches/src/main/kotlin/app/revanced/patches/spotify/misc/privacy/Fingerprints.kt`
- `patches/src/main/kotlin/app/revanced/patches/spotify/misc/privacy/SanitizeSharingLinksPatch.kt`
- `extensions/shared/src/main/java/app/revanced/extension/spotify/misc/privacy/SanitizeSharingLinksPatch.java`

The theme's color map starts from that implementation's resource selection
and extends it to the colors listed in `patches/src/main/resources/theme/`,
checked against Spotify 9.1.80.2221. The patch no longer edits color values;
it declares the mapped colors overlayable and applies the chosen theme at
runtime. It does not import the historical extension, animation hooks, or
icon assets.

The sharing implementation uses a new fingerprint for Spotify 9.1.80.2221's
URL builder. Its new Java helper removes named tracking parameters while
preserving other query parameters and fragments, instead of truncating the
query. These adaptations were made on September 17, 2026.

No code from binary-only candidates was imported. Spotify APKs and other
proprietary assets are excluded.

## Morphe theme overlay

`ThemeOverlay.java` ports the self-targeting overlay technique of Morphe's
`ThemeColorOverlay.java` in MorpheApp/morphe-patches (GPL-3.0 with the
Morphe NOTICE this repository retains), read on September 25, 2026.
`ThemeRuntime.java` follows Morphe in registering the overlay on every
start, because Android deletes an app's own overlays when it is installed
again. Local changes: one overlay for all mapped Spotify colors instead of
separate dark and light background overlays, a single shared
`ResourcesLoader` that is updated in place, and loading through activity
lifecycle callbacks instead of Morphe's base-context hook.
