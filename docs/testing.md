# Testing

Phase 4 keeps tests focused on important behavior and security invariants. JVM/Robolectric coverage includes repository transactions, autosave, soft deletion, attachment validation and cleanup, encryption authentication/corruption, lock policy, one-use grants, FTS query compilation, public filename search, OCR state persistence, OCR-to-FTS updates, unchanged-file avoidance, and explicit retry rules. Room migration tests remain under `androidTest` for a connected API 26-or-newer device.

Use Java 17. Robolectric in this project is not compatible with the host's OpenJDK 26 bytecode instrumentation.

```bash
JAVA_HOME="$HOME/.jdks/temurin-17.0.19+10" \
PATH="$HOME/.jdks/temurin-17.0.19+10/bin:$PATH" \
./gradlew testDebugUnitTest lintDebug assembleRelease
```

ML Kit recognition quality is device/library behavior and is not faked as an assertion about specific photographed text. Repository tests inject a deterministic `OcrProcessor` and plaintext lease so persistence, retry, checksum, and indexing behavior are verified without production credentials or downloading a model.
