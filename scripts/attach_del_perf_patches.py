#!/usr/bin/env python3
"""
Track B upstream patches for Colgram (2GB Downloads attach, deleted large-file
infinite send/load loop elimination, Saved Messages / big-chat SQLite performance,
startup UI offload, temp-file safety, and auto-delete cache floor guards).

Called from scripts/apply-patches.py as:
    attach_del_perf_patches.apply(repo_path, patch_file, _patch_file_eol)
Zero overlap with Track A (bypass_bg_patches) and Track C (koliti_split_patches).
"""

import os


def _join(repo_path: str, rel: str) -> str:
    return os.path.join(repo_path, *rel.split("/"))


def apply(repo_path: str, patch_file_fn, eol_patch_fn) -> None:
    # =========================================================================
    # 1. ChatAttachAlert.java: 2GB/4GB limit, immediate non-blocking dismiss,
    #    staged progress toast, and cleanup if rejected.
    # =========================================================================
    chat_attach_alert = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java")

    def _replace_pick(norm: str, mime: str, audio_only: bool) -> str:
        mode_tag = "audio" if audio_only else "doc"
        if ("colgram-nonblocking-" + mode_tag) in norm:
            # Upgrade Pass-1/Pass-2 ProgressListener (safe appCtx Toast + unknown totalBytes MB readout)
            old_progress_sig = (
                '                            public void onProgress(int done, int total, String currentName, long bytesCopied, long totalBytes) {\n'
                '                                long now = android.os.SystemClock.uptimeMillis();\n'
                '                                if (now - colgramLastToast[0] >= 1800L && totalBytes >= org.colgram.core.ColgramFileImport.PROGRESS_THRESHOLD_BYTES) {\n'
                '                                    colgramLastToast[0] = now;\n'
                '                                    int pct = totalBytes > 0L ? (int) Math.min(99L, (bytesCopied * 100L) / totalBytes) : 0;\n'
                '                                    android.widget.Toast.makeText(activity, "Подготовка файла (" + pct + "%): " + currentName, android.widget.Toast.LENGTH_SHORT).show();\n'
                '                                }\n'
                '                            }\n'
                '                            @Override\n'
                '                            public void onRejected(String fileName, String reason) {\n'
                '                                String msg = "file_too_large".equals(reason)\n'
                '                                        ? ("Файл слишком большой (" + (colgramPremium ? "4 ГБ" : "2 ГБ") + "): " + fileName)\n'
                '                                        : ("insufficient_storage".equals(reason)\n'
                '                                                ? ("Недостаточно места на диске для: " + fileName)\n'
                '                                                : ("Не удалось подготовить файл: " + fileName));\n'
                '                                android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_LONG).show();\n'
                '                            }'
            )
            new_progress_sig = (
                '                            final android.content.Context colgramAppCtx = activity.getApplicationContext();\n'
                '                            @Override\n'
                '                            public void onProgress(int done, int total, String currentName, long bytesCopied, long totalBytes) {\n'
                '                                long now = android.os.SystemClock.uptimeMillis();\n'
                '                                long effectiveMax = Math.max(totalBytes, bytesCopied);\n'
                '                                if (now - colgramLastToast[0] >= 1800L && effectiveMax >= org.colgram.core.ColgramFileImport.PROGRESS_THRESHOLD_BYTES) {\n'
                '                                    colgramLastToast[0] = now;\n'
                '                                    String label = totalBytes > 0L\n'
                '                                            ? ((int) Math.min(99L, (bytesCopied * 100L) / totalBytes) + "%")\n'
                '                                            : ((bytesCopied / (1024L * 1024L)) + " МБ");\n'
                '                                    android.widget.Toast.makeText(colgramAppCtx, "Подготовка файла (" + label + "): " + currentName, android.widget.Toast.LENGTH_SHORT).show();\n'
                '                                }\n'
                '                            }\n'
                '                            @Override\n'
                '                            public void onRejected(String fileName, String reason) {\n'
                '                                String msg = "file_too_large".equals(reason)\n'
                '                                        ? ("Файл слишком большой (" + (colgramPremium ? "4 ГБ" : "2 ГБ") + "): " + fileName)\n'
                '                                        : ("insufficient_storage".equals(reason)\n'
                '                                                ? ("Недостаточно места на диске для: " + fileName)\n'
                '                                                : ("Не удалось подготовить файл: " + fileName));\n'
                '                                android.widget.Toast.makeText(colgramAppCtx, msg, android.widget.Toast.LENGTH_LONG).show();\n'
                '                            }'
            )
            old_progress_head = "                        org.colgram.core.ColgramFileImport.ProgressListener colgramProgress = new org.colgram.core.ColgramFileImport.ProgressListener() {\n                            @Override\n" + old_progress_sig
            new_progress_head = "                        org.colgram.core.ColgramFileImport.ProgressListener colgramProgress = new org.colgram.core.ColgramFileImport.ProgressListener() {\n" + new_progress_sig
            if old_progress_head in norm:
                norm = norm.replace(old_progress_head, new_progress_head, 1)
            # Upgrade Pass-1 didSelectFiles(paths, -> didSelectFiles(colgramPathsList,
            pass1_sig = (
                'org.colgram.core.ColgramFileImport.pickFiles(activity, "' + mime + '", true, colgramMaxBytes, paths -> {\n'
                '                            if (paths == null || paths.isEmpty()) {\n'
                '                                return;\n'
                '                            }\n'
                '                            try {\n'
                '                                if (documentsDelegate != null) {\n'
                '                                    documentsDelegate.didSelectFiles(paths,'
            )
            if pass1_sig in norm:
                norm = norm.replace(
                    '                                if (documentsDelegate != null) {\n'
                    '                                    documentsDelegate.didSelectFiles(paths,',
                    '                                final ArrayList<String> colgramPathsList = new ArrayList<>(paths);\n'
                    '                                if (documentsDelegate != null) {\n'
                    '                                    documentsDelegate.didSelectFiles(colgramPathsList,',
                    1,
                )
                norm = norm.replace(
                    '                                } else if (baseFragment instanceof ChatAttachAlertDocumentLayout.DocumentSelectActivityDelegate) {\n'
                    '                                    ((ChatAttachAlertDocumentLayout.DocumentSelectActivityDelegate) baseFragment)\n'
                    '                                            .didSelectFiles(paths,',
                    '                                } else if (baseFragment instanceof ChatAttachAlertDocumentLayout.DocumentSelectActivityDelegate) {\n'
                    '                                    ((ChatAttachAlertDocumentLayout.DocumentSelectActivityDelegate) baseFragment)\n'
                    '                                            .didSelectFiles(colgramPathsList,',
                    1,
                )
            return norm
        old_block = (
            '                        org.colgram.core.ColgramFileImport.pickFiles(activity, "' + mime + '", true, paths -> {\n'
            '                            if (paths == null || paths.isEmpty()) {\n'
            '                                return;\n'
            '                            }\n'
            '                            try {\n'
            '                                if (documentsDelegate != null) {\n'
            '                                    documentsDelegate.didSelectFiles(paths, "", null, new ArrayList<>(), true, 0, 0, 0, false, 0);\n'
            '                                } else if (baseFragment instanceof ChatAttachAlertDocumentLayout.DocumentSelectActivityDelegate) {\n'
            '                                    ((ChatAttachAlertDocumentLayout.DocumentSelectActivityDelegate) baseFragment)\n'
            '                                            .didSelectFiles(paths, "", null, new ArrayList<>(), true, 0, 0, 0, false, 0);\n'
            '                                }\n'
            '                            } catch (Throwable t) {\n'
            '                                FileLog.e(t);\n'
            '                            } finally {\n'
            '                                try {\n'
            '                                    dismiss(true);\n'
            '                                } catch (Throwable ignored) {}\n'
            '                            }\n'
            '                        });'
        )
        new_block = (
            '                        final boolean colgramPremium = org.telegram.messenger.UserConfig.getInstance(currentAccount).isPremium();\n'
            '                        final long colgramMaxBytes = colgramPremium\n'
            '                                ? org.colgram.core.ColgramFileImport.PREMIUM_MAX_FILE_BYTES\n'
            '                                : org.colgram.core.ColgramFileImport.DEFAULT_MAX_FILE_BYTES;\n'
            '                        final long[] colgramLastToast = new long[]{0L};\n'
            '                        org.colgram.core.ColgramFileImport.ProgressListener colgramProgress = new org.colgram.core.ColgramFileImport.ProgressListener() {\n'
            '                            final android.content.Context colgramAppCtx = activity.getApplicationContext();\n'
            '                            @Override\n'
            '                            public void onProgress(int done, int total, String currentName, long bytesCopied, long totalBytes) {\n'
            '                                long now = android.os.SystemClock.uptimeMillis();\n'
            '                                long effectiveMax = Math.max(totalBytes, bytesCopied);\n'
            '                                if (now - colgramLastToast[0] >= 1800L && effectiveMax >= org.colgram.core.ColgramFileImport.PROGRESS_THRESHOLD_BYTES) {\n'
            '                                    colgramLastToast[0] = now;\n'
            '                                    String label = totalBytes > 0L\n'
            '                                            ? ((int) Math.min(99L, (bytesCopied * 100L) / totalBytes) + "%")\n'
            '                                            : ((bytesCopied / (1024L * 1024L)) + " МБ");\n'
            '                                    android.widget.Toast.makeText(colgramAppCtx, "Подготовка файла (" + label + "): " + currentName, android.widget.Toast.LENGTH_SHORT).show();\n'
            '                                }\n'
            '                            }\n'
            '                            @Override\n'
            '                            public void onRejected(String fileName, String reason) {\n'
            '                                String msg = "file_too_large".equals(reason)\n'
            '                                        ? ("Файл слишком большой (" + (colgramPremium ? "4 ГБ" : "2 ГБ") + "): " + fileName)\n'
            '                                        : ("insufficient_storage".equals(reason)\n'
            '                                                ? ("Недостаточно места на диске для: " + fileName)\n'
            '                                                : ("Не удалось подготовить файл: " + fileName));\n'
            '                                android.widget.Toast.makeText(colgramAppCtx, msg, android.widget.Toast.LENGTH_LONG).show();\n'
            '                            }\n'
            '                        };\n'
            '                        org.colgram.core.ColgramFileImport.pickFiles(activity, "' + mime + '", true, colgramMaxBytes, paths -> {\n'
            '                            if (paths == null || paths.isEmpty()) {\n'
            '                                return;\n'
            '                            }\n'
            '                            try {\n'
            '                                final ArrayList<String> colgramPathsList = new ArrayList<>(paths);\n'
            '                                if (documentsDelegate != null) {\n'
            '                                    documentsDelegate.didSelectFiles(colgramPathsList, "", null, new ArrayList<>(), true, 0, 0, 0, false, 0);\n'
            '                                } else if (baseFragment instanceof ChatAttachAlertDocumentLayout.DocumentSelectActivityDelegate) {\n'
            '                                    ((ChatAttachAlertDocumentLayout.DocumentSelectActivityDelegate) baseFragment)\n'
            '                                            .didSelectFiles(colgramPathsList, "", null, new ArrayList<>(), true, 0, 0, 0, false, 0);\n'
            '                                }\n'
            '                            } catch (Throwable t) {\n'
            '                                FileLog.e(t);\n'
            '                            }\n'
            '                        }, colgramProgress);\n'
            '                        try { dismiss(true); } catch (Throwable ignored) {} // colgram-nonblocking-' + mode_tag
        )
        if old_block in norm:
            return norm.replace(old_block, new_block, 1)
        return norm

    def patch_chat_attach(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        updated = _replace_pick(norm, "audio/*", True)
        updated = _replace_pick(updated, "*/*", False)
        if updated == norm:
            return content
        eol = "\r\n" if "\r\n" in content else "\n"
        return updated.replace("\n", eol)

    patch_file_fn(chat_attach_alert, patch_chat_attach, "Math.max(totalBytes, bytesCopied)", "ChatAttachAlert.java non-blocking 2GB/4GB file picker")

    # =========================================================================
    # 2. ChatAttachAlertDocumentLayout.java: match FileLoader '<' limit + guard
    #    scoped storage DIRECTORY_DOWNLOADS access.
    # =========================================================================
    doc_layout = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlertDocumentLayout.java")
    eol_patch_fn(
        doc_layout,
        "if ((item.file.length() > FileLoader.DEFAULT_MAX_FILE_SIZE && !UserConfig.getInstance(UserConfig.selectedAccount).isPremium()) || item.file.length() > FileLoader.DEFAULT_MAX_FILE_SIZE_PREMIUM) {",
        "if ((item.file.length() >= FileLoader.DEFAULT_MAX_FILE_SIZE && !UserConfig.getInstance(UserConfig.selectedAccount).isPremium()) || item.file.length() >= FileLoader.DEFAULT_MAX_FILE_SIZE_PREMIUM) {",
        "ChatAttachAlertDocumentLayout.java strict < 2GB/4GB file limit",
    )
    eol_patch_fn(
        doc_layout,
        "        checkDirectory(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));",
        "        try {\n"
        "            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);\n"
        "            if (downloadsDir != null && downloadsDir.exists() && downloadsDir.canRead()) {\n"
        "                checkDirectory(downloadsDir);\n"
        "            }\n"
        "        } catch (Throwable e) {\n"
        "            FileLog.e(e);\n"
        "        }",
        "ChatAttachAlertDocumentLayout.java safe Downloads scoped-storage guard",
    )

    # =========================================================================
    # 3. MediaController.java: fix 32-bit int totalLen overflow (>2GB), enlarge
    #    copy buffer to 256 KiB, add StatFs check, and clean up failed temps.
    # =========================================================================
    media_ctrl = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/messenger/MediaController.java")
    eol_patch_fn(
        media_ctrl,
        "        int totalLen = 0;\n"
        "        File f = null;",
        "        long totalLen = 0L;\n"
        "        boolean completed = false;\n"
        "        File f = null;",
        "MediaController.java long totalLen in copyFileToCache",
    )
    eol_patch_fn(
        media_ctrl,
        "            output = new FileOutputStream(f);\n"
        "            byte[] buffer = new byte[1024 * 20];\n"
        "            int len;\n"
        "            while ((len = inputStream.read(buffer)) != -1) {\n"
        "                output.write(buffer, 0, len);\n"
        "                totalLen += len;\n"
        "                if (sizeLimit > 0 && totalLen > sizeLimit) {\n"
        "                    return null;\n"
        "                }\n"
        "            }\n"
        "            return f.getAbsolutePath();\n"
        "        } catch (Exception e) {\n"
        "            FileLog.e(e);\n"
        "        } finally {\n"
        "            try {\n"
        "                if (inputStream != null) {\n"
        "                    inputStream.close();\n"
        "                }\n"
        "            } catch (Exception e2) {\n"
        "                FileLog.e(e2);\n"
        "            }\n"
        "            try {\n"
        "                if (output != null) {\n"
        "                    output.close();\n"
        "                }\n"
        "            } catch (Exception e2) {\n"
        "                FileLog.e(e2);\n"
        "            }\n"
        "            if (sizeLimit > 0 && totalLen > sizeLimit) {\n"
        "                f.delete();\n"
        "            }\n"
        "        }",
        "            if (inputStream == null) {\n"
        "                return null;\n"
        "            }\n"
        "            if (sizeLimit > 0) {\n"
        "                try {\n"
        "                    android.os.StatFs fs = new android.os.StatFs(f.getParentFile().getAbsolutePath());\n"
        "                    long avail = fs.getAvailableBytes();\n"
        "                    if (avail > 0L && avail < Math.min(sizeLimit, 64L * 1024L * 1024L)) {\n"
        "                        return null;\n"
        "                    }\n"
        "                } catch (Throwable ignored) {}\n"
        "            }\n"
        "            output = new FileOutputStream(f);\n"
        "            byte[] buffer = new byte[256 * 1024];\n"
        "            int len;\n"
        "            while ((len = inputStream.read(buffer)) != -1) {\n"
        "                output.write(buffer, 0, len);\n"
        "                totalLen += len;\n"
        "                if (sizeLimit > 0 && totalLen > sizeLimit) {\n"
        "                    return null;\n"
        "                }\n"
        "            }\n"
        "            output.flush();\n"
        "            completed = true;\n"
        "            return f.getAbsolutePath();\n"
        "        } catch (Exception e) {\n"
        "            FileLog.e(e);\n"
        "        } finally {\n"
        "            try {\n"
        "                if (inputStream != null) {\n"
        "                    inputStream.close();\n"
        "                }\n"
        "            } catch (Exception e2) {\n"
        "                FileLog.e(e2);\n"
        "            }\n"
        "            try {\n"
        "                if (output != null) {\n"
        "                    output.close();\n"
        "                }\n"
        "            } catch (Exception e2) {\n"
        "                FileLog.e(e2);\n"
        "            }\n"
        "            if (!completed && f != null && f.exists()) {\n"
        "                f.delete();\n"
        "            }\n"
        "        }",
        "MediaController.java 256KB buffer, StatFs check, and failed temp cleanup",
    )

    # =========================================================================
    # 4. AndroidUtilities.java: handle raw: and msf: / non-numeric Downloads URIs
    # =========================================================================
    android_utils = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java")
    eol_patch_fn(
        android_utils,
        '                } else if (isDownloadsDocument(uri)) {\n'
        '                    final String id = DocumentsContract.getDocumentId(uri);\n'
        '                    final Uri contentUri = ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));\n'
        '                    return getDataColumn(ApplicationLoader.applicationContext, contentUri, null, null);\n'
        '                }',
        '                } else if (isDownloadsDocument(uri)) {\n'
        '                    final String id = DocumentsContract.getDocumentId(uri);\n'
        '                    if (id == null || id.isEmpty()) {\n'
        '                        return null;\n'
        '                    }\n'
        '                    if (id.startsWith("raw:")) {\n'
        '                        return id.substring(4);\n'
        '                    }\n'
        '                    if (id.startsWith("msf:")) {\n'
        '                        return null;\n'
        '                    }\n'
        '                    try {\n'
        '                        final Uri contentUri = ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), Long.parseLong(id));\n'
        '                        return getDataColumn(ApplicationLoader.applicationContext, contentUri, null, null);\n'
        '                    } catch (Throwable ignored) {\n'
        '                        return null;\n'
        '                    }\n'
        '                }',
        "AndroidUtilities.java raw:/msf: safe Downloads URI handling",
    )

    # =========================================================================
    # 5. FileUploadOperation.java: fix 64-bit checkpoint byte offset arithmetic
    # =========================================================================
    file_upload_op = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/messenger/FileUploadOperation.java")
    eol_patch_fn(
        file_upload_op,
        "final long currentRequestBytesOffset = currentRequestPartNum + currentRequestBytes;",
        "final long currentRequestBytesOffset = (long) currentRequestPartNum * (long) uploadChunkSize + currentRequestBytes;",
        "FileUploadOperation.java 64-bit chunk offset multiplication",
    )

    # =========================================================================
    # 6. SendMessagesHelper.java:
    #    - Shared colgramCancelMessageTransfers helper
    #    - Offload prepareSendingDocuments URI copies off Utilities.globalQueue
    #    - Clean up rejected colgram_inbox staged files
    # =========================================================================
    send_helper = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java")

    def patch_send_helper_cancel(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        eol = "\r\n" if "\r\n" in content else "\n"
        downloads_helper = (
            "    public static void colgramCancelFileDownloads(int account, MessageObject msg) {\n"
            "        if (msg == null) {\n"
            "            return;\n"
            "        }\n"
            "        try {\n"
            "            FileLoader fl = FileLoader.getInstance(account);\n"
            "            TLRPC.Document doc = msg.getDocument();\n"
            "            if (doc != null) {\n"
            "                fl.cancelLoadFile(doc, true);\n"
            "            }\n"
            "            String fn = msg.getFileName();\n"
            "            if (fn != null && !fn.isEmpty()) {\n"
            "                fl.cancelLoadFile(fn);\n"
            "            }\n"
            "            if (msg.messageOwner != null && msg.messageOwner.media != null && msg.messageOwner.media.photo != null) {\n"
            "                TLRPC.PhotoSize ps = FileLoader.getClosestPhotoSizeWithSize(msg.messageOwner.media.photo.sizes, AndroidUtilities.getPhotoSize());\n"
            "                if (ps != null) {\n"
            "                    fl.cancelLoadFile(ps, true);\n"
            "                }\n"
            "            }\n"
            "        } catch (Throwable ignored) {}\n"
            "    }\n\n"
        )
        full_block = (
            "    private static final java.util.concurrent.ExecutorService colgramDocPrepExecutor =\n"
            "            java.util.concurrent.Executors.newFixedThreadPool(2);\n\n"
            + downloads_helper
            + "    public static void colgramCancelMessageTransfers(int account, MessageObject msg) {\n"
            "        if (msg == null) {\n"
            "            return;\n"
            "        }\n"
            "        try {\n"
            "            if (msg.isSending() || (msg.messageOwner != null && msg.messageOwner.send_state != 0 && msg.messageOwner.send_state != 3)) {\n"
            "                getInstance(account).cancelSendingMessage(msg);\n"
            "            }\n"
            "        } catch (Throwable ignored) {}\n"
            "        colgramCancelFileDownloads(account, msg);\n"
            "    }\n\n"
            "    public void cancelSendingMessage(MessageObject object) {"
        )
        if "public static void colgramCancelFileDownloads(" in norm:
            return content
        pass1_marker = "    public static void colgramCancelMessageTransfers(int account, MessageObject msg) {"
        end_pass1 = "    public void cancelSendingMessage(MessageObject object) {"
        if pass1_marker in norm and end_pass1 in norm:
            p1 = norm.find("    private static final java.util.concurrent.ExecutorService colgramDocPrepExecutor =")
            p2 = norm.find(end_pass1, p1)
            if p1 >= 0 and p2 > p1:
                return (norm[:p1] + full_block + norm[p2 + len(end_pass1):]).replace("\n", eol)
        if end_pass1 in norm:
            return norm.replace(end_pass1, full_block, 1).replace("\n", eol)
        return content

    patch_file_fn(
        send_helper,
        patch_send_helper_cancel,
        "public static void colgramCancelFileDownloads(",
        "SendMessagesHelper.java shared colgramCancelMessageTransfers and colgramCancelFileDownloads helpers",
    )

    def patch_send_helper_prep(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        idx = norm.find("    @UiThread\n    public static void prepareSendingDocuments(AccountInstance accountInstance, ArrayList<String> paths, ArrayList<String> originalPaths, ArrayList<Uri> uris,")
        if idx < 0:
            idx = norm.find("    public static void prepareSendingDocuments(AccountInstance accountInstance, ArrayList<String> paths, ArrayList<String> originalPaths, ArrayList<Uri> uris,")
        if idx < 0:
            return content
        if "colgramDocPrepExecutor.execute(colgramPrepTask);" in norm[idx:idx + 8000]:
            return content
        old_line = "        Utilities.globalQueue.postRunnable(() -> {"
        pos = norm.find(old_line, idx)
        if pos < 0 or pos - idx > 6000:
            return content
        end_anchor = "            handleError(error, accountInstance);\n        });\n"
        end_pos = norm.find(end_anchor, pos)
        if end_pos < 0 or end_pos - pos > 8000:
            return content
        end_close = end_pos + len("            handleError(error, accountInstance);\n")
        inner = norm[pos + len(old_line):end_close]
        repl = (
            "        final Runnable colgramPrepTask = () -> {"
            + inner
            + "        };\n"
            "        if (uris != null && !uris.isEmpty()) {\n"
            "            colgramDocPrepExecutor.execute(colgramPrepTask);\n"
            "        } else {\n"
            "            Utilities.globalQueue.postRunnable(colgramPrepTask);\n"
            "        }\n"
        )
        new_norm = norm[:pos] + repl + norm[end_pos + len(end_anchor):]
        eol = "\r\n" if "\r\n" in content else "\n"
        return new_norm.replace("\n", eol)

    patch_file_fn(send_helper, patch_send_helper_prep, "colgramDocPrepExecutor.execute(colgramPrepTask);", "SendMessagesHelper.java dedicated executor for URI document prep")

    eol_patch_fn(
        send_helper,
        "        if (!FileLoader.checkUploadFileSize(accountInstance.getCurrentAccount(), f.length())) {\n"
        "            return ERROR_TYPE_FILE_TOO_LARGE;\n"
        "        }",
        "        if (!FileLoader.checkUploadFileSize(accountInstance.getCurrentAccount(), f.length())) {\n"
        "            org.colgram.core.ColgramFileImport.cleanupRejectedStagedFile(ApplicationLoader.applicationContext, path);\n"
        "            return ERROR_TYPE_FILE_TOO_LARGE;\n"
        "        }",
        "SendMessagesHelper.java clean up rejected colgram_inbox staged file",
    )

    # =========================================================================
    # 7. AlertsCreator.java & DeleteMessagesBottomSheet.java:
    #    Cancel active uploads & downloads before local delete / purge
    # =========================================================================
    alerts_creator = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/ui/Components/AlertsCreator.java")
    eol_patch_fn(
        alerts_creator,
        "                if (!ids.isEmpty()) {\n"
        "                    MessagesController.getInstance(currentAccount).colgramDeleteMessages(ids, random_ids, encryptedChat, thisDialogId, topicId, deleteForAll[0], mode, colgramPurgeLocal[0]);\n"
        "                }",
        "                if (selectedGroup != null) {\n"
        "                    for (int cgi = 0; cgi < selectedGroup.messages.size(); cgi++) {\n"
        "                        SendMessagesHelper.colgramCancelMessageTransfers(currentAccount, selectedGroup.messages.get(cgi));\n"
        "                    }\n"
        "                } else {\n"
        "                    SendMessagesHelper.colgramCancelMessageTransfers(currentAccount, selectedMessage);\n"
        "                }\n"
        "                if (!ids.isEmpty()) {\n"
        "                    MessagesController.getInstance(currentAccount).colgramDeleteMessages(ids, random_ids, encryptedChat, thisDialogId, topicId, deleteForAll[0], mode, colgramPurgeLocal[0]);\n"
        "                }",
        "AlertsCreator.java cancel single/grouped transfers before colgramDeleteMessages",
    )
    eol_patch_fn(
        alerts_creator,
        "                    MessagesController.getInstance(currentAccount).colgramDeleteMessages(ids, random_ids, encryptedChat, (a == 1 && mergeDialogId != 0) ? mergeDialogId : thisDialogId, topicId, deleteForAll[0], mode, colgramPurgeLocal[0]);",
        "                    for (int cgb = 0; cgb < selectedMessages[a].size(); cgb++) {\n"
        "                        SendMessagesHelper.colgramCancelMessageTransfers(currentAccount, selectedMessages[a].valueAt(cgb));\n"
        "                    }\n"
        "                    MessagesController.getInstance(currentAccount).colgramDeleteMessages(ids, random_ids, encryptedChat, (a == 1 && mergeDialogId != 0) ? mergeDialogId : thisDialogId, topicId, deleteForAll[0], mode, colgramPurgeLocal[0]);",
        "AlertsCreator.java cancel multi-selected transfers before colgramDeleteMessages",
    )

    del_sheet = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/ui/Components/DeleteMessagesBottomSheet.java")

    def patch_delsheet_cancel(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        eol = "\r\n" if "\r\n" in content else "\n"
        pass1 = (
            "            for (int cgi = 0; cgi < messages.size(); cgi++) {\n"
            "                SendMessagesHelper.colgramCancelMessageTransfers(currentAccount, messages.get(cgi));\n"
            "            }"
        )
        qualified = (
            "            for (int cgi = 0; cgi < messages.size(); cgi++) {\n"
            "                org.telegram.messenger.SendMessagesHelper.colgramCancelMessageTransfers(currentAccount, messages.get(cgi));\n"
            "            }"
        )
        if qualified in norm:
            return content
        if pass1 in norm:
            return norm.replace(pass1, qualified, 1).replace("\n", eol)
        fresh = (
            "            if (!supergroupMessageIds.isEmpty()) {\n"
            "                MessagesController.getInstance(currentAccount).colgramDeleteMessages(supergroupMessageIds, null, null, -inChat.id, topicId, false, mode, colgramPurgeLocal);\n"
            "            }"
        )
        if fresh in norm:
            return norm.replace(fresh, qualified + "\n" + fresh, 1).replace("\n", eol)
        return content

    patch_file_fn(
        del_sheet,
        patch_delsheet_cancel,
        "org.telegram.messenger.SendMessagesHelper.colgramCancelMessageTransfers(",
        "DeleteMessagesBottomSheet.java cancel transfers before colgramDeleteMessages",
    )

    # =========================================================================
    # 8. MessagesController.java: reject mid <= 0 / unsent from anti-delete,
    #    scope dialogMessagesByIds strictly to channelId==0 & targetDid,
    #    and cancel downloads non-reentrantly without calling cancelSendingMessage.
    # =========================================================================
    msgs_ctrl = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java")

    def patch_msgs_ctrl_delete(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        eol = "\r\n" if "\r\n" in content else "\n"
        new_loop = (
            "                    for (int a = 0; a < messages.size(); a++) {\n"
            "                        Integer id = messages.get(a);\n"
            "                        if (id == null) continue;\n"
            "                        MessageObject delObj = (channelId == 0) ? dialogMessagesByIds.get(id) : null;\n"
            "                        if (delObj != null && delObj.getDialogId() != targetDid) {\n"
            "                            delObj = null;\n"
            "                        }\n"
            "                        boolean isUnsent = id <= 0 || (delObj != null && (delObj.isSending()\n"
            "                                || (delObj.messageOwner != null && delObj.messageOwner.send_state != 0 && delObj.messageOwner.send_state != 3)));\n"
            "                        if (isUnsent) {\n"
            "                            if (delObj != null) {\n"
            "                                if (id > 0 && SendMessagesHelper.getInstance(currentAccount).isSendingMessage(id)) {\n"
            "                                    SendMessagesHelper.getInstance(currentAccount).cancelSendingMessage(delObj);\n"
            "                                }\n"
            "                                SendMessagesHelper.colgramCancelFileDownloads(currentAccount, delObj);\n"
            "                            }\n"
            "                            alreadyDeletedPurge.add(id);\n"
            "                        } else if (org.colgram.core.ColgramHookHandler.isForcePurged(targetDid, id)\n"
            "                                || (dialogId != targetDid && org.colgram.core.ColgramHookHandler.isForcePurged(dialogId, id))\n"
            "                                || org.colgram.core.ColgramHookHandler.isMessageMarkedDeleted(targetDid, id)\n"
            "                                || (dialogId != targetDid && org.colgram.core.ColgramHookHandler.isMessageMarkedDeleted(dialogId, id))) {\n"
            "                            alreadyDeletedPurge.add(id);\n"
            "                        } else {\n"
            "                            freshKeep.add(id);\n"
            "                        }\n"
            "                    }"
        )
        if "(channelId == 0) ? dialogMessagesByIds.get(id) : null;" in norm and "SendMessagesHelper.getInstance(currentAccount).isSendingMessage(id)" in norm:
            return content
        pass1_loop = (
            "                    for (int a = 0; a < messages.size(); a++) {\n"
            "                        Integer id = messages.get(a);\n"
            "                        if (id == null) continue;\n"
            "                        MessageObject delObj = dialogMessagesByIds.get(id);\n"
            "                        boolean isUnsent = id <= 0 || (delObj != null && (delObj.isSending()\n"
            "                                || (delObj.messageOwner != null && delObj.messageOwner.send_state != 0 && delObj.messageOwner.send_state != 3)));\n"
            "                        if (isUnsent) {\n"
            "                            if (delObj != null) {\n"
            "                                SendMessagesHelper.colgramCancelMessageTransfers(currentAccount, delObj);\n"
            "                            }\n"
            "                            alreadyDeletedPurge.add(id);\n"
            "                        } else if (org.colgram.core.ColgramHookHandler.isForcePurged(targetDid, id)\n"
            "                                || (dialogId != targetDid && org.colgram.core.ColgramHookHandler.isForcePurged(dialogId, id))\n"
            "                                || org.colgram.core.ColgramHookHandler.isMessageMarkedDeleted(targetDid, id)\n"
            "                                || (dialogId != targetDid && org.colgram.core.ColgramHookHandler.isMessageMarkedDeleted(dialogId, id))) {\n"
            "                            alreadyDeletedPurge.add(id);\n"
            "                        } else {\n"
            "                            freshKeep.add(id);\n"
            "                        }\n"
            "                    }"
        )
        fresh_loop = (
            "                    for (int a = 0; a < messages.size(); a++) {\n"
            "                        Integer id = messages.get(a);\n"
            "                        if (id == null) continue;\n"
            "                        if (org.colgram.core.ColgramHookHandler.isForcePurged(targetDid, id)\n"
            "                                || (dialogId != targetDid && org.colgram.core.ColgramHookHandler.isForcePurged(dialogId, id))\n"
            "                                || org.colgram.core.ColgramHookHandler.isMessageMarkedDeleted(targetDid, id)\n"
            "                                || (dialogId != targetDid && org.colgram.core.ColgramHookHandler.isMessageMarkedDeleted(dialogId, id))) {\n"
            "                            alreadyDeletedPurge.add(id);\n"
            "                        } else {\n"
            "                            freshKeep.add(id);\n"
            "                        }\n"
            "                    }"
        )
        for cand in (pass1_loop, fresh_loop):
            if cand in norm:
                return norm.replace(cand, new_loop, 1).replace("\n", eol)
        return content

    patch_file_fn(
        msgs_ctrl,
        patch_msgs_ctrl_delete,
        "SendMessagesHelper.getInstance(currentAccount).isSendingMessage(id)",
        "MessagesController.java scoped mid<=0/unsent anti-delete rejection",
    )

    # =========================================================================
    # 9. MessagesStorage.java:
    #    - Race-free per-account cleanup of poisoned mid<=0 rows (peek first, purge only once all active accounts cleaned)
    #    - O(1) deleteDialog(messagesOnly==1) via TABLE_DELETED_DIALOGS
    #    - keepClause / keepClauseFull applied to messages_topics in deleteDialog
    #    - Preserve cached media before addFilesToDelete & on media_v4/media_topics
    #    - Move commitTransaction outside loop; exclude mid <= 0 / unsent
    # =========================================================================
    msgs_storage = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java")

    def patch_msgs_storage_open_purge(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        eol = "\r\n" if "\r\n" in content else "\n"
        target_marker = "colgram_purged_neg_mids_acc_"
        if target_marker in norm:
            return content
        new_hook = (
            "            databaseCreated = true;\n"
            "            try {\n"
            "                android.content.SharedPreferences colgramPrefs = MessagesController.getMainSettings(currentAccount);\n"
            "                final String colgramPurgeKey = \"colgram_purged_neg_mids_acc_\" + currentAccount;\n"
            "                if (colgramPrefs != null && !colgramPrefs.getBoolean(colgramPurgeKey, false)) {\n"
            "                    org.colgram.core.ColgramDatabase colgramDb = org.colgram.core.ColgramDatabase.getInstance(ApplicationLoader.applicationContext);\n"
            "                    java.util.List<long[]> colgramPoisoned = colgramDb.peekPoisonedNonPositiveEntries();\n"
            "                    if (!colgramPoisoned.isEmpty() && database != null) {\n"
            "                        database.beginTransaction();\n"
            "                        try {\n"
            "                            for (int pi = 0; pi < colgramPoisoned.size(); pi++) {\n"
            "                                long[] pair = colgramPoisoned.get(pi);\n"
            "                                database.executeFast(String.format(Locale.US, \"DELETE FROM messages_v2 WHERE uid = %d AND mid = %d\", pair[0], (int) pair[1])).stepThis().dispose();\n"
            "                                database.executeFast(String.format(Locale.US, \"DELETE FROM messages_topics WHERE uid = %d AND mid = %d\", pair[0], (int) pair[1])).stepThis().dispose();\n"
            "                            }\n"
            "                        } finally {\n"
            "                            database.commitTransaction();\n"
            "                        }\n"
            "                    }\n"
            "                    colgramPrefs.edit().putBoolean(colgramPurgeKey, true).apply();\n"
            "                    boolean allClean = true;\n"
            "                    for (int accIdx = 0; accIdx < UserConfig.MAX_ACCOUNT_COUNT; accIdx++) {\n"
            "                        if (UserConfig.getInstance(accIdx).isClientActivated()) {\n"
            "                            android.content.SharedPreferences accPrefs = MessagesController.getMainSettings(accIdx);\n"
            "                            if (accPrefs == null || !accPrefs.getBoolean(\"colgram_purged_neg_mids_acc_\" + accIdx, false)) {\n"
            "                                allClean = false;\n"
            "                                break;\n"
            "                            }\n"
            "                        }\n"
            "                    }\n"
            "                    if (allClean) {\n"
            "                        colgramDb.purgePoisonedNonPositiveEntries();\n"
            "                    }\n"
            "                }\n"
            "            } catch (Throwable ignored) {}\n"
            "        } catch (Exception e) {"
        )
        pass1_v2_hook = (
            "            databaseCreated = true;\n"
            "            try {\n"
            "                android.content.SharedPreferences colgramPrefs = MessagesController.getMainSettings(currentAccount);\n"
            "                if (colgramPrefs != null && !colgramPrefs.getBoolean(\"colgram_poison_purged_v1\", false)) {\n"
            "                    org.colgram.core.ColgramDatabase colgramDb = org.colgram.core.ColgramDatabase.getInstance(ApplicationLoader.applicationContext);\n"
            "                    java.util.List<long[]> colgramPoisoned = colgramDb.peekPoisonedNonPositiveEntries();\n"
            "                    if (!colgramPoisoned.isEmpty() && database != null) {\n"
            "                        database.beginTransaction();\n"
            "                        try {\n"
            "                            for (int pi = 0; pi < colgramPoisoned.size(); pi++) {\n"
            "                                long[] pair = colgramPoisoned.get(pi);\n"
            "                                database.executeFast(String.format(Locale.US, \"DELETE FROM messages_v2 WHERE uid = %d AND mid = %d\", pair[0], (int) pair[1])).stepThis().dispose();\n"
            "                                database.executeFast(String.format(Locale.US, \"DELETE FROM messages_topics WHERE uid = %d AND mid = %d\", pair[0], (int) pair[1])).stepThis().dispose();\n"
            "                            }\n"
            "                        } finally {\n"
            "                            database.commitTransaction();\n"
            "                        }\n"
            "                    }\n"
            "                    colgramPrefs.edit().putBoolean(\"colgram_poison_purged_v1\", true).apply();\n"
            "                    boolean allClean = true;\n"
            "                    for (int accIdx = 0; accIdx < UserConfig.MAX_ACCOUNT_COUNT; accIdx++) {\n"
            "                        if (UserConfig.getInstance(accIdx).isClientActivated()) {\n"
            "                            android.content.SharedPreferences accPrefs = MessagesController.getMainSettings(accIdx);\n"
            "                            if (accPrefs == null || !accPrefs.getBoolean(\"colgram_poison_purged_v1\", false)) {\n"
            "                                allClean = false;\n"
            "                                break;\n"
            "                            }\n"
            "                        }\n"
            "                    }\n"
            "                    if (allClean) {\n"
            "                        colgramDb.purgePoisonedNonPositiveEntries();\n"
            "                    }\n"
            "                }\n"
            "            } catch (Throwable ignored) {}\n"
            "        } catch (Exception e) {"
        )
        pass1_hook = (
            "            databaseCreated = true;\n"
            "            try {\n"
            "                java.util.List<long[]> colgramPoisoned = org.colgram.core.ColgramDatabase\n"
            "                        .getInstance(ApplicationLoader.applicationContext)\n"
            "                        .drainPoisonedNonPositiveEntries();\n"
            "                if (!colgramPoisoned.isEmpty() && database != null) {\n"
            "                    database.beginTransaction();\n"
            "                    try {\n"
            "                        for (int pi = 0; pi < colgramPoisoned.size(); pi++) {\n"
            "                            long[] pair = colgramPoisoned.get(pi);\n"
            "                            database.executeFast(String.format(Locale.US, \"DELETE FROM messages_v2 WHERE uid = %d AND mid = %d\", pair[0], (int) pair[1])).stepThis().dispose();\n"
            "                            database.executeFast(String.format(Locale.US, \"DELETE FROM messages_topics WHERE uid = %d AND mid = %d\", pair[0], (int) pair[1])).stepThis().dispose();\n"
            "                        }\n"
            "                    } finally {\n"
            "                        database.commitTransaction();\n"
            "                    }\n"
            "                }\n"
            "            } catch (Throwable ignored) {}\n"
            "        } catch (Exception e) {"
        )
        fresh_hook = (
            "            databaseCreated = true;\n"
            "        } catch (Exception e) {"
        )
        for cand in (pass1_v2_hook, pass1_hook, fresh_hook):
            if cand in norm:
                return norm.replace(cand, new_hook, 1).replace("\n", eol)
        return content

    patch_file_fn(
        msgs_storage,
        patch_msgs_storage_open_purge,
        "colgram_purged_neg_mids_acc_",
        "MessagesStorage.java race-free multi-account startup purge of poisoned mid<=0 tombstones",
    )

    eol_patch_fn(
        msgs_storage,
        '                        database.executeFast("DELETE FROM messages_topics WHERE uid = " + did + " AND mid != " + last_mid_i + " AND mid != " + last_mid).stepThis().dispose();',
        '                        database.executeFast("DELETE FROM messages_topics WHERE uid = " + did + " AND mid != " + last_mid_i + " AND mid != " + last_mid + keepClause).stepThis().dispose();',
        "MessagesStorage.java apply keepClause to messages_topics in deleteDialog(messagesOnly==2)",
    )
    eol_patch_fn(
        msgs_storage,
        '                database.executeFast("DELETE FROM messages_topics WHERE uid = " + did).stepThis().dispose();',
        '                database.executeFast("DELETE FROM messages_topics WHERE uid = " + did + keepClauseFull).stepThis().dispose();',
        "MessagesStorage.java apply keepClauseFull to messages_topics in deleteDialog",
    )

    eol_patch_fn(
        msgs_storage,
        "                if (messagesOnly == 1 && org.colgram.core.ColgramConfig.isAntiDeleteEnabled()) {\n"
        "                    org.colgram.core.ColgramHookHandler.hookShouldPreventDialogDelete(did, \"\");\n"
        "                    SQLiteCursor antidelCur = null;\n"
        "                    try {\n"
        "                        antidelCur = database.queryFinalized(\"SELECT mid FROM messages_v2 WHERE uid = \" + did);\n"
        "                        java.util.ArrayList<Integer> antidelBatch = new java.util.ArrayList<>(256);\n"
        "                        while (antidelCur.next()) {\n"
        "                            antidelBatch.add(antidelCur.intValue(0));\n"
        "                            if (antidelBatch.size() >= 2000) {\n"
        "                                org.colgram.core.ColgramHookHandler.hookShouldPreventDeleteBatch(did, antidelBatch);\n"
        "                                antidelBatch.clear();\n"
        "                            }\n"
        "                        }\n"
        "                        if (!antidelBatch.isEmpty()) {\n"
        "                            org.colgram.core.ColgramHookHandler.hookShouldPreventDeleteBatch(did, antidelBatch);\n"
        "                        }\n"
        "                    } catch (Exception ignored) {\n"
        "                    } finally {\n"
        "                        if (antidelCur != null) antidelCur.dispose();\n"
        "                    }\n"
        "                    database.executeFast(\"UPDATE dialogs SET unread_count = 0, unread_count_i = 0 WHERE did = \" + did).stepThis().dispose();\n"
        "                    return;\n"
        "                }",
        "                if (messagesOnly == 1 && org.colgram.core.ColgramConfig.isAntiDeleteEnabled()) {\n"
        "                    org.colgram.core.ColgramHookHandler.hookShouldPreventDialogDelete(did, \"\");\n"
        "                    database.executeFast(\"UPDATE dialogs SET unread_count = 0, unread_count_i = 0 WHERE did = \" + did).stepThis().dispose();\n"
        "                    return;\n"
        "                }",
        "MessagesStorage.java O(1) deleteDialog(messagesOnly==1) via TABLE_DELETED_DIALOGS",
    )

    eol_patch_fn(
        msgs_storage,
        "                            if (DialogObject.isEncryptedDialog(did) || deleteFiles) {\n"
        "                                addFilesToDelete(message, filesToDelete, idsToDelete, namesToDelete, false);\n"
        "                            }",
        "                            if ((DialogObject.isEncryptedDialog(did) || deleteFiles)\n"
        "                                    && !org.colgram.core.ColgramHookHandler.isMessageMarkedDeleted(did, mid)) {\n"
        "                                addFilesToDelete(message, filesToDelete, idsToDelete, namesToDelete, false);\n"
        "                            }",
        "MessagesStorage.java preserve cached media of anti-deleted messages_v2",
    )

    eol_patch_fn(
        msgs_storage,
        "                            data.reuse();\n"
        "                            addFilesToDelete(message, filesToDelete, idsToDelete, namesToDelete, false);",
        "                            data.reuse();\n"
        "                            if (!org.colgram.core.ColgramHookHandler.isMessageMarkedDeleted(did, mid)) {\n"
        "                                addFilesToDelete(message, filesToDelete, idsToDelete, namesToDelete, false);\n"
        "                            }",
        "MessagesStorage.java preserve cached media of anti-deleted messages_topics",
    )

    def patch_msgs_storage_tx_and_filter(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        eol = "\r\n" if "\r\n" in content else "\n"
        changed = False

        # Move database.commitTransaction() outside for (int i = 0; i < 5; i++) loop
        loop_old = (
            "                database.beginTransaction();\n"
            "                for (int i = 0; i < 5; i++) {"
        )
        if loop_old in norm and "colgram-tx-outside-loop" not in norm:
            loop_pos = norm.find(loop_old)
            commit_inside = "                    database.commitTransaction();\n                    data.reuse();\n                }"
            commit_pos = norm.find(commit_inside, loop_pos)
            if commit_pos > 0 and commit_pos - loop_pos < 4000:
                norm = (
                    norm[:loop_pos]
                    + "                database.beginTransaction(); // colgram-tx-outside-loop\n"
                    + "                try {\n"
                    + "                for (int i = 0; i < 5; i++) {"
                    + norm[loop_pos + len(loop_old):commit_pos]
                    + "                    data.reuse();\n                }\n"
                    + "                } finally {\n"
                    + "                    database.commitTransaction();\n"
                    + "                }"
                    + norm[commit_pos + len(commit_inside):]
                )
                changed = True

        # Apply keepClauseDel to media_v4 SELECT & DELETE and media_topics DELETE
        old_media_sel = 'cursor = database.queryFinalized(String.format(Locale.US, "SELECT uid, type FROM media_v4 WHERE mid IN(%s) AND uid = %d", ids, did));'
        new_media_sel = 'cursor = database.queryFinalized(String.format(Locale.US, "SELECT uid, type FROM media_v4 WHERE mid IN(%s) AND uid = %d%s", ids, did, keepClauseDel));'
        if old_media_sel in norm:
            norm = norm.replace(old_media_sel, new_media_sel, 1)
            changed = True

        old_media_del = (
            '                    database.executeFast(String.format(Locale.US, "DELETE FROM media_v4 WHERE mid IN(%s) AND uid = %d", ids, did)).stepThis().dispose();\n'
            '                    database.executeFast(String.format(Locale.US, "DELETE FROM media_topics WHERE mid IN(%s) AND uid = %d", ids, did)).stepThis().dispose();'
        )
        new_media_del = (
            '                    database.executeFast(String.format(Locale.US, "DELETE FROM media_v4 WHERE mid IN(%s) AND uid = %d%s", ids, did, keepClauseDel)).stepThis().dispose();\n'
            '                    database.executeFast(String.format(Locale.US, "DELETE FROM media_topics WHERE mid IN(%s) AND uid = %d%s", ids, did, keepClauseDel)).stepThis().dispose();'
        )
        if old_media_del in norm:
            norm = norm.replace(old_media_del, new_media_del, 1)
            changed = True

        # Exclude mid <= 0 and unsent messages (send_state != 0) from filterAntiDeletedMessagesLocked
        old_filter_head = (
            "    private java.util.ArrayList<Integer> filterAntiDeletedMessagesLocked(long dialogId, java.util.ArrayList<Integer> messagesToDelete) {\n"
            "        if (messagesToDelete == null || messagesToDelete.isEmpty()) {\n"
            "            return messagesToDelete;\n"
            "        }\n"
            "        if (dialogId != 0) {"
        )
        new_filter_head = (
            "    private java.util.ArrayList<Integer> filterAntiDeletedMessagesLocked(long dialogId, java.util.ArrayList<Integer> messagesToDelete) {\n"
            "        if (messagesToDelete == null || messagesToDelete.isEmpty()) {\n"
            "            return messagesToDelete;\n"
            "        }\n"
            "        java.util.ArrayList<Integer> colgramPositive = new java.util.ArrayList<>(messagesToDelete.size());\n"
            "        for (int ci = 0, cn = messagesToDelete.size(); ci < cn; ci++) {\n"
            "            Integer cmid = messagesToDelete.get(ci);\n"
            "            if (cmid != null && cmid > 0) {\n"
            "                colgramPositive.add(cmid);\n"
            "            }\n"
            "        }\n"
            "        if (colgramPositive.isEmpty()) {\n"
            "            return messagesToDelete;\n"
            "        }\n"
            "        if (dialogId != 0) {\n"
            "            try {\n"
            "                java.util.HashSet<Integer> unsentIds = new java.util.HashSet<>();\n"
            "                for (int start = 0; start < colgramPositive.size(); start += 500) {\n"
            "                    int end = Math.min(colgramPositive.size(), start + 500);\n"
            "                    StringBuilder sb = new StringBuilder();\n"
            "                    for (int j = start; j < end; j++) {\n"
            "                        if (j > start) sb.append(',');\n"
            "                        sb.append(colgramPositive.get(j));\n"
            "                    }\n"
            "                    SQLiteCursor sc = database.queryFinalized(String.format(Locale.US,\n"
            "                            \"SELECT mid FROM messages_v2 WHERE uid = %d AND mid IN(%s) AND send_state != 0\", dialogId, sb.toString()));\n"
            "                    try {\n"
            "                        while (sc.next()) { unsentIds.add(sc.intValue(0)); }\n"
            "                    } finally { sc.dispose(); }\n"
            "                }\n"
            "                if (!unsentIds.isEmpty()) { colgramPositive.removeAll(unsentIds); }\n"
            "            } catch (Exception ignored) {}\n"
            "            if (colgramPositive.isEmpty()) return messagesToDelete;"
        )
        if old_filter_head in norm and "colgramPositive" not in norm:
            norm = norm.replace(old_filter_head, new_filter_head, 1)
            norm = norm.replace(
                "                    .hookShouldPreventDeleteBatch(dialogId, messagesToDelete);",
                "                    .hookShouldPreventDeleteBatch(dialogId, colgramPositive);",
                1,
            )
            norm = norm.replace(
                '"SELECT mid, uid FROM messages_v2 WHERE mid IN(%s) AND is_channel = 0", idsSb.toString()',
                '"SELECT mid, uid FROM messages_v2 WHERE mid IN(%s) AND mid > 0 AND is_channel = 0 AND send_state = 0", idsSb.toString()',
                1,
            )
            changed = True

        if not changed:
            return content
        return norm.replace("\n", eol)

    patch_file_fn(msgs_storage, patch_msgs_storage_tx_and_filter, "colgram-tx-outside-loop", "MessagesStorage.java loop tx fix, media_v4 keepClauseDel, and unsent filter")

    # =========================================================================
    # 10. SavedMessagesController.java:
    #     - Transaction in saveCache()
    #     - Fix NativeByteBuffer.reuse() leaks in loadCache() & background refresh
    #     - Batch N+1 messages_topics queries in loadCache()
    #     - Deduplicate SQL IN(...) IDs
    #     - Replace O(N^2) dialog list scans with LongSparseArray
    # =========================================================================
    saved_ctrl = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/messenger/SavedMessagesController.java")

    def patch_saved_ctrl(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        eol = "\r\n" if "\r\n" in content else "\n"
        changed = False

        # 1) Transaction around saveCache
        old_save = (
            "                SQLitePreparedStatement state2 = db.executeFast(\"DELETE FROM saved_dialogs WHERE forumChatId = ?\");\n"
            "                state2.requery();\n"
            "                state2.bindLong(1, 0);\n"
            "                state2.step();\n"
            "                state2.dispose();\n\n"
            "                state = db.executeFast(\"REPLACE INTO saved_dialogs VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\");"
        )
        if old_save in norm and "db.beginTransaction(); // colgram-savecache-tx" not in norm:
            pos = norm.find(old_save)
            end_anchor = "\n                state.dispose();\n\n            } catch (Exception e) {"
            end_pos = norm.find(end_anchor, pos)
            if end_pos > pos:
                inner = norm[pos:end_pos + len("\n                state.dispose();")]
                wrapped = (
                    "                db.beginTransaction(); // colgram-savecache-tx\n"
                    "                try {\n"
                    + inner + "\n"
                    "                } finally {\n"
                    "                    db.commitTransaction();\n"
                    "                }"
                )
                norm = norm[:pos] + wrapped + norm[end_pos + len("\n                state.dispose();"):]
                changed = True

        # 2) Fix NativeByteBuffer.reuse() leak + batch N+1 messages_topics queries in loadCache()
        old_load_loop = (
            "                    if (d.dialogId < 0) {\n"
            "                        chatsToLoad.add(-d.dialogId);\n"
            "                    } else {\n"
            "                        usersToLoad.add(d.dialogId);\n"
            "                    }\n\n"
            "                    cursor2 = db.queryFinalized(\"SELECT data FROM messages_topics WHERE uid = ? AND mid = ? AND topic_id = ?\", selfId, d.top_message_id, d.dialogId);\n"
            "                    if (cursor2.next()) {\n"
            "                        NativeByteBuffer buffer = cursor2.byteBufferValue(0);\n"
            "                        TLRPC.Message message = TLRPC.Message.TLdeserialize(buffer, buffer.readInt32(true), true);\n"
            "                        MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, emojiToLoad);\n"
            "                        d.message = new MessageObject(currentAccount, message, null, null, null, null, null, false, false, 0, false, false, true);\n"
            "                    }\n"
            "                    cursor2.dispose();\n\n"
            "                    dialogs.add(d);\n"
            "                }"
        )
        new_load_loop = (
            "                    if (d.dialogId < 0) {\n"
            "                        chatsToLoad.add(-d.dialogId);\n"
            "                    } else {\n"
            "                        usersToLoad.add(d.dialogId);\n"
            "                    }\n"
            "                    dialogs.add(d);\n"
            "                }\n"
            "                if (!dialogs.isEmpty()) {\n"
            "                    for (int start = 0; start < dialogs.size(); start += 200) {\n"
            "                        int end = Math.min(dialogs.size(), start + 200);\n"
            "                        StringBuilder midList = new StringBuilder();\n"
            "                        StringBuilder topicList = new StringBuilder();\n"
            "                        java.util.HashMap<String, SavedDialog> batchMap = new java.util.HashMap<>(end - start);\n"
            "                        for (int bi = start; bi < end; bi++) {\n"
            "                            SavedDialog bd = dialogs.get(bi);\n"
            "                            if (bi > start) {\n"
            "                                midList.append(',');\n"
            "                                topicList.append(',');\n"
            "                            }\n"
            "                            midList.append(bd.top_message_id);\n"
            "                            topicList.append(bd.dialogId);\n"
            "                            batchMap.put(bd.dialogId + \":\" + bd.top_message_id, bd);\n"
            "                        }\n"
            "                        cursor2 = db.queryFinalized(String.format(java.util.Locale.US,\n"
            "                                \"SELECT topic_id, mid, data FROM messages_topics WHERE uid = %d AND mid IN(%s) AND topic_id IN(%s)\",\n"
            "                                selfId, midList.toString(), topicList.toString()));\n"
            "                        while (cursor2.next()) {\n"
            "                            long tId = cursor2.longValue(0);\n"
            "                            int mId = cursor2.intValue(1);\n"
            "                            SavedDialog match = batchMap.get(tId + \":\" + mId);\n"
            "                            if (match != null && match.message == null) {\n"
            "                                NativeByteBuffer buffer = cursor2.byteBufferValue(2);\n"
            "                                if (buffer != null) {\n"
            "                                    try {\n"
            "                                        TLRPC.Message message = TLRPC.Message.TLdeserialize(buffer, buffer.readInt32(true), true);\n"
            "                                        if (message != null) {\n"
            "                                            MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, emojiToLoad);\n"
            "                                            match.message = new MessageObject(currentAccount, message, null, null, null, null, null, false, false, 0, false, false, true);\n"
            "                                        }\n"
            "                                    } finally {\n"
            "                                        buffer.reuse();\n"
            "                                    }\n"
            "                                }\n"
            "                            }\n"
            "                        }\n"
            "                        cursor2.dispose();\n"
            "                        cursor2 = null;\n"
            "                    }\n"
            "                }"
        )
        if old_load_loop in norm:
            norm = norm.replace(old_load_loop, new_load_loop, 1)
            changed = True

        # 3) Fix second NativeByteBuffer.reuse() leak around line 916
        old_buf2 = (
            "                        int topMessageId = cursor.intValue(0);\n"
            "                        NativeByteBuffer buffer = cursor.byteBufferValue(1);\n"
            "                        TLRPC.Message message = TLRPC.Message.TLdeserialize(buffer, buffer.readInt32(true), true);\n"
            "                        MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, emojiToLoad);\n"
            "                        newMessages.put(d.dialogId, message);"
        )
        new_buf2 = (
            "                        int topMessageId = cursor.intValue(0);\n"
            "                        NativeByteBuffer buffer = cursor.byteBufferValue(1);\n"
            "                        if (buffer != null) {\n"
            "                            try {\n"
            "                                TLRPC.Message message = TLRPC.Message.TLdeserialize(buffer, buffer.readInt32(true), true);\n"
            "                                if (message != null) {\n"
            "                                    MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, emojiToLoad);\n"
            "                                    newMessages.put(d.dialogId, message);\n"
            "                                }\n"
            "                            } finally {\n"
            "                                buffer.reuse();\n"
            "                            }\n"
            "                        }"
        )
        if old_buf2 in norm:
            norm = norm.replace(old_buf2, new_buf2, 1)
            changed = True

        # 4) Replace O(N^2) cachedDialogs/loadedDialogs scans in savedDialogs response handlers with LongSparseArray
        old_all_scan = (
            "                for (int i = 0; i < r.dialogs.size(); ++i) {\n"
            "                    SavedDialog d = SavedDialog.fromTL(currentAccount, r.dialogs.get(i), r.messages, true);\n"
            "                    for (int j = 0; j < cachedDialogs.size(); ++j) {\n"
            "                        if (cachedDialogs.get(j).dialogId == d.dialogId) {\n"
            "                            d.messagesCount = cachedDialogs.get(j).messagesCount;\n"
            "                            cachedDialogs.get(j).pinned = d.pinned;\n"
            "                            break;\n"
            "                        }\n"
            "                    }\n"
            "                    boolean found = false;\n"
            "                    for (int j = 0; j < loadedDialogs.size(); ++j) {\n"
            "                        if (loadedDialogs.get(j).dialogId == d.dialogId) {\n"
            "                            found = true;\n"
            "                            break;\n"
            "                        }\n"
            "                    }\n"
            "                    if (!found) {\n"
            "                        loadedDialogs.add(d);\n"
            "                        if (d.isHidden())\n"
            "                            dialogsCountHidden++;\n"
            "                    }\n"
            "                }"
        )
        new_all_scan = (
            "                androidx.collection.LongSparseArray<SavedDialog> colgramCachedMap = new androidx.collection.LongSparseArray<>(cachedDialogs.size());\n"
            "                for (int j = 0; j < cachedDialogs.size(); ++j) { SavedDialog cd = cachedDialogs.get(j); colgramCachedMap.put(cd.dialogId, cd); }\n"
            "                androidx.collection.LongSparseArray<SavedDialog> colgramLoadedMap = new androidx.collection.LongSparseArray<>(loadedDialogs.size() + r.dialogs.size());\n"
            "                for (int j = 0; j < loadedDialogs.size(); ++j) { SavedDialog ld = loadedDialogs.get(j); colgramLoadedMap.put(ld.dialogId, ld); }\n"
            "                for (int i = 0; i < r.dialogs.size(); ++i) {\n"
            "                    SavedDialog d = SavedDialog.fromTL(currentAccount, r.dialogs.get(i), r.messages, true);\n"
            "                    SavedDialog cachedMatch = colgramCachedMap.get(d.dialogId);\n"
            "                    if (cachedMatch != null) {\n"
            "                        d.messagesCount = cachedMatch.messagesCount;\n"
            "                        cachedMatch.pinned = d.pinned;\n"
            "                    }\n"
            "                    if (colgramLoadedMap.get(d.dialogId) == null) {\n"
            "                        colgramLoadedMap.put(d.dialogId, d);\n"
            "                        loadedDialogs.add(d);\n"
            "                        if (d.isHidden())\n"
            "                            dialogsCountHidden++;\n"
            "                    }\n"
            "                }"
        )
        if old_all_scan in norm:
            norm = norm.replace(old_all_scan, new_all_scan)
            changed = True

        # Upgrade Pass-1 Locale.US -> java.util.Locale.US if present
        pass1_locale = 'cursor2 = db.queryFinalized(String.format(Locale.US,\n                                "SELECT topic_id, mid, data FROM messages_topics WHERE uid = %d'
        if pass1_locale in norm:
            norm = norm.replace(pass1_locale, 'cursor2 = db.queryFinalized(String.format(java.util.Locale.US,\n                                "SELECT topic_id, mid, data FROM messages_topics WHERE uid = %d', 1)
            changed = True

        # 5) Deduplicate IDs before SQL IN(...) queries
        for col_var in ("chatsToLoad", "emojiToLoad"):
            target_join = f'TextUtils.join(",", {col_var})'
            if target_join in norm and f"new java.util.LinkedHashSet<>({col_var})" not in norm:
                norm = norm.replace(target_join, f'TextUtils.join(",", new java.util.LinkedHashSet<>({col_var}))')
                changed = True

        if not changed:
            return content
        return norm.replace("\n", eol)

    patch_file_fn(saved_ctrl, patch_saved_ctrl, "String.format(java.util.Locale.US,", "SavedMessagesController.java tx, batch query, NativeByteBuffer reuse, O(1) map")

    # =========================================================================
    # 11. ChatActivity.java:
    #     - Preserve waitingForLoad/endReached on 9s firstLoadMessages timeout
    #     - Exclude mid<=0 / unsent from processDeletedMessages preserve
    #     - Cancel active transfers on option 9990 local delete
    # =========================================================================
    chat_act = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
    eol_patch_fn(
        chat_act,
        "                waitingForLoad.clear();\n"
        "                loading = false;\n"
        "                firstLoading = false;\n"
        "                endReached[0] = endReached[1] = true;\n"
        "                forwardEndReached[0] = forwardEndReached[1] = true;",
        "                // Colgram: keep waitingForLoad/endReached intact so delayed Saved Messages responses still render.\n"
        "                firstLoading = false;",
        "ChatActivity.java keep waitingForLoad/endReached intact on 9s firstLoad timer",
    )

    eol_patch_fn(
        chat_act,
        "                boolean forcePurged = org.colgram.core.ColgramHookHandler.consumeForcePurged(dialog_id, messageId)\n"
        "                        || (objDid != dialog_id && org.colgram.core.ColgramHookHandler.consumeForcePurged(objDid, messageId))\n"
        "                        || (existingObj != null && existingObj.deleted);",
        "                boolean unsentOrLocal = messageId <= 0 || (existingObj != null && (existingObj.isSending()\n"
        "                        || (existingObj.messageOwner != null && existingObj.messageOwner.send_state != 0 && existingObj.messageOwner.send_state != 3)));\n"
        "                if (unsentOrLocal && existingObj != null) {\n"
        "                    SendMessagesHelper.colgramCancelMessageTransfers(currentAccount, existingObj);\n"
        "                }\n"
        "                boolean forcePurged = unsentOrLocal\n"
        "                        || org.colgram.core.ColgramHookHandler.consumeForcePurged(dialog_id, messageId)\n"
        "                        || (objDid != dialog_id && org.colgram.core.ColgramHookHandler.consumeForcePurged(objDid, messageId))\n"
        "                        || (existingObj != null && existingObj.deleted);",
        "ChatActivity.java exclude mid<=0 and unsent from processDeletedMessages preserve",
    )

    eol_patch_fn(
        chat_act,
        "                for (int i = 0; i < selectedObjectGroup.messages.size(); i++) {\n"
        "                    MessageObject gm = selectedObjectGroup.messages.get(i);\n"
        "                    gm.deleted = true;\n"
        "                    org.colgram.core.ColgramHookHandler.markForcePurged(gm.getDialogId(), gm.getId());\n"
        "                    org.colgram.core.ColgramHookHandler.markForcePurged(dialog_id, gm.getId());\n"
        "                    localIds.add(gm.getId());\n"
        "                }\n"
        "            } else {\n"
        "                selectedObject.deleted = true;\n"
        "                org.colgram.core.ColgramHookHandler.markForcePurged(did, selectedObject.getId());",
        "                for (int i = 0; i < selectedObjectGroup.messages.size(); i++) {\n"
        "                    MessageObject gm = selectedObjectGroup.messages.get(i);\n"
        "                    SendMessagesHelper.colgramCancelMessageTransfers(currentAccount, gm);\n"
        "                    gm.deleted = true;\n"
        "                    org.colgram.core.ColgramHookHandler.markForcePurged(gm.getDialogId(), gm.getId());\n"
        "                    org.colgram.core.ColgramHookHandler.markForcePurged(dialog_id, gm.getId());\n"
        "                    localIds.add(gm.getId());\n"
        "                }\n"
        "            } else {\n"
        "                SendMessagesHelper.colgramCancelMessageTransfers(currentAccount, selectedObject);\n"
        "                selectedObject.deleted = true;\n"
        "                org.colgram.core.ColgramHookHandler.markForcePurged(did, selectedObject.getId());",
        "ChatActivity.java cancel transfers on 9990 local delete",
    )

    # =========================================================================
    # 12. ChatMessageCell.java:
    #     - Primitive LongSparseArray<SparseBooleanArray> for colgramPendingDeleteAnims
    #     - Guard isSending() buttonState=1 / buttonState=4 against anti-deleted
    #       preserved files that are no longer actively sending
    # =========================================================================
    chat_cell = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java")
    eol_patch_fn(
        chat_cell,
        "    private static final java.util.Set<String> colgramPendingDeleteAnims =\n"
        "            java.util.concurrent.ConcurrentHashMap.newKeySet();\n\n"
        "    public static void colgramScheduleDeleteAnim(long dialogId, int messageId) {\n"
        "        if (dialogId != 0 && messageId != 0) {\n"
        "            colgramPendingDeleteAnims.add(dialogId + \":\" + messageId);\n"
        "        }\n"
        "    }",
        "    private static final android.util.LongSparseArray<android.util.SparseBooleanArray> colgramPendingDeleteAnims =\n"
        "            new android.util.LongSparseArray<>();\n\n"
        "    public static void colgramScheduleDeleteAnim(long dialogId, int messageId) {\n"
        "        if (dialogId != 0 && messageId > 0) {\n"
        "            synchronized (colgramPendingDeleteAnims) {\n"
        "                android.util.SparseBooleanArray arr = colgramPendingDeleteAnims.get(dialogId);\n"
        "                if (arr == null) {\n"
        "                    arr = new android.util.SparseBooleanArray();\n"
        "                    colgramPendingDeleteAnims.put(dialogId, arr);\n"
        "                }\n"
        "                arr.put(messageId, true);\n"
        "            }\n"
        "        }\n"
        "    }\n\n"
        "    private static boolean colgramHasPendingDeleteAnim(long dialogId, int messageId) {\n"
        "        synchronized (colgramPendingDeleteAnims) {\n"
        "            android.util.SparseBooleanArray arr = colgramPendingDeleteAnims.get(dialogId);\n"
        "            return arr != null && arr.get(messageId, false);\n"
        "        }\n"
        "    }\n\n"
        "    private static boolean colgramConsumePendingDeleteAnim(long dialogId, int messageId) {\n"
        "        synchronized (colgramPendingDeleteAnims) {\n"
        "            android.util.SparseBooleanArray arr = colgramPendingDeleteAnims.get(dialogId);\n"
        "            if (arr == null || !arr.get(messageId, false)) {\n"
        "                return false;\n"
        "            }\n"
        "            arr.delete(messageId);\n"
        "            if (arr.size() == 0) {\n"
        "                colgramPendingDeleteAnims.remove(dialogId);\n"
        "            }\n"
        "            return true;\n"
        "        }\n"
        "    }",
        "ChatMessageCell.java primitive colgramPendingDeleteAnims container",
    )

    def patch_chat_cell_use(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        eol = "\r\n" if "\r\n" in content else "\n"
        changed = False

        old_chk = "!colgramPendingDeleteAnims.isEmpty() && colgramPendingDeleteAnims.contains(messageObject.getDialogId() + \":\" + messageObject.getId())"
        if old_chk in norm:
            norm = norm.replace(old_chk, "colgramHasPendingDeleteAnim(messageObject.getDialogId(), messageObject.getId())", 1)
            changed = True

        old_rem = (
            "        boolean pendingDeleteAnim = antiDeleteMarked\n"
            "                && !colgramPendingDeleteAnims.isEmpty()\n"
            "                && colgramPendingDeleteAnims.remove(deleteDialogId + \":\" + deleteMessageId);"
        )
        if old_rem in norm:
            norm = norm.replace(
                old_rem,
                "        boolean pendingDeleteAnim = antiDeleteMarked\n"
                "                && colgramConsumePendingDeleteAnim(deleteDialogId, deleteMessageId);",
                1,
            )
            changed = True

        old_send = "currentMessageObject.isOut() && (currentMessageObject.isSending() && !currentMessageObject.isForwarded() || currentMessageObject.isEditing() && currentMessageObject.isEditingMedia())"
        new_send = "currentMessageObject.isOut() && ((currentMessageObject.isSending() && (!colgramMarkedDeleteVisible || SendMessagesHelper.getInstance(currentAccount).isSendingMessage(currentMessageObject.getId()))) && !currentMessageObject.isForwarded() || currentMessageObject.isEditing() && currentMessageObject.isEditingMedia())"
        if old_send in norm and "!colgramMarkedDeleteVisible || SendMessagesHelper" not in norm:
            norm = norm.replace(old_send, new_send)
            changed = True

        if not changed:
            return content
        return norm.replace("\n", eol)

    patch_file_fn(chat_cell, patch_chat_cell_use, "colgramConsumePendingDeleteAnim(deleteDialogId, deleteMessageId)", "ChatMessageCell.java primitive anim lookup and anti-delete spinner guard")

    # =========================================================================
    # 13. FileRefController.java: handle TL_messageEmpty cleanly for preserved
    #     anti-deleted messages instead of silent indefinite retry/failure.
    # =========================================================================
    file_ref = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/messenger/FileRefController.java")

    def patch_file_ref_empty(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        eol = "\r\n" if "\r\n" in content else "\n"
        if "colgramNotifyDeletedUncached" in norm:
            return content
        anchor = "            } else if (response instanceof TLRPC.messages_Messages) {\n                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;"
        if anchor not in norm:
            return content
        guard = (
            "            } else if (response instanceof TLRPC.messages_Messages) {\n"
            "                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;\n"
            "                boolean colgramEmptyResp = res.messages.isEmpty()\n"
            "                        || (res.messages.size() == 1 && res.messages.get(0) instanceof TLRPC.TL_messageEmpty);\n"
            "                if (colgramEmptyResp && requester.args != null && requester.args.length > 0 && requester.args[0] instanceof MessageObject) {\n"
            "                    MessageObject refMsg = (MessageObject) requester.args[0];\n"
            "                    if (org.colgram.core.ColgramHookHandler.isMessageMarkedDeleted(refMsg.getDialogId(), refMsg.getId())) {\n"
            "                        colgramNotifyDeletedUncached();\n"
            "                        sendErrorToObject(requester.args, 0);\n"
            "                        continue;\n"
            "                    }\n"
            "                }"
        )
        helper = (
            "    private static long colgramLastDeletedUncachedToastAt = 0L;\n"
            "    private static void colgramNotifyDeletedUncached() {\n"
            "        AndroidUtilities.runOnUIThread(() -> {\n"
            "            long now = android.os.SystemClock.uptimeMillis();\n"
            "            if (now - colgramLastDeletedUncachedToastAt < 3000L) return;\n"
            "            colgramLastDeletedUncachedToastAt = now;\n"
            "            android.widget.Toast.makeText(ApplicationLoader.applicationContext,\n"
            "                    \"Файл удалён с сервера и не был кэширован локально\", android.widget.Toast.LENGTH_SHORT).show();\n"
            "        });\n"
            "    }\n\n"
        )
        norm = norm.replace(anchor, guard, 1)
        cls_anchor = "public class FileRefController extends BaseController {"
        if cls_anchor in norm:
            norm = norm.replace(cls_anchor, cls_anchor + "\n" + helper, 1)
        return norm.replace("\n", eol)

    patch_file_fn(file_ref, patch_file_ref_empty, "colgramNotifyDeletedUncached", "FileRefController.java TL_messageEmpty anti-deleted notice")

    # =========================================================================
    # 14. AutoDeleteMediaTask.java: enforce >= 24h cleanup interval and never
    #     clamp unset cache_limit == 0 down to 92 MB.
    # =========================================================================
    auto_del = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/messenger/AutoDeleteMediaTask.java")
    eol_patch_fn(
        auto_del,
        "long minIntervalSec = unlimited ? 24 * 60 * 60 : 15 * 60;",
        "long minIntervalSec = 24L * 60L * 60L;",
        "AutoDeleteMediaTask.java minimum 24h cleanup interval",
    )
    eol_patch_fn(
        auto_del,
        "            int maxCacheGb = SharedConfig.getPreferences().getInt(\"cache_limit\", org.colgram.core.ColgramConfig.isUnlimitedMemoryEnabled() ? Integer.MAX_VALUE : 0);\n"
        "            if (maxCacheGb != Integer.MAX_VALUE) {\n"
        "                long maxCacheSize;\n"
        "                if (maxCacheGb <= 0) {",
        "            int maxCacheGb = SharedConfig.getPreferences().getInt(\"cache_limit\", Integer.MAX_VALUE);\n"
        "            if (maxCacheGb != Integer.MAX_VALUE && maxCacheGb > 0) {\n"
        "                long maxCacheSize;\n"
        "                if (false) {",
        "AutoDeleteMediaTask.java do not wipe cache when cache_limit is unset",
    )

    # =========================================================================
    # 15. ApplicationLoader.java, FileLoader.java, SecretMediaViewer.java:
    #     off-UI backup startup check, unique decrypt temp file, off-UI decrypt.
    # =========================================================================
    app_loader = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java")
    eol_patch_fn(
        app_loader,
        "        // COLGRAM_PERSISTENT_BACKUP_RESTORE\n"
        "        try {\n"
        "            org.colgram.core.ColgramPersistentBackup.restoreIfFreshInstallSync(\n"
        "                    applicationContext != null ? applicationContext : this);\n"
        "        } catch (Throwable ignored) {}",
        "        // COLGRAM_PERSISTENT_BACKUP_RESTORE\n"
        "        try {\n"
        "            final Context colgramAppCtx = applicationContext != null ? applicationContext : this;\n"
        "            Utilities.globalQueue.postRunnable(() -> {\n"
        "                try { org.colgram.core.ColgramPersistentBackup.restoreIfFreshInstallSync(colgramAppCtx); } catch (Throwable ignored) {}\n"
        "            });\n"
        "        } catch (Throwable ignored) {}",
        "ApplicationLoader.java async startup backup check off UI thread",
    )

    file_loader = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/messenger/FileLoader.java")

    def patch_file_loader_tmp(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        safe_tmp_file = (
            '        final File tmpFile;\n'
            '        try {\n'
            '            tmpFile = File.createTempFile("colgram_dec_", ".tmp", outDir);\n'
            '        } catch (Throwable e) {\n'
            '            FileLog.e(e);\n'
            '            return null;\n'
            '        }'
        )
        if safe_tmp_file in norm:
            return content
        for old_line in (
            '        File tmpFile = File.createTempFile("colgram_dec_", ".tmp", outDir);',
            '        File tmpFile = new File(outDir, "colgram_dec_" + baseName + ".tmp");',
        ):
            if old_line in norm:
                eol = "\r\n" if "\r\n" in content else "\n"
                return norm.replace(old_line, safe_tmp_file, 1).replace("\n", eol)
        return content

    patch_file_fn(
        file_loader,
        patch_file_loader_tmp,
        'final File tmpFile;',
        "FileLoader.java unique File.createTempFile with IOException guard",
    )

    secret_viewer = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/ui/SecretMediaViewer.java")

    def patch_secret_viewer_async(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        eol = "\r\n" if "\r\n" in content else "\n"
        pass1 = (
            "                    Utilities.globalQueue.postRunnable(() -> {\n"
            "                        final File file = FileLoader.getInstance(currentAccount).colgramResolvePlayableFile(saveOwner);"
        )
        qualified = (
            "                    org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {\n"
            "                        final File file = FileLoader.getInstance(currentAccount).colgramResolvePlayableFile(saveOwner);"
        )
        if qualified in norm:
            return content
        if pass1 in norm:
            return norm.replace(pass1, qualified, 1).replace("\n", eol)
        fresh = (
            "                    File file = FileLoader.getInstance(currentAccount).colgramResolvePlayableFile(currentMessageObject.messageOwner);\n"
            "                    if (file != null && file.exists()) {\n"
            "                        final boolean videoFile = isVideo || currentMessageObject.isVideo();\n"
            "                        org.telegram.messenger.MediaController.saveFile(file.getAbsolutePath(), parentActivity, videoFile ? 1 : 0, null, null, uri -> {\n"
            "                            if (containerView != null) {\n"
            "                                org.telegram.ui.Components.BulletinFactory.createSaveToGalleryBulletin(containerView, videoFile, 0xf9222222, 0xffffffff).show();\n"
            "                            }\n"
            "                        });\n"
            "                    }"
        )
        new_fresh = (
            "                    final TLRPC.Message saveOwner = currentMessageObject.messageOwner;\n"
            "                    final boolean videoFile = isVideo || currentMessageObject.isVideo();\n"
            "                    org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {\n"
            "                        final File file = FileLoader.getInstance(currentAccount).colgramResolvePlayableFile(saveOwner);\n"
            "                        if (file != null && file.exists()) {\n"
            "                            AndroidUtilities.runOnUIThread(() -> {\n"
            "                                if (parentActivity != null) {\n"
            "                                    org.telegram.messenger.MediaController.saveFile(file.getAbsolutePath(), parentActivity, videoFile ? 1 : 0, null, null, uri -> {\n"
            "                                        if (containerView != null) {\n"
            "                                            org.telegram.ui.Components.BulletinFactory.createSaveToGalleryBulletin(containerView, videoFile, 0xf9222222, 0xffffffff).show();\n"
            "                                        }\n"
            "                                    });\n"
            "                                }\n"
            "                            });\n"
            "                        }\n"
            "                    });"
        )
        if fresh in norm:
            return norm.replace(fresh, new_fresh, 1).replace("\n", eol)
        return content

    patch_file_fn(
        secret_viewer,
        patch_secret_viewer_async,
        "org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {",
        "SecretMediaViewer.java resolve encrypted playable file off UI thread",
    )
