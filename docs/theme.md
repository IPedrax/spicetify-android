# Theme colors

The **Theme colors** patch adds a **Spicetify Marketplace** button and a
**Theme** section to Spicetify settings. Themes need Android 14 or later. On
older Android, the Theme section just says so, Spotify keeps its own colors,
and the Marketplace still opens, on its **Extensions** tab.

## The settings page

Spicetify settings opens with a **Spicetify Marketplace** button whenever
Theme colors or Spicetify extensions is installed, on any Android version. With
Theme colors on Android 14 or later, your current theme comes next: its name
and a strip of its colors. Tap either to open the Marketplace. When the
current theme has a background image, a **Blur background image** switch
appears below it. On older Android, the **Theme** section only says that
themes need Android 14 or later.

## The Marketplace

Where themes apply, the Marketplace opens on its **Themes** tab, with an
**Extensions** tab next to it (see [extensions](extensions.md)). The Themes
tab lists, in order:

| Item | What it is |
| --- | --- |
| Spotify default | Spotify's own colors. |
| AMOLED black | A black background, with menus and sheets slightly lighter. |
| Material You | Your wallpaper's palette. It updates when Spotify starts after a wallpaper change. |
| Material You, black background | The wallpaper's accents on a black background. |
| Galaxy V2 | Galaxy's colors over its own fullscreen background image, the way the desktop theme shows it. |
| Paste a Spicetify theme | Opens the paste form below. |
| Community themes | Everything the desktop Marketplace lists (GitHub topic spicetify-themes, minus archived repositories and Marketplace's blacklist), most stars first, with previews and search. Themes without a usable color scheme are left out. Themes with a background image on desktop bring it along. The list is cached for 6 hours; Refresh reloads it. |

Tapping a preset applies it right away. Tapping Galaxy V2 or a community
theme downloads its colors, then opens a scheme chooser when it defines more
than one. Previews and color schemes load from hosts the theme authors
choose.

Below Android 14, every theme card is disabled and says **Needs Android 14 or
later**, tapping one only shows that message, and the Marketplace opens on its
**Extensions** tab instead, where extensions work normally. On Android 14 or
later with only the Spicetify extensions patch installed, the cards are
disabled the same way and say **Needs the Theme colors patch**.

## Pasting a theme

**Paste a Spicetify theme** opens a form for a `color.ini`, or CSS with
`--spice-*` variables, and an optional accent key that picks the accent from
a custom key, such as Catppuccin's `mauve`.

Applying a theme reopens the settings screen; a theme without a background
image clears the previous one. Some colors change only after Spotify
restarts.

Some screens, such as Settings, and some controls, such as the Home filter
chips, keep Spotify's colors: they use colors the theme doesn't map, or
colors Spotify draws in its code.

Only colors carry over from Spicetify themes. `user.css`, `theme.js`,
extensions and custom apps need Spotify's desktop web interface. The keys
map like this:

| Key | Colors on Android |
| --- | --- |
| `main` | App background |
| `main-elevated` | Menus, sheets and dialogs (derived from `main` when missing) |
| `card` | Cards and tiles |
| `highlight`, `highlight-elevated` | Pressed rows (derived from `main` when missing) |
| `text`, `subtext` | Primary and secondary text and icons |
| `button` | Accent, unless an accent key names another key |
| `button-active` | Pressed accent (derived when missing) |
| `button-disabled` | Disabled controls |
| `selected-row` | Translucent row overlays, keeping Spotify's transparency |
| `tab-active` | Spotify's `gray_20` color (the Home filter chips don't use it) |
| `notification`, `notification-error` | Announcements and errors |
| `shadow` | Shadows and scrims, keeping Spotify's transparency |

Other keys aren't used, including `sidebar`, `player`, `misc`, and newer ones
such as `play-button` and `nav-active`. Keys a scheme leaves out keep
Spotify's colors. Values are read the way desktop Spicetify reads them: bare
hex such as `1db954` (the first six digits of a longer run) and `r,g,b`
decimals. A value ends at `;`, or at a `#` after its first character. A value
desktop would pad with white, such as a color name, is skipped, and so are
`${xrdb:...}` and environment variables, because they read the desktop.
`#RGB`, `#RRGGBB` and `#AARRGGBB` are an Android addition: desktop reads a
value that starts with `#` as a comment.

## How it works

The patch declares Spotify's mapped colors overlayable and changes no color
values. The chosen theme becomes an overlay that Spotify registers for
itself, loaded into its resources at startup and into every screen as it
opens.

## Upgrading from patch-time colors

Earlier versions of the Theme colors patch chose colors with background,
accent, and pressed accent options in Morphe Manager. Those options are
gone, and Morphe Manager ignores any values saved for them. After
repatching, Spotify starts with its own colors, and you choose a theme in
Spicetify settings instead. AMOLED black recreates the old default. On
Android 13 and older, Spotify keeps its own colors.
