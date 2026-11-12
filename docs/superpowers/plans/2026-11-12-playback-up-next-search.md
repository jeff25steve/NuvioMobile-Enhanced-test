# Playback Up Next Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox syntax.

**Goal:** Make six Up Next controls searchable and directly reachable.

**Architecture:** Extend strict anchor registry, mirror UI visibility in the catalogue, and use existing row reveal/highlight support.

**Tech Stack:** Kotlin 2.4.10, Compose Multiplatform 1.13.0-beta01, Compose Foundation.

**Spec:** docs/superpowers/specs/2026-11-12-playback-up-next-search-design.md

## Global Constraints
- Do not change playback behavior, values, persistence or callbacks.
- No new dependencies.
- No builds/tests/compilation/device sessions/CI without authorization.
- Commit with [skip ci]; never force-push.

## Review Focus
- Fallback requires auto-play enabled and MANUAL mode.
- Reuse binge requires Prefer Binge Group.
- Six catalogue keys match actual controls.
- Phone/tablet highlight forwarding remains intact.
- Threshold slider remains page-only.

### Task 1: Regression source
- [x] Add six exact-anchor assertions and header-offset/unknown-key coverage.
- [ ] Tests not executed by request.

### Task 2: Catalogue and row anchors
- [x] Add strict allow-list and localized entries.
- [x] Mirror conditional visibility.
- [x] Attach keys to five switches and threshold-mode row.
- [x] Forward highlight through PlaybackUpNextSettings.

### Task 3: Notes and integration
- [x] Update notes, design and plan.
- [ ] Runtime verification awaits authorization.
- [x] Source reviewed; [skip ci] commit.
