# Performance decisions

VaultNote preserves the cold-start sequence: construct a lightweight lazy container, inflate lock/local UI, start bounded Room flows, draw, then run optional attachment migration, OCR, and sync scheduling. Backup crypto, staging, ZIP processing, ML Kit, WorkManager, Coil, hashing, thumbnails, PDFs, and OCR temporary storage are not initialized from `Application.onCreate()`.

Search waits for 120 ms of input inactivity beginning with the first character, cancels superseded queries, uses a bounded prefix FTS expression, and projects at most 100 result rows. Results never include complete attachments or full note bodies. The result list uses stable IDs, `ListAdapter`, and `DiffUtil`.

OCR is serial at the activity level in batches of two. Images are sampled to a maximum 2048-pixel edge and approximately four million pixels. PDFs are limited to 50 pages, rendered sequentially, and never loaded into a byte array. OCR output is capped at 200,000 characters. Completed attachments retain their source SHA-256 so unchanged files are not processed again.

Attachment save/export uses 64 KiB buffered streaming and never loads a complete file in memory. External viewers require a seekable descriptor, so the provider verifies and decrypts to private cache with at least 16 MiB of free-space reserve, opens the file read-only, and immediately unlinks its name. This disk pass occurs only after an explicit open/share action and never during startup or list scrolling.

Manual backup/restore is user initiated, serialized with a process-local mutex, and runs on the I/O dispatcher. Room and attachment records are keyset-paged in batches of 100; large attachments pass through 64 KiB buffers. Export first writes a complete private archive, while restore uses a private SQLite staging database and at most one plaintext attachment temporary at a time. PBKDF2 intentionally consumes CPU and runs off-main. No backup code runs during startup or scrolling.

Offline ML Kit OCR contributes native libraries for four supported ABIs and is the dominant universal-APK size cost. Gradle therefore emits ABI-specific APKs for ARM64, 32-bit ARM, x86 and x86-64 in addition to the universal APK. Manual installation should use the matching ABI output; the universal output remains available for unknown targets, and x86 variants remain available for emulator/benchmark work.

These are architectural bounds, not benchmark claims. Release-like startup, list, search, and note-opening measurements remain Phase 7 work in the planned `:baselineprofile` and `:benchmark` modules.
