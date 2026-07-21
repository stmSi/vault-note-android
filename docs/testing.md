# Testing

Tests stay focused on important behavior and security invariants. JVM/Robolectric coverage includes repository transactions, autosave, soft deletion, attachment validation and cleanup, encryption authentication/corruption, lock policy, bounded attachment grants, correct external metadata and repeatable seekable reads, SAF export cleanup, Sharesheet URI grants, per-character FTS prefix compilation, deterministic highlighting across title/body/tag/filename/OCR sources, OCR persistence/indexing, sync idempotency, revision acknowledgements, authentication failure, bounded exponential backoff, preserve-both conflict resolution, strict backup manifest versions, password/path-bound backup authentication with zero plaintext on failure, path-traversal rejection, and staged restore with collision copies. Room migration tests remain under `androidTest` for a connected API 26-or-newer device.

Use Java 17. Robolectric in this project is not compatible with the host's OpenJDK 26 bytecode instrumentation.

```bash
JAVA_HOME="$HOME/.jdks/temurin-17.0.19+10" \
PATH="$HOME/.jdks/temurin-17.0.19+10/bin:$PATH" \
./gradlew testDebugUnitTest lintDebug assembleRelease
```

ML Kit recognition quality is device/library behavior and is not faked as an assertion about specific photographed text. Repository tests inject a deterministic `OcrProcessor` and plaintext lease so persistence, retry, checksum, and indexing behavior are verified without production credentials or downloading a model.
