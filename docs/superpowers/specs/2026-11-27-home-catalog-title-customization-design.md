# Home catalog title customization

**Date:** 2026-11-27  
**Target:** Nuvio Mobile Enhanced — Android and iOS, shared Compose Multiplatform UI

## User problem

The homepage contains multiple independent catalog shelves (for example, Popular Movies and Popular Series). Users need to rename and style each heading independently without changing poster metadata, collection names, player subtitles, or other shelves.

## Interaction

- Keep the existing per-catalog expanded row in Settings > Home screen > Catalogs.
- Keep the existing Display name field and add a compact Customize title disclosure immediately beneath it.
- The editor previews the selected heading and exposes Font, Font size, Bold, and Colour. Changes apply immediately to that one catalog.
- Use the existing subtitle font picker, native TTF/OTF import path, and font-label resolution rather than building a second font subsystem.
- Keep Use theme and Reset title style as explicit, discoverable actions.
- Imported font bytes remain on the device. The font ID is synchronized; a device without that imported font uses the normal platform fallback.

## Settings discoverability

The existing searchable home-catalogs destination is retained so the result jumps to the catalog list through the current anchor/highlight behavior. Its title becomes Catalog title appearance and its search aliases include catalog titles, shelf title, heading font, font size, colour/color, and typeface. People can search for the capability without needing to know the implementation label.

## Style model

Each catalog stores a small serializable value:
- font ID: built-in platform/default choices or an existing imported-font identifier;
- font size: integer 16–32 sp, with 22 sp as the starting value;
- bold toggle, using Semibold as the normal custom-title weight;
- named colour token or theme colour.

The eight named colours resolve to separate light-surface and dark-surface text shades. This avoids persisting platform Color objects and keeps the common model deterministic. Invalid IDs/colours are normalized to safe defaults and font sizes are clamped before display or persistence.

## Persistence and sync compatibility

- Style lives beside the existing catalog preference in the current native preference storage. No additional storage layer or dependency is needed.
- The existing per-profile shared settings payload gains an optional title_style field.
- Old payloads that do not contain title_style continue to decode and preserve the current local style.
- New clients send a concrete default style for reset operations, so a reset propagates instead of being mistaken for a legacy missing field.
- Collections are deliberately unchanged; this request is for add-on catalog shelf headings.

## Rendering and performance

- The existing homepage catalog model carries the style along with the already-loaded section.
- Only the heading TextStyle/Color changes; poster rows, image prefetch, catalog fetching, and navigation are not modified.
- The default style passes null overrides to the shelf header and therefore preserves its exact existing Material theme typography/colour.
- Imported fonts are loaded through the existing asynchronous rememberSubtitleFontFamily path on a background dispatcher, with default-font fallback if the font is unavailable.
- No per-frame animation, bitmap processing, network request, or new dependency is introduced.

## Accessibility and platform guidance

Apple's Human Interface Guidelines recommend legible sizes and weights, avoiding thin weights, maintaining contrast across appearances, and using system text styles where possible. The editor keeps the size in sp, defaults to the existing Material heading style, offers Semibold/Bold rather than thin weights, and adapts each named colour to light or dark surfaces. Apple's hit-target guidance specifies at least 44 x 44 points; colour swatches use 44 dp touch areas and a visible check mark plus accessibility semantics for the selected state.

Sources:
- Apple, Typography: https://developer.apple.com/design/human-interface-guidelines/typography
- Apple, Color: https://developer.apple.com/design/human-interface-guidelines/color
- Apple, Settings: https://developer.apple.com/design/human-interface-guidelines/settings
- Apple, UI Design Dos and Don'ts: https://developer.apple.com/design/tips/
- Apple, Design principles: https://developer.apple.com/design/human-interface-guidelines/design-principles
- Compose Multiplatform documentation: https://www.jetbrains.com/compose-multiplatform/

## Community research (anecdotal)

Recent community posts and implementations suggest that users value per-catalog names, configurable home lists, previewable styling, and clear discoverability. These are product signals from individual threads and projects, not representative surveys:

- Stremboxd announced support for custom catalog names; commenters asked for more control over catalog organization and presentation: https://www.reddit.com/r/StremioAddons/comments/1r6enwg/stremboxd_v101_sorting_liked_films_catalog_custom/
- Jellyfin users shared positive reactions to custom subtitle fonts and discussed how to discover or use the controls across devices: https://www.reddit.com/r/jellyfin/comments/1rpykg0/i_just_discovered_custom_subtitle_fonts_in/
- Jellyfin Enhanced documents a live-preview subtitle styling editor with font, size, colour, and preset controls: https://n00bcodr.github.io/Jellyfin-Enhanced/12.17/enhanced/enhanced-features/

## Verification status

Unit and Android host tests are added for normalization, palette fallback, sync backward compatibility, search aliases, and persisted style. Builds/tests are intentionally not run as requested; compile and runtime verification remain pending.
