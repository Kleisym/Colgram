package org.colgram.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unified Attachment Processor for Koliti:
 * - Images (JPG/PNG/WEBP/HEIC/HEIF/AVIF/GIF/BMP): API 28+ ImageDecoder + 8-state EXIF matrix
 *   fallback on API 24-27, solid white-canvas alpha compositing (prevents pitch-black transparent PNGs),
 *   and crisp 560px downscaling for fast mobile ViT inference.
 * - Videos (MP4/MOV/MKV/WEBM/3GP/AVI): 2x2 timestamped storyboard grid.
 * - PDFs: Built-in PdfRenderer renders pages 1-2 onto white canvas (max 768px edge) for Vision OCR.
 * - Text / Code / Configs & DOCX / ODT: Bounded UTF-8 reader and ZipInputStream XML extractor (4500-char cap).
 */
public final class ColgramAiVisionProcessor {

    public enum AttachmentKind {
        IMAGE,
        VIDEO,
        PDF,
        TEXT_DOC,
        UNSUPPORTED
    }

    public static final class PreparedMedia {
        public final File file;
        public final boolean isVideoGrid;
        public final String summary;

        public PreparedMedia(File file, boolean isVideoGrid, String summary) {
            this.file = file;
            this.isVideoGrid = isVideoGrid;
            this.summary = summary;
        }
    }

    public static final class PreparedAttachment {
        public final AttachmentKind kind;
        public final String displayName;
        public final List<File> imageFiles;
        public final String extractedText;
        public final boolean isVideoGrid;
        public final String summary;
        public final String errorMessage;

        public PreparedAttachment(
                AttachmentKind kind,
                String displayName,
                List<File> imageFiles,
                String extractedText,
                boolean isVideoGrid,
                String summary,
                String errorMessage
        ) {
            this.kind = kind == null ? AttachmentKind.UNSUPPORTED : kind;
            this.displayName = displayName == null ? "" : displayName;
            this.imageFiles = imageFiles == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(imageFiles));
            this.extractedText = extractedText == null ? "" : extractedText;
            this.isVideoGrid = isVideoGrid;
            this.summary = summary == null ? "" : summary;
            this.errorMessage = errorMessage == null ? "" : errorMessage;
        }

        public boolean hasImages() {
            return !imageFiles.isEmpty();
        }

        public boolean hasText() {
            return !extractedText.isEmpty();
        }
    }

    public static final int MAX_DOC_TEXT_CHARS = 4500;
    private static final int MAX_RAW_TEXT_BYTES = 96 * 1024;
    private static final int MAX_XML_ENTRY_BYTES = 512 * 1024;
    private static final int MAX_PDF_PAGES = 2;
    private static final int IMAGE_MAX_EDGE = 560;
    private static final int PDF_MAX_EDGE = 768;

    private static final AtomicInteger IMG_SEQ = new AtomicInteger(1);

    private ColgramAiVisionProcessor() {}

    public static PreparedMedia prepareMediaFile(Context ctx, String sourcePath) {
        PreparedAttachment att = prepareAttachment(ctx, sourcePath);
        if (att == null || att.imageFiles.isEmpty()) return null;
        return new PreparedMedia(att.imageFiles.get(0), att.isVideoGrid, att.summary);
    }

    public static PreparedAttachment prepareAttachment(Context ctx, String sourcePath) {
        if (sourcePath == null || sourcePath.isEmpty()) {
            return null;
        }
        File src = new File(sourcePath);
        if (!src.isFile() || src.length() == 0L) {
            return new PreparedAttachment(AttachmentKind.UNSUPPORTED, src.getName(), null, "", false,
                    "", "Файл не найден или пуст");
        }
        String name = src.getName();
        AttachmentKind kind = classifyKind(name);
        if (kind == AttachmentKind.TEXT_DOC) {
            return prepareDocumentAttachment(src);
        }
        if (kind == AttachmentKind.UNSUPPORTED) {
            if (!isKnownBinaryExtension(name) && src.length() <= 512L * 1024L) {
                PreparedAttachment probed = tryPrepareUnknownAsText(src);
                if (probed != null) {
                    return probed;
                }
            }
            return new PreparedAttachment(AttachmentKind.UNSUPPORTED, name, null, "", false,
                    "", "Формат файла «" + name + "» не поддерживается для анализа");
        }

        if (ctx == null) {
            return new PreparedAttachment(AttachmentKind.UNSUPPORTED, name, null, "", false,
                    "", "Контекст недоступен для декодирования медиа");
        }

        File cacheDir = new File(ctx.getCacheDir(), "colgram_ai_media");
        if (!cacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
        }
        cleanupOldFiles(cacheDir);

        if (kind == AttachmentKind.VIDEO) {
            PreparedMedia vid = buildVideoStoryboard(cacheDir, src);
            if (vid == null || vid.file == null) {
                return new PreparedAttachment(AttachmentKind.UNSUPPORTED, name, null, "", false,
                        "", "Не удалось извлечь кадры из видео «" + name + "»");
            }
            return new PreparedAttachment(AttachmentKind.VIDEO, name,
                    Collections.singletonList(vid.file), "", true, vid.summary, "");
        }
        if (kind == AttachmentKind.PDF) {
            return renderPdfPages(cacheDir, src);
        }
        PreparedMedia img = normalizeImage(cacheDir, src);
        if (img == null || img.file == null) {
            return new PreparedAttachment(AttachmentKind.UNSUPPORTED, name, null, "", false,
                    "", "Не удалось декодировать изображение «" + name + "»");
        }
        return new PreparedAttachment(AttachmentKind.IMAGE, name,
                Collections.singletonList(img.file), "", false, img.summary, "");
    }

    public static AttachmentKind classifyKind(String fileNameOrPath) {
        if (fileNameOrPath == null || fileNameOrPath.trim().isEmpty()) {
            return AttachmentKind.UNSUPPORTED;
        }
        String base = new File(fileNameOrPath.trim()).getName().toLowerCase(Locale.ROOT);
        if (isVideoPath(base)) return AttachmentKind.VIDEO;
        if (isPdfPath(base)) return AttachmentKind.PDF;
        if (isImagePath(base)) return AttachmentKind.IMAGE;
        if (isTextOrOfficeDocPath(base)) return AttachmentKind.TEXT_DOC;
        return AttachmentKind.UNSUPPORTED;
    }

    public static boolean isImagePath(String path) {
        if (path == null) return false;
        String l = path.toLowerCase(Locale.ROOT);
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png")
                || l.endsWith(".webp") || l.endsWith(".gif") || l.endsWith(".bmp")
                || l.endsWith(".heic") || l.endsWith(".heif") || l.endsWith(".avif");
    }

    public static boolean isVideoPath(String path) {
        if (path == null) return false;
        String l = path.toLowerCase(Locale.ROOT);
        return l.endsWith(".mp4") || l.endsWith(".mov") || l.endsWith(".mkv")
                || l.endsWith(".webm") || l.endsWith(".3gp") || l.endsWith(".avi")
                || l.endsWith(".m4v");
    }

    public static boolean isPdfPath(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    public static boolean isOfficeXmlDocPath(String path) {
        if (path == null) return false;
        String l = path.toLowerCase(Locale.ROOT);
        return l.endsWith(".docx") || l.endsWith(".odt");
    }

    public static boolean isTextOrOfficeDocPath(String path) {
        if (path == null) return false;
        String base = new File(path).getName().toLowerCase(Locale.ROOT);
        if (isOfficeXmlDocPath(base)) return true;
        if (base.equals("readme") || base.equals("license") || base.equals("makefile")
                || base.equals("dockerfile") || base.equals("procfile")
                || base.equals(".gitignore") || base.equals(".gitattributes")
                || base.equals(".editorconfig")) {
            return true;
        }
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) return false;
        String ext = base.substring(dot + 1);
        switch (ext) {
            case "txt": case "md": case "markdown": case "log": case "csv": case "tsv":
            case "json": case "jsonl": case "xml": case "yaml": case "yml": case "toml":
            case "ini": case "cfg": case "conf": case "properties": case "env":
            case "sql": case "graphql": case "svg": case "tex": case "srt": case "vtt":
            case "patch": case "diff": case "proto": case "tf": case "hcl":
            case "py": case "java": case "kt": case "kts": case "js": case "jsx":
            case "ts": case "tsx": case "mjs": case "cjs": case "c": case "cc":
            case "cpp": case "cxx": case "h": case "hpp": case "cs": case "go":
            case "rs": case "rb": case "php": case "sh": case "bash": case "zsh":
            case "ps1": case "bat": case "cmd": case "gradle": case "dart":
            case "swift": case "lua": case "r": case "html": case "htm":
            case "css": case "scss": case "less": case "vue": case "svelte":
            case "asm": case "s": case "rtf":
                return true;
            default:
                return false;
        }
    }

    private static boolean isKnownBinaryExtension(String name) {
        String l = name.toLowerCase(Locale.ROOT);
        int dot = l.lastIndexOf('.');
        if (dot < 0 || dot == l.length() - 1) return false;
        String ext = l.substring(dot + 1);
        switch (ext) {
            case "exe": case "dll": case "so": case "apk": case "aab": case "dex":
            case "zip": case "rar": case "7z": case "tar": case "gz": case "xz": case "bz2":
            case "gguf": case "onnx": case "bin": case "dat": case "db": case "sqlite":
            case "mp3": case "ogg": case "oga": case "opus": case "wav": case "m4a":
            case "aac": case "flac": case "wma":
            case "doc": case "xls": case "xlsx": case "ppt": case "pptx":
            case "class": case "jar": case "aar": case "o": case "a": case "wasm":
                return true;
            default:
                return false;
        }
    }

    public static String getExtensionUpper(String nameOrPath) {
        if (nameOrPath == null || nameOrPath.trim().isEmpty()) return "FILE";
        String base = new File(nameOrPath.trim()).getName();
        int dot = base.lastIndexOf('.');
        if (dot <= 0 || dot >= base.length() - 1) return "FILE";
        String ext = base.substring(dot + 1).toUpperCase(Locale.ROOT);
        return ext.length() > 5 ? ext.substring(0, 5) : ext;
    }

    public static String formatFileSize(long bytes) {
        if (bytes <= 0L) return "0 Б";
        if (bytes < 1024L) return bytes + " Б";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.US, kb < 10.0 ? "%.1f КБ" : "%.0f КБ", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.US, "%.1f МБ", mb);
        return String.format(Locale.US, "%.2f ГБ", mb / 1024.0);
    }

    /**
     * Pure-Java document extraction entrypoint (usable directly on host JVM without Android framework).
     */
    public static PreparedAttachment prepareDocumentAttachment(File src) {
        if (src == null || !src.isFile() || src.length() == 0L) {
            return new PreparedAttachment(AttachmentKind.UNSUPPORTED,
                    src != null ? src.getName() : "", null, "", false, "", "Файл не найден или пуст");
        }
        String name = src.getName();
        if (isOfficeXmlDocPath(name)) {
            String docText = extractDocxOrOdtText(src, MAX_DOC_TEXT_CHARS);
            if (docText == null || docText.isEmpty()) {
                return new PreparedAttachment(AttachmentKind.UNSUPPORTED, name, null, "", false,
                        "", "Не удалось извлечь текст из документа «" + name + "»");
            }
            int lines = countLines(docText);
            String block = formatDocBlock(name, lines, src.length(), docText);
            return new PreparedAttachment(AttachmentKind.TEXT_DOC, name, null, block, false,
                    "Документ " + name + " (" + lines + " строк)", "");
        }
        String text = extractTextFromFile(src, MAX_DOC_TEXT_CHARS);
        if (text == null) {
            return new PreparedAttachment(AttachmentKind.UNSUPPORTED, name, null, "", false,
                    "", "Файл «" + name + "» содержит бинарные данные и не читается как текст");
        }
        int lines = countLines(text);
        String block = formatDocBlock(name, lines, src.length(), text);
        return new PreparedAttachment(AttachmentKind.TEXT_DOC, name, null, block, false,
                "Файл " + name + " (" + lines + " строк)", "");
    }

    private static PreparedAttachment tryPrepareUnknownAsText(File src) {
        String text = extractTextFromFile(src, MAX_DOC_TEXT_CHARS);
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String name = src.getName();
        int lines = countLines(text);
        String block = formatDocBlock(name, lines, src.length(), text);
        return new PreparedAttachment(AttachmentKind.TEXT_DOC, name, null, block, false,
                "Файл " + name + " (" + lines + " строк)", "");
    }

    private static String formatDocBlock(String name, int lines, long bytes, String body) {
        return "[Файл: " + name + " | " + formatFileSize(bytes) + " | " + lines + " строк]\n" + body;
    }

    private static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int lines = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    /**
     * Reads up to {@code MAX_RAW_TEXT_BYTES} UTF-8 bytes, rejects binary NUL/control payloads,
     * normalizes CRLF, and caps cleanly at {@code maxChars}.
     */
    public static String extractTextFromFile(File src, int maxChars) {
        if (src == null || !src.isFile()) return null;
        int capChars = maxChars > 0 ? maxChars : MAX_DOC_TEXT_CHARS;
        byte[] raw;
        boolean hitByteCap = false;
        try (InputStream is = new BufferedInputStream(new FileInputStream(src))) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = is.read(buf)) != -1) {
                int take = Math.min(n, MAX_RAW_TEXT_BYTES - total);
                if (take > 0) {
                    baos.write(buf, 0, take);
                    total += take;
                }
                if (total >= MAX_RAW_TEXT_BYTES) {
                    hitByteCap = (is.read() != -1);
                    break;
                }
            }
            raw = baos.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
        if (raw.length == 0) return "";

        int off = 0;
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            off = 3;
        }
        int probeLen = Math.min(raw.length, 4096);
        int ctrlCount = 0;
        for (int i = off; i < probeLen; i++) {
            int b = raw[i] & 0xFF;
            if (b == 0) {
                return null;
            }
            if (b < 0x20 && b != '\n' && b != '\r' && b != '\t' && b != '\f') {
                ctrlCount++;
            }
        }
        if (probeLen > off && (ctrlCount * 20) > (probeLen - off)) {
            return null;
        }

        String decoded = new String(raw, off, raw.length - off, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        boolean truncated = hitByteCap || decoded.length() > capChars;
        if (decoded.length() > capChars) {
            decoded = decoded.substring(0, capChars);
        }
        if (truncated) {
            decoded = decoded.trim() + "\n… [обрезано до " + capChars + " символов]";
        }
        return decoded;
    }

    /**
     * Extracts plain text from `.docx` (`word/document.xml`) or `.odt` (`content.xml`) via ZipInputStream.
     */
    public static String extractDocxOrOdtText(File src, int maxChars) {
        if (src == null || !src.isFile()) return null;
        int capChars = maxChars > 0 ? maxChars : MAX_DOC_TEXT_CHARS;
        boolean wantDocx = src.getName().toLowerCase(Locale.ROOT).endsWith(".docx");
        String targetEntry = wantDocx ? "word/document.xml" : "content.xml";
        String xml = null;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(src)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (targetEntry.equalsIgnoreCase(entry.getName())) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int total = 0;
                    int n;
                    while ((n = zis.read(buf)) != -1) {
                        int take = Math.min(n, MAX_XML_ENTRY_BYTES - total);
                        if (take > 0) {
                            baos.write(buf, 0, take);
                            total += take;
                        }
                        if (total >= MAX_XML_ENTRY_BYTES) break;
                    }
                    xml = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                    break;
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        if (xml == null || xml.isEmpty()) return null;
        String plain = extractPlainTextFromOfficeXml(xml, capChars);
        return plain.isEmpty() ? null : plain;
    }

    static String extractPlainTextFromOfficeXml(String xml, int maxChars) {
        StringBuilder out = new StringBuilder(Math.min(4096, xml.length() / 4));
        int len = xml.length();
        boolean inTag = false;
        boolean skipElementText = false;
        StringBuilder tagBuf = new StringBuilder(64);

        for (int i = 0; i < len; i++) {
            char c = xml.charAt(i);
            if (inTag) {
                if (c == '>') {
                    inTag = false;
                    String tag = tagBuf.toString().trim();
                    tagBuf.setLength(0);
                    String low = tag.toLowerCase(Locale.ROOT);
                    if (low.startsWith("w:instrtext") || low.startsWith("office:automatic-styles")
                            || low.startsWith("office:styles") || low.startsWith("style:")) {
                        if (!low.endsWith("/")) skipElementText = true;
                    } else if (low.startsWith("/w:instrtext") || low.startsWith("/office:automatic-styles")
                            || low.startsWith("/office:styles") || low.startsWith("/style:")) {
                        skipElementText = false;
                    } else if (low.equals("/w:p") || low.equals("/text:p") || low.equals("/text:h")
                            || low.startsWith("w:br") || low.startsWith("w:cr") || low.startsWith("text:line-break")) {
                        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                            out.append('\n');
                        }
                    } else if (low.startsWith("w:tab") || low.startsWith("text:tab")) {
                        out.append('\t');
                    } else if (low.equals("/w:tc") || low.equals("/table:table-cell")) {
                        if (out.length() > 0 && out.charAt(out.length() - 1) != '\t' && out.charAt(out.length() - 1) != '\n') {
                            out.append('\t');
                        }
                    } else if (low.equals("/w:tr") || low.equals("/table:table-row")) {
                        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                            out.append('\n');
                        }
                    }
                    if (out.length() >= maxChars + 64) break;
                } else if (tagBuf.length() < 96) {
                    tagBuf.append(c);
                }
                continue;
            }
            if (c == '<') {
                inTag = true;
                tagBuf.setLength(0);
                continue;
            }
            if (skipElementText) continue;
            if (c == '&') {
                int semi = xml.indexOf(';', i + 1);
                if (semi > i && semi - i <= 10) {
                    String ent = xml.substring(i + 1, semi);
                    char decoded = decodeXmlEntity(ent);
                    if (decoded != 0) {
                        out.append(decoded);
                        i = semi;
                        continue;
                    }
                }
            }
            out.append(c);
            if (out.length() >= maxChars + 64) break;
        }

        String text = out.toString()
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars).trim() + "\n… [обрезано до " + maxChars + " символов]";
        }
        return text;
    }

    private static char decodeXmlEntity(String ent) {
        if ("amp".equals(ent)) return '&';
        if ("lt".equals(ent)) return '<';
        if ("gt".equals(ent)) return '>';
        if ("quot".equals(ent)) return '"';
        if ("apos".equals(ent)) return '\'';
        if (ent.startsWith("#x") || ent.startsWith("#X")) {
            try {
                int cp = Integer.parseInt(ent.substring(2), 16);
                if (cp >= 0x20 && cp <= 0xFFFF) return (char) cp;
            } catch (Throwable ignored) {}
        } else if (ent.startsWith("#")) {
            try {
                int cp = Integer.parseInt(ent.substring(1), 10);
                if (cp >= 0x20 && cp <= 0xFFFF) return (char) cp;
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    /**
     * Pure-Java mapping for all 8 EXIF orientation constants (1..8):
     * returns {@code {rotateDegrees, scaleX, scaleY}} applied in order rotate -> scale.
     */
    public static int[] exifFlipAndRotate(int orientation) {
        switch (orientation) {
            case 2: // ORIENTATION_FLIP_HORIZONTAL
                return new int[]{0, -1, 1};
            case 3: // ORIENTATION_ROTATE_180
                return new int[]{180, 1, 1};
            case 4: // ORIENTATION_FLIP_VERTICAL
                return new int[]{0, 1, -1};
            case 5: // ORIENTATION_TRANSPOSE
                return new int[]{90, -1, 1};
            case 6: // ORIENTATION_ROTATE_90
                return new int[]{90, 1, 1};
            case 7: // ORIENTATION_TRANSVERSE
                return new int[]{270, -1, 1};
            case 8: // ORIENTATION_ROTATE_270
                return new int[]{270, 1, 1};
            default:
                return new int[]{0, 1, 1};
        }
    }

    private static int readExifOrientation(File src) {
        try {
            ExifInterface exif = new ExifInterface(src.getAbsolutePath());
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap decodeImageNormalized(File src, int maxEdge) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(src);
                return ImageDecoder.decodeBitmap(source, (decoder, info, s) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int w = info.getSize().getWidth();
                    int h = info.getSize().getHeight();
                    int max = Math.max(w, h);
                    if (max > maxEdge && max > 0) {
                        float ratio = maxEdge / (float) max;
                        decoder.setTargetSize(
                                Math.max(1, Math.round(w * ratio)),
                                Math.max(1, Math.round(h * ratio)));
                    }
                });
            } catch (Throwable ignored) {
                // Fall back to BitmapFactory + 8-state EXIF matrix
            }
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (maxDim / (sample * 2) >= maxEdge) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeFile(src.getAbsolutePath(), opts);
        if (bmp == null) return null;

        int orient = readExifOrientation(src);
        int[] tf = exifFlipAndRotate(orient);
        if (tf[0] != 0 || tf[1] != 1 || tf[2] != 1) {
            try {
                Matrix matrix = new Matrix();
                if (tf[0] != 0) matrix.postRotate(tf[0]);
                if (tf[1] != 1 || tf[2] != 1) matrix.postScale(tf[1], tf[2]);
                Bitmap transformed = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
                if (transformed != null && transformed != bmp) {
                    bmp.recycle();
                    bmp = transformed;
                }
            } catch (Throwable ignored) {}
        }
        Bitmap scaled = scaleDownTo(bmp, maxEdge);
        if (scaled != bmp) {
            bmp.recycle();
            bmp = scaled;
        }
        return bmp;
    }

    private static PreparedMedia normalizeImage(File cacheDir, File src) {
        Bitmap bmp = null;
        Bitmap flattened = null;
        try {
            bmp = decodeImageNormalized(src, IMAGE_MAX_EDGE);
            if (bmp == null) return null;

            Bitmap toSave = bmp;
            if (bmp.hasAlpha()) {
                // Composite onto solid white so transparent PNGs/stickers/diagrams never turn pitch black in JPEG!
                flattened = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(flattened);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(bmp, 0f, 0f, null);
                toSave = flattened;
            }

            File out = new File(cacheDir, "img_" + System.currentTimeMillis() + "_" + IMG_SEQ.getAndIncrement() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                toSave.compress(Bitmap.CompressFormat.JPEG, 86, fos);
            }
            if (!out.isFile() || out.length() == 0L) {
                return null;
            }
            String desc = "Фото " + toSave.getWidth() + "x" + toSave.getHeight();
            return new PreparedMedia(out, false, desc);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (flattened != null && !flattened.isRecycled()) {
                try { flattened.recycle(); } catch (Throwable ignored) {}
            }
            if (bmp != null && !bmp.isRecycled()) {
                try { bmp.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    private static PreparedAttachment renderPdfPages(File cacheDir, File src) {
        String name = src.getName();
        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        try {
            pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            int totalPages = renderer.getPageCount();
            if (totalPages <= 0) {
                return new PreparedAttachment(AttachmentKind.UNSUPPORTED, name, null, "", false,
                        "", "PDF «" + name + "» не содержит страниц");
            }
            int renderCount = Math.min(MAX_PDF_PAGES, totalPages);
            List<File> pageFiles = new ArrayList<>(renderCount);
            for (int i = 0; i < renderCount; i++) {
                PdfRenderer.Page page = null;
                Bitmap bmp = null;
                try {
                    page = renderer.openPage(i);
                    int pw = Math.max(1, page.getWidth());
                    int ph = Math.max(1, page.getHeight());
                    float scale = PDF_MAX_EDGE / (float) Math.max(pw, ph);
                    int targetW = Math.max(1, Math.round(pw * scale));
                    int targetH = Math.max(1, Math.round(ph * scale));
                    bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(bmp);
                    c.drawColor(Color.WHITE);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    File out = new File(cacheDir, "pdf_p" + (i + 1) + "_" + System.currentTimeMillis()
                            + "_" + IMG_SEQ.getAndIncrement() + ".jpg");
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos);
                    }
                    if (out.isFile() && out.length() > 0L) {
                        pageFiles.add(out);
                    }
                } finally {
                    if (bmp != null && !bmp.isRecycled()) {
                        try { bmp.recycle(); } catch (Throwable ignored) {}
                    }
                    if (page != null) {
                        try { page.close(); } catch (Throwable ignored) {}
                    }
                }
            }
            if (pageFiles.isEmpty()) {
                return new PreparedAttachment(AttachmentKind.UNSUPPORTED, name, null, "", false,
                        "", "Не удалось отрендерить страницы PDF «" + name + "»");
            }
            String summary = "PDF «" + name + "» (" + totalPages + " стр., переданы 1–" + pageFiles.size() + ")";
            return new PreparedAttachment(AttachmentKind.PDF, name, pageFiles, "", false, summary, "");
        } catch (SecurityException sec) {
            return new PreparedAttachment(AttachmentKind.UNSUPPORTED, name, null, "", false,
                    "", "PDF «" + name + "» защищён паролем");
        } catch (Throwable err) {
            return new PreparedAttachment(AttachmentKind.UNSUPPORTED, name, null, "", false,
                    "", "Не удалось прочитать PDF «" + name + "»");
        } finally {
            if (renderer != null) {
                try { renderer.close(); } catch (Throwable ignored) {}
            }
            if (pfd != null) {
                try { pfd.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static PreparedMedia buildVideoStoryboard(File cacheDir, File src) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(src.getAbsolutePath());
            String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durMs = 4000L;
            if (durStr != null && !durStr.isEmpty()) {
                durMs = Math.max(1000L, Long.parseLong(durStr));
            }
            final int cellW = 288;
            final int cellH = 216;
            Bitmap grid = Bitmap.createBitmap(cellW * 2, cellH * 2, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(grid);
            canvas.drawColor(Color.BLACK);

            Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
            labelBg.setColor(0xB8000000);
            Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);
            labelText.setColor(Color.WHITE);
            labelText.setTextSize(20f);

            int extracted = 0;
            for (int i = 0; i < 4; i++) {
                long tUs = ((durMs * (2 * i + 1)) / 8L) * 1000L;
                Bitmap frame = mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame == null) {
                    frame = mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST);
                }
                if (frame != null) {
                    int col = i % 2;
                    int row = i / 2;
                    int left = col * cellW;
                    int top = row * cellH;
                    Bitmap fit = Bitmap.createScaledBitmap(frame, cellW, cellH, true);
                    canvas.drawBitmap(fit, left, top, null);
                    if (fit != frame) fit.recycle();
                    frame.recycle();

                    long sec = (tUs / 1000000L);
                    String stamp = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
                    canvas.drawRect(left + 8, top + 8, left + 86, top + 36, labelBg);
                    canvas.drawText(stamp, left + 16, top + 29, labelText);
                    extracted++;
                }
            }
            if (extracted == 0) {
                grid.recycle();
                return null;
            }
            File out = new File(cacheDir, "vid_grid_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                grid.compress(Bitmap.CompressFormat.JPEG, 86, fos);
            }
            grid.recycle();
            long totalSec = Math.max(1L, durMs / 1000L);
            return new PreparedMedia(out, true, "Видео (" + totalSec + " сек, 4 ключевых кадра)");
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                mmr.release();
            } catch (Throwable ignored) {}
        }
    }

    private static Bitmap scaleDownTo(Bitmap src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= maxEdge) return src;
        float ratio = maxEdge / (float) max;
        return Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * ratio)), Math.max(1, Math.round(h * ratio)), true);
    }

    private static void cleanupOldFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= 16) return;
        long cutoff = System.currentTimeMillis() - 3600_000L;
        for (File f : files) {
            if (f.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
