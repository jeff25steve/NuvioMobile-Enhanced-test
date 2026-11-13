# Liquid Glass Editor — Settings and Cloud Background Images

## Intent
Let users choose, replace, and remove the Settings-page wallpaper directly from the Liquid Glass Editor, alongside the existing Cloud Library background controls. Users should not need to leave the editor to configure either previewed backdrop.

## Goals
- Expose background controls when editing the Settings surface.
- Preserve the existing Cloud Library controls and behavior.
- Keep Settings and Cloud images independent, profile-local, and device-local.
- Reuse the existing Android Photo Picker / iOS PHPicker, image normalization, storage limits, local-file URI validation, and preview invalidation.
- Keep the editor preview and applied screen using the same persisted image.

## Non-goals
- No remote image URLs, uploads, account sync, new permissions, or new dependencies.
- No changes to other Liquid Glass surfaces or the navigation-bar glass subsystem.
- No replacement of the existing Appearance-page Settings wallpaper entry point.

## Architecture
The editor resolves a selected surface to one of two explicit background targets: Settings or Cloud Library. A small shared Compose control binds the appropriate existing StateFlow and calls the matching profile-scoped save/clear repository methods. The native picker remains the existing expect/actual implementation; no new platform API is introduced.

## Approaches
1. Duplicate the Cloud picker block for Settings — rejected because the two paths would drift in save, cancellation, and profile-switch behavior.
2. Add a shared target-aware editor control and reuse the existing repositories/picker — selected because it minimizes new code and preserves the proven platform pipeline.
3. Replace the picker/storage architecture — rejected because it adds risk and dependencies without solving a gap in the existing implementation.

## Constraints and acceptance
- SETTINGS maps only to the Settings image key.
- LIBRARY_CLOUD_FILTERS, LIBRARY_CLOUD_SEARCH, and LIBRARY_CLOUD_CARDS map to the Cloud image key.
- Other surfaces expose no background picker.
- A successful save/clear uses the existing revision StateFlows so the preview and live screen refresh.
- Cancellation is rethrown; ordinary persistence failures are shown in the editor.
- No build or CI is run on main-build-testing; Linux validation runs only on a dedicated ci/... branch.
