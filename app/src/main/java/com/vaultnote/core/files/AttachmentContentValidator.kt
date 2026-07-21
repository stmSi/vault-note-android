package com.vaultnote.core.files

import android.util.JsonReader
import android.util.JsonToken
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import java.io.BufferedReader
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val CONTENT_SNIFF_BYTES: Int = 64 * 1024

internal data class ContentSample(
    val bytes: ByteArray,
    val isTruncated: Boolean,
)

internal data class ValidatedAttachmentContent(
    val format: AttachmentFormat,
    val validationLevel: AttachmentValidationLevel,
)

internal class AttachmentContentValidator {
    fun validatePreview(
        filename: String,
        claimedMimeType: String?,
        sample: ContentSample,
    ): RepositoryResult<ValidatedAttachmentContent> {
        val expected = formatForFilename(filename)
            ?: return RepositoryResult.Failure(AppError.UnsupportedFile)
        if (!isCompatibleMimeClaim(expected, claimedMimeType)) {
            return RepositoryResult.Failure(AppError.UnsupportedFile)
        }
        if (!hasExpectedPreviewContent(expected, sample)) {
            return RepositoryResult.Failure(AppError.UnsupportedFile)
        }
        val level = if (expected.isZipContainer()) {
            AttachmentValidationLevel.PRELIMINARY
        } else {
            AttachmentValidationLevel.FULL
        }
        return RepositoryResult.Success(ValidatedAttachmentContent(expected, level))
    }

    suspend fun validateStored(
        file: File,
        filename: String,
        claimedMimeType: String?,
    ): RepositoryResult<ValidatedAttachmentContent> {
        val job = currentCoroutineContext()[Job]
        val expected = formatForFilename(filename)
            ?: return RepositoryResult.Failure(AppError.UnsupportedFile)
        if (!isCompatibleMimeClaim(expected, claimedMimeType)) {
            return RepositoryResult.Failure(AppError.UnsupportedFile)
        }

        val sample = try {
            cancellationChecking(file.inputStream().buffered(), job).use(::readContentSample)
        } catch (_: SecurityException) {
            return RepositoryResult.Failure(AppError.PermissionDenied)
        } catch (_: IOException) {
            return RepositoryResult.Failure(AppError.CorruptedFile)
        }
        if (!hasExpectedPreviewContent(expected, sample)) {
            return RepositoryResult.Failure(AppError.UnsupportedFile)
        }

        val valid = when (expected) {
            AttachmentFormat.JPEG -> hasValidJpeg(file, job)
            AttachmentFormat.PNG -> hasExactTailMarker(file, PNG_END)
            AttachmentFormat.GIF -> hasGifTrailer(file)
            AttachmentFormat.WEBP -> hasValidWebpLength(file, sample.bytes)
            AttachmentFormat.HEIF -> file.length() >= 16L
            AttachmentFormat.PDF -> hasPdfEndMarker(file)
            AttachmentFormat.PLAIN_TEXT,
            AttachmentFormat.CSV,
            -> isValidUtf8Text(file, job)
            AttachmentFormat.JSON -> isValidJson(file, job)
            AttachmentFormat.RTF -> hasRtfEndMarker(file)
            AttachmentFormat.DOCX,
            AttachmentFormat.XLSX,
            AttachmentFormat.PPTX,
            AttachmentFormat.ODT,
            AttachmentFormat.ODS,
            AttachmentFormat.ODP,
            -> validateZipContainer(file, expected, job)
        }
        return if (valid) {
            RepositoryResult.Success(
                ValidatedAttachmentContent(expected, AttachmentValidationLevel.FULL),
            )
        } else {
            RepositoryResult.Failure(AppError.CorruptedFile)
        }
    }

    fun readContentSample(input: InputStream): ContentSample {
        val buffer = ByteArray(CONTENT_SNIFF_BYTES + 1)
        var total = 0
        while (total < buffer.size) {
            val readCount = input.read(buffer, total, buffer.size - total)
            val count = if (readCount == 0) {
                val singleByte = input.read()
                if (singleByte < 0) break
                buffer[total] = singleByte.toByte()
                1
            } else {
                readCount
            }
            if (count < 0) break
            total += count
        }
        val truncated = total > CONTENT_SNIFF_BYTES
        val retained = minOf(total, CONTENT_SNIFF_BYTES)
        return ContentSample(buffer.copyOf(retained), truncated)
    }

    private fun formatForFilename(filename: String): AttachmentFormat? {
        val dot = filename.lastIndexOf('.')
        if (dot <= 0 || dot == filename.lastIndex) return null
        val extension = filename.substring(dot + 1).lowercase(Locale.ROOT)
        return AttachmentFormat.entries.firstOrNull { extension in it.extensions }
    }

    private fun isCompatibleMimeClaim(format: AttachmentFormat, rawClaim: String?): Boolean {
        val claim = rawClaim
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        if (claim.isEmpty() || claim in GENERIC_MIME_TYPES) return true
        if (format.category == AttachmentCategory.IMAGE && claim == "image/*") return true
        if (format.category == AttachmentCategory.TEXT && claim == "text/plain") return true
        if (format.isZipContainer() && claim in ZIP_MIME_TYPES) return true
        return claim in format.acceptedMimeTypes
    }

    private fun hasExpectedPreviewContent(
        format: AttachmentFormat,
        sample: ContentSample,
    ): Boolean = when (format) {
        AttachmentFormat.JPEG -> sample.bytes.startsWith(JPEG_START)
        AttachmentFormat.PNG -> sample.bytes.startsWith(PNG_START)
        AttachmentFormat.GIF -> sample.bytes.startsWith(GIF_87_START) || sample.bytes.startsWith(GIF_89_START)
        AttachmentFormat.WEBP ->
            sample.bytes.startsWith(RIFF_START) && sample.bytes.matchesAt(WEBP_MARKER, offset = 8)
        AttachmentFormat.HEIF -> hasHeifBrand(sample.bytes)
        AttachmentFormat.PDF -> sample.bytes.indexOf(PDF_START, maximumStartOffset = 1024) >= 0
        AttachmentFormat.PLAIN_TEXT,
        AttachmentFormat.CSV,
        -> isValidUtf8Sample(sample)
        AttachmentFormat.JSON -> isPlausibleJsonSample(sample)
        AttachmentFormat.RTF -> sample.bytes.withoutUtf8Bom().startsWith(RTF_START)
        AttachmentFormat.DOCX,
        AttachmentFormat.XLSX,
        AttachmentFormat.PPTX,
        AttachmentFormat.ODT,
        AttachmentFormat.ODS,
        AttachmentFormat.ODP,
        -> sample.bytes.startsWith(ZIP_LOCAL_HEADER) || sample.bytes.startsWith(ZIP_EMPTY_HEADER)
    }

    private fun isValidUtf8Sample(sample: ContentSample): Boolean {
        if (sample.bytes.isEmpty()) return true
        var bytes = sample.bytes.withoutUtf8Bom()
        if (sample.isTruncated) bytes = bytes.dropIncompleteUtf8Suffix()
        if (bytes.isEmpty()) return true
        return try {
            val decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            decoded.none { character -> character.isUnsafeTextControl() }
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private fun isPlausibleJsonSample(sample: ContentSample): Boolean {
        if (!isValidUtf8Sample(sample)) return false
        val text = sample.bytes.withoutUtf8Bom().toString(StandardCharsets.UTF_8).trimStart()
        if (text.isEmpty()) return false
        return text.first() in JSON_START_CHARACTERS
    }

    private fun isValidUtf8Text(file: File, job: Job?): Boolean {
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            InputStreamReader(cancellationChecking(file.inputStream().buffered(), job), decoder).use { reader ->
                val characters = CharArray(8 * 1024)
                var firstRead = true
                while (true) {
                    val count = reader.read(characters)
                    if (count < 0) break
                    for (index in 0 until count) {
                        val character = characters[index]
                        if (firstRead && index == 0 && character == '\uFEFF') continue
                        if (character.isUnsafeTextControl()) return false
                    }
                    firstRead = false
                }
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun isValidJson(file: File, job: Job?): Boolean {
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val input = cancellationChecking(file.inputStream().buffered(), job)
            val buffered = BufferedReader(InputStreamReader(input, decoder))
            buffered.use { reader ->
                reader.mark(1)
                if (reader.read() != UTF8_BOM_CODE_POINT) reader.reset()
                JsonReader(reader).use { jsonReader ->
                    jsonReader.isLenient = false
                    if (jsonReader.peek() == JsonToken.END_DOCUMENT) return false
                    jsonReader.skipValue()
                    jsonReader.peek() == JsonToken.END_DOCUMENT
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            false
        } catch (_: IllegalStateException) {
            false
        } catch (_: NumberFormatException) {
            false
        }
    }

    private fun validateZipContainer(file: File, expected: AttachmentFormat, job: Job?): Boolean {
        return try {
            if (!verifyEveryZipEntry(file, job)) return false
            ZipFile(file).use { zip ->
            val names = HashSet<String>()
            var entryCount = 0
            var expandedBytes = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount += 1
                if (entryCount > MAX_CONTAINER_ENTRIES || !isSafeContainerEntry(entry.name)) return false
                if (!names.add(entry.name)) return false
                if (!entry.isDirectory) {
                    if (entry.size < 0L) return false
                    expandedBytes = try {
                        Math.addExact(expandedBytes, entry.size)
                    } catch (_: ArithmeticException) {
                        return false
                    }
                    if (expandedBytes > MAX_CONTAINER_EXPANDED_BYTES) return false
                }
                if (entry.name.endsWith("/vbaProject.bin", ignoreCase = true)) return false
            }

            when (expected) {
                AttachmentFormat.DOCX -> validateOpenXml(
                    zip = zip,
                    names = names,
                    primaryEntry = "word/document.xml",
                    requiredContentType = DOCX_MAIN_CONTENT_TYPE,
                )
                AttachmentFormat.XLSX -> validateOpenXml(
                    zip = zip,
                    names = names,
                    primaryEntry = "xl/workbook.xml",
                    requiredContentType = XLSX_MAIN_CONTENT_TYPE,
                )
                AttachmentFormat.PPTX -> validateOpenXml(
                    zip = zip,
                    names = names,
                    primaryEntry = "ppt/presentation.xml",
                    requiredContentType = PPTX_MAIN_CONTENT_TYPE,
                )
                AttachmentFormat.ODT,
                AttachmentFormat.ODS,
                AttachmentFormat.ODP,
                -> validateOpenDocument(zip, names, expected.canonicalMimeType)
                else -> false
            }
            }
        } catch (_: ZipException) {
            false
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun verifyEveryZipEntry(file: File, job: Job?): Boolean {
        return try {
            val input = cancellationChecking(file.inputStream().buffered(), job)
            ZipInputStream(input).use { zip ->
            val names = HashSet<String>()
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            var entryCount = 0
            var totalExpandedBytes = 0L
            while (true) {
                job?.ensureActive()
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > MAX_CONTAINER_ENTRIES || !isSafeContainerEntry(entry.name)) return false
                if (!names.add(entry.name)) return false
                var entryBytes = 0L
                if (!entry.isDirectory) {
                    while (true) {
                        val readCount = zip.read(buffer)
                        val count = if (readCount == 0) {
                            if (zip.read() < 0) break else 1
                        } else {
                            readCount
                        }
                        if (count < 0) break
                        entryBytes += count
                        totalExpandedBytes += count
                        if (
                            entryBytes > MAX_CONTAINER_EXPANDED_BYTES ||
                            totalExpandedBytes > MAX_CONTAINER_EXPANDED_BYTES
                        ) {
                            return false
                        }
                    }
                }
                zip.closeEntry()
            }
            entryCount > 0
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ZipException) {
            false
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun validateOpenXml(
        zip: ZipFile,
        names: Set<String>,
        primaryEntry: String,
        requiredContentType: String,
    ): Boolean {
        if ("[Content_Types].xml" !in names || primaryEntry !in names || "_rels/.rels" !in names) {
            return false
        }
        val typesEntry = zip.getEntry("[Content_Types].xml") ?: return false
        val types = readBoundedEntry(zip, typesEntry, MAX_XML_VALIDATION_BYTES) ?: return false
        if (!types.isPlausibleXml() || !types.toString(StandardCharsets.UTF_8).contains(requiredContentType)) {
            return false
        }
        val primary = zip.getEntry(primaryEntry) ?: return false
        val primaryPrefix = readBoundedEntry(zip, primary, XML_PREFIX_BYTES) ?: return false
        return primaryPrefix.isPlausibleXml()
    }

    private fun validateOpenDocument(
        zip: ZipFile,
        names: Set<String>,
        requiredMimeType: String,
    ): Boolean {
        if ("mimetype" !in names || "META-INF/manifest.xml" !in names || "content.xml" !in names) {
            return false
        }
        val mimeEntry = zip.getEntry("mimetype") ?: return false
        val mime = readBoundedEntry(zip, mimeEntry, MAX_MIMETYPE_BYTES)
            ?.toString(StandardCharsets.US_ASCII)
            ?.trim()
            ?: return false
        if (mime != requiredMimeType) return false
        val manifest = zip.getEntry("META-INF/manifest.xml") ?: return false
        val manifestPrefix = readBoundedEntry(zip, manifest, XML_PREFIX_BYTES) ?: return false
        return manifestPrefix.isPlausibleXml()
    }

    private fun readBoundedEntry(zip: ZipFile, entry: java.util.zip.ZipEntry, maximumBytes: Int): ByteArray? {
        if (entry.size !in 1..maximumBytes.toLong()) return null
        return zip.getInputStream(entry).buffered().use { input ->
            val bytes = ByteArray(entry.size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val readCount = input.read(bytes, offset, bytes.size - offset)
                val count = if (readCount == 0) {
                    val singleByte = input.read()
                    if (singleByte < 0) return null
                    bytes[offset] = singleByte.toByte()
                    1
                } else {
                    readCount
                }
                if (count < 0) return null
                offset += count
            }
            if (input.read() != -1) return null
            bytes
        }
    }

    private fun isSafeContainerEntry(name: String): Boolean {
        if (
            name.isBlank() ||
            name.startsWith('/') ||
            name.startsWith('\\') ||
            name.indexOf('\u0000') >= 0 ||
            name.indexOf('\\') >= 0
        ) {
            return false
        }
        val segments = name.split('/')
        return segments.none { it == ".." || it == "." }
    }

    private fun hasExactTailMarker(file: File, marker: ByteArray): Boolean =
        readFileTail(file, TAIL_VALIDATION_BYTES)?.endsWith(marker) == true

    private fun hasValidJpeg(file: File, job: Job?): Boolean {
        // Preserve broad compatibility for ordinary JPEG, MPO, and gain-map files.
        if (hasExactTailMarker(file, JPEG_END)) return true
        val jpegLength = parsedJpegLength(file, job) ?: return false
        val fileLength = file.length()
        if (jpegLength == fileLength) return true
        if (hasOnlyBenignJpegPadding(file, jpegLength, fileLength)) return true
        return hasMotionPhotoPayload(file, jpegLength, job)
    }

    /**
     * Parses JPEG markers through the end of the entropy-coded scan instead of searching for an
     * FF-D9 byte pair inside metadata or compressed image data.
     */
    private fun parsedJpegLength(file: File, job: Job?): Long? {
        return try {
            cancellationChecking(file.inputStream().buffered(STREAM_BUFFER_BYTES), job).use { input ->
                parseJpeg(PositionedInput(input))
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun parseJpeg(source: PositionedInput): Long? {
        if (source.readByte() != JPEG_MARKER_PREFIX || source.readByte() != JPEG_SOI_MARKER) {
            return null
        }
        var inEntropyData = false
        while (true) {
            val marker = if (inEntropyData) {
                source.readEntropyMarker()
            } else {
                source.readMarker()
            } ?: return null
            when {
                marker == JPEG_EOI_MARKER -> return source.position
                marker == JPEG_SOI_MARKER || marker == JPEG_STUFFED_BYTE -> return null
                marker == JPEG_TEMPORARY_MARKER || marker in JPEG_RESTART_MARKERS -> Unit
                else -> {
                    val segmentLength = source.readUnsignedShort() ?: return null
                    if (segmentLength < JPEG_SEGMENT_LENGTH_BYTES) return null
                    if (!source.skipFully((segmentLength - JPEG_SEGMENT_LENGTH_BYTES).toLong())) {
                        return null
                    }
                    inEntropyData = marker == JPEG_START_OF_SCAN_MARKER
                }
            }
            if (marker != JPEG_START_OF_SCAN_MARKER && marker !in JPEG_RESTART_MARKERS) {
                inEntropyData = false
            }
        }
    }

    private fun hasOnlyBenignJpegPadding(file: File, start: Long, end: Long): Boolean {
        val length = end - start
        if (length !in 1..MAX_BENIGN_JPEG_PADDING_BYTES.toLong()) return false
        return try {
            RandomAccessFile(file, "r").use { source ->
                source.seek(start)
                repeat(length.toInt()) {
                    if (source.read() !in BENIGN_JPEG_PADDING_BYTES) return false
                }
                true
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun hasMotionPhotoPayload(file: File, jpegEnd: Long, job: Job?): Boolean {
        if (file.length() - jpegEnd < MINIMUM_MOTION_PHOTO_TRAILER_BYTES) return false
        return try {
            RandomAccessFile(file, "r").use { source ->
                source.seek(jpegEnd)
                val ftyp = StreamingMarker(FTYP_MARKER)
                val mdat = StreamingMarker(MDAT_MARKER)
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                while (true) {
                    job?.ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    for (index in 0 until count) {
                        val value = buffer[index]
                        ftyp.accept(value)
                        mdat.accept(value)
                    }
                }
                ftyp.found && mdat.found
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun hasGifTrailer(file: File): Boolean =
        readFileTail(file, TAIL_VALIDATION_BYTES)?.lastOrNull() == GIF_TRAILER

    private fun hasPdfEndMarker(file: File): Boolean {
        val tail = readFileTail(file, PDF_TAIL_VALIDATION_BYTES) ?: return false
        val markerOffset = tail.lastIndexOf(PDF_END)
        if (markerOffset < 0) return false
        return (markerOffset + PDF_END.size until tail.size).all { index ->
            tail[index].toInt().toChar().isWhitespace()
        }
    }

    private fun hasRtfEndMarker(file: File): Boolean {
        val tail = readFileTail(file, TAIL_VALIDATION_BYTES) ?: return false
        for (index in tail.lastIndex downTo 0) {
            val value = tail[index].toInt().toChar()
            if (value.isWhitespace()) continue
            return value == '}'
        }
        return false
    }

    private fun hasValidWebpLength(file: File, header: ByteArray): Boolean {
        if (header.size < 12) return false
        val encodedPayloadSize =
            (header[4].toLong() and 0xffL) or
                ((header[5].toLong() and 0xffL) shl 8) or
                ((header[6].toLong() and 0xffL) shl 16) or
                ((header[7].toLong() and 0xffL) shl 24)
        return encodedPayloadSize + 8L == file.length()
    }

    private fun hasHeifBrand(bytes: ByteArray): Boolean {
        if (bytes.size < 16 || !bytes.matchesAt(FTYP_MARKER, offset = 4)) return false
        val scanEnd = minOf(bytes.size - 4, HEIF_BRAND_SCAN_BYTES)
        var offset = 8
        while (offset <= scanEnd) {
            if (HEIF_BRANDS.any { bytes.matchesAt(it, offset) }) return true
            offset += 4
        }
        return false
    }

    private fun readFileTail(file: File, maximumBytes: Int): ByteArray? {
        return try {
            java.io.RandomAccessFile(file, "r").use { randomAccess ->
                val length = randomAccess.length()
                val count = minOf(length, maximumBytes.toLong()).toInt()
                if (count == 0) return ByteArray(0)
                randomAccess.seek(length - count)
                ByteArray(count).also(randomAccess::readFully)
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun AttachmentFormat.isZipContainer(): Boolean = category == AttachmentCategory.DOCUMENT

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = matchesAt(prefix, 0)

    private fun ByteArray.endsWith(suffix: ByteArray): Boolean = matchesAt(suffix, size - suffix.size)

    private fun ByteArray.matchesAt(expected: ByteArray, offset: Int): Boolean {
        if (offset < 0 || offset + expected.size > size) return false
        return expected.indices.all { index -> this[offset + index] == expected[index] }
    }

    private fun ByteArray.indexOf(needle: ByteArray, maximumStartOffset: Int = Int.MAX_VALUE): Int {
        if (needle.isEmpty()) return 0
        val lastStart = minOf(size - needle.size, maximumStartOffset)
        for (offset in 0..lastStart) if (matchesAt(needle, offset)) return offset
        return -1
    }

    private fun ByteArray.lastIndexOf(needle: ByteArray): Int {
        if (needle.isEmpty()) return size
        for (offset in size - needle.size downTo 0) if (matchesAt(needle, offset)) return offset
        return -1
    }

    private fun ByteArray.withoutUtf8Bom(): ByteArray =
        if (startsWith(UTF8_BOM)) copyOfRange(UTF8_BOM.size, size) else this

    private fun ByteArray.dropIncompleteUtf8Suffix(): ByteArray {
        if (isEmpty()) return this
        var continuationBytes = 0
        var index = lastIndex
        while (index >= 0 && (this[index].toInt() and 0xC0) == 0x80 && continuationBytes < 3) {
            continuationBytes += 1
            index -= 1
        }
        if (index < 0) return ByteArray(0)
        val lead = this[index].toInt() and 0xff
        val expectedLength = when {
            lead and 0x80 == 0 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        val presentLength = continuationBytes + 1
        return if (expectedLength > presentLength) copyOf(index) else this
    }

    private fun Char.isUnsafeTextControl(): Boolean =
        (code in 0x00..0x08) || (code in 0x0B..0x0C) || (code in 0x0E..0x1F) || code == 0x7F

    private fun ByteArray.isPlausibleXml(): Boolean {
        val prefix = withoutUtf8Bom().toString(StandardCharsets.UTF_8).trimStart()
        return prefix.startsWith("<?xml") || prefix.startsWith('<')
    }

    private fun cancellationChecking(input: InputStream, job: Job?): InputStream =
        object : FilterInputStream(input) {
            override fun read(): Int {
                job?.ensureActive()
                return super.read()
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                job?.ensureActive()
                return super.read(buffer, offset, length)
            }

            override fun skip(byteCount: Long): Long {
                job?.ensureActive()
                return super.skip(byteCount)
            }
        }

    private class PositionedInput(private val input: InputStream) {
        var position: Long = 0L
            private set

        fun readByte(): Int {
            val value = input.read()
            if (value >= 0) position += 1L
            return value
        }

        fun readUnsignedShort(): Int? {
            val high = readByte()
            val low = readByte()
            if (high < 0 || low < 0) return null
            return (high shl 8) or low
        }

        fun readMarker(): Int? {
            if (readByte() != JPEG_MARKER_PREFIX) return null
            var marker = readByte()
            while (marker == JPEG_MARKER_PREFIX) marker = readByte()
            return marker.takeIf { it >= 0 }
        }

        fun readEntropyMarker(): Int? {
            while (true) {
                val value = readByte()
                if (value < 0) return null
                if (value != JPEG_MARKER_PREFIX) continue
                var marker = readByte()
                while (marker == JPEG_MARKER_PREFIX) marker = readByte()
                if (marker < 0) return null
                if (marker != JPEG_STUFFED_BYTE && marker !in JPEG_RESTART_MARKERS) return marker
            }
        }

        fun skipFully(byteCount: Long): Boolean {
            var remaining = byteCount
            while (remaining > 0L) {
                val skipped = input.skip(remaining)
                if (skipped > 0L) {
                    position += skipped
                    remaining -= skipped
                } else if (readByte() < 0) {
                    return false
                } else {
                    remaining -= 1L
                }
            }
            return true
        }
    }

    private class StreamingMarker(private val marker: ByteArray) {
        private var matchedBytes = 0
        var found: Boolean = false
            private set

        fun accept(value: Byte) {
            if (found) return
            matchedBytes = when {
                value == marker[matchedBytes] -> matchedBytes + 1
                value == marker[0] -> 1
                else -> 0
            }
            if (matchedBytes == marker.size) found = true
        }
    }

    companion object {
        private const val MAX_CONTAINER_ENTRIES = 4_096
        private const val MAX_CONTAINER_EXPANDED_BYTES = 512L * 1024L * 1024L
        private const val MAX_XML_VALIDATION_BYTES = 512 * 1024
        private const val XML_PREFIX_BYTES = 4 * 1024
        private const val MAX_MIMETYPE_BYTES = 256
        private const val TAIL_VALIDATION_BYTES = 64 * 1024
        private const val PDF_TAIL_VALIDATION_BYTES = 4 * 1024
        private const val HEIF_BRAND_SCAN_BYTES = 128
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private const val JPEG_MARKER_PREFIX = 0xFF
        private const val JPEG_STUFFED_BYTE = 0x00
        private const val JPEG_TEMPORARY_MARKER = 0x01
        private const val JPEG_SOI_MARKER = 0xD8
        private const val JPEG_EOI_MARKER = 0xD9
        private const val JPEG_START_OF_SCAN_MARKER = 0xDA
        private const val JPEG_SEGMENT_LENGTH_BYTES = 2
        private val JPEG_RESTART_MARKERS = 0xD0..0xD7
        private const val MAX_BENIGN_JPEG_PADDING_BYTES = 4 * 1024
        private val BENIGN_JPEG_PADDING_BYTES = setOf(0x00, 0x09, 0x0A, 0x0D, 0x20)
        private const val MINIMUM_MOTION_PHOTO_TRAILER_BYTES = 16L
        private const val UTF8_BOM_CODE_POINT = 0xFEFF
        private const val DOCX_MAIN_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
        private const val XLSX_MAIN_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
        private const val PPTX_MAIN_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
        private const val GIF_TRAILER: Byte = 0x3B
        private val JSON_START_CHARACTERS = setOf('{', '[', '"', '-', 't', 'f', 'n') + ('0'..'9')
        private val GENERIC_MIME_TYPES = setOf("application/octet-stream", "binary/octet-stream")
        private val ZIP_MIME_TYPES = setOf("application/zip", "application/x-zip-compressed")
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        private val JPEG_START = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val JPEG_END = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        private val PNG_START = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        private val PNG_END = byteArrayOf(0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())
        private val GIF_87_START = "GIF87a".toByteArray(StandardCharsets.US_ASCII)
        private val GIF_89_START = "GIF89a".toByteArray(StandardCharsets.US_ASCII)
        private val RIFF_START = "RIFF".toByteArray(StandardCharsets.US_ASCII)
        private val WEBP_MARKER = "WEBP".toByteArray(StandardCharsets.US_ASCII)
        private val PDF_START = "%PDF-".toByteArray(StandardCharsets.US_ASCII)
        private val PDF_END = "%%EOF".toByteArray(StandardCharsets.US_ASCII)
        private val RTF_START = "{\\rtf".toByteArray(StandardCharsets.US_ASCII)
        private val ZIP_LOCAL_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private val ZIP_EMPTY_HEADER = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
        private val FTYP_MARKER = "ftyp".toByteArray(StandardCharsets.US_ASCII)
        private val MDAT_MARKER = "mdat".toByteArray(StandardCharsets.US_ASCII)
        private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1")
            .map { it.toByteArray(StandardCharsets.US_ASCII) }
    }
}
