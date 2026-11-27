# Implementation plan: Home catalog poster shuffle

1. **Pure ordering contract and tests**
   - Add a common test first for disabled passthrough, empty/single-item lists, deterministic same-seed order, changed seed, per-catalog separation, stable order independent of input order, item membership, and relative order stability when a preview grows.
   - Implement a deterministic 64-bit item-rank helper and generic stable poster ordering in `HomeCatalogPosterShuffle.kt`.
   - Keep one lazy in-memory process seed; never persist it.

2. **Persist and sync the off-by-default setting**
   - Add the boolean to `HomeCatalogSettingsUiState`, `HomeCatalogSettingsSnapshot`, `StoredHomeCatalogSettingsPayload`, repository profile/reset/clear paths, load/publish/persist paths, and the setter.
   - Extend `SyncHomeCatalogPayload` with nullable `shuffle_catalog_posters` for old-client compatibility.
   - Export explicit true/false values; on remote import, preserve the local value when the field is absent/null and apply an explicit false as an intentional off state.
   - Add common sync serialization tests and Android-host persistence/sync tests.

3. **Render without changing catalog order or artwork identity**
   - Add the toggle parameter to `HomeCatalogRowSection` and pass it from `HomeScreen`'s existing collected Home settings state.
   - Memoize the capped homepage preview by its source section-items list, then remember the stable shuffled preview by item list, catalog key, setting state and process seed.
   - Leave the outer `HomeScreen` row order, section definitions, full section passed to View All, collection rows, and image request builders untouched.
   - Keep `NuvioShelfSection` stable poster keys, and align Coil prefetch targets with the displayed preview order.

4. **Settings UX and search**
   - Add the switch to the existing Catalogs settings section, before the catalog list. Only show/search it when add-on/server catalog shelves exist; collection-only profiles should not see an inactive option.
   - Use the wording **Shuffle catalog posters** with a short explanation that shelves retain their order and posters change when Nuvio restarts.
   - Add a dedicated Home settings search anchor and aliases for poster order, shuffle, random posters, and mix up posters.
   - Add anchor and search-result tests.

5. **Documentation and review**
   - Record the chosen process-seed approach, rejected hourly/recomposition alternatives, existing preview/pagination/cache behavior, Apple guidance, Compose keys, and prior-art review.
   - Review local preference migration, remote missing-field handling, reset behavior, progressive catalog updates, item identity, and all Home settings call sites.
   - Compare the final feature branch against `main-build-testing` and integrate without running builds/tests/CI.
