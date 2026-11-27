# Implementation plan: per-catalog homepage title appearance

1. Add a common serializable HomeCatalogTitleStyle with safe bounds, known font IDs, named light/dark-safe colour tokens, and unit tests.
2. Extend the existing local per-catalog preference and published homepage section models.
3. Extend the current per-profile sync item with an optional title_style field; make explicit default styles represent resets.
4. Pass optional heading TextStyle/Color through the reusable shelf header while preserving existing defaults and all poster-row behavior.
5. Add the inline editor to each expanded add-on catalog settings row. Reuse the existing subtitle font picker and the current app settings controls.
6. Keep the existing catalog settings search anchor and add natural aliases for catalog title, shelf title, heading font, size, colour/color, and typeface.
7. Add model, persistence, sync, and settings-search tests; record the Apple/community design research.
8. Review every changed file and compare the feature branch with main-build-testing. Do not start builds/tests/CI until the user authorizes them.

No dependency changes are planned: the app already contains the required Compose Multiplatform APIs, native font importer, and storage/sync infrastructure.
