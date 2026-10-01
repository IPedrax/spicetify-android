# Spicetify Android patches

Spotify for Android, themed the Spicetify way. These are patches for use with
[Morphe](https://morphe.software/): add this source to Morphe Manager, patch
your own Spotify APK, and get in-app themes, the Spicetify Marketplace and
background images. The repository never distributes Spotify.

This is my personal build of
[spicetify/morphe-patches](https://github.com/spicetify/morphe-patches), with
features that aren't upstream yet. It isn't the official Spicetify source.

[**➕ Add to Morphe Manager**](https://morphe.software/add-source?github=IPedrax/spicetify-android/tree/main)

Open the link on your phone with Morphe Manager installed.

## What you get

- **Themes inside Spotify.** Switch between AMOLED black, Material You, or any
  Spicetify theme without repatching. Requires Android 14 or later.
- **Spicetify Marketplace.** Browse the community themes the desktop
  Marketplace lists, with previews and search, and apply a color scheme in one
  tap.
- **Four extensions, no repatching.** Trash Bin, Play a random song,
  Shuffle+ and Hide podcasts turn on with a switch in the Marketplace's
  Extensions tab.
- **Background images.** Themes that have a background on desktop (Galaxy,
  Hazy, CyberNight, Sakura and others) bring it along. Galaxy V2, pinned at the
  top of the Marketplace, is Galaxy over its fullscreen image. A switch blurs
  the image if you like it soft.
- **More of Spotify follows the theme.** Your Library, playlist headers and
  the play buttons use the theme's colors too, not just the classic screens.
- **Clean sharing links, Home pins and server files.** Tracking parameters
  come off shared links, and you can pin shortcuts from your whole library
  first on Home. Local Files can stream from an HTTPS WebDAV folder.

## Patches

| Patch | Default | What it does |
| --- | --- | --- |
| Clean sharing links | On | Removes `si`, `pi` and known `utm_*` parameters from `open.spotify.com` links, keeping timestamps and everything else. |
| Theme colors | Off | Adds a **Spicetify Marketplace** button and your current theme to Spicetify settings: presets, Galaxy V2, community themes and pasted ones. Android 14 or later. See [Theme colors](docs/theme.md). |
| Spicetify extensions | Off | Adds Trash Bin, Play a random song, Shuffle+ and Hide podcasts to the Marketplace's Extensions tab. See [Extensions](docs/extensions.md). |
| Pin shortcuts on Home | Off | Pins playlists, albums or Liked Songs from your library first on Home, in the order you pick them. |
| Local files from a server | Off | Streams an HTTPS WebDAV folder into Local Files. Needs byte-range support. Not available for root mount installs. |

<!-- PATCHES_START EXPANDED -->
> **[v1.1.1](https://github.com/IPedrax/spicetify-android/releases/tag/v1.1.1)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;5 patches total
<details open>
<summary>📦 Spotify&nbsp;&nbsp;•&nbsp;&nbsp;5 patches</summary>
<br>

**🎯 Supported versions:**

| 🧪&nbsp;9.1.80.2221 |
| :---: |
| Experimental Android customization patches; see the repository verification report. |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Clean sharing links](#clean-sharing-links) | Removes sharing identifiers and marketing parameters from open.spotify.com links. Keeps playback timestamps, context, and other parameters. |  |
| [Local files from a server](#local-files-from-a-server) | Streams audio from an HTTPS WebDAV folder into Local Files. Configure the server in Spicetify settings. Experimental; requires byte-range support. |  |
| [Pin shortcuts on Home](#pin-shortcuts-on-home) | Pick playlists, albums or Liked Songs to pin first on Home, or turn on Show only my pins to hide Spotify's other shortcuts. Pins are saved on this device; restart Spotify after changing them. |  |
| [Spicetify extensions](#spicetify-extensions) | Adds Android versions of Spicetify extensions to the Spicetify Marketplace: Trash Bin, Play a random song, Shuffle+ and Hide podcasts. Turn each one on in the Marketplace's Extensions tab. |  |
| [Theme colors](#theme-colors) | Adds a Theme section to Spicetify settings, with the Spicetify Marketplace for presets, Galaxy V2, community themes and a way to paste your own. Requires Android 14 or later; some hardcoded colors and animations keep Spotify's look. |  |

</details>

<!-- PATCHES_END -->

## Install

You need Spotify **9.1.80.2221** for ARM64 (`com.spotify.music`) as an APK or
split-APK archive, and Morphe Manager.

1. Tap **Add to Morphe Manager** above and confirm. To add it by hand, open
   **Sources**, select **Add**, choose **Remote**, and paste:

   ```text
   https://raw.githubusercontent.com/IPedrax/spicetify-android/refs/heads/main/patches-bundle.json
   ```

2. Expand **Spicetify Android patches** and enable **Experimental
   app versions**.
3. Open **Settings > Advanced** and turn on **Expert mode**. Without it, Manager
   applies only Clean sharing links.
4. Select **Spotify**, choose your own APK, pick the patches you want (at least
   **Theme colors** for the themes), and patch.
5. Install. A patched APK is signed with Manager's key, so replacing stock
   Spotify means uninstalling it first, which removes its downloads. On a
   rooted phone, Manager's mount install keeps your data instead.
6. In the Play Store, open Spotify's page, tap **⋮** and turn off **Enable auto
   update**. An update to another Spotify version drops the patches.

## Use it

In Spotify, open your profile menu, **Settings and privacy**, then
**Spicetify**, just above **Log out**. With Theme colors installed, it opens
with a **Spicetify Marketplace** button, then your current theme; tap either
to open the Marketplace. The Marketplace's **Themes** tab has the presets,
**Galaxy V2**, **Paste a Spicetify theme** and community themes; its
**Extensions** tab turns on Trash Bin, Play a random song, Shuffle+ and Hide
podcasts. Picking a theme reopens the screen with it; a theme without a
background image clears the image. Clean sharing links, Home pins and server
files have their own controls there when installed. See
[extensions](docs/extensions.md) and
[optional features](docs/optional-features.md).

## Releases

Stable releases come from `main`, and the **Add to Morphe Manager** link
follows them. Pre-releases come from `dev`:
[add the pre-release source](https://morphe.software/add-source?github=IPedrax/spicetify-android/tree/dev).
All versions are on the [releases page](https://github.com/IPedrax/spicetify-android/releases).

## Build it yourself

Follow [CONTRIBUTING.md](CONTRIBUTING.md) to build `patches/build/libs/patches-*.mpp`,
then load it in [Morphe Desktop](https://github.com/MorpheApp/morphe-desktop)
with your own Spotify APK.

## Credits and license

- [spicetify/morphe-patches](https://github.com/spicetify/morphe-patches), the
  upstream project this build extends, which began from Morphe's patch
  template.
- [spicetify/marketplace](https://github.com/spicetify/marketplace), whose
  discovery rules the Marketplace follows, and the theme authors whose work it
  lists. Galaxy is by [harbassan](https://github.com/harbassan/spicetify-galaxy).

The code is licensed under [GPL-3.0](LICENSE), with the upstream
[NOTICE](NOTICE) retained. See [third-party sources](THIRD_PARTY_NOTICES.md).
This project is independent of Spotify, Spicetify and Morphe.
