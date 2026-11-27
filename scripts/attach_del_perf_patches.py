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
    # 1. ChatAttachAlert.java: native Telegram-style file/music/folder tabs,
    #    zero blind SAF auto-send, zero forced permission nag in sandbox mode,
    #    SAF-to-DocumentLayout composer bridge, and cancelActiveImport on dismiss.
    # =========================================================================
    chat_attach_alert = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java")

    def _replace_branch_slice(norm: str, start_anchor: str, end_anchor: str, marker: str, replacement: str) -> str:
        start = norm.find(start_anchor)
        if start < 0:
            return norm
        end = norm.find(end_anchor, start)
        if end < 0 or (end - start) > 8000:
            return norm
        existing = norm[start:end + len(end_anchor)]
        if marker in existing:
            return norm
        return norm[:start] + replacement + norm[end + len(end_anchor):]

    def patch_chat_attach(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        updated = norm

        # 1a. num == 3 ("Музыка"): remove blind SAF auto-send hijack; open native audio layout without storage permission nag
        updated = _replace_branch_slice(
            updated,
            "                } else if (num == 3) {\n",
            "                    openAudioLayout(true);\n",
            "// colgram-nonblocking-audio (native audio composer)",
            "                } else if (num == 3) {\n"
            "                    // colgram-nonblocking-audio (native audio composer)\n"
            "                    if (!musicEnabled && checkCanRemoveRestrictionsByBoosts()) {\n"
            "                        return;\n"
            "                    }\n"
            "                    if (!org.colgram.core.ColgramConfig.isSandboxStorageEnabled() && activity != null) {\n"
            "                        if (Build.VERSION.SDK_INT >= 33) {\n"
            "                            if (activity.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {\n"
            "                                activity.requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO}, BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE);\n"
            "                                return;\n"
            "                            }\n"
            "                        } else if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {\n"
            "                            AndroidUtilities.findActivity(getContext()).requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE);\n"
            "                            return;\n"
            "                        }\n"
            "                    }\n"
            "                    openAudioLayout(true);\n",
        )

        # 1b. num == 4 ("Файл"): remove blind SAF auto-send hijack; open native DocumentLayout composer with zero permission nag
        updated = _replace_branch_slice(
            updated,
            "                } else if (num == 4) {\n",
            "                    openDocumentsLayout(true);\n",
            "// colgram-nonblocking-doc (native document composer)",
            "                } else if (num == 4) {\n"
            "                    // colgram-nonblocking-doc (native document composer)\n"
            "                    if (!documentsEnabled && checkCanRemoveRestrictionsByBoosts()) {\n"
            "                        return;\n"
            "                    }\n"
            "                    if (!org.colgram.core.ColgramConfig.isSandboxStorageEnabled() && activity != null) {\n"
            "                        if (Build.VERSION.SDK_INT >= 33) {\n"
            "                            if (activity.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED ||\n"
            "                                    activity.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {\n"
            "                                activity.requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO}, BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE);\n"
            "                                return;\n"
            "                            }\n"
            "                        } else if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {\n"
            "                            AndroidUtilities.findActivity(getContext()).requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE);\n"
            "                            return;\n"
            "                        }\n"
            "                    }\n"
            "                    openDocumentsLayout(true);\n",
        )

        # 1c. num == 10 ("Папка Colgram"): open directly inside native DocumentLayout instead of single-file blind send
        old_num10 = (
            "                } else if (num == 10) {\n"
            "                    // Our folder, presented on top of the chat; the sheet dismisses itself because\n"
            "                    // the picker is a full screen now. The listener is one-shot and static: this\n"
            "                    // sheet does not survive the transition, so nothing could hold the callback.\n"
            "                    org.telegram.ui.ColgramFilesActivity colgramPick = new org.telegram.ui.ColgramFilesActivity();\n"
            "                    org.telegram.ui.ColgramFilesActivity.beginPick(colgramPaths -> {\n"
            "                        if (documentsDelegate != null) {\n"
            "                            documentsDelegate.didSelectFiles(colgramPaths, \"\", null, new ArrayList<>(), true, 0, 0, 0, false, 0);\n"
            "                        } else if (baseFragment instanceof ChatActivity) {\n"
            "                            ((ChatActivity) baseFragment).didSelectFiles(colgramPaths, \"\", null, new ArrayList<>(), true, 0, 0, 0, false, 0);\n"
            "                        }\n"
            "                    });\n"
            "                    if (baseFragment != null) {\n"
            "                        baseFragment.presentFragment(colgramPick);\n"
            "                    }\n"
            "                    dismiss();"
        )
        new_num10 = (
            "                } else if (num == 10) {\n"
            "                    if (!documentsEnabled && checkCanRemoveRestrictionsByBoosts()) {\n"
            "                        return;\n"
            "                    }\n"
            "                    openDocumentsLayout(true);\n"
            "                    if (documentLayout != null && documentsEnabled) {\n"
            "                        File colgramSandboxDir = org.colgram.core.ColgramStorageSandbox.getSandboxRootDir(getContext());\n"
            "                        if (colgramSandboxDir != null) {\n"
            "                            documentLayout.colgramOpenDirectory(colgramSandboxDir, \"Папка Colgram\");\n"
            "                        }\n"
            "                    }"
        )
        if old_num10 in updated:
            updated = updated.replace(old_num10, new_num10, 1)

        # 1d. openAttachLayoutForType(LAYOUT_TYPE_MUSIC): skip forced permission check when sandbox storage is enabled
        old_type_music = (
            "        if (layoutType == LAYOUT_TYPE_MUSIC) {\n"
            "            if (!musicEnabled && checkCanRemoveRestrictionsByBoosts()) {\n"
            "                return;\n"
            "            }\n"
            "            final Activity activity = baseFragment != null ? baseFragment.getParentActivity() : null;\n"
            "            if (activity != null) {"
        )
        new_type_music = (
            "        if (layoutType == LAYOUT_TYPE_MUSIC) {\n"
            "            if (!musicEnabled && checkCanRemoveRestrictionsByBoosts()) {\n"
            "                return;\n"
            "            }\n"
            "            final Activity activity = baseFragment != null ? baseFragment.getParentActivity() : null;\n"
            "            if (!org.colgram.core.ColgramConfig.isSandboxStorageEnabled() && activity != null) {"
        )
        if old_type_music in updated:
            updated = updated.replace(old_type_music, new_type_music, 1)

        # 1e. Suppress false red '!' permission error badges on File & Music tabs in sandbox mode
        old_doc_err = "                        err = !checkPhotoAndDocumentsPermission(mContext);"
        new_doc_err = "                        err = !org.colgram.core.ColgramConfig.isSandboxStorageEnabled() && !checkPhotoAndDocumentsPermission(mContext);"
        if old_doc_err in updated:
            updated = updated.replace(old_doc_err, new_doc_err, 1)

        old_music_err = "                        err = !checkMusicPermission(mContext);"
        new_music_err = "                        err = !org.colgram.core.ColgramConfig.isSandboxStorageEnabled() && !checkMusicPermission(mContext);"
        if old_music_err in updated:
            updated = updated.replace(old_music_err, new_music_err, 1)

        # 1f. Route documentLayout's startDocumentSelectActivity() through ColgramFileImport into composer
        old_start_doc = (
            "                @Override\n"
            "                public void startDocumentSelectActivity() {\n"
            "                    if (documentsDelegate != null) {"
        )
        new_start_doc = (
            "                @Override\n"
            "                public void startDocumentSelectActivity() {\n"
            "                    if (documentsDelegate == null && baseFragment instanceof ChatActivity) {\n"
            "                        colgramLaunchSafIntoDocumentComposer(\"*/*\");\n"
            "                        return;\n"
            "                    }\n"
            "                    if (documentsDelegate != null) {"
        )
        if old_start_doc in updated and 'colgramLaunchSafIntoDocumentComposer("*/*");' not in updated:
            updated = updated.replace(old_start_doc, new_start_doc, 1)

        # 1g. Repair any earlier mis-scoped injection inside static AttachAlertLayout.onDestroy()
        for broken_inner in (
            "        public void onDestroy() {\n"
            "        if (!buttonPressed && !sent && (documentLayout == null || !documentLayout.colgramIsSendPressed())) {\n"
            "            org.colgram.core.ColgramFileImport.cancelActiveImport();\n"
            "        }\n\n"
            "        }",
            "        public void onDestroy() {\n"
            "        if (!buttonPressed && !sent && (documentLayout == null || !documentLayout.colgramIsSendPressed())) {\n"
            "            org.colgram.core.ColgramFileImport.cancelActiveImport();\n"
            "        }\n"
            "        }",
        ):
            if broken_inner in updated:
                updated = updated.replace(broken_inner, "        public void onDestroy() {\n\n        }")

        # Cancel active SAF copy on outer ChatAttachAlert destroy / dismiss if user did not press Send
        on_destroy_marker = "// colgram-attach-composer-v2"
        hud_dismiss_lines = (
            "        if (colgramImportHud != null) {\n"
            "            colgramImportHud.dismiss();\n"
            "            colgramImportHud = null;\n"
            "        }\n"
        )
        old_on_destroy = "\n    public void onDestroy() {\n"
        new_on_destroy = (
            "\n    public void onDestroy() {\n"
            f"        {on_destroy_marker}\n"
            + hud_dismiss_lines
            + "        if (!buttonPressed && !sent && (documentLayout == null || !documentLayout.colgramIsSendPressed())) {\n"
            "            org.colgram.core.ColgramFileImport.cancelActiveImport();\n"
            "        }\n"
        )
        if old_on_destroy in updated and on_destroy_marker not in updated:
            if "\n    public void onDestroy() {\n        if (!buttonPressed && !sent" not in updated:
                updated = updated.replace(old_on_destroy, new_on_destroy, 1)
            else:
                updated = updated.replace(
                    old_on_destroy,
                    f"\n    public void onDestroy() {{\n        {on_destroy_marker}\n{hud_dismiss_lines}",
                    1,
                )

        old_dismiss_int = "\n    public void dismissInternal() {\n"
        new_dismiss_int = (
            "\n    public void dismissInternal() {\n"
            + hud_dismiss_lines
            + "        if (!buttonPressed && !sent && (documentLayout == null || !documentLayout.colgramIsSendPressed())) {\n"
            "            org.colgram.core.ColgramFileImport.cancelActiveImport();\n"
            "        }\n"
        )
        if old_dismiss_int in updated and "if (colgramImportHud != null)" not in updated.split(old_dismiss_int, 1)[1][:300]:
            if (old_dismiss_int + "        if (!buttonPressed && !sent") not in updated:
                updated = updated.replace(old_dismiss_int, new_dismiss_int, 1)
            else:
                updated = updated.replace(old_dismiss_int, old_dismiss_int + hud_dismiss_lines, 1)

        # 1h. Add shared SAF-to-DocumentLayout composer bridge methods
        doc_getter_anchor = "    public ChatAttachAlertDocumentLayout getDocumentLayout() {"
        hud_progress_and_deliver = (
            "    private org.telegram.ui.ColgramUiKit.ProgressHudHandle colgramImportHud;\n\n"
            "    private org.colgram.core.ColgramFileImport.ProgressListener colgramCreateImportProgress(\n"
            "            final Context colgramAppCtx, final boolean colgramPremium) {\n"
            "        return new org.colgram.core.ColgramFileImport.ProgressListener() {\n"
            "            @Override\n"
            "            public void onProgress(int done, int total, String currentName, long bytesCopied, long totalBytes) {\n"
            "                long effectiveMax = Math.max(totalBytes, bytesCopied);\n"
            "                if (effectiveMax < org.colgram.core.ColgramFileImport.PROGRESS_THRESHOLD_BYTES) {\n"
            "                    return;\n"
            "                }\n"
            "                if (colgramImportHud == null) {\n"
            "                    android.app.Activity act = baseFragment != null ? baseFragment.getParentActivity() : null;\n"
            "                    Context hudCtx = act != null ? act : colgramAppCtx;\n"
            "                    colgramImportHud = org.telegram.ui.ColgramUiKit.showProgressHud(\n"
            "                            hudCtx, R.drawable.msg_download, currentName, \"\",\n"
            "                            () -> {\n"
            "                                colgramImportHud = null;\n"
            "                                org.colgram.core.ColgramFileImport.cancelActiveImport();\n"
            "                            });\n"
            "                }\n"
            "                if (totalBytes > 0L) {\n"
            "                    colgramImportHud.update(bytesCopied, totalBytes, currentName);\n"
            "                } else {\n"
            "                    colgramImportHud.setIndeterminate(currentName);\n"
            "                }\n"
            "            }\n\n"
            "            @Override\n"
            "            public void onRejected(String fileName, String reason) {\n"
            "                String msg = \"file_too_large\".equals(reason)\n"
            "                        ? (\"Файл слишком большой (\" + (colgramPremium ? \"4 ГБ\" : \"2 ГБ\") + \"): \" + fileName)\n"
            "                        : (\"insufficient_storage\".equals(reason)\n"
            "                                ? (\"Недостаточно места на диске для: \" + fileName)\n"
            "                                : (\"Не удалось подготовить файл: \" + fileName));\n"
            "                if (colgramImportHud != null) {\n"
            "                    colgramImportHud.fail(\"Не удалось подготовить\", msg);\n"
            "                    colgramImportHud = null;\n"
            "                } else {\n"
            "                    org.telegram.ui.ColgramUiKit.showNotice(colgramAppCtx, R.drawable.msg_info, msg, true);\n"
            "                }\n"
            "            }\n"
            "        };\n"
            "    }\n\n"
            "    private void colgramDeliverImportedPaths(java.util.List<String> paths) {\n"
            "        if (colgramImportHud != null) {\n"
            "            if (paths != null && !paths.isEmpty() && !destroyed && !isDismissed()) {\n"
            "                String doneSub = paths.size() == 1\n"
            "                        ? new java.io.File(paths.get(0)).getName()\n"
            "                        : (\"Готово файлов: \" + paths.size());\n"
            "                colgramImportHud.complete(\"Файл готов к отправке\", doneSub);\n"
            "            } else {\n"
            "                colgramImportHud.dismiss();\n"
            "            }\n"
            "            colgramImportHud = null;\n"
            "        }\n"
            "        if (paths == null || paths.isEmpty() || destroyed || isDismissed()) {\n"
            "            return;\n"
            "        }\n"
            "        try {\n"
            "            openDocumentsLayout(false);\n"
            "            if (documentLayout != null) {\n"
            "                if (currentAttachLayout != documentLayout) {\n"
            "                    showLayout(documentLayout, LAYOUT_TYPE_DOCUMENTS, false);\n"
            "                }\n"
            "                documentLayout.colgramAddImportedFiles(paths);\n"
            "            }\n"
            "        } catch (Throwable t) {\n"
            "            FileLog.e(t);\n"
            "        }\n"
            "    }\n\n"
        )
        saf_bridge_methods = (
            hud_progress_and_deliver
            + "    public void colgramLaunchSafIntoDocumentComposer(String mime) {\n"
            "        final Activity activity = baseFragment != null\n"
            "                ? baseFragment.getParentActivity()\n"
            "                : AndroidUtilities.findActivity(getContext());\n"
            "        if (activity == null) {\n"
            "            return;\n"
            "        }\n"
            "        final boolean colgramPremium = org.telegram.messenger.UserConfig.getInstance(currentAccount).isPremium();\n"
            "        final long colgramMaxBytes = colgramPremium\n"
            "                ? org.colgram.core.ColgramFileImport.PREMIUM_MAX_FILE_BYTES\n"
            "                : org.colgram.core.ColgramFileImport.DEFAULT_MAX_FILE_BYTES;\n"
            "        final Context colgramAppCtx = activity.getApplicationContext();\n"
            "        org.colgram.core.ColgramFileImport.pickFiles(\n"
            "                activity, mime, true, colgramMaxBytes,\n"
            "                this::colgramDeliverImportedPaths,\n"
            "                colgramCreateImportProgress(colgramAppCtx, colgramPremium));\n"
            "    }\n\n"
            "    public void colgramStageIntentResultToDocumentComposer(Intent data) {\n"
            "        final Context ctx = baseFragment != null && baseFragment.getParentActivity() != null\n"
            "                ? baseFragment.getParentActivity()\n"
            "                : getContext();\n"
            "        if (ctx == null || data == null) {\n"
            "            return;\n"
            "        }\n"
            "        final boolean colgramPremium = org.telegram.messenger.UserConfig.getInstance(currentAccount).isPremium();\n"
            "        final long colgramMaxBytes = colgramPremium\n"
            "                ? org.colgram.core.ColgramFileImport.PREMIUM_MAX_FILE_BYTES\n"
            "                : org.colgram.core.ColgramFileImport.DEFAULT_MAX_FILE_BYTES;\n"
            "        final Context colgramAppCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;\n"
            "        org.colgram.core.ColgramFileImport.importIntentResult(\n"
            "                ctx, data, colgramMaxBytes,\n"
            "                this::colgramDeliverImportedPaths,\n"
            "                colgramCreateImportProgress(colgramAppCtx, colgramPremium));\n"
            "    }\n\n"
            + doc_getter_anchor
        )
        if doc_getter_anchor in updated and "public void colgramLaunchSafIntoDocumentComposer(" not in updated:
            updated = updated.replace(doc_getter_anchor, saf_bridge_methods, 1)
        elif "public void colgramLaunchSafIntoDocumentComposer(String mime) {" in updated and "private org.telegram.ui.ColgramUiKit.ProgressHudHandle colgramImportHud;" not in updated:
            _old_prog_hdr = "    private org.colgram.core.ColgramFileImport.ProgressListener colgramCreateImportProgress("
            _launch_hdr = "    public void colgramLaunchSafIntoDocumentComposer(String mime) {"
            if _old_prog_hdr in updated:
                _s_idx = updated.index(_old_prog_hdr)
                _e_idx = updated.index(_launch_hdr, _s_idx)
                updated = updated[:_s_idx] + hud_progress_and_deliver + updated[_e_idx:]

        if updated == norm:
            return content
        eol = "\r\n" if "\r\n" in content else "\n"
        return updated.replace("\n", eol)

    patch_file_fn(chat_attach_alert, patch_chat_attach, "org.telegram.ui.ColgramUiKit.ProgressHudHandle", "ChatAttachAlert.java native composer routing for File, Music, and Colgram Folder")

    # =========================================================================
    # 2. ChatAttachAlertDocumentLayout.java:
    #    - Strict < 2GB/4GB limit + scheduleRepeatPeriod forwarding
    #    - Off-UI-thread recent files from Downloads, ColgramStorageSandbox, MediaStore.Downloads
    #    - Top "Выбрать с устройства (Файлы / Загрузки)" + "Папка Colgram" roots
    #    - Gallery media permission prompt only on Gallery tap
    #    - colgramAddImportedFiles() & colgramOpenDirectory() into native composer
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
        "delegate.didSelectFiles(files, caption, captionEntities, fmessages, notify, scheduleDate, 0, effectId, invertMedia, payStars);",
        "delegate.didSelectFiles(files, caption, captionEntities, fmessages, notify, scheduleDate, scheduleRepeatPeriod, effectId, invertMedia, payStars);",
        "ChatAttachAlertDocumentLayout.java forward scheduleRepeatPeriod",
    )

    def patch_doc_layout(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        updated = norm

        # 2a. Safe SAF-null root tap + Gallery media permission prompt on demand
        old_click = (
            "                if (!BuildVars.NO_SCOPED_STORAGE && (item.icon == R.drawable.files_storage || item.icon == R.drawable.files_internal) && !isExternalStorageManager) {\n"
            "                    delegate.startDocumentSelectActivity();\n"
            "                } else if (file == null) {\n"
            "                    if (item.icon == R.drawable.files_gallery) {"
        )
        new_click = (
            "                if (item.icon == R.drawable.files_storage && file == null) {\n"
            "                    if (delegate != null) {\n"
            "                        delegate.startDocumentSelectActivity();\n"
            "                    }\n"
            "                } else if (!BuildVars.NO_SCOPED_STORAGE && (item.icon == R.drawable.files_storage || item.icon == R.drawable.files_internal) && !isExternalStorageManager) {\n"
            "                    if (delegate != null) {\n"
            "                        delegate.startDocumentSelectActivity();\n"
            "                    }\n"
            "                } else if (file == null) {\n"
            "                    if (item.icon == R.drawable.files_gallery) {\n"
            "                        android.app.Activity galleryAct = parentAlert != null && parentAlert.baseFragment != null\n"
            "                                ? parentAlert.baseFragment.getParentActivity()\n"
            "                                : AndroidUtilities.findActivity(getContext());\n"
            "                        if (galleryAct != null) {\n"
            "                            if (Build.VERSION.SDK_INT >= 33) {\n"
            "                                if (galleryAct.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) != android.content.pm.PackageManager.PERMISSION_GRANTED\n"
            "                                        || galleryAct.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {\n"
            "                                    galleryAct.requestPermissions(new String[]{android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO}, org.telegram.ui.BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE);\n"
            "                                    return;\n"
            "                                }\n"
            "                            } else if (Build.VERSION.SDK_INT >= 23 && galleryAct.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {\n"
            "                                galleryAct.requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, org.telegram.ui.BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE);\n"
            "                                return;\n"
            "                            }\n"
            "                        }"
        )
        if old_click in updated and "if (item.icon == R.drawable.files_storage && file == null)" not in updated:
            updated = updated.replace(old_click, new_click, 1)

        # 2b. listRoots(): add top SAF picker + browsable "Папка Colgram" root; hide redundant unbrowsable storage row under Scoped Storage
        old_roots_anchor = (
            "        boolean isExternalStorageManager = false;\n"
            "        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {\n"
            "            isExternalStorageManager = Environment.isExternalStorageManager();\n"
            "        }\n"
            "        // TODO add permission for read all files and uncomment for direct version\n"
            "//        if (!BuildVars.NO_SCOPED_STORAGE && !isExternalStorageManager) {\n"
            "//            ListItem ext = new ListItem();\n"
            "//            ext.title = LocaleController.getString(R.string.InternalStorage);\n"
            "//            ext.icon = R.drawable.files_storage;\n"
            "//            ext.subtitle = LocaleController.getString(R.string.InternalFolderInfo);\n"
            "//            items.add(ext);\n"
            "//        } else {\n"
            "            String defaultPath = Environment.getExternalStorageDirectory().getPath();"
        )
        new_roots_anchor = (
            "        boolean isExternalStorageManager = false;\n"
            "        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {\n"
            "            isExternalStorageManager = Environment.isExternalStorageManager();\n"
            "        }\n"
            "        ListItem safRoot = new ListItem();\n"
            "        safRoot.title = \"Выбрать с устройства (Файлы / Загрузки)\";\n"
            "        safRoot.subtitle = \"Открыть системный проводник Android\";\n"
            "        safRoot.icon = R.drawable.files_storage;\n"
            "        safRoot.file = null;\n"
            "        listAdapter.items.add(safRoot);\n"
            "        try {\n"
            "            File colgramSandboxDir = org.colgram.core.ColgramStorageSandbox.getSandboxRootDir(ApplicationLoader.applicationContext);\n"
            "            if (colgramSandboxDir != null && colgramSandboxDir.exists()) {\n"
            "                ListItem colgramRoot = new ListItem();\n"
            "                colgramRoot.title = \"Папка Colgram\";\n"
            "                colgramRoot.subtitle = \"Песочница и сохранённые файлы\";\n"
            "                colgramRoot.icon = R.drawable.files_folder;\n"
            "                colgramRoot.file = colgramSandboxDir;\n"
            "                listAdapter.items.add(colgramRoot);\n"
            "                paths.add(colgramSandboxDir.getPath());\n"
            "            }\n"
            "        } catch (Throwable ignored) {}\n"
            "        if (BuildVars.NO_SCOPED_STORAGE || isExternalStorageManager) {\n"
            "            String defaultPath = Environment.getExternalStorageDirectory().getPath();"
        )
        if old_roots_anchor in updated and 'safRoot.title = "Выбрать с устройства (Файлы / Загрузки)";' not in updated:
            updated = updated.replace(old_roots_anchor, new_roots_anchor, 1)
            old_roots_close = (
                "            }\n"
                "        //}\n\n"
                "        ListItem fs;"
            )
            new_roots_close = (
                "            }\n"
                "        }\n\n"
                "        ListItem fs;"
            )
            if old_roots_close in updated:
                updated = updated.replace(old_roots_close, new_roots_close, 1)

        # 2c. Off-UI-thread loadRecentFiles + ColgramStorageSandbox + MediaStore.Downloads + Composer bridge methods
        recent_start = "    public void loadRecentFiles() {\n"
        recent_end = "\n    private void sortRecentItems() {"
        rs = updated.find(recent_start)
        re_pos = updated.find(recent_end, rs) if rs >= 0 else -1
        if rs >= 0 and re_pos > rs and "colgramAddImportedFiles" not in updated[rs:re_pos]:
            new_recent_block = (
                "    public boolean colgramIsSendPressed() {\n"
                "        return sendPressed;\n"
                "    }\n\n"
                "    public void colgramOpenDirectory(File dir, String title) {\n"
                "        if (dir == null || !dir.isDirectory() || !dir.canRead()) {\n"
                "            return;\n"
                "        }\n"
                "        HistoryEntry he = new HistoryEntry();\n"
                "        he.dir = currentDir;\n"
                "        he.title = (parentAlert != null && parentAlert.actionBar != null && parentAlert.actionBar.getTitle() != null)\n"
                "                ? parentAlert.actionBar.getTitle()\n"
                "                : LocaleController.getString(R.string.SelectFile);\n"
                "        listAdapter.history.add(he);\n"
                "        if (!listFiles(dir)) {\n"
                "            listAdapter.history.remove(he);\n"
                "            return;\n"
                "        }\n"
                "        if (parentAlert != null && parentAlert.actionBar != null && title != null && !title.isEmpty()) {\n"
                "            parentAlert.actionBar.setTitle(title);\n"
                "        }\n"
                "        updateSearchButton();\n"
                "        updateEmptyView();\n"
                "        if (listView != null && listView.getChildCount() == 0 && layoutManager != null) {\n"
                "            layoutManager.scrollToPositionWithOffset(0, 0);\n"
                "        }\n"
                "    }\n\n"
                "    public void colgramAddImportedFiles(java.util.List<String> paths) {\n"
                "        if (paths == null || paths.isEmpty()) {\n"
                "            return;\n"
                "        }\n"
                "        int acc = parentAlert != null ? parentAlert.currentAccount : UserConfig.selectedAccount;\n"
                "        boolean isPremium = UserConfig.getInstance(acc).isPremium();\n"
                "        boolean addedAny = false;\n"
                "        for (int i = 0; i < paths.size(); i++) {\n"
                "            String path = paths.get(i);\n"
                "            if (path == null || path.isEmpty()) {\n"
                "                continue;\n"
                "            }\n"
                "            if (maxSelectedFiles >= 0 && selectedFiles.size() >= maxSelectedFiles) {\n"
                "                break;\n"
                "            }\n"
                "            File file = new File(path);\n"
                "            if (!file.exists() || !file.isFile() || !file.canRead() || file.length() <= 0L) {\n"
                "                continue;\n"
                "            }\n"
                "            if ((file.length() >= FileLoader.DEFAULT_MAX_FILE_SIZE && !isPremium) || file.length() >= FileLoader.DEFAULT_MAX_FILE_SIZE_PREMIUM) {\n"
                "                org.colgram.core.ColgramFileImport.cleanupRejectedStagedFile(ApplicationLoader.applicationContext, path);\n"
                "                continue;\n"
                "            }\n"
                "            if (isSoundPicker && !isRingtone(file)) {\n"
                "                continue;\n"
                "            }\n"
                "            ListItem item = colgramCreateFileListItem(file);\n"
                "            if (canSelectOnlyImageFiles && item.thumb == null) {\n"
                "                continue;\n"
                "            }\n"
                "            String absPath = file.getAbsolutePath();\n"
                "            for (int r = listAdapter.recentItems.size() - 1; r >= 0; r--) {\n"
                "                ListItem existing = listAdapter.recentItems.get(r);\n"
                "                if (existing.file != null && absPath.equals(existing.file.getAbsolutePath())) {\n"
                "                    listAdapter.recentItems.remove(r);\n"
                "                }\n"
                "            }\n"
                "            listAdapter.recentItems.add(0, item);\n"
                "            if (!selectedFiles.containsKey(absPath)) {\n"
                "                selectedFiles.put(absPath, item);\n"
                "                selectedFilesOrder.add(absPath);\n"
                "            }\n"
                "            addedAny = true;\n"
                "        }\n"
                "        if (!addedAny) {\n"
                "            return;\n"
                "        }\n"
                "        if (searching && parentAlert != null && parentAlert.actionBar != null) {\n"
                "            parentAlert.actionBar.closeSearchField();\n"
                "        }\n"
                "        if (!listAdapter.history.isEmpty() || currentDir != null) {\n"
                "            listAdapter.history.clear();\n"
                "            currentDir = null;\n"
                "            listRoots();\n"
                "            if (parentAlert != null && parentAlert.actionBar != null) {\n"
                "                parentAlert.actionBar.setTitle(LocaleController.getString(R.string.SelectFile));\n"
                "            }\n"
                "        }\n"
                "        hasFiles = true;\n"
                "        updateSearchButton();\n"
                "        listAdapter.notifyDataSetChanged();\n"
                "        if (parentAlert != null) {\n"
                "            parentAlert.updateCountButton(1);\n"
                "        }\n"
                "    }\n\n"
                "    private static ListItem colgramCreateFileListItem(File file) {\n"
                "        ListItem item = new ListItem();\n"
                "        String fname = file.getName();\n"
                "        item.title = fname;\n"
                "        item.file = file;\n"
                "        String[] sp = fname.split(\"\\\\.\");\n"
                "        item.ext = sp.length > 1 ? sp[sp.length - 1] : \"?\";\n"
                "        item.subtitle = AndroidUtilities.formatFileSize(file.length());\n"
                "        String lower = fname.toLowerCase(Locale.US);\n"
                "        if (lower.endsWith(\".jpg\") || lower.endsWith(\".png\") || lower.endsWith(\".gif\") || lower.endsWith(\".jpeg\") || lower.endsWith(\".webp\")) {\n"
                "            item.thumb = file.getAbsolutePath();\n"
                "        }\n"
                "        return item;\n"
                "    }\n\n"
                "    private static boolean colgramIsSkippedRecentName(String name) {\n"
                "        return name == null || name.isEmpty() || name.startsWith(\".\") || \".nomedia\".equalsIgnoreCase(name) || name.startsWith(\".cgtmp\") || name.endsWith(\".cgtmp\");\n"
                "    }\n\n"
                "    private static void colgramCollectRecentFromDir(File rootDir, ArrayList<ListItem> out, HashSet<String> seen, File storiesDir, int maxCount) {\n"
                "        if (rootDir == null || !rootDir.isDirectory() || !rootDir.canRead() || out.size() >= maxCount) {\n"
                "            return;\n"
                "        }\n"
                "        File[] files = rootDir.listFiles();\n"
                "        if (files == null) {\n"
                "            return;\n"
                "        }\n"
                "        for (int a = 0; a < files.length && out.size() < maxCount; a++) {\n"
                "            File file = files[a];\n"
                "            if (file == null || file.equals(storiesDir) || colgramIsSkippedRecentName(file.getName())) {\n"
                "                continue;\n"
                "            }\n"
                "            if (file.isDirectory()) {\n"
                "                if (\"Telegram\".equals(file.getName())) {\n"
                "                    colgramCollectRecentFromDir(file, out, seen, storiesDir, maxCount);\n"
                "                }\n"
                "                continue;\n"
                "            }\n"
                "            if (!file.isFile() || !file.canRead() || file.length() <= 0L) {\n"
                "                continue;\n"
                "            }\n"
                "            String abs = file.getAbsolutePath();\n"
                "            if (seen.add(abs)) {\n"
                "                out.add(colgramCreateFileListItem(file));\n"
                "            }\n"
                "        }\n"
                "    }\n\n"
                "    private void colgramMergeRecentItems(ArrayList<ListItem> loaded) {\n"
                "        if (loaded == null || loaded.isEmpty()) {\n"
                "            return;\n"
                "        }\n"
                "        HashSet<String> existing = new HashSet<>();\n"
                "        for (int i = 0; i < listAdapter.recentItems.size(); i++) {\n"
                "            ListItem cur = listAdapter.recentItems.get(i);\n"
                "            if (cur != null && cur.file != null) {\n"
                "                existing.add(cur.file.getAbsolutePath());\n"
                "            }\n"
                "        }\n"
                "        for (int i = 0; i < loaded.size(); i++) {\n"
                "            ListItem item = loaded.get(i);\n"
                "            if (item != null && item.file != null && existing.add(item.file.getAbsolutePath())) {\n"
                "                listAdapter.recentItems.add(item);\n"
                "            }\n"
                "        }\n"
                "        sortRecentItems();\n"
                "        if (!listAdapter.recentItems.isEmpty()) {\n"
                "            hasFiles = true;\n"
                "        }\n"
                "        updateSearchButton();\n"
                "        listAdapter.notifyDataSetChanged();\n"
                "    }\n\n"
                "    public void loadRecentFiles() {\n"
                "        final boolean soundMode = isSoundPicker;\n"
                "        Utilities.globalQueue.postRunnable(() -> {\n"
                "            final ArrayList<ListItem> loaded = new ArrayList<>();\n"
                "            try {\n"
                "                Context appCtx = ApplicationLoader.applicationContext;\n"
                "                if (appCtx == null) {\n"
                "                    return;\n"
                "                }\n"
                "                if (soundMode) {\n"
                "                    if (Build.VERSION.SDK_INT >= 23) {\n"
                "                        String perm = Build.VERSION.SDK_INT >= 33 ? android.Manifest.permission.READ_MEDIA_AUDIO : android.Manifest.permission.READ_EXTERNAL_STORAGE;\n"
                "                        if (appCtx.checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {\n"
                "                            return;\n"
                "                        }\n"
                "                    }\n"
                "                    String[] projection = {\n"
                "                            MediaStore.Audio.Media._ID,\n"
                "                            MediaStore.Audio.Media.DATA,\n"
                "                            MediaStore.Audio.Media.DURATION,\n"
                "                            MediaStore.Audio.Media.SIZE,\n"
                "                            MediaStore.Audio.Media.MIME_TYPE\n"
                "                    };\n"
                "                    try (Cursor cursor = appCtx.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, MediaStore.Audio.Media.IS_MUSIC + \" != 0\", null, MediaStore.Audio.Media.DATE_ADDED + \" DESC\")) {\n"
                "                        while (cursor != null && cursor.moveToNext()) {\n"
                "                            String dataPath = cursor.getString(1);\n"
                "                            if (dataPath == null || dataPath.isEmpty()) continue;\n"
                "                            File file = new File(dataPath);\n"
                "                            if (!file.isFile() || !file.canRead()) continue;\n"
                "                            long duration = cursor.getLong(2);\n"
                "                            long fileSize = cursor.getLong(3);\n"
                "                            String mimeType = cursor.getString(4);\n"
                "                            if (duration > MessagesController.getInstance(UserConfig.selectedAccount).ringtoneDurationMax * 1000 || fileSize > MessagesController.getInstance(UserConfig.selectedAccount).ringtoneSizeMax || (!TextUtils.isEmpty(mimeType) && !(\"audio/mpeg\".equals(mimeType) || !\"audio/mpeg4\".equals(mimeType)))) {\n"
                "                                continue;\n"
                "                            }\n"
                "                            loaded.add(colgramCreateFileListItem(file));\n"
                "                        }\n"
                "                    } catch (Throwable e) {\n"
                "                        FileLog.e(e);\n"
                "                    }\n"
                "                } else {\n"
                "                    final HashSet<String> seen = new HashSet<>();\n"
                "                    final File storiesDir = FileLoader.checkDirectory(FileLoader.MEDIA_DIR_STORIES);\n"
                "                    try {\n"
                "                        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);\n"
                "                        colgramCollectRecentFromDir(downloadsDir, loaded, seen, storiesDir, 40);\n"
                "                    } catch (Throwable e) {\n"
                "                        FileLog.e(e);\n"
                "                    }\n"
                "                    try {\n"
                "                        colgramCollectRecentFromDir(org.colgram.core.ColgramStorageSandbox.getSandboxedDirectory(appCtx, 3), loaded, seen, storiesDir, 80);\n"
                "                        colgramCollectRecentFromDir(org.colgram.core.ColgramStorageSandbox.getSandboxedDirectory(appCtx, 5), loaded, seen, storiesDir, 100);\n"
                "                        colgramCollectRecentFromDir(appCtx.getExternalFilesDir(\"colgram_inbox\"), loaded, seen, storiesDir, 120);\n"
                "                        if (appCtx.getFilesDir() != null) {\n"
                "                            colgramCollectRecentFromDir(new File(appCtx.getFilesDir(), \"colgram_inbox\"), loaded, seen, storiesDir, 130);\n"
                "                        }\n"
                "                    } catch (Throwable ignored) {}\n"
                "                    if (Build.VERSION.SDK_INT >= 29) {\n"
                "                        try {\n"
                "                            String[] dlProj = {\n"
                "                                    MediaStore.MediaColumns._ID,\n"
                "                                    MediaStore.MediaColumns.DISPLAY_NAME,\n"
                "                                    MediaStore.MediaColumns.SIZE,\n"
                "                                    MediaStore.MediaColumns.DATE_MODIFIED,\n"
                "                                    MediaStore.MediaColumns.DATA\n"
                "                            };\n"
                "                            try (Cursor c = appCtx.getContentResolver().query(\n"
                "                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,\n"
                "                                    dlProj, null, null,\n"
                "                                    MediaStore.MediaColumns.DATE_MODIFIED + \" DESC\")) {\n"
                "                                int count = 0;\n"
                "                                while (c != null && c.moveToNext() && count < 60) {\n"
                "                                    String p = c.getString(4);\n"
                "                                    if (p == null || p.isEmpty()) continue;\n"
                "                                    File f = new File(p);\n"
                "                                    if (colgramIsSkippedRecentName(f.getName()) || !f.isFile() || !f.canRead() || f.length() <= 0L) {\n"
                "                                        continue;\n"
                "                                    }\n"
                "                                    if (seen.add(f.getAbsolutePath())) {\n"
                "                                        loaded.add(colgramCreateFileListItem(f));\n"
                "                                        count++;\n"
                "                                    }\n"
                "                                }\n"
                "                            }\n"
                "                        } catch (Throwable ignored) {}\n"
                "                    }\n"
                "                }\n"
                "            } catch (Throwable e) {\n"
                "                FileLog.e(e);\n"
                "            }\n"
                "            if (!loaded.isEmpty()) {\n"
                "                AndroidUtilities.runOnUIThread(() -> colgramMergeRecentItems(loaded));\n"
                "            }\n"
                "        });\n"
                "    }\n\n"
                "    private void checkDirectory(File rootDir) {\n"
                "        if (rootDir == null || !rootDir.isDirectory() || !rootDir.canRead()) {\n"
                "            return;\n"
                "        }\n"
                "        File[] files = rootDir.listFiles();\n"
                "        File storiesDir = FileLoader.checkDirectory(FileLoader.MEDIA_DIR_STORIES);\n"
                "        if (files != null) {\n"
                "            for (int a = 0; a < files.length; a++) {\n"
                "                File file = files[a];\n"
                "                if (file == null || colgramIsSkippedRecentName(file.getName()) || file.equals(storiesDir)) {\n"
                "                    continue;\n"
                "                }\n"
                "                if (file.isDirectory() && file.getName().equals(\"Telegram\")) {\n"
                "                    checkDirectory(file);\n"
                "                    continue;\n"
                "                }\n"
                "                if (file.isFile() && file.canRead()) {\n"
                "                    listAdapter.recentItems.add(colgramCreateFileListItem(file));\n"
                "                }\n"
                "            }\n"
                "        }\n"
                "    }"
            )
            updated = updated[:rs] + new_recent_block + updated[re_pos:]

        if updated == norm:
            return content
        eol = "\r\n" if "\r\n" in content else "\n"
        return updated.replace("\n", eol)

    patch_file_fn(
        doc_layout,
        patch_doc_layout,
        "colgramAddImportedFiles(",
        "ChatAttachAlertDocumentLayout.java SAF/Colgram roots, off-UI recent files, and composer bridge",
    )

    # =========================================================================
    # 2b. ChatAttachAlertAudioLayout.java:
    #     - Guard MediaStore.Audio query when audio permission is ungranted
    #     - Add "Выбрать аудиофайл с устройства" SAF-to-composer button
    # =========================================================================
    audio_layout = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlertAudioLayout.java")

    def patch_audio_layout(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        updated = norm

        old_consts = "    private int LOAD_MORE_SEARCH_PROFILE = 3;"
        new_consts = (
            "    private int LOAD_MORE_SEARCH_PROFILE = 3;\n"
            "    private static final int COLGRAM_PICK_AUDIO_SAF = 4099;"
        )
        if old_consts in updated and "COLGRAM_PICK_AUDIO_SAF" not in updated:
            updated = updated.replace(old_consts, new_consts, 1)

        old_fill = (
            "        items.add(UItem.asSpace(-100, dp(1)));\n"
            "        int firstIndex = items.size();"
        )
        new_fill = (
            "        items.add(UItem.asSpace(-100, dp(1)));\n"
            "        if (TextUtils.isEmpty(query) && parentAlert != null && !parentAlert.isStoryAudioPicker && !parentAlert.isPollAttach) {\n"
            "            adapter.whiteSectionStart();\n"
            "            items.add(UItem.asButton(COLGRAM_PICK_AUDIO_SAF, R.drawable.msg_openin, \"Выбрать аудиофайл с устройства\").accent());\n"
            "            adapter.whiteSectionEnd();\n"
            "            items.add(UItem.asShadow(-101, null));\n"
            "        }\n"
            "        int firstIndex = items.size();"
        )
        if old_fill in updated and "COLGRAM_PICK_AUDIO_SAF, R.drawable.msg_openin" not in updated:
            updated = updated.replace(old_fill, new_fill, 1)

        old_click = (
            "    private void onItemClick(UItem item, View view, int position, float x, float y) {\n"
            "        if (item != null && item.id == LOAD_MORE_SEARCH_PROFILE) {"
        )
        new_click = (
            "    private void onItemClick(UItem item, View view, int position, float x, float y) {\n"
            "        if (item != null && item.id == COLGRAM_PICK_AUDIO_SAF) {\n"
            "            if (parentAlert != null) {\n"
            "                parentAlert.colgramLaunchSafIntoDocumentComposer(\"audio/*\");\n"
            "            }\n"
            "            return;\n"
            "        }\n"
            "        if (item != null && item.id == LOAD_MORE_SEARCH_PROFILE) {"
        )
        if old_click in updated and "item.id == COLGRAM_PICK_AUDIO_SAF" not in updated:
            updated = updated.replace(old_click, new_click, 1)

        old_load = (
            "    private void loadAudio() {\n"
            "        loadingAudio = true;\n"
            "        Utilities.globalQueue.postRunnable(() -> {"
        )
        new_load = (
            "    private void loadAudio() {\n"
            "        if (android.os.Build.VERSION.SDK_INT >= 23) {\n"
            "            String perm = android.os.Build.VERSION.SDK_INT >= 33\n"
            "                    ? android.Manifest.permission.READ_MEDIA_AUDIO\n"
            "                    : android.Manifest.permission.READ_EXTERNAL_STORAGE;\n"
            "            if (ApplicationLoader.applicationContext == null\n"
            "                    || ApplicationLoader.applicationContext.checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {\n"
            "                loadingAudio = false;\n"
            "                if (listView != null) {\n"
            "                    updateWithSavingScroll();\n"
            "                }\n"
            "                return;\n"
            "            }\n"
            "        }\n"
            "        loadingAudio = true;\n"
            "        Utilities.globalQueue.postRunnable(() -> {"
        )
        if old_load in updated and "Manifest.permission.READ_MEDIA_AUDIO" not in updated:
            updated = updated.replace(old_load, new_load, 1)

        if updated == norm:
            return content
        eol = "\r\n" if "\r\n" in content else "\n"
        return updated.replace("\n", eol)

    patch_file_fn(
        audio_layout,
        patch_audio_layout,
        "COLGRAM_PICK_AUDIO_SAF",
        "ChatAttachAlertAudioLayout.java permission guard and SAF audio picker button",
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
    # 5. FileUploadOperation.java: fix 64-bit checkpoint byte offset arithmetic,
    #    make state volatile, notify didFailedUploadingFile at most once, and
    #    close stream strictly on stageQueue to prevent cancel race.
    # =========================================================================
    file_upload_op = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/messenger/FileUploadOperation.java")
    eol_patch_fn(
        file_upload_op,
        "final long currentRequestBytesOffset = currentRequestPartNum + currentRequestBytes;",
        "final long currentRequestBytesOffset = (long) currentRequestPartNum * (long) uploadChunkSize + currentRequestBytes;",
        "FileUploadOperation.java 64-bit chunk offset multiplication",
    )

    def patch_file_upload_cancel(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        eol = "\r\n" if "\r\n" in content else "\n"
        changed = False

        old_state = "    private int state;"
        new_state = (
            "    private volatile int state;\n"
            "    private final java.util.concurrent.atomic.AtomicBoolean colgramFailedNotified = new java.util.concurrent.atomic.AtomicBoolean(false);\n\n"
            "    private void colgramNotifyFailedOnce() {\n"
            "        if (delegate != null && colgramFailedNotified.compareAndSet(false, true)) {\n"
            "            delegate.didFailedUploadingFile(this);\n"
            "        }\n"
            "    }"
        )
        if old_state in norm and "colgramFailedNotified" not in norm:
            norm = norm.replace(old_state, new_state, 1)
            changed = True

        old_cancel = (
            "    public void cancel() {\n"
            "        if (state == 3) {\n"
            "            return;\n"
            "        }\n"
            "        state = 2;\n"
            "        Utilities.stageQueue.postRunnable(() -> {\n"
            "            for (int a = 0; a < requestTokens.size(); a++) {\n"
            "                ConnectionsManager.getInstance(currentAccount).cancelRequest(requestTokens.valueAt(a), true);\n"
            "            }\n"
            "        });\n"
            "        AutoDeleteMediaTask.unlockFile(uploadingFilePath);\n"
            "        delegate.didFailedUploadingFile(this);\n"
            "        cleanup();\n"
            "    }"
        )
        new_cancel = (
            "    public void cancel() {\n"
            "        if (state == 3 || state == 2) {\n"
            "            return;\n"
            "        }\n"
            "        state = 2;\n"
            "        Utilities.stageQueue.postRunnable(() -> {\n"
            "            for (int a = 0; a < requestTokens.size(); a++) {\n"
            "                ConnectionsManager.getInstance(currentAccount).cancelRequest(requestTokens.valueAt(a), true);\n"
            "            }\n"
            "            requestTokens.clear();\n"
            "            cleanup();\n"
            "        });\n"
            "        AutoDeleteMediaTask.unlockFile(uploadingFilePath);\n"
            "        colgramNotifyFailedOnce();\n"
            "    }"
        )
        if old_cancel in norm:
            norm = norm.replace(old_cancel, new_cancel, 1)
            changed = True

        old_catch = (
            "        } catch (Exception e) {\n"
            "            FileLog.e(e);\n"
            "            state = 4;\n"
            "            delegate.didFailedUploadingFile(this);\n"
            "            cleanup();\n"
            "            return;\n"
            "        }"
        )
        new_catch = (
            "        } catch (Exception e) {\n"
            "            FileLog.e(e);\n"
            "            if (state != 2 && state != 3) {\n"
            "                state = 4;\n"
            "                colgramNotifyFailedOnce();\n"
            "            }\n"
            "            cleanup();\n"
            "            return;\n"
            "        }"
        )
        if old_catch in norm:
            norm = norm.replace(old_catch, new_catch, 1)
            changed = True

        old_rpc_fail = (
            "            } else {\n"
            "                state = 4;\n"
            "                delegate.didFailedUploadingFile(FileUploadOperation.this);\n"
            "                cleanup();\n"
            "            }"
        )
        new_rpc_fail = (
            "            } else {\n"
            "                if (state != 2 && state != 3) {\n"
            "                    state = 4;\n"
            "                    colgramNotifyFailedOnce();\n"
            "                }\n"
            "                cleanup();\n"
            "            }"
        )
        if old_rpc_fail in norm:
            norm = norm.replace(old_rpc_fail, new_rpc_fail, 1)
            changed = True

        if not changed:
            return content
        return norm.replace("\n", eol)

    patch_file_fn(
        file_upload_op,
        patch_file_upload_cancel,
        "        colgramNotifyFailedOnce();",
        "FileUploadOperation.java volatile state, single failed callback, and stageQueue cleanup on cancel",
    )

    # =========================================================================
    # 6. SendMessagesHelper.java:
    #    - Non-reentrant colgramCancelMessageTransfers (catch Exception only, never Error)
    #    - Guard cancelSendingMessage with colgramCancellingIds & immediate send_state update
    #    - Skip redundant rememberPendingReply(..., null) on normal text sends
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
            "        } catch (Exception ignored) {}\n"
            "    }\n\n"
        )
        clean_call = (
            "            if (helper != null && helper.colgramShouldCancelSending(msg)) {\n"
            "                helper.cancelSendingMessage(msg); // non-reentrant\n"
            "            }\n"
        )
        stale_call = (
            "            if (helper != null && helper.colgramShouldCancelSending(msg)) {\n"
            "                int mid = msg.getId();\n"
            "                helper.colgramCancellingIds.add(mid);\n"
            "                try {\n"
            "                    helper.cancelSendingMessage(msg);\n"
            "                } finally {\n"
            "                    helper.colgramCancellingIds.remove(mid);\n"
            "                }\n"
            "            }\n"
        )
        full_block = (
            "    private static final java.util.concurrent.ExecutorService colgramDocPrepExecutor =\n"
            "            java.util.concurrent.Executors.newFixedThreadPool(2);\n"
            "    private final java.util.HashSet<Integer> colgramCancellingIds = new java.util.HashSet<>();\n"
            "    private volatile boolean colgramHasAnyPendingReply = false;\n"
            "    private final androidx.collection.LongSparseArray<Boolean> colgramPendingReplyPeers = new androidx.collection.LongSparseArray<>();\n\n"
            "    private void colgramRememberPendingReplyIfNeeded(long peer, MessageObject replyToMsg, boolean isDotCmd) {\n"
            "        if (isDotCmd) {\n"
            "            synchronized (colgramPendingReplyPeers) {\n"
            "                colgramPendingReplyPeers.put(peer, Boolean.TRUE);\n"
            "                colgramHasAnyPendingReply = true;\n"
            "            }\n"
            "            ColgramNativeColemailerBridge.rememberPendingReply(currentAccount, peer, replyToMsg);\n"
            "            return;\n"
            "        }\n"
            "        if (!colgramHasAnyPendingReply) {\n"
            "            return;\n"
            "        }\n"
            "        boolean hadPeer = false;\n"
            "        synchronized (colgramPendingReplyPeers) {\n"
            "            int idx = colgramPendingReplyPeers.indexOfKey(peer);\n"
            "            if (idx >= 0) {\n"
            "                colgramPendingReplyPeers.removeAt(idx);\n"
            "                hadPeer = true;\n"
            "                colgramHasAnyPendingReply = colgramPendingReplyPeers.size() > 0;\n"
            "            }\n"
            "        }\n"
            "        if (hadPeer) {\n"
            "            ColgramNativeColemailerBridge.rememberPendingReply(currentAccount, peer, null);\n"
            "        }\n"
            "    }\n\n"
            "    private boolean colgramHasDelayedMessage(int mid) {\n"
            "        if (delayedMessages == null || delayedMessages.isEmpty()) {\n"
            "            return false;\n"
            "        }\n"
            "        for (HashMap.Entry<String, ArrayList<DelayedMessage>> entry : delayedMessages.entrySet()) {\n"
            "            ArrayList<DelayedMessage> messages = entry.getValue();\n"
            "            if (messages == null) continue;\n"
            "            for (int a = 0; a < messages.size(); a++) {\n"
            "                DelayedMessage dm = messages.get(a);\n"
            "                if (dm == null) continue;\n"
            "                if (dm.type == 4 && dm.messageObjects != null) {\n"
            "                    for (int b = 0; b < dm.messageObjects.size(); b++) {\n"
            "                        MessageObject mo = dm.messageObjects.get(b);\n"
            "                        if (mo != null && mo.getId() == mid) return true;\n"
            "                    }\n"
            "                } else if (dm.obj != null && dm.obj.getId() == mid) {\n"
            "                    return true;\n"
            "                }\n"
            "            }\n"
            "        }\n"
            "        return false;\n"
            "    }\n\n"
            "    private boolean colgramShouldCancelSending(MessageObject msg) {\n"
            "        if (msg == null || msg.messageOwner == null) {\n"
            "            return false;\n"
            "        }\n"
            "        int mid = msg.getId();\n"
            "        if (colgramCancellingIds.contains(mid)) {\n"
            "            return false;\n"
            "        }\n"
            "        if (!(msg.isSending() || msg.isEditing())) {\n"
            "            return false;\n"
            "        }\n"
            "        return isSendingMessage(mid) || uploadMessages.indexOfKey(mid) >= 0 || colgramHasDelayedMessage(mid);\n"
            "    }\n\n"
            + downloads_helper
            + "    public static void colgramCancelMessageTransfers(int account, MessageObject msg) {\n"
            "        if (msg == null) {\n"
            "            return;\n"
            "        }\n"
            "        try {\n"
            "            SendMessagesHelper helper = getInstance(account);\n"
            + clean_call +
            "        } catch (Exception ignored) {}\n"
            "        colgramCancelFileDownloads(account, msg);\n"
            "    }\n\n"
            "    public void cancelSendingMessage(MessageObject object) {"
        )
        if "helper.cancelSendingMessage(msg); // non-reentrant" in norm:
            return content
        if "private boolean colgramShouldCancelSending(" in norm:
            if stale_call in norm:
                return norm.replace(stale_call, clean_call, 1).replace("\n", eol)
            return content
        end_anchor = "    public void cancelSendingMessage(MessageObject object) {"
        old_start = norm.find("    private static final java.util.concurrent.ExecutorService colgramDocPrepExecutor =")
        old_end = norm.find(end_anchor, old_start) if old_start >= 0 else -1
        if old_start >= 0 and old_end > old_start:
            return (norm[:old_start] + full_block + norm[old_end + len(end_anchor):]).replace("\n", eol)
        if end_anchor in norm:
            return norm.replace(end_anchor, full_block, 1).replace("\n", eol)
        return content

    patch_file_fn(
        send_helper,
        patch_send_helper_cancel,
        "helper.cancelSendingMessage(msg); // non-reentrant",
        "SendMessagesHelper.java non-reentrant colgramCancelMessageTransfers and pending-reply fast helpers",
    )

    def patch_send_helper_cancel_array(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        eol = "\r\n" if "\r\n" in content else "\n"
        if "ArrayList<Integer> colgramGuardIds" in norm:
            return content
        old_head = (
            "    public void cancelSendingMessage(ArrayList<MessageObject> objects) {\n"
            "        ArrayList<String> keysToRemove = new ArrayList<>();\n"
            "        ArrayList<DelayedMessage> checkReadyToSendGroups = new ArrayList<>();\n"
            "        ArrayList<Integer> messageIds = new ArrayList<>();\n"
            "        boolean enc = false;\n"
            "        boolean scheduled = false;\n"
            "        long dialogId = 0;\n"
            "        int topicId = 0;\n"
            "        for (int c = 0; c < objects.size(); c++) {\n"
            "            MessageObject object = objects.get(c);\n"
            "            if (object.scheduled) {"
        )
        new_head = (
            "    public void cancelSendingMessage(ArrayList<MessageObject> objects) {\n"
            "        if (objects == null || objects.isEmpty()) {\n"
            "            return;\n"
            "        }\n"
            "        ArrayList<Integer> colgramGuardIds = new ArrayList<>(objects.size());\n"
            "        try {\n"
            "        ArrayList<String> keysToRemove = new ArrayList<>();\n"
            "        ArrayList<DelayedMessage> checkReadyToSendGroups = new ArrayList<>();\n"
            "        ArrayList<Integer> messageIds = new ArrayList<>();\n"
            "        boolean enc = false;\n"
            "        boolean scheduled = false;\n"
            "        long dialogId = 0;\n"
            "        int topicId = 0;\n"
            "        for (int c = 0; c < objects.size(); c++) {\n"
            "            MessageObject object = objects.get(c);\n"
            "            if (object == null) {\n"
            "                continue;\n"
            "            }\n"
            "            int colgramObjId = object.getId();\n"
            "            if (!colgramCancellingIds.add(colgramObjId)) {\n"
            "                continue;\n"
            "            }\n"
            "            colgramGuardIds.add(colgramObjId);\n"
            "            if (object.messageOwner != null && !object.isEditing()) {\n"
            "                object.messageOwner.send_state = MessageObject.MESSAGE_SEND_STATE_SEND_ERROR;\n"
            "            }\n"
            "            if (object.scheduled) {"
        )
        old_tail = (
            "        for (int a = 0; a < keysToRemove.size(); a++) {\n"
            "            String key = keysToRemove.get(a);\n"
            "            if (key.startsWith(\"http\")) {\n"
            "                ImageLoader.getInstance().cancelLoadHttpFile(key);\n"
            "            } else {\n"
            "                getFileLoader().cancelFileUpload(key, enc);\n"
            "            }\n"
            "            delayedMessages.remove(key);\n"
            "        }\n"
            "        for (int a = 0, N = checkReadyToSendGroups.size(); a < N; a++) {\n"
            "            sendReadyToSendGroup(checkReadyToSendGroups.get(a), false, true);\n"
            "        }\n"
            "        if (objects.size() == 1 && objects.get(0).isEditing() && objects.get(0).previousMedia != null) {\n"
            "            revertEditingMessageObject(objects.get(0));\n"
            "        } else if (objects.size() == 1 && objects.get(0).isEditing() && objects.get(0).isPoll()) {\n"
            "            // do not delete polls if lose previousMedia field\n"
            "        } else {\n"
            "            int mode = 0;\n"
            "            if (!objects.isEmpty() && objects.get(0).isWelcomeMessage()) {\n"
            "                mode = ChatActivity.MODE_WELCOME_MESSAGES;\n"
            "            } else if (!objects.isEmpty() && objects.get(0).isQuickReply()) {\n"
            "                mode = ChatActivity.MODE_QUICK_REPLIES;\n"
            "            } else if (scheduled) {\n"
            "                mode = ChatActivity.MODE_SCHEDULED;\n"
            "            }\n"
            "            getMessagesController().deleteMessages(messageIds, null, null, dialogId, topicId, false, mode);\n"
            "        }\n"
            "    }"
        )
        new_tail = (
            "        if (colgramGuardIds.isEmpty()) {\n"
            "            return;\n"
            "        }\n"
            "        for (int a = 0; a < keysToRemove.size(); a++) {\n"
            "            String key = keysToRemove.get(a);\n"
            "            if (key.startsWith(\"http\")) {\n"
            "                ImageLoader.getInstance().cancelLoadHttpFile(key);\n"
            "            } else {\n"
            "                getFileLoader().cancelFileUpload(key, enc);\n"
            "            }\n"
            "            delayedMessages.remove(key);\n"
            "        }\n"
            "        for (int a = 0, N = checkReadyToSendGroups.size(); a < N; a++) {\n"
            "            sendReadyToSendGroup(checkReadyToSendGroups.get(a), false, true);\n"
            "        }\n"
            "        if (objects.size() == 1 && objects.get(0).isEditing() && objects.get(0).previousMedia != null) {\n"
            "            revertEditingMessageObject(objects.get(0));\n"
            "        } else if (objects.size() == 1 && objects.get(0).isEditing() && objects.get(0).isPoll()) {\n"
            "            // do not delete polls if lose previousMedia field\n"
            "        } else {\n"
            "            int mode = 0;\n"
            "            if (!objects.isEmpty() && objects.get(0).isWelcomeMessage()) {\n"
            "                mode = ChatActivity.MODE_WELCOME_MESSAGES;\n"
            "            } else if (!objects.isEmpty() && objects.get(0).isQuickReply()) {\n"
            "                mode = ChatActivity.MODE_QUICK_REPLIES;\n"
            "            } else if (scheduled) {\n"
            "                mode = ChatActivity.MODE_SCHEDULED;\n"
            "            }\n"
            "            if (!messageIds.isEmpty()) {\n"
            "                getMessagesController().deleteMessages(messageIds, null, null, dialogId, topicId, false, mode);\n"
            "            }\n"
            "        }\n"
            "        } finally {\n"
            "            for (int cgi = 0; cgi < colgramGuardIds.size(); cgi++) {\n"
            "                colgramCancellingIds.remove(colgramGuardIds.get(cgi));\n"
            "            }\n"
            "        }\n"
            "    }"
        )
        pos = norm.find(old_head)
        if pos < 0:
            return content
        tail_pos = norm.find(old_tail, pos)
        if tail_pos < 0 or (tail_pos - pos) > 8000:
            return content
        norm = norm[:pos] + new_head + norm[pos + len(old_head):tail_pos] + new_tail + norm[tail_pos + len(old_tail):]
        return norm.replace("\n", eol)

    patch_file_fn(
        send_helper,
        patch_send_helper_cancel_array,
        "ArrayList<Integer> colgramGuardIds",
        "SendMessagesHelper.java re-entrancy guard and send_state clear in cancelSendingMessage",
    )

    eol_patch_fn(
        send_helper,
        "        if (sendMessageParams != null && sendMessageParams.message != null) {\n"
        "            int replyId = sendMessageParams.replyToMsg != null ? sendMessageParams.replyToMsg.getId() : 0;\n"
        "            long colgramReplySender = 0L;\n"
        "            if (sendMessageParams.replyToMsg != null) {\n"
        "                colgramReplySender = sendMessageParams.replyToMsg.getSenderId();\n"
        "                if (colgramReplySender == 0L && sendMessageParams.replyToMsg.messageOwner != null\n"
        "                        && sendMessageParams.replyToMsg.messageOwner.from_id != null) {\n"
        "                    colgramReplySender = MessageObject.getPeerId(sendMessageParams.replyToMsg.messageOwner.from_id);\n"
        "                }\n"
        "            }\n"
        "            if (sendMessageParams.message.trim().startsWith(\".\")) {\n"
        "                ColgramNativeColemailerBridge.rememberPendingReply(currentAccount, sendMessageParams.peer, sendMessageParams.replyToMsg);\n"
        "            } else {\n"
        "                ColgramNativeColemailerBridge.rememberPendingReply(currentAccount, sendMessageParams.peer, null);\n"
        "            }\n"
        "            if (org.colgram.core.ColgramHookHandler.hookOnSendMessage(currentAccount, sendMessageParams.peer, replyId, sendMessageParams.message, colgramReplySender)) {\n"
        "                return;\n"
        "            }\n"
        "        }",
        "        if (sendMessageParams != null && sendMessageParams.message != null) {\n"
        "            if (org.colgram.core.ColgramHookHandler.startsWithCommandPrefix(sendMessageParams.message)) {\n"
        "                int replyId = sendMessageParams.replyToMsg != null ? sendMessageParams.replyToMsg.getId() : 0;\n"
        "                long colgramReplySender = 0L;\n"
        "                if (sendMessageParams.replyToMsg != null) {\n"
        "                    colgramReplySender = sendMessageParams.replyToMsg.getSenderId();\n"
        "                    if (colgramReplySender == 0L && sendMessageParams.replyToMsg.messageOwner != null\n"
        "                            && sendMessageParams.replyToMsg.messageOwner.from_id != null) {\n"
        "                        colgramReplySender = MessageObject.getPeerId(sendMessageParams.replyToMsg.messageOwner.from_id);\n"
        "                    }\n"
        "                }\n"
        "                colgramRememberPendingReplyIfNeeded(sendMessageParams.peer, sendMessageParams.replyToMsg,\n"
        "                        org.colgram.core.ColgramHookHandler.startsWithDotCommand(sendMessageParams.message));\n"
        "                if (org.colgram.core.ColgramHookHandler.hookOnSendMessage(currentAccount, sendMessageParams.peer, replyId, sendMessageParams.message, colgramReplySender)) {\n"
        "                    return;\n"
        "                }\n"
        "            } else if (colgramHasAnyPendingReply) {\n"
        "                colgramRememberPendingReplyIfNeeded(sendMessageParams.peer, null, false);\n"
        "            }\n"
        "        }",
        "SendMessagesHelper.java zero-alloc command check and skip redundant rememberPendingReply null",
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

    eol_patch_fn(
        msgs_ctrl,
        "                if (!alreadyDeletedPurge.isEmpty()) {\n"
        "                    if (channelId == 0) {\n"
        "                        for (int a = 0; a < alreadyDeletedPurge.size(); a++) {\n"
        "                            Integer id = alreadyDeletedPurge.get(a);\n"
        "                            MessageObject obj = dialogMessagesByIds.get(id);\n"
        "                            if (obj != null) {\n"
        "                                obj.deleted = true;\n"
        "                            }\n"
        "                        }\n"
        "                    } else {\n"
        "                        markDialogMessageAsDeleted(dialogId, alreadyDeletedPurge);\n"
        "                    }\n"
        "                    org.colgram.core.ColgramHookHandler.purgeMessagesCompletely(targetDid, alreadyDeletedPurge);\n"
        "                    if (dialogId != targetDid) {\n"
        "                        org.colgram.core.ColgramHookHandler.purgeMessagesCompletely(dialogId, alreadyDeletedPurge);\n"
        "                    }\n"
        "                    getMessagesStorage().markMessagesAsDeletedLocal(dialogId, alreadyDeletedPurge, true, forAll, 0, topicId);\n"
        "                    getMessagesStorage().updateDialogsWithDeletedMessages(dialogId, channelId, alreadyDeletedPurge, null);\n"
        "                }",
        "                if (!alreadyDeletedPurge.isEmpty()) {\n"
        "                    boolean colgramHasPositivePurge = false;\n"
        "                    for (int a = 0; a < alreadyDeletedPurge.size(); a++) {\n"
        "                        Integer id = alreadyDeletedPurge.get(a);\n"
        "                        if (id != null && id > 0) {\n"
        "                            colgramHasPositivePurge = true;\n"
        "                            break;\n"
        "                        }\n"
        "                    }\n"
        "                    if (channelId == 0) {\n"
        "                        for (int a = 0; a < alreadyDeletedPurge.size(); a++) {\n"
        "                            Integer id = alreadyDeletedPurge.get(a);\n"
        "                            MessageObject obj = dialogMessagesByIds.get(id);\n"
        "                            if (obj != null) {\n"
        "                                obj.deleted = true;\n"
        "                            }\n"
        "                        }\n"
        "                    } else {\n"
        "                        markDialogMessageAsDeleted(dialogId, alreadyDeletedPurge);\n"
        "                    }\n"
        "                    if (colgramHasPositivePurge) {\n"
        "                        org.colgram.core.ColgramHookHandler.purgeMessagesCompletely(targetDid, alreadyDeletedPurge);\n"
        "                        if (dialogId != targetDid) {\n"
        "                            org.colgram.core.ColgramHookHandler.purgeMessagesCompletely(dialogId, alreadyDeletedPurge);\n"
        "                        }\n"
        "                    }\n"
        "                    getMessagesStorage().markMessagesAsDeletedLocal(dialogId, alreadyDeletedPurge, true, forAll, 0, topicId);\n"
        "                    getMessagesStorage().updateDialogsWithDeletedMessages(dialogId, channelId, alreadyDeletedPurge, null);\n"
        "                }",
        "MessagesController.java skip UI-thread SQLite purge when alreadyDeletedPurge has only mid<=0",
    )

    eol_patch_fn(
        msgs_ctrl,
        "        if (cacheOnly) {\n"
        "            return;\n"
        "        }\n\n"
        "        long newTaskId;",
        "        if (cacheOnly) {\n"
        "            return;\n"
        "        }\n"
        "        if (taskId == 0 && (toSend == null || toSend.isEmpty())) {\n"
        "            if (randoms != null && encryptedChat != null && !randoms.isEmpty()) {\n"
        "                getSecretChatHelper().sendMessagesDeleteMessage(encryptedChat, randoms, null);\n"
        "            }\n"
        "            return;\n"
        "        }\n\n"
        "        long newTaskId;",
        "MessagesController.java guard deleteMessages against empty toSend RPC and pending_tasks",
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

    eol_patch_fn(
        msgs_storage,
        "    private void colgramDispatchPlugins(ArrayList<TLRPC.Message> messages) {\n"
        "        try {\n"
        "            if (messages == null || messages.isEmpty()) {\n"
        "                return;\n"
        "            }",
        "    private void colgramDispatchPlugins(ArrayList<TLRPC.Message> messages) {\n"
        "        try {\n"
        "            if (messages == null || messages.isEmpty()) {\n"
        "                return;\n"
        "            }\n"
        "            boolean colgramHasPositive = false;\n"
        "            for (int ci = 0, cn = messages.size(); ci < cn; ci++) {\n"
        "                TLRPC.Message cm = messages.get(ci);\n"
        "                if (cm != null && cm.id > 0) {\n"
        "                    colgramHasPositive = true;\n"
        "                    break;\n"
        "                }\n"
        "            }\n"
        "            if (!colgramHasPositive) {\n"
        "                return;\n"
        "            }",
        "MessagesStorage.java fast-skip mid<=0 in colgramDispatchPlugins",
    )

    def patch_msgs_storage_put_messages_off_ui(content: str) -> str:
        eol = "\r\n" if "\r\n" in content else "\n"
        norm = content.replace("\r\n", "\n")
        if "colgramPreFilterSpamMentions(messages);" in norm:
            return content
        old_block = (
            "    public void putMessages(ArrayList<TLRPC.Message> messages, boolean withTransaction, boolean useQueue, boolean doNotUpdateDialogDate, int downloadMask, int mode, long threadMessageId) {\n"
            "        if (mode == ChatActivity.MODE_DEFAULT) {\n"
            "            colgramDispatchPlugins(messages);\n"
            "        }\n"
            "        putMessages(messages, withTransaction, useQueue, doNotUpdateDialogDate, downloadMask, false, mode, threadMessageId);\n"
            "    }\n\n"
            "    public void putMessages(ArrayList<TLRPC.Message> messages, boolean withTransaction, boolean useQueue, boolean doNotUpdateDialogDate, int downloadMask, boolean ifNoLastMessage, int mode, long threadMessageId) {\n"
            "        if (messages == null || messages.isEmpty()) {\n"
            "            return;\n"
            "        }\n"
            "        if (useQueue) {\n"
            "            storageQueue.postRunnable(() -> putMessagesInternal(messages, withTransaction, doNotUpdateDialogDate, downloadMask, ifNoLastMessage, mode, threadMessageId));\n"
            "        } else {\n"
            "            putMessagesInternal(messages, withTransaction, doNotUpdateDialogDate, downloadMask, ifNoLastMessage, mode, threadMessageId);\n"
            "        }\n"
            "    }"
        )
        new_block = (
            "    public void putMessages(ArrayList<TLRPC.Message> messages, boolean withTransaction, boolean useQueue, boolean doNotUpdateDialogDate, int downloadMask, int mode, long threadMessageId) {\n"
            "        putMessages(messages, withTransaction, useQueue, doNotUpdateDialogDate, downloadMask, false, mode, threadMessageId);\n"
            "    }\n\n"
            "    public void putMessages(ArrayList<TLRPC.Message> messages, boolean withTransaction, boolean useQueue, boolean doNotUpdateDialogDate, int downloadMask, boolean ifNoLastMessage, int mode, long threadMessageId) {\n"
            "        if (messages == null || messages.isEmpty()) {\n"
            "            return;\n"
            "        }\n"
            "        if (useQueue) {\n"
            "            storageQueue.postRunnable(() -> {\n"
            "                if (mode == ChatActivity.MODE_DEFAULT) {\n"
            "                    colgramDispatchPlugins(messages);\n"
            "                }\n"
            "                putMessagesInternal(messages, withTransaction, doNotUpdateDialogDate, downloadMask, ifNoLastMessage, mode, threadMessageId);\n"
            "            });\n"
            "        } else {\n"
            "            if (mode == ChatActivity.MODE_DEFAULT) {\n"
            "                colgramDispatchPlugins(messages);\n"
            "            }\n"
            "            putMessagesInternal(messages, withTransaction, doNotUpdateDialogDate, downloadMask, ifNoLastMessage, mode, threadMessageId);\n"
            "        }\n"
            "    }"
        )
        if old_block in norm:
            return norm.replace(old_block, new_block, 1).replace("\n", eol)
        return content

    patch_file_fn(
        msgs_storage,
        patch_msgs_storage_put_messages_off_ui,
        "colgramPreFilterSpamMentions(messages);",
        "MessagesStorage.java move colgramDispatchPlugins off UI thread into storageQueue",
    )

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
    #     - Route requestCode == 21 through ColgramFileImport into DocumentLayout composer
    #     - Preserve waitingForLoad/endReached on 9s firstLoadMessages timeout
    #     - Exclude mid<=0 / unsent from processDeletedMessages preserve
    #     - Cancel active transfers on option 9990 local delete
    # =========================================================================
    chat_act = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
    eol_patch_fn(
        chat_act,
        "            } else if (requestCode == 21) {\n"
        "                if (data == null) {\n"
        "                    showAttachmentError();\n"
        "                    return;\n"
        "                }\n"
        "                if (editingMessageObject == null && chatMode == MODE_SCHEDULED) {",
        "            } else if (requestCode == 21) {\n"
        "                if (data == null) {\n"
        "                    showAttachmentError();\n"
        "                    return;\n"
        "                }\n"
        "                if (chatAttachAlert != null && chatAttachAlert.isShowing()) {\n"
        "                    chatAttachAlert.colgramStageIntentResultToDocumentComposer(data);\n"
        "                    return;\n"
        "                }\n"
        "                if (editingMessageObject == null && chatMode == MODE_SCHEDULED) {",
        "ChatActivity.java route requestCode 21 through ColgramFileImport into DocumentLayout composer",
    )
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

    def patch_chat_process_deleted(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        eol = "\r\n" if "\r\n" in content else "\n"
        desired_marker = "SendMessagesHelper.colgramCancelFileDownloads(currentAccount, existingObj);"
        if desired_marker in norm:
            return content
        old_pass1 = (
            "                if (unsentOrLocal && existingObj != null) {\n"
            "                    SendMessagesHelper.colgramCancelMessageTransfers(currentAccount, existingObj);\n"
            "                }"
        )
        new_downloads = (
            "                if (unsentOrLocal && existingObj != null) {\n"
            "                    SendMessagesHelper.colgramCancelFileDownloads(currentAccount, existingObj);\n"
            "                }"
        )
        if old_pass1 in norm:
            return norm.replace(old_pass1, new_downloads, 1).replace("\n", eol)
        fresh_anchor = (
            "                boolean forcePurged = org.colgram.core.ColgramHookHandler.consumeForcePurged(dialog_id, messageId)\n"
            "                        || (objDid != dialog_id && org.colgram.core.ColgramHookHandler.consumeForcePurged(objDid, messageId))\n"
            "                        || (existingObj != null && existingObj.deleted);"
        )
        new_fresh = (
            "                boolean unsentOrLocal = messageId <= 0 || (existingObj != null && (existingObj.isSending()\n"
            "                        || (existingObj.messageOwner != null && existingObj.messageOwner.send_state != 0 && existingObj.messageOwner.send_state != 3)));\n"
            "                if (unsentOrLocal && existingObj != null) {\n"
            "                    SendMessagesHelper.colgramCancelFileDownloads(currentAccount, existingObj);\n"
            "                }\n"
            "                boolean forcePurged = unsentOrLocal\n"
            "                        || org.colgram.core.ColgramHookHandler.consumeForcePurged(dialog_id, messageId)\n"
            "                        || (objDid != dialog_id && org.colgram.core.ColgramHookHandler.consumeForcePurged(objDid, messageId))\n"
            "                        || (existingObj != null && existingObj.deleted);"
        )
        if fresh_anchor in norm:
            return norm.replace(fresh_anchor, new_fresh, 1).replace("\n", eol)
        return content

    patch_file_fn(
        chat_act,
        patch_chat_process_deleted,
        "SendMessagesHelper.colgramCancelFileDownloads(currentAccount, existingObj);",
        "ChatActivity.java non-reentrant colgramCancelFileDownloads in processDeletedMessages",
    )

    eol_patch_fn(
        chat_act,
        "            case OPTION_CANCEL_SENDING: {\n"
        "                if (selectedObject.isEditing() || selectedObject.isSending() && selectedObjectGroup == null) {\n"
        "                    getSendMessagesHelper().cancelSendingMessage(selectedObject);\n"
        "                } else if (selectedObject.isSending() && selectedObjectGroup != null) {\n"
        "                    for (int a = 0; a < selectedObjectGroup.messages.size(); a++) {\n"
        "                        getSendMessagesHelper().cancelSendingMessage(new ArrayList<>(selectedObjectGroup.messages));\n"
        "                    }\n"
        "                }\n"
        "                break;\n"
        "            }",
        "            case OPTION_CANCEL_SENDING: {\n"
        "                if (selectedObject.isEditing() || selectedObject.isSending() && selectedObjectGroup == null) {\n"
        "                    getSendMessagesHelper().cancelSendingMessage(selectedObject);\n"
        "                } else if (selectedObject.isSending() && selectedObjectGroup != null && !selectedObjectGroup.messages.isEmpty()) {\n"
        "                    getSendMessagesHelper().cancelSendingMessage(new ArrayList<>(selectedObjectGroup.messages));\n"
        "                }\n"
        "                break;\n"
        "            }",
        "ChatActivity.java fix OPTION_CANCEL_SENDING grouped-messages N-times cancel loop",
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
    def patch_auto_delete_cache_limit(content: str) -> str:
        eol = "\r\n" if "\r\n" in content else "\n"
        norm = content.replace("\r\n", "\n")
        if "ColgramStorageCapEnforcer.enforceIfNeeded(" in norm:
            return content
        old_block = (
            "            int maxCacheGb = SharedConfig.getPreferences().getInt(\"cache_limit\", org.colgram.core.ColgramConfig.isUnlimitedMemoryEnabled() ? Integer.MAX_VALUE : 0);\n"
            "            if (maxCacheGb != Integer.MAX_VALUE) {\n"
            "                long maxCacheSize;\n"
            "                if (maxCacheGb <= 0) {"
        )
        new_block = (
            "            int maxCacheGb = SharedConfig.getPreferences().getInt(\"cache_limit\", Integer.MAX_VALUE);\n"
            "            if (maxCacheGb != Integer.MAX_VALUE && maxCacheGb > 0) {\n"
            "                long maxCacheSize;\n"
            "                if (false) {"
        )
        if old_block in norm:
            return norm.replace(old_block, new_block, 1).replace("\n", eol)
        return content

    patch_file_fn(
        auto_del,
        patch_auto_delete_cache_limit,
        "ColgramStorageCapEnforcer.enforceIfNeeded(",
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

    def patch_file_loader_upload_cancel(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        eol = "\r\n" if "\r\n" in content else "\n"
        changed = False

        old_fields = (
            "    private int currentUploadOperationsCount = 0;\n"
            "    private int currentUploadSmallOperationsCount = 0;"
        )
        new_fields = (
            "    private int currentUploadOperationsCount = 0;\n"
            "    private int currentUploadSmallOperationsCount = 0;\n"
            "    private final java.util.HashSet<FileUploadOperation> colgramSkippedCancelOps = new java.util.HashSet<>();"
        )
        if old_fields in norm and "colgramSkippedCancelOps" not in norm:
            norm = norm.replace(old_fields, new_fields, 1)
            changed = True

        old_cancel = (
            "            if (operation != null) {\n"
            "                uploadOperationPathsEnc.remove(location);\n"
            "                uploadOperationQueue.remove(operation);\n"
            "                uploadSmallOperationQueue.remove(operation);\n"
            "                operation.cancel();\n"
            "            }"
        )
        new_cancel = (
            "            if (operation != null) {\n"
            "                if (enc) {\n"
            "                    uploadOperationPathsEnc.remove(location);\n"
            "                } else {\n"
            "                    uploadOperationPaths.remove(location);\n"
            "                }\n"
            "                boolean colgramWasQueued = uploadOperationQueue.remove(operation) | uploadSmallOperationQueue.remove(operation);\n"
            "                if (colgramWasQueued) {\n"
            "                    colgramSkippedCancelOps.add(operation);\n"
            "                }\n"
            "                operation.cancel();\n"
            "            }"
        )
        if old_cancel in norm:
            norm = norm.replace(old_cancel, new_cancel, 1)
            changed = True

        old_finish_counts = (
            "                        if (small) {\n"
            "                            currentUploadSmallOperationsCount--;\n"
            "                            if (currentUploadSmallOperationsCount < 1) {\n"
            "                                FileUploadOperation operation12 = uploadSmallOperationQueue.poll();\n"
            "                                if (operation12 != null) {\n"
            "                                    currentUploadSmallOperationsCount++;\n"
            "                                    operation12.start();\n"
            "                                }\n"
            "                            }\n"
            "                        } else {\n"
            "                            currentUploadOperationsCount--;\n"
            "                            if (currentUploadOperationsCount < 1) {\n"
            "                                FileUploadOperation operation12 = uploadOperationQueue.poll();\n"
            "                                if (operation12 != null) {\n"
            "                                    currentUploadOperationsCount++;\n"
            "                                    operation12.start();\n"
            "                                }\n"
            "                            }\n"
            "                        }"
        )
        new_finish_counts = (
            "                        if (small) {\n"
            "                            currentUploadSmallOperationsCount = Math.max(0, currentUploadSmallOperationsCount - 1);\n"
            "                            if (currentUploadSmallOperationsCount < 1) {\n"
            "                                FileUploadOperation operation12 = uploadSmallOperationQueue.poll();\n"
            "                                if (operation12 != null) {\n"
            "                                    currentUploadSmallOperationsCount++;\n"
            "                                    operation12.start();\n"
            "                                }\n"
            "                            }\n"
            "                        } else {\n"
            "                            currentUploadOperationsCount = Math.max(0, currentUploadOperationsCount - 1);\n"
            "                            if (currentUploadOperationsCount < 1) {\n"
            "                                FileUploadOperation operation12 = uploadOperationQueue.poll();\n"
            "                                if (operation12 != null) {\n"
            "                                    currentUploadOperationsCount++;\n"
            "                                    operation12.start();\n"
            "                                }\n"
            "                            }\n"
            "                        }"
        )
        if old_finish_counts in norm:
            norm = norm.replace(old_finish_counts, new_finish_counts, 1)
            changed = True

        old_fail_counts = (
            "                        if (small) {\n"
            "                            currentUploadSmallOperationsCount--;\n"
            "                            if (currentUploadSmallOperationsCount < 1) {\n"
            "                                FileUploadOperation operation1 = uploadSmallOperationQueue.poll();\n"
            "                                if (operation1 != null) {\n"
            "                                    currentUploadSmallOperationsCount++;\n"
            "                                    operation1.start();\n"
            "                                }\n"
            "                            }\n"
            "                        } else {\n"
            "                            currentUploadOperationsCount--;\n"
            "                            if (currentUploadOperationsCount < 1) {\n"
            "                                FileUploadOperation operation1 = uploadOperationQueue.poll();\n"
            "                                if (operation1 != null) {\n"
            "                                    currentUploadOperationsCount++;\n"
            "                                    operation1.start();\n"
            "                                }\n"
            "                            }\n"
            "                        }"
        )
        new_fail_counts = (
            "                        if (!colgramSkippedCancelOps.remove(operation)) {\n"
            "                            if (small) {\n"
            "                                currentUploadSmallOperationsCount = Math.max(0, currentUploadSmallOperationsCount - 1);\n"
            "                                if (currentUploadSmallOperationsCount < 1) {\n"
            "                                    FileUploadOperation operation1 = uploadSmallOperationQueue.poll();\n"
            "                                    if (operation1 != null) {\n"
            "                                        currentUploadSmallOperationsCount++;\n"
            "                                        operation1.start();\n"
            "                                    }\n"
            "                                }\n"
            "                            } else {\n"
            "                                currentUploadOperationsCount = Math.max(0, currentUploadOperationsCount - 1);\n"
            "                                if (currentUploadOperationsCount < 1) {\n"
            "                                    FileUploadOperation operation1 = uploadOperationQueue.poll();\n"
            "                                    if (operation1 != null) {\n"
            "                                        currentUploadOperationsCount++;\n"
            "                                        operation1.start();\n"
            "                                    }\n"
            "                                }\n"
            "                            }\n"
            "                        }"
        )
        if old_fail_counts in norm:
            norm = norm.replace(old_fail_counts, new_fail_counts, 1)
            changed = True

        if not changed:
            return content
        return norm.replace("\n", eol)

    patch_file_fn(
        file_loader,
        patch_file_loader_upload_cancel,
        "if (!colgramSkippedCancelOps.remove(operation)) {",
        "FileLoader.java cancelFileUpload map fix and upload counter underflow guard",
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

    # =========================================================================
    # 16. NotificationCenter.java:
    #     Wrap postNotificationNameInternal observer dispatch in try/finally so
    #     broadcasting can never leak >= 1 and lock out all app observers.
    # =========================================================================
    notif_center = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/messenger/NotificationCenter.java")
    eol_patch_fn(
        notif_center,
        "        broadcasting++;\n"
        "        ArrayList<NotificationCenterDelegate> objects = observers.get(id);\n"
        "        if (objects != null && !objects.isEmpty()) {\n"
        "            for (int a = 0; a < objects.size(); a++) {\n"
        "                NotificationCenterDelegate obj = objects.get(a);\n"
        "                obj.didReceivedNotification(id, currentAccount, args);\n"
        "            }\n"
        "        }\n"
        "        broadcasting--;\n"
        "        if (broadcasting == 0) {\n"
        "            if (removeAfterBroadcast.size() != 0) {\n"
        "                for (int a = 0; a < removeAfterBroadcast.size(); a++) {\n"
        "                    int key = removeAfterBroadcast.keyAt(a);\n"
        "                    ArrayList<NotificationCenterDelegate> arrayList = removeAfterBroadcast.get(key);\n"
        "                    for (int b = 0; b < arrayList.size(); b++) {\n"
        "                        removeObserver(arrayList.get(b), key);\n"
        "                    }\n"
        "                }\n"
        "                removeAfterBroadcast.clear();\n"
        "            }\n"
        "            if (addAfterBroadcast.size() != 0) {\n"
        "                for (int a = 0; a < addAfterBroadcast.size(); a++) {\n"
        "                    int key = addAfterBroadcast.keyAt(a);\n"
        "                    ArrayList<NotificationCenterDelegate> arrayList = addAfterBroadcast.get(key);\n"
        "                    for (int b = 0; b < arrayList.size(); b++) {\n"
        "                        addObserverInternal(arrayList.get(b), key);\n"
        "                    }\n"
        "                }\n"
        "                addAfterBroadcast.clear();\n"
        "            }\n"
        "        }",
        "        broadcasting++;\n"
        "        try {\n"
        "            ArrayList<NotificationCenterDelegate> objects = observers.get(id);\n"
        "            if (objects != null && !objects.isEmpty()) {\n"
        "                for (int a = 0; a < objects.size(); a++) {\n"
        "                    NotificationCenterDelegate obj = objects.get(a);\n"
        "                    obj.didReceivedNotification(id, currentAccount, args);\n"
        "                }\n"
        "            }\n"
        "        } finally {\n"
        "            broadcasting--;\n"
        "            if (broadcasting == 0) {\n"
        "                if (removeAfterBroadcast.size() != 0) {\n"
        "                    for (int a = 0; a < removeAfterBroadcast.size(); a++) {\n"
        "                        int key = removeAfterBroadcast.keyAt(a);\n"
        "                        ArrayList<NotificationCenterDelegate> arrayList = removeAfterBroadcast.get(key);\n"
        "                        for (int b = 0; b < arrayList.size(); b++) {\n"
        "                            removeObserver(arrayList.get(b), key);\n"
        "                        }\n"
        "                    }\n"
        "                    removeAfterBroadcast.clear();\n"
        "                }\n"
        "                if (addAfterBroadcast.size() != 0) {\n"
        "                    for (int a = 0; a < addAfterBroadcast.size(); a++) {\n"
        "                        int key = addAfterBroadcast.keyAt(a);\n"
        "                        ArrayList<NotificationCenterDelegate> arrayList = addAfterBroadcast.get(key);\n"
        "                        for (int b = 0; b < arrayList.size(); b++) {\n"
        "                            addObserverInternal(arrayList.get(b), key);\n"
        "                        }\n"
        "                    }\n"
        "                    addAfterBroadcast.clear();\n"
        "                }\n"
        "            }\n"
        "        }",
        "NotificationCenter.java try/finally guard for broadcasting counter",
    )

    # =========================================================================
    # 17. UserConfig.java:
    #     Gate persistent auto-backup scheduling on withFile so saveConfig(false)
    #     on every sent message does not schedule backup I/O.
    # =========================================================================
    user_cfg = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java")
    eol_patch_fn(
        user_cfg,
        "                    editor.apply();\n"
        "                    if (currentUser != null && ApplicationLoader.applicationContext != null) {\n"
        "                        try {\n"
        "                            org.colgram.core.ColgramPersistentBackup.scheduleAutoBackup(ApplicationLoader.applicationContext);\n"
        "                        } catch (Throwable ignored) {}\n"
        "                    }",
        "                    editor.apply();\n"
        "                    if (withFile && currentUser != null && ApplicationLoader.applicationContext != null) {\n"
        "                        try {\n"
        "                            org.colgram.core.ColgramPersistentBackup.scheduleAutoBackup(ApplicationLoader.applicationContext);\n"
        "                        } catch (Throwable ignored) {}\n"
        "                    }",
        "UserConfig.java gate scheduleAutoBackup on withFile",
    )

    # =========================================================================
    # 18. Theme.java + ChatActivity.java (P0-3):
    #     Eliminate uncached double colgramGuardImpl in getColor(key, provider)
    #     and add per-instance ThemeDelegate cache when chatTheme != null.
    # =========================================================================
    theme_java = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/Theme.java")
    eol_patch_fn(
        theme_java,
        "    public static int getNonAnimatedColor(int key) {\n"
        "        return getColor(key, null, true);\n"
        "    }",
        "    public static int getNonAnimatedColor(int key) {\n"
        "        return getColor(key, null, true);\n"
        "    }\n\n"
        "    public static boolean colgramCanUseUiColorCache() {\n"
        "        return colgramIsMainThread() && !colgramUiInGuard;\n"
        "    }",
        "Theme.java expose colgramCanUseUiColorCache helper",
    )
    eol_patch_fn(
        theme_java,
        "    public interface ResourcesProvider {\n\n        int getColor(int key);\n",
        "    public interface ResourcesProvider {\n\n        int getColor(int key);\n\n"
        "        default boolean colgramDelegatesToGlobalTheme() {\n"
        "            return false;\n"
        "        }\n\n"
        "        default boolean colgramReturnsGuardedColor() {\n"
        "            return false;\n"
        "        }\n",
        "Theme.java ResourcesProvider colgramDelegatesToGlobalTheme & colgramReturnsGuardedColor defaults",
    )
    eol_patch_fn(
        theme_java,
        "    public static int getColor(int key, ResourcesProvider provider) {\n"
        "        if (provider == null) {\n"
        "            return getColor(key);\n"
        "        }\n"
        "        if (org.colgram.core.ColgramConfig.isCyberThemeEnabled()) {\n"
        "            // Delegate to the shared palette so this path and the\n"
        "            // getColor(key, isDefault, ignoreAnimation) wrapper cannot disagree.\n"
        "            int colgramCyberColor = colgramCyberOverride(key);\n"
        "            if (colgramCyberColor != 0) {\n"
        "                return colgramGuard(key, colgramCyberColor, provider);\n"
        "            }\n"
        "        }\n"
        "        int resolved = provider.getColor(key);\n"
        "        if (resolved == 0 && (provider.isDark() || colgramHasDarkSurface())) {\n"
        "            if (colgramNightHasKey(key)) {\n"
        "                resolved = colgramNightColor(key);\n"
        "            } else if (colgramIsReadableKey(key)) {\n"
        "                resolved = 0xffffffff;\n"
        "            } else if (colgramLooksLikeBackground(key)) {\n"
        "                resolved = colgramNightColor(key_windowBackgroundWhite);\n"
        "            }\n"
        "        }\n"
        "        return colgramGuard(key, resolved, provider);\n"
        "    }",
        "    public static int getColor(int key, ResourcesProvider provider) {\n"
        "        if (provider == null || provider.colgramDelegatesToGlobalTheme()) {\n"
        "            return getColor(key);\n"
        "        }\n"
        "        if (org.colgram.core.ColgramConfig.isCyberThemeEnabled()) {\n"
        "            // Delegate to the shared palette so this path and the\n"
        "            // getColor(key, isDefault, ignoreAnimation) wrapper cannot disagree.\n"
        "            int colgramCyberColor = colgramCyberOverride(key);\n"
        "            if (colgramCyberColor != 0) {\n"
        "                return colgramGuard(key, colgramCyberColor, provider);\n"
        "            }\n"
        "        }\n"
        "        int resolved = provider.getColor(key);\n"
        "        boolean colgramNeedsGuard = !provider.colgramReturnsGuardedColor();\n"
        "        if (resolved == 0 && (provider.isDark() || colgramHasDarkSurface())) {\n"
        "            if (colgramNightHasKey(key)) {\n"
        "                resolved = colgramNightColor(key);\n"
        "                colgramNeedsGuard = true;\n"
        "            } else if (colgramIsReadableKey(key)) {\n"
        "                resolved = 0xffffffff;\n"
        "                colgramNeedsGuard = true;\n"
        "            } else if (colgramLooksLikeBackground(key)) {\n"
        "                resolved = colgramNightColor(key_windowBackgroundWhite);\n"
        "                colgramNeedsGuard = true;\n"
        "            }\n"
        "        }\n"
        "        return colgramNeedsGuard ? colgramGuard(key, resolved, provider) : resolved;\n"
        "    }",
        "Theme.java short-circuit global ThemeDelegate and avoid double colgramGuard",
    )

    eol_patch_fn(
        chat_act,
        "        private boolean isDark;\n"
        "        private AnimatorSet patternAlphaAnimator;\n\n"
        "        MessageDrawable animatingMessageDrawable;\n"
        "        MessageDrawable animatingMessageMediaDrawable;\n",
        "        private boolean isDark;\n"
        "        private AnimatorSet patternAlphaAnimator;\n"
        "        private SparseIntArray colgramGuardedColorCache;\n"
        "        private long colgramCacheThemeRev = -1L;\n"
        "        private long colgramCacheDesignRev = -1L;\n"
        "        private boolean colgramCacheCyber;\n"
        "        private SparseIntArray colgramCacheColorsRef;\n\n"
        "        MessageDrawable animatingMessageDrawable;\n"
        "        MessageDrawable animatingMessageMediaDrawable;\n",
        "ChatActivity.ThemeDelegate cache fields for guarded colors",
    )
    def patch_chat_theme_delegate_cache(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        eol = "\r\n" if "\r\n" in content else "\n"
        new_body = (
            "        @Override\n"
            "        public boolean colgramDelegatesToGlobalTheme() {\n"
            "            return chatTheme == null && animatingColors == null;\n"
            "        }\n\n"
            "        @Override\n"
            "        public boolean colgramReturnsGuardedColor() {\n"
            "            return true;\n"
            "        }\n\n"
            "        @Override\n"
            "        public int getColor(int key) {\n"
            "            if (animatingColors != null) {\n"
            "                int index = animatingColors.indexOfKey(key);\n"
            "                if (index >= 0) {\n"
            "                    return Theme.colgramGuard(key, animatingColors.valueAt(index), this);\n"
            "                }\n"
            "            }\n"
            "            if (chatTheme == null) {\n"
            "                return Theme.getColor(key);\n"
            "            }\n"
            "            final boolean canUseCache = Theme.colgramCanUseUiColorCache();\n"
            "            final SparseIntArray colorsRef = currentColors;\n"
            "            SparseIntArray cache = null;\n"
            "            if (canUseCache) {\n"
            "                final long tRev = Theme.colgramGetThemeRevision();\n"
            "                final long dRev = org.colgram.core.ColgramDesignConfig.getRevision();\n"
            "                final boolean cyber = org.colgram.core.ColgramConfig.isCyberThemeEnabled();\n"
            "                cache = colgramGuardedColorCache;\n"
            "                if (cache == null\n"
            "                        || colgramCacheThemeRev != tRev\n"
            "                        || colgramCacheDesignRev != dRev\n"
            "                        || colgramCacheCyber != cyber\n"
            "                        || colgramCacheColorsRef != colorsRef) {\n"
            "                    if (cache == null) {\n"
            "                        cache = new SparseIntArray(64);\n"
            "                        colgramGuardedColorCache = cache;\n"
            "                    } else {\n"
            "                        cache.clear();\n"
            "                    }\n"
            "                    colgramCacheThemeRev = tRev;\n"
            "                    colgramCacheDesignRev = dRev;\n"
            "                    colgramCacheCyber = cyber;\n"
            "                    colgramCacheColorsRef = colorsRef;\n"
            "                } else {\n"
            "                    int cachedIdx = cache.indexOfKey(key);\n"
            "                    if (cachedIdx >= 0) {\n"
            "                        return cache.valueAt(cachedIdx);\n"
            "                    }\n"
            "                }\n"
            "            }\n"
            "            final int result;\n"
            "            int index = colorsRef != null ? colorsRef.indexOfKey(key) : -1;\n"
            "            if (index >= 0) {\n"
            "                result = Theme.colgramGuard(key, colorsRef.valueAt(index), this);\n"
            "            } else {\n"
            "                int fallbackKey = Theme.getFallbackKey(key);\n"
            "                if (fallbackKey >= 0 && colorsRef != null) {\n"
            "                    index = colorsRef.indexOfKey(fallbackKey);\n"
            "                } else {\n"
            "                    index = -1;\n"
            "                }\n"
            "                if (index >= 0) {\n"
            "                    result = Theme.colgramGuard(key, colorsRef.valueAt(index), this);\n"
            "                } else {\n"
            "                    result = Theme.getColor(key);\n"
            "                }\n"
            "            }\n"
            "            if (cache != null) {\n"
            "                cache.put(key, result);\n"
            "            }\n"
            "            return result;\n"
            "        }"
        )
        if "final boolean canUseCache = Theme.colgramCanUseUiColorCache();" in norm:
            return content
        old_body = (
            "        @Override\n"
            "        public int getColor(int key) {\n"
            "            if (animatingColors != null) {\n"
            "                int index = animatingColors.indexOfKey(key);\n"
            "                if (index >= 0) {\n"
            "                    return Theme.colgramGuard(key, animatingColors.valueAt(index), this);\n"
            "                }\n"
            "            }\n"
            "            if (chatTheme == null) {\n"
            "                return Theme.getColor(key);\n"
            "            }\n"
            "            int index = currentColors.indexOfKey(key);\n"
            "            if (index >= 0) {\n"
            "                return Theme.colgramGuard(key, currentColors.valueAt(index), this);\n"
            "            }\n\n"
            "            int fallbackKey = Theme.getFallbackKey(key);\n"
            "            if (fallbackKey >= 0) {\n"
            "                index = currentColors.indexOfKey(fallbackKey);\n"
            "                if (index >= 0) {\n"
            "                    return Theme.colgramGuard(key, currentColors.valueAt(index), this);\n"
            "                }\n"
            "            }\n"
            "            return Theme.getColor(key);\n"
            "        }"
        )
        prev_body = (
            "        @Override\n"
            "        public boolean colgramDelegatesToGlobalTheme() {\n"
            "            return chatTheme == null && animatingColors == null;\n"
            "        }\n\n"
            "        @Override\n"
            "        public boolean colgramReturnsGuardedColor() {\n"
            "            return true;\n"
            "        }\n\n"
            "        @Override\n"
            "        public int getColor(int key) {\n"
            "            if (animatingColors != null) {\n"
            "                int index = animatingColors.indexOfKey(key);\n"
            "                if (index >= 0) {\n"
            "                    return Theme.colgramGuard(key, animatingColors.valueAt(index), this);\n"
            "                }\n"
            "            }\n"
            "            if (chatTheme == null) {\n"
            "                return Theme.getColor(key);\n"
            "            }\n"
            "            final long tRev = Theme.colgramGetThemeRevision();\n"
            "            final long dRev = org.colgram.core.ColgramDesignConfig.getRevision();\n"
            "            final boolean cyber = org.colgram.core.ColgramConfig.isCyberThemeEnabled();\n"
            "            final SparseIntArray colorsRef = currentColors;\n"
            "            SparseIntArray cache = colgramGuardedColorCache;\n"
            "            if (cache == null\n"
            "                    || colgramCacheThemeRev != tRev\n"
            "                    || colgramCacheDesignRev != dRev\n"
            "                    || colgramCacheCyber != cyber\n"
            "                    || colgramCacheColorsRef != colorsRef) {\n"
            "                if (cache == null) {\n"
            "                    cache = new SparseIntArray(64);\n"
            "                    colgramGuardedColorCache = cache;\n"
            "                } else {\n"
            "                    cache.clear();\n"
            "                }\n"
            "                colgramCacheThemeRev = tRev;\n"
            "                colgramCacheDesignRev = dRev;\n"
            "                colgramCacheCyber = cyber;\n"
            "                colgramCacheColorsRef = colorsRef;\n"
            "            } else {\n"
            "                int cachedIdx = cache.indexOfKey(key);\n"
            "                if (cachedIdx >= 0) {\n"
            "                    return cache.valueAt(cachedIdx);\n"
            "                }\n"
            "            }\n"
            "            final int result;\n"
            "            int index = colorsRef != null ? colorsRef.indexOfKey(key) : -1;\n"
            "            if (index >= 0) {\n"
            "                result = Theme.colgramGuard(key, colorsRef.valueAt(index), this);\n"
            "            } else {\n"
            "                int fallbackKey = Theme.getFallbackKey(key);\n"
            "                if (fallbackKey >= 0 && colorsRef != null) {\n"
            "                    index = colorsRef.indexOfKey(fallbackKey);\n"
            "                } else {\n"
            "                    index = -1;\n"
            "                }\n"
            "                if (index >= 0) {\n"
            "                    result = Theme.colgramGuard(key, colorsRef.valueAt(index), this);\n"
            "                } else {\n"
            "                    result = Theme.getColor(key);\n"
            "                }\n"
            "            }\n"
            "            cache.put(key, result);\n"
            "            return result;\n"
            "        }"
        )
        for cand in (prev_body, old_body):
            if cand in norm:
                return norm.replace(cand, new_body, 1).replace("\n", eol)
        return content

    patch_file_fn(
        chat_act,
        patch_chat_theme_delegate_cache,
        "final boolean canUseCache = Theme.colgramCanUseUiColorCache();",
        "ChatActivity.ThemeDelegate colgramDelegatesToGlobalTheme & re-entrancy-safe UI color cache",
    )
    eol_patch_fn(
        chat_act,
        "        private void setupChatTheme(EmojiThemes chatTheme, TLRPC.WallPaper wallPaper, boolean withAnimation, boolean createNewResources) {\n"
        "            if (parentThemeDelegate != null) return;\n\n"
        "            this.chatTheme = chatTheme;",
        "        private void setupChatTheme(EmojiThemes chatTheme, TLRPC.WallPaper wallPaper, boolean withAnimation, boolean createNewResources) {\n"
        "            if (parentThemeDelegate != null) return;\n"
        "            if (colgramGuardedColorCache != null) {\n"
        "                colgramGuardedColorCache.clear();\n"
        "            }\n"
        "            colgramCacheColorsRef = null;\n\n"
        "            this.chatTheme = chatTheme;",
        "ChatActivity.ThemeDelegate clear guarded cache on setupChatTheme",
    )

    # =========================================================================
    # 19. ChatActivity.java & DialogsActivity.java (P0-4):
    #     Increase RecyclerListView view cache, RecycledViewPool, and prefetch.
    # =========================================================================
    eol_patch_fn(
        chat_act,
        "        chatListView.setLayoutManager(chatLayoutManager);",
        "        chatListView.setLayoutManager(chatLayoutManager);\n"
        "        chatListView.setItemViewCacheSize(16);\n"
        "        chatListView.getRecycledViewPool().setMaxRecycledViews(0, 24);\n"
        "        chatLayoutManager.setInitialPrefetchItemCount(6);",
        "ChatActivity.java RecyclerListView cache, pool, and prefetch tuning",
    )
    dialogs_activity = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java")
    eol_patch_fn(
        dialogs_activity,
        "            viewPage.listView.setLayoutManager(viewPage.layoutManager);",
        "            viewPage.listView.setLayoutManager(viewPage.layoutManager);\n"
        "            viewPage.listView.setItemViewCacheSize(12);\n"
        "            viewPage.listView.getRecycledViewPool().setMaxRecycledViews(0, 20);\n"
        "            viewPage.layoutManager.setInitialPrefetchItemCount(6);",
        "DialogsActivity.java RecyclerListView cache, pool, and prefetch tuning",
    )

    # =========================================================================
    # 20. ChatActivity.java (P0-7):
    #     Coalesce continuous 120Hz scroll-blur recaptures to 60Hz (16ms cadence)
    #     while applying clip/position/scroll-idle updates immediately.
    # =========================================================================
    eol_patch_fn(
        chat_act,
        "                    updateVisibleRows();\n"
        "                    invalidateMergedVisibleBlurredPositionsAndSources(BLUR_INVALIDATE_FLAG_SCROLL);\n"
        "                    scrollByTouch = false;",
        "                    updateVisibleRows();\n"
        "                    colgramLastChatBlurMs = 0L;\n"
        "                    invalidateMergedVisibleBlurredPositionsAndSources(BLUR_INVALIDATE_FLAG_SCROLL);\n"
        "                    scrollByTouch = false;",
        "ChatActivity.java reset blur coalesce timestamp on SCROLL_STATE_IDLE",
    )
    eol_patch_fn(
        chat_act,
        "    private void invalidateMergedVisibleBlurredPositionsAndSourcesImpl(int flags) {\n"
        "        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || scrollableViewNoiseSuppressor == null) {\n"
        "            return;\n"
        "        }\n\n"
        "        if (BitwiseUtils.hasFlag(flags, BLUR_INVALIDATE_FLAG_CLIP)) {",
        "    private long colgramLastChatBlurMs;\n\n"
        "    private void invalidateMergedVisibleBlurredPositionsAndSourcesImpl(int flags) {\n"
        "        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || scrollableViewNoiseSuppressor == null) {\n"
        "            return;\n"
        "        }\n"
        "        final boolean immediate = BitwiseUtils.hasFlag(flags, BLUR_INVALIDATE_FLAG_CLIP)\n"
        "                || BitwiseUtils.hasFlag(flags, BLUR_INVALIDATE_FLAG_POSITIONS);\n"
        "        final long nowMs = android.os.SystemClock.uptimeMillis();\n"
        "        if (!immediate && nowMs - colgramLastChatBlurMs < 16L) {\n"
        "            return;\n"
        "        }\n"
        "        colgramLastChatBlurMs = nowMs;\n\n"
        "        if (BitwiseUtils.hasFlag(flags, BLUR_INVALIDATE_FLAG_CLIP)) {",
        "ChatActivity.java coalesce scroll-blur RenderNode recaptures to 60Hz",
    )

    # =========================================================================
    # 21. MessagesStorage.java (P0-8):
    #     SQLite synchronous=NORMAL + 64MB mmap + 16MB page cache + busy_timeout,
    #     strictly preserving PRAGMA secure_delete = ON.
    # =========================================================================
    def patch_messages_storage_pragmas(content: str) -> str:
        norm = content.replace("\r\n", "\n")
        eol = "\r\n" if "\r\n" in content else "\n"
        open_old = (
            '            database.executeFast("PRAGMA secure_delete = ON").stepThis().dispose();\n'
            '            database.executeFast("PRAGMA temp_store = MEMORY").stepThis().dispose();\n'
            '            database.executeFast("PRAGMA journal_mode = WAL").stepThis().dispose();\n'
            '            database.executeFast("PRAGMA journal_size_limit = 10485760").stepThis().dispose();'
        )
        open_new = (
            open_old + "\n"
            '            database.executeFast("PRAGMA synchronous = NORMAL").stepThis().dispose();\n'
            '            database.executeFast("PRAGMA mmap_size = 67108864").stepThis().dispose();\n'
            '            database.executeFast("PRAGMA cache_size = -16384").stepThis().dispose();\n'
            '            database.executeFast("PRAGMA busy_timeout = 5000").stepThis().dispose();'
        )
        rec_old = (
            '                database.executeFast("PRAGMA secure_delete = ON").stepThis().dispose();\n'
            '                database.executeFast("PRAGMA temp_store = MEMORY").stepThis().dispose();\n'
            '                database.executeFast("PRAGMA journal_mode = WAL").stepThis().dispose();\n'
            '                database.executeFast("PRAGMA journal_size_limit = 10485760").stepThis().dispose();'
        )
        rec_new = (
            rec_old + "\n"
            '                database.executeFast("PRAGMA synchronous = NORMAL").stepThis().dispose();\n'
            '                database.executeFast("PRAGMA mmap_size = 67108864").stepThis().dispose();\n'
            '                database.executeFast("PRAGMA cache_size = -16384").stepThis().dispose();\n'
            '                database.executeFast("PRAGMA busy_timeout = 5000").stepThis().dispose();'
        )
        updated = norm
        if open_new not in updated and open_old in updated:
            updated = updated.replace(open_old, open_new, 1)
        if rec_new not in updated and rec_old in updated:
            updated = updated.replace(rec_old, rec_new, 1)
        return updated if updated == norm else updated.replace("\n", eol)

    patch_file_fn(
        msgs_storage,
        patch_messages_storage_pragmas,
        '                database.executeFast("PRAGMA mmap_size = 67108864").stepThis().dispose();',
        "MessagesStorage.java SQLite WAL/mmap/cache/busy_timeout PRAGMAs preserving secure_delete",
    )

    # =========================================================================
    # 22. Telegram background worker thread priorities (P0-10):
    #     Yield DispatchQueuePriority, RLottie, AnimatedFileDrawable, ImageLoader,
    #     FileLoader, and Utilities background queues below UI/RenderThread.
    #     Keep Utilities.stageQueue at default priority.
    # =========================================================================
    dispatch_prio = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/DispatchQueuePriority.java")
    eol_patch_fn(
        dispatch_prio,
        "        ThreadFactory factory = r -> {\n"
        "            Thread t = new Thread(r, prefix + threadNumber.getAndIncrement());\n"
        "            t.setDaemon(true);\n"
        "            return t;\n"
        "        };",
        "        ThreadFactory factory = r -> {\n"
        "            Thread t = new Thread(() -> {\n"
        "                try {\n"
        "                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);\n"
        "                } catch (Throwable ignored) {}\n"
        "                r.run();\n"
        "            }, prefix + threadNumber.getAndIncrement());\n"
        "            t.setDaemon(true);\n"
        "            return t;\n"
        "        };",
        "DispatchQueuePriority.java THREAD_PRIORITY_BACKGROUND worker priority",
    )

    rlottie = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/ui/Components/RLottieDrawable.java")
    eol_patch_fn(
        rlottie,
        '    private static final Executor loadFrameRunnableQueue = Executors.newFixedThreadPool(4, r -> new Thread(r, "Lottie-" + threadId.getAndIncrement()));\n'
        '    private static final Executor loadFrameRunnableQueueLimitFps = Executors.newFixedThreadPool(2, r -> new Thread(r, "LottieLow-" + threadId2.getAndIncrement()));',
        '    private static final Executor loadFrameRunnableQueue = Executors.newFixedThreadPool(4, r -> new Thread(() -> {\n'
        '        try {\n'
        '            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);\n'
        '        } catch (Throwable ignored) {}\n'
        '        r.run();\n'
        '    }, "Lottie-" + threadId.getAndIncrement()));\n'
        '    private static final Executor loadFrameRunnableQueueLimitFps = Executors.newFixedThreadPool(2, r -> new Thread(() -> {\n'
        '        try {\n'
        '            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);\n'
        '        } catch (Throwable ignored) {}\n'
        '        r.run();\n'
        '    }, "LottieLow-" + threadId2.getAndIncrement()));',
        "RLottieDrawable.java THREAD_PRIORITY_BACKGROUND on decode pools",
    )

    anim_file = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/ui/Components/AnimatedFileDrawable.java")
    eol_patch_fn(
        anim_file,
        "    private static final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(8, new ThreadPoolExecutor.DiscardPolicy());",
        "    private static final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(8, r -> {\n"
        "        Thread t = new Thread(() -> {\n"
        "            try {\n"
        "                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);\n"
        "            } catch (Throwable ignored) {}\n"
        "            r.run();\n"
        "        }, \"AnimatedFileDrawable\");\n"
        "        t.setDaemon(true);\n"
        "        return t;\n"
        "    }, new ThreadPoolExecutor.DiscardPolicy());",
        "AnimatedFileDrawable.java THREAD_PRIORITY_BACKGROUND on decode executor",
    )

    image_loader = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/messenger/ImageLoader.java")
    eol_patch_fn(
        image_loader,
        '    private DispatchQueue thumbGeneratingQueue = new DispatchQueue("thumbGeneratingQueue");\n'
        '    private DispatchQueue imageLoadQueue = new DispatchQueue("imageLoadQueue");',
        '    private DispatchQueue thumbGeneratingQueue = new DispatchQueue("thumbGeneratingQueue", true, android.os.Process.THREAD_PRIORITY_BACKGROUND);\n'
        '    private DispatchQueue imageLoadQueue = new DispatchQueue("imageLoadQueue", true, android.os.Process.THREAD_PRIORITY_BACKGROUND);',
        "ImageLoader.java THREAD_PRIORITY_BACKGROUND on thumb and image load queues",
    )

    eol_patch_fn(
        file_loader,
        '    private volatile static DispatchQueue fileLoaderQueue = new DispatchQueue("fileUploadQueue");',
        '    private volatile static DispatchQueue fileLoaderQueue = new DispatchQueue("fileUploadQueue", true, android.os.Process.THREAD_PRIORITY_BACKGROUND);',
        "FileLoader.java THREAD_PRIORITY_BACKGROUND on fileLoaderQueue",
    )

    utilities = _join(repo_path, "TMessagesProj/src/main/java/org/telegram/messenger/Utilities.java")
    eol_patch_fn(
        utilities,
        '    public static volatile DispatchQueue stageQueue = new DispatchQueue("stageQueue");\n'
        '    public static volatile DispatchQueue globalQueue = new DispatchQueue("globalQueue");\n'
        '    public static volatile DispatchQueue cacheClearQueue = new DispatchQueue("cacheClearQueue");\n'
        '    public static volatile DispatchQueue searchQueue = new DispatchQueue("searchQueue");\n'
        '    public static volatile DispatchQueue phoneBookQueue = new DispatchQueue("phoneBookQueue");\n'
        '    public static volatile DispatchQueue themeQueue = new DispatchQueue("themeQueue");\n'
        '    public static volatile DispatchQueue externalNetworkQueue = new DispatchQueue("externalNetworkQueue");',
        '    public static volatile DispatchQueue stageQueue = new DispatchQueue("stageQueue");\n'
        '    public static volatile DispatchQueue globalQueue = new DispatchQueue("globalQueue", true, android.os.Process.THREAD_PRIORITY_BACKGROUND);\n'
        '    public static volatile DispatchQueue cacheClearQueue = new DispatchQueue("cacheClearQueue", true, android.os.Process.THREAD_PRIORITY_BACKGROUND);\n'
        '    public static volatile DispatchQueue searchQueue = new DispatchQueue("searchQueue", true, android.os.Process.THREAD_PRIORITY_BACKGROUND);\n'
        '    public static volatile DispatchQueue phoneBookQueue = new DispatchQueue("phoneBookQueue", true, android.os.Process.THREAD_PRIORITY_BACKGROUND);\n'
        '    public static volatile DispatchQueue themeQueue = new DispatchQueue("themeQueue", true, android.os.Process.THREAD_PRIORITY_BACKGROUND);\n'
        '    public static volatile DispatchQueue externalNetworkQueue = new DispatchQueue("externalNetworkQueue", true, android.os.Process.THREAD_PRIORITY_BACKGROUND);',
        "Utilities.java THREAD_PRIORITY_BACKGROUND on background queues keeping stageQueue default",
    )

    # =========================================================================
    # 23. ChatActivity.java (P1-1):
    #     Lazy-allocate ColgramVideoWallpaperView ONLY when a non-empty videoPath
    #     is resolved instead of eagerly creating TextureView on every chat open.
    # =========================================================================
    eol_patch_fn(
        chat_act,
        "        colgramWallpaperHostActive = !paused;\n"
        "        colgramVideoWallpaperView = new ColgramVideoWallpaperView(context);\n"
        "        colgramVideoWallpaperView.setHostPaused(!colgramWallpaperHostActive);\n"
        "        colgramVideoWallpaperView.setVisibility(View.GONE);\n"
        "        contentView.addView(colgramVideoWallpaperView, 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));",
        "        colgramWallpaperHostActive = !paused;\n"
        "        colgramVideoWallpaperView = null;",
        "ChatActivity.java remove eager ColgramVideoWallpaperView allocation on createView",
    )
    eol_patch_fn(
        chat_act,
        "    private void colgramEnsureVideoWallpaperZOrder() {",
        "    private ColgramVideoWallpaperView colgramEnsureVideoWallpaperView() {\n"
        "        if (colgramVideoWallpaperView != null || contentView == null) {\n"
        "            return colgramVideoWallpaperView;\n"
        "        }\n"
        "        Context ctx = contentView.getContext() != null ? contentView.getContext() : getParentActivity();\n"
        "        if (ctx == null) {\n"
        "            return null;\n"
        "        }\n"
        "        colgramVideoWallpaperView = new ColgramVideoWallpaperView(ctx);\n"
        "        colgramVideoWallpaperView.setHostPaused(!colgramWallpaperHostActive);\n"
        "        colgramVideoWallpaperView.setVisibility(View.GONE);\n"
        "        contentView.addView(colgramVideoWallpaperView, 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));\n"
        "        return colgramVideoWallpaperView;\n"
        "    }\n\n"
        "    private void colgramEnsureVideoWallpaperZOrder() {",
        "ChatActivity.java add lazy colgramEnsureVideoWallpaperView helper",
    )
    eol_patch_fn(
        chat_act,
        "            if (colgramVideoWallpaperView != null) {\n"
        "                colgramEnsureVideoWallpaperZOrder();\n"
        "                colgramVideoWallpaperView.setHostPaused(!colgramWallpaperHostActive);\n"
        "                colgramVideoWallpaperView.setVideoPath(videoPath);\n"
        "            }\n"
        "            ColgramVideoWallpaperView.resolveWallpaperAsync(",
        "            ColgramVideoWallpaperView lazyVideoView = colgramEnsureVideoWallpaperView();\n"
        "            if (lazyVideoView != null) {\n"
        "                colgramEnsureVideoWallpaperZOrder();\n"
        "                lazyVideoView.setHostPaused(!colgramWallpaperHostActive);\n"
        "                lazyVideoView.setVideoPath(videoPath);\n"
        "            }\n"
        "            ColgramVideoWallpaperView.resolveWallpaperAsync(",
        "ChatActivity.java lazy-init ColgramVideoWallpaperView on non-empty videoPath",
    )
    eol_patch_fn(
        chat_act,
        "        contentView.setSkipBackgroundDrawing(false);\n"
        "        if (colgramVideoWallpaperView != null) {\n"
        "            colgramEnsureVideoWallpaperZOrder();\n"
        "            colgramVideoWallpaperView.setHostPaused(!colgramWallpaperHostActive);\n"
        "            colgramVideoWallpaperView.setVideoPath(videoPath);\n"
        "        }",
        "        contentView.setSkipBackgroundDrawing(false);\n"
        "        ColgramVideoWallpaperView lazyResolvedView = (videoPath != null && !videoPath.isEmpty())\n"
        "                ? colgramEnsureVideoWallpaperView()\n"
        "                : colgramVideoWallpaperView;\n"
        "        if (lazyResolvedView != null) {\n"
        "            colgramEnsureVideoWallpaperZOrder();\n"
        "            lazyResolvedView.setHostPaused(!colgramWallpaperHostActive);\n"
        "            lazyResolvedView.setVideoPath(videoPath);\n"
        "        }",
        "ChatActivity.java lazy-init ColgramVideoWallpaperView on colgramApplyResolvedVideoWallpaper",
    )

    # =========================================================================
    # 24. ImageLoader.java (P1-5):
    #     Expand bitmap LruCache headroom on >=512MB heap devices without altering
    #     the >=192MB baseline formula.
    # =========================================================================
    eol_patch_fn(
        image_loader,
        "        if (canForce8888 = memoryClass >= 192) {\n"
        "            maxSize = unlimited ? 30 : 24;\n"
        "        } else {\n"
        "            maxSize = unlimited ? 15 : 12;\n"
        "        }\n"
        "        int cacheSize = DEBUG_MODE ? 1 : Math.min(maxSize, memoryClass / 7) * 1024 * 1024;",
        "        if (canForce8888 = memoryClass >= 192) {\n"
        "            maxSize = unlimited ? 30 : 24;\n"
        "        } else {\n"
        "            maxSize = unlimited ? 15 : 12;\n"
        "        }\n"
        "        if (memoryClass >= 512) {\n"
        "            maxSize = unlimited ? 56 : 44;\n"
        "        }\n"
        "        int cacheSize = DEBUG_MODE ? 1 : Math.min(maxSize, memoryClass / 7) * 1024 * 1024;",
        "ImageLoader.java high-RAM bitmap cache headroom",
    )
