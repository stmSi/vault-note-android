# Performance decisions

Phase 4 preserves the cold-start sequence: construct a lightweight lazy container, inflate lock/local UI, start bounded Room flows, draw, then run optional attachment migration and OCR. ML Kit's automatic provider is removed and explicitly initialized on the first OCR request; ML Kit, Coil, hashing, thumbnails, PDFs, and OCR temporary storage are not initialized from `Application.onCreate()`.

Search waits for 300 ms of input inactivity, cancels superseded queries, uses a bounded FTS expression, and projects at most 100 result rows. Results never include complete attachments or full note bodies. The result list uses stable IDs, `ListAdapter`, and `DiffUtil`.

OCR is serial at the activity level in batches of two. Images are sampled to a maximum 2048-pixel edge and approximately four million pixels. PDFs are limited to 50 pages, rendered sequentially, and never loaded into a byte array. OCR output is capped at 200,000 characters. Completed attachments retain their source SHA-256 so unchanged files are not processed again.

These are architectural bounds, not benchmark claims. Release-like startup, list, search, and note-opening measurements remain Phase 7 work in the planned `:baselineprofile` and `:benchmark` modules.
