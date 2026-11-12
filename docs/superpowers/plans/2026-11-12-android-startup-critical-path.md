# Plan — Android startup critical-path split

1. Split StartupPreloader into Home-critical and post-first-frame phases using a single-flight coordinator.
2. Make Android MainActivity await only critical work; schedule warmup from a Compose effect after the first frame.
3. Remove duplicate Android first-composition feature ensures from MainAppContent, preserve iOS eager initialization, and retain Live TV tab behaviour while preferences restore.
4. Expose download-manifest readiness and gate initial offline routing until it is restored.
5. Add phase coordinator and offline routing regression tests.
6. Update checkpoints, run focused validation and attempt Android Full debug build if repository access allows. Do not dispatch CI or run Macrobenchmark.
