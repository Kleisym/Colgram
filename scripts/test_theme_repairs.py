"""Pinned DialogsActivity hint & video wallpaper fixes: host anchors and convergence."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

try:
    from scripts.theme_repairs import patch_story_hints, patch_video_wallpaper_fixes
except ImportError:
    from theme_repairs import patch_story_hints, patch_video_wallpaper_fixes

ROOT = Path(__file__).resolve().parents[1]
REL = "TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java"
TG_UI_REL = Path("TMessagesProj/src/main/java/org/telegram/ui")


FIXTURE = """public class DialogsActivity {
    void createView() {
                storyHint = new HintView2(context, HintView2.DIRECTION_RIGHT)
                        .setCloseButton(true)
                        .setText(StoryRecorder.cameraBtnSpan(context))
                        .setBgColor(getThemedColor(Theme.key_undo_background))
                        .setOnHiddenListener(() -> {});
    }

    void onResume() {
            storyHint.show();
    }

    private void updateStoriesPosting() {
                storyHint.show();
    }

    void showPremiumHint() {
            Spannable text = AndroidUtilities.replaceSingleTag(LocaleController.getString("StoriesPremiumHint2").replace('\\n', ' '), Theme.key_undo_cancelColor, 0, () -> {
                goPremium();
            });
            storyPremiumHint = new HintView2(context, HintView2.DIRECTION_RIGHT)
                .setText(text)
                .setBgColor(getThemedColor(Theme.key_undo_background));
    }
}
"""


class ThemeRepairsTest(unittest.TestCase):
    def test_rejects_unrelated_source(self):
        with self.assertRaisesRegex(ValueError, "story hint"):
            patch_story_hints("public class Unexpected {}")

    def assert_paired_colors(self, source):
        patched = patch_story_hints(source)
        self.assertEqual(patched, patch_story_hints(patched))
        self.assertEqual(1, patched.count("COLGRAM_STORY_HINT_PALETTE = 1"))
        self.assertEqual(1, patched.count("ColgramThemePolicy.forStoryHint("))
        self.assertEqual(2, patched.count(".setBgColor(hintColors.background)"))
        self.assertEqual(2, patched.count(".setTextColor(hintColors.text)"))
        self.assertIn("AndroidUtilities.replaceSingleLinkBold(", patched)
        self.assertIn("hintColors.link, () -> {", patched)
        self.assertNotIn(".setBgColor(getThemedColor(Theme.key_undo_background))", patched)
        self.assertEqual(2, patched.count("showColgramStoryHint();"))
        self.assertIn(".setCloseButton(true)", patched)
        self.assertIn("StoryRecorder.cameraBtnSpan(context)", patched)

    def test_both_hints_and_premium_link_keep_their_paired_colors(self):
        self.assert_paired_colors(FIXTURE)

    def test_pinned_upstream_and_existing_sources_converge(self):
        repo = ROOT / "Telegram-Src"
        if not (repo / REL).is_file() or not shutil.which("git"):
            self.skipTest("pinned upstream checkout is not installed")
        pristine = subprocess.check_output(["git", "-C", str(repo), "show", f"HEAD:{REL}"]).decode("utf-8")
        existing = (repo / REL).read_text(encoding="utf-8")
        for source in (pristine, existing):
            with self.subTest(source_len=len(source)):
                self.assert_paired_colors(source)


VIDEO_VIEW_LEGACY_FIXTURE = """package org.telegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.LruCache;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.colgram.core.ColgramConfig;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentHashMap;

public final class ColgramVideoWallpaperView extends TextureView {
    private String videoPath;
    private MediaPlayer mediaPlayer;

    private void applyValidatedVideoPath(String clean) {
        if (clean.equals(this.videoPath) && mediaPlayer != null) {
            setVisibility(View.VISIBLE);
            if (shouldBePlaying()) {
                if (renderSurface == null || !renderSurface.isValid()) {
                    if (isAvailable() && getSurfaceTexture() != null) {
                        surfaceReady = true;
                        startPlayer(getSurfaceTexture());
                    }
                } else if (playerPrepared) {
                    // CRITICAL: NEVER call start() while MediaPlayer is still in Preparing state!
                    try {
                        if (!mediaPlayer.isPlaying()) {
                            mediaPlayer.start();
                        }
                        if (firstFrameRendered && getAlpha() < 1f) {
                            setAlpha(1f);
                        }
                        scheduleStartFallbackCheck(playerGeneration);
                    } catch (Throwable ignored) {}
                }
            }
            return;
        }
        this.videoPath = clean;
        errorRetryCount = 0;
        stallRecoveryCount = 0;
        setVisibility(View.VISIBLE);
        if (shouldBePlaying() && isAvailable() && getSurfaceTexture() != null) {
            surfaceReady = true;
            startPlayer(getSurfaceTexture());
        }
    }

    public void setHostPaused(boolean paused) {
        pausedByHost = paused;
        if (paused) {
            pausePlaybackOnly();
            return;
        }
        errorRetryCount = 0;
        stallRecoveryCount = 0;
        resumePlaybackIfReady();
    }

    public void onHostConfigurationChanged() {
        applyCenterCropTransform();
        if (shouldBePlaying()) {
            resumePlaybackIfReady();
        }
    }

    private boolean shouldBePlaying() {
        Context ctx = getContext();
        return !pausedByHost
                && windowVisible
                && isAttachedToWindow()
                && getVisibility() == View.VISIBLE
                && videoPath != null
                && !videoPath.isEmpty()
                && !org.colgram.core.ColgramPowerGuard.isPowerSaveMode(ctx)
                && !org.colgram.core.ColgramPowerGuard.isThermalThrottled(ctx);
    }

    public void resumePlaybackIfReady() {
        if (!shouldBePlaying()) {
            return;
        } else if (playerPrepared) {
            try {
                if (!mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                }
                if (firstFrameRendered && getAlpha() < 1f) {
                    setAlpha(1f);
                }
                scheduleStartFallbackCheck(playerGeneration);
            } catch (Throwable ignored) {}
        }
    }

    private void scheduleStartFallbackCheck(final int gen) {
        cancelScheduledChecks();
        fallbackCheckRunnable = () -> {
            fallbackCheckRunnable = null;
            if (gen != playerGeneration || mediaPlayer == null || !playerPrepared || !shouldBePlaying()) {
                return;
            }
            try {
                if (!mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                }
                if (firstFrameRendered && mediaPlayer.isPlaying() && getAlpha() < 1f) {
                    setAlpha(1f);
                }
            } catch (Throwable ignored) {}
        };
        watchdogCheckRunnable = () -> {
            watchdogCheckRunnable = null;
            if (gen != playerGeneration || mediaPlayer == null || !playerPrepared || !shouldBePlaying()) {
                return;
            }
            try {
                if (!firstFrameRendered || renderSurface == null || !renderSurface.isValid()) {
                    if (stallRecoveryCount < 1 && isAvailable() && getSurfaceTexture() != null) {
                        stallRecoveryCount++;
                        startPlayer(getSurfaceTexture());
                    }
                    return;
                }
                if (!mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                }
                if (mediaPlayer.isPlaying() && getAlpha() < 1f) {
                    setAlpha(1f);
                }
            } catch (Throwable ignored) {}
        };
        postDelayed(fallbackCheckRunnable, 160L);
        postDelayed(watchdogCheckRunnable, 900L);
    }

    public void releasePlayer() {
        playerGeneration++;
        playerPrepared = false;
        firstFrameRendered = false;
        cancelScheduledChecks();
        final MediaPlayer mp = mediaPlayer;
        mediaPlayer = null;
        if (mp != null) {
            try {
                mp.setOnPreparedListener(null);
                mp.setOnErrorListener(null);
                mp.setOnInfoListener(null);
                mp.setOnVideoSizeChangedListener(null);
            } catch (Throwable ignored) {}
            try {
                mp.setSurface(null);
            } catch (Throwable ignored) {}
        }
        Surface s = renderSurface;
        renderSurface = null;
        if (s != null) {
            try {
                s.release();
            } catch (Throwable ignored) {}
        }
        if (mp != null) {
            RELEASE_QUEUE.postRunnable(() -> {
                try {
                    mp.reset();
                } catch (Throwable ignored) {}
                try {
                    mp.release();
                } catch (Throwable ignored) {}
            });
        }
    }

    private void startPlayer(SurfaceTexture texture) {
        releasePlayer();
        if (texture == null || !shouldBePlaying()) {
            return;
        }
        if (Boolean.FALSE.equals(FILE_EXISTS_CACHE.get(videoPath))) {
            return;
        }
        final int gen = playerGeneration;
        try {
            // Keep underlying poster crisp during ~100ms codec warmup without triggering HWUI skip-draw (0.01f > 0).
            setAlpha(0.01f);
            firstFrameRendered = false;
            renderSurface = new Surface(texture);
            final MediaPlayer mp = new MediaPlayer();
            mp.setSurface(renderSurface);
            mp.setDataSource(videoPath);
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setOnVideoSizeChangedListener((player, width, height) -> {
                if (mediaPlayer != player) return;
                videoWidth = width;
                videoHeight = height;
                applyCenterCropTransform();
            });
            mp.setOnInfoListener((player, what, extra) -> {
                if (mediaPlayer == player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    firstFrameRendered = true;
                    if (shouldBePlaying() && getAlpha() < 1f) {
                        setAlpha(1f);
                    }
                    applyCenterCropTransform();
                }
                return false;
            });
            mp.setOnPreparedListener(player -> {
                if (mediaPlayer != player) return;
                playerPrepared = true;
                videoWidth = player.getVideoWidth();
                videoHeight = player.getVideoHeight();
                applyCenterCropTransform();
                if (shouldBePlaying()) {
                    try {
                        player.start();
                        scheduleStartFallbackCheck(gen);
                    } catch (Throwable ignored) {}
                }
            });
            mp.setOnErrorListener((player, what, extra) -> {
                if (mediaPlayer != player) return true;
                releasePlayer();
                setAlpha(0.01f);
                if (shouldBePlaying() && errorRetryCount < 2) {
                    errorRetryCount++;
                    final int retryGen = playerGeneration;
                    retryRunnable = () -> {
                        retryRunnable = null;
                        if (retryGen == playerGeneration && shouldBePlaying()
                                && isAvailable() && getSurfaceTexture() != null && mediaPlayer == null) {
                            startPlayer(getSurfaceTexture());
                        }
                    };
                    postDelayed(retryRunnable, 250L * errorRetryCount);
                } else {
                    setVisibility(View.GONE);
                }
                return true;
            });
            mediaPlayer = mp;
            mp.prepareAsync();
        } catch (Throwable ignored) {
            releasePlayer();
            setAlpha(0.01f);
            setVisibility(View.GONE);
        }
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        firstFrameRendered = true;
        if (getAlpha() < 1f && mediaPlayer != null && playerPrepared && shouldBePlaying()) {
            setAlpha(1f);
        }
    }

    public static String getOrCreateVideoPosterPath(String videoPath) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(videoPath);
            Bitmap frame = mmr.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } finally {}
        return "";
    }
}
"""

BOTTOM_SHEET_LEGACY_FIXTURE = """public class ChatThemeBottomSheet {
    public static void openGalleryForBackground(
            Activity activity,
            BaseFragment fragment,
            long dialogId,
            Theme.ResourcesProvider resourcesProvider,
            Utilities.Callback<TLRPC.WallPaper> onSet,
            ThemePreviewActivity.DayNightSwitchDelegate toggleTheme,
            TL_stories.TL_premium_boostsStatus cachedBoostsStatus
    ) {
        ChatAttachAlert chatAttachAlert = new ChatAttachAlert(activity, fragment, false, false, false, resourcesProvider);
        chatAttachAlert.drawNavigationBar = true;
        chatAttachAlert.setupPhotoPicker(LocaleController.getString(R.string.ChooseBackground));
        chatAttachAlert.colgramEnableVideoWallpapers();
        chatAttachAlert.setDelegate(new ChatAttachAlert.ChatAttachViewDelegate() {
            long start;
            @Override
            public boolean selectItemOnClicking() {
                start = System.currentTimeMillis();
                return true;
            }

            @Override
            public void didPressedButton(int button, boolean arg, boolean notify, int scheduleDate, int scheduleRepeatPeriod, long effectId, boolean invertMedia, boolean forceDocument, long payStars) {
                try {
                    HashMap<Object, Object> photos = chatAttachAlert.getPhotoLayout().getSelectedPhotos();
                    if (!photos.isEmpty()) {
                        MediaController.PhotoEntry entry = (MediaController.PhotoEntry) photos.values().iterator().next();
                        String path;
                        if (entry.imagePath != null) {
                            path = entry.imagePath;
                        } else {
                            path = entry.path;
                        }
                        if (path != null) {
                            if (entry.isVideo || path.toLowerCase().endsWith(".mp4") || path.toLowerCase().endsWith(".webm") || path.toLowerCase().endsWith(".mkv")) {
                                org.telegram.ui.ColgramVideoWallpaperView.copyFileAndApplyVideoWallpaper(
                                        activity, new File(path), dialogId, null);
                                chatAttachAlert.dismissInternal();
                                if (onSet != null) {
                                    onSet.run(null);
                                }
                                return;
                            }
                        }
                    }
                } catch (Throwable e) {}
            }
        });
        chatAttachAlert.setMaxSelectedPhotos(1, false);
        chatAttachAlert.init();
        chatAttachAlert.getPhotoLayout().loadGalleryPhotos();
        chatAttachAlert.show();
    }


    private void openGalleryForBackground() {
        chatAttachAlert = new ChatAttachAlert(chatActivity.getParentActivity(), chatActivity, false, false, false, chatActivity.getResourceProvider());
        chatAttachAlert.drawNavigationBar = true;
        chatAttachAlert.setupPhotoPicker(LocaleController.getString(R.string.ChooseBackground));
        chatAttachAlert.colgramEnableVideoWallpapers();
        chatAttachAlert.setDelegate(new ChatAttachAlert.ChatAttachViewDelegate() {
            long start;
            @Override
            public boolean selectItemOnClicking() {
                start = System.currentTimeMillis();
                return true;
            }

            @Override
            public void didPressedButton(int button, boolean arg, boolean notify, int scheduleDate, int scheduleRepeatPeriod, long effectId, boolean invertMedia, boolean forceDocument, long payStars) {
                try {
                    HashMap<Object, Object> photos = chatAttachAlert.getPhotoLayout().getSelectedPhotos();
                    if (!photos.isEmpty()) {
                        MediaController.PhotoEntry entry = (MediaController.PhotoEntry) photos.values().iterator().next();
                        String path;
                        if (entry.imagePath != null) {
                            path = entry.imagePath;
                        } else {
                            path = entry.path;
                        }
                        if (path != null) {
                            if (entry.isVideo || path.toLowerCase().endsWith(".mp4") || path.toLowerCase().endsWith(".webm") || path.toLowerCase().endsWith(".mkv")) {
                                org.telegram.ui.ColgramVideoWallpaperView.copyFileAndApplyVideoWallpaper(
                                        chatActivity.getParentActivity(), new File(path), chatActivity.getDialogId(), null);
                                isApplyClicked = true;
                                chatAttachAlert.dismissInternal();
                                dismiss();
                                return;
                            }
                        }
                    }
                } catch (Throwable e) {}
            }
        });
        chatAttachAlert.setMaxSelectedPhotos(1, false);
        chatAttachAlert.init();
        chatAttachAlert.getPhotoLayout().loadGalleryPhotos();
        chatAttachAlert.show();
    }
}
"""

WALLPAPERS_LIST_LEGACY_FIXTURE = """public class WallpapersListActivity {
    void init() {
        updater = new WallpaperUpdater(getParentActivity(), this, new WallpaperUpdater.WallpaperUpdaterDelegate() {
            @Override
            public void didSelectWallpaper(File file, Bitmap bitmap, boolean gallery) {
                ThemePreviewActivity themePreviewActivity = new ThemePreviewActivity(new FileWallpaper("", file, file), bitmap);
            }
        });
        listView.setOnItemClickListener((view, position) -> {
            if (position == uploadImageRow) {
                updater.openGallery();
            } else if (position == uploadVideoRow) {
                try {
                    Intent videoIntent = new Intent(Intent.ACTION_GET_CONTENT);
                    videoIntent.setType("video/*");
                    videoIntent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(videoIntent, "Выбрать видео-обои"), COLGRAM_REQ_PICK_VIDEO_WALLPAPER);
                } catch (Throwable e) {}
            }
        });
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == COLGRAM_REQ_PICK_VIDEO_WALLPAPER) {
            if (resultCode == android.app.Activity.RESULT_OK && data != null && data.getData() != null && getParentActivity() != null) {
                ColgramVideoWallpaperView.copyUriAndApplyVideoWallpaper(getParentActivity(), data.getData(), dialogId, this::updateRows);
            }
            return;
        }
        updater.onActivityResult(requestCode, resultCode, data);
    }

    public void didReceivedNotification(int id, int account, Object... args) {
        if (false) {
        } else if (id == NotificationCenter.didSetNewWallpapper) {
            if (listView != null) {
                listView.invalidateViews();
            }
        }
    }
}
"""

THEME_PREVIEW_LEGACY_FIXTURE = """public class ThemePreviewActivity {
    private void applyWallpaperBackground(boolean forBoth) {
        if (currentWallpaper instanceof WallpapersListActivity.EmojiWallpaper) {
            if (delegate != null) {
                delegate.didSetNewBackground(null);
            }
            finishFragment();
            return;
        }
    }
}
"""

CHAT_ACTIVITY_LEGACY_FIXTURE = """public class ChatActivity {
        @Override
        protected void onAttachedToWindow() {
            if (pullingDownDrawable != null) {
                pullingDownDrawable.onAttach();
            }
            emojiAnimationsOverlay.onAttachedToWindow();
        }

    public void colgramPickVideoWallpaper() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("video/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, "Выбрать видео-обои для чата"), COLGRAM_REQ_PICK_VIDEO_WALLPAPER);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (org.colgram.core.ColgramFileImport.handleActivityResult(getParentActivity(), requestCode, resultCode, data)) {
            return;
        }
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == COLGRAM_REQ_PICK_VIDEO_WALLPAPER && data != null && data.getData() != null && getParentActivity() != null) {
                ColgramVideoWallpaperView.copyUriAndApplyVideoWallpaper(getParentActivity(), data.getData(), dialog_id, this::updateBackground);
                return;
            }
        }
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        if (isOpen) {
            checkShowBlur(false);
            openAnimationEnded = true;
        }
    }

    @Override
    public void onResume() {
        if (colgramVideoWallpaperView != null) {
            updateBackground();
            colgramVideoWallpaperView.setHostPaused(false);
        }
    }

    private void colgramEnsureVideoWallpaperZOrder() {
        if (contentView == null || colgramVideoWallpaperView == null || contentView.backgroundView == null) {
            return;
        }
        int bgIdx = contentView.indexOfChild(contentView.backgroundView);
        int vidIdx = contentView.indexOfChild(colgramVideoWallpaperView);
        if (bgIdx >= 0 && vidIdx >= 0 && vidIdx < bgIdx) {
            contentView.removeView(colgramVideoWallpaperView);
            contentView.addView(colgramVideoWallpaperView, Math.min(bgIdx + 1, contentView.getChildCount()),
                    LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
    }

    private void checkUi_backgroundViewVisible() {
        if (colgramVideoWallpaperView != null
                && colgramVideoWallpaperView.getVideoPath() != null
                && !colgramVideoWallpaperView.getVideoPath().isEmpty()) {
            colgramVideoWallpaperView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }
}
"""


class VideoWallpaperFixesTest(unittest.TestCase):
    def _write_repo_fixtures(self, root: Path, crlf: bool = False):
        ui = root / TG_UI_REL
        (ui / "Components").mkdir(parents=True, exist_ok=True)
        files = {
            ui / "ColgramVideoWallpaperView.java": VIDEO_VIEW_LEGACY_FIXTURE,
            ui / "Components" / "ChatThemeBottomSheet.java": BOTTOM_SHEET_LEGACY_FIXTURE,
            ui / "WallpapersListActivity.java": WALLPAPERS_LIST_LEGACY_FIXTURE,
            ui / "ThemePreviewActivity.java": THEME_PREVIEW_LEGACY_FIXTURE,
            ui / "ChatActivity.java": CHAT_ACTIVITY_LEGACY_FIXTURE,
        }
        for path, body in files.items():
            text = body.replace("\n", "\r\n") if crlf else body
            path.write_bytes(text.encode("utf-8"))
        return files

    def _assert_fixed_contracts(self, root: Path):
        ui = root / TG_UI_REL
        view_src = (ui / "ColgramVideoWallpaperView.java").read_text(encoding="utf-8")
        self.assertNotIn("ColgramPowerGuard.isPowerSaveMode", view_src)
        self.assertNotIn("ColgramPowerGuard.isThermalThrottled", view_src)
        self.assertIn("private String activePlayerPath;", view_src)
        self.assertIn("VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING", view_src)
        self.assertIn("(firstFrameRendered || player.isPlaying()) && getAlpha() < 1f", view_src)
        self.assertIn("(firstFrameRendered || mediaPlayer.isPlaying()) && getAlpha() < 1f", view_src)
        self.assertIn("postDelayed(fallbackCheckRunnable, 120L);", view_src)
        self.assertIn("(!firstFrameRendered && !mediaPlayer.isPlaying())", view_src)
        self.assertIn("mp.setDataSource(fis.getFD())", view_src)
        self.assertIn("AudioAttributes.USAGE_MEDIA", view_src)
        self.assertIn("AudioAttributes.CONTENT_TYPE_MOVIE", view_src)
        self.assertIn("public static boolean isVideoFileName(", view_src)
        self.assertIn("public static boolean isVideoUri(", view_src)
        for ext in (".mp4", ".webm", ".mkv", ".mov", ".m4v", ".3gp", ".avi"):
            self.assertIn(ext, view_src)

        sheet_src = (ui / "Components" / "ChatThemeBottomSheet.java").read_text(encoding="utf-8")
        self.assertIn("COLGRAM_VIDEO_WP_GALLERY_FIX = 1", sheet_src)
        self.assertIn("(entry.path != null && !entry.path.isEmpty()) ? entry.path : entry.imagePath", sheet_src)
        self.assertEqual(2, sheet_src.count("colgramIsVideoPhotoEntry(entry)"))
        self.assertEqual(2, sheet_src.count("colgramResolvePickedMediaPath(entry, isVideo)"))
        self.assertGreaterEqual(sheet_src.count("setIncludeVideosInGallery(true)"), 4)
        self.assertEqual(2, sheet_src.count("onInit(true, true, false)"))
        self.assertNotIn("if (entry.imagePath != null) {\n                            path = entry.imagePath;", sheet_src)

        list_src = (ui / "WallpapersListActivity.java").read_text(encoding="utf-8")
        self.assertIn("COLGRAM_VIDEO_WP_LIST_FIX = 1", list_src)
        self.assertIn("ColgramVideoWallpaperView.isVideoUri(getParentActivity(), pickedUri)", list_src)
        self.assertIn("ColgramVideoWallpaperView.isVideoFileName(file.getName())", list_src)
        self.assertIn("colgramExtractPickedUri(Intent data)", list_src)
        self.assertIn("updateRows();", list_src)

        preview_src = (ui / "ThemePreviewActivity.java").read_text(encoding="utf-8")
        self.assertIn("COLGRAM_VIDEO_WP_PREVIEW_FIX = 1", preview_src)
        self.assertIn("ColgramVideoWallpaperView.isVideoFileName(vidFile.getName())", preview_src)

        chat_src = (ui / "ChatActivity.java").read_text(encoding="utf-8")
        self.assertIn("COLGRAM_VIDEO_WP_CHAT_FIX = 1", chat_src)
        self.assertIn("public void colgramResumeVideoWallpaperFromPicker()", chat_src)
        self.assertIn("this::colgramResumeVideoWallpaperFromPicker", chat_src)
        self.assertIn("int targetBgIdx = contentView.indexOfChild(contentView.backgroundView);", chat_src)
        self.assertIn("vidIdx == bgIdx + 1 || (vidIdx > bgIdx && (chatIdx < 0 || vidIdx < chatIdx))", chat_src)

    def test_patch_video_wallpaper_fixes_idempotent_and_preserves_crlf(self):
        for crlf in (False, True):
            with self.subTest(crlf=crlf), tempfile.TemporaryDirectory() as tmp:
                repo = Path(tmp)
                files = self._write_repo_fixtures(repo, crlf=crlf)
                changed1 = patch_video_wallpaper_fixes(repo)
                self.assertEqual(5, len(changed1))
                self._assert_fixed_contracts(repo)
                snapshot = {p: p.read_bytes() for p in files}
                for raw in snapshot.values():
                    if crlf:
                        self.assertIn(b"\r\n", raw)
                    else:
                        self.assertNotIn(b"\r\n", raw)
                changed2 = patch_video_wallpaper_fixes(repo)
                self.assertEqual([], changed2)
                for p, prev in snapshot.items():
                    self.assertEqual(prev, p.read_bytes())

    def test_patch_video_wallpaper_fixes_rejects_unexpected_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            sheet = repo / TG_UI_REL / "Components" / "ChatThemeBottomSheet.java"
            sheet.parent.mkdir(parents=True, exist_ok=True)
            sheet.write_text("public class ChatThemeBottomSheet {}", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "video wallpaper"):
                patch_video_wallpaper_fixes(repo)

    def test_live_telegram_src_and_template_converged(self):
        repo = ROOT / "Telegram-Src"
        if not (repo / TG_UI_REL / "ColgramVideoWallpaperView.java").is_file():
            self.skipTest("Telegram-Src checkout is not installed")
        self.assertEqual([], patch_video_wallpaper_fixes(repo))
        self._assert_fixed_contracts(repo)
        tpl = (ROOT / "scripts" / "templates" / "ColgramVideoWallpaperView.java").read_text(encoding="utf-8")
        synced = (repo / TG_UI_REL / "ColgramVideoWallpaperView.java").read_text(encoding="utf-8")
        self.assertEqual(tpl, synced)


class ChatWallpaperUnlockContractsTest(unittest.TestCase):
    def test_colgram_config_per_chat_video_and_image_do_not_shadow_each_other(self):
        cfg = (ROOT / "colgram-core" / "src" / "main" / "java" / "org" / "colgram" / "core" / "ColgramConfig.java").read_text(encoding="utf-8")
        self.assertIn("if (!getExactChatImageWallpaper(dialogId).isEmpty()) return \"\";", cfg)
        self.assertIn("if (!getExactChatVideoWallpaper(dialogId).isEmpty()) return \"\";", cfg)

    def test_groups_and_channels_custom_wallpaper_contracts(self):
        repo = ROOT / "Telegram-Src"
        if not (repo / TG_UI_REL / "ChatActivity.java").is_file():
            self.skipTest("Telegram-Src checkout is not installed")
        chat = (repo / TG_UI_REL / "ChatActivity.java").read_text(encoding="utf-8")
        self.assertIn("!canEdit || currentChat != null || currentUser != null", chat)
        show_sheet = chat.split("private void showChatThemeBottomSheet()", 1)[1].split("}", 1)[0]
        self.assertNotIn("GroupColorActivity", show_sheet)
        self.assertNotIn("ChannelColorActivity", show_sheet)
        self.assertIn("new ChatThemeBottomSheet(ChatActivity.this, themeDelegate)", show_sheet)
        self.assertIn("hasExplicitPerChatTheme", chat)

        sheet = (repo / TG_UI_REL / "Components" / "ChatThemeBottomSheet.java").read_text(encoding="utf-8")
        self.assertNotIn("ReactionLevelRequiredBtn", sheet)
        self.assertNotIn("TYPE_BOOSTS_FOR_WALLPAPER", sheet)
        self.assertIn("clearLocalWallpaper(dId, false);", sheet)
        self.assertIn("setDialogTheme(dId, tlChatTheme, sendServerTheme);", sheet)

        preview = (repo / TG_UI_REL / "ThemePreviewActivity.java").read_text(encoding="utf-8")
        self.assertNotIn("TYPE_BOOSTS_FOR_CUSTOM_WALLPAPER", preview)
        self.assertIn("private boolean colgramRenderCurrentWallpaperToFile(File outFile)", preview)
        self.assertIn("boolean localOnlyChat = dialogId < 0 || isBotAccount;", preview)
        self.assertIn("if (selectedPattern != null || localOnlyChat)", preview)

        ctrl = (repo / "TMessagesProj" / "src" / "main" / "java" / "org" / "telegram" / "messenger" / "ChatThemeController.java").read_text(encoding="utf-8")
        self.assertIn("public void clearLocalWallpaper(long dialogId, boolean notify)", ctrl)
        self.assertIn("if (chatFull != null && chatFull.wallpaper != null)", ctrl)

    def test_apply_patches_converges_chat_wallpaper_unlock_specs(self):
        import importlib.util
        import shutil
        import tempfile

        ap = ROOT / "scripts" / "apply-patches.py"
        src = ap.read_text(encoding="utf-8")
        self.assertIn("inject_chat_wallpaper_unlock_patches(target_repo)", src.split("def main():", 1)[1])

        spec = importlib.util.spec_from_file_location("apply_patches_wp_unlock_mod", ap)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)

        repo = ROOT / "Telegram-Src"
        if not (repo / TG_UI_REL / "ChatActivity.java").is_file():
            self.skipTest("Telegram-Src checkout is not installed")
        with tempfile.TemporaryDirectory() as tmp:
            tmp_tg = Path(tmp) / "Telegram-Src"
            for rel in {r for r, _, _, _ in mod._CHAT_WALLPAPER_UNLOCK_PATCH_SPECS}:
                dst = tmp_tg / rel
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(repo / rel, dst)
            mod.PATCH_MISSES.clear()
            mod.inject_chat_wallpaper_unlock_patches(str(tmp_tg))
            self.assertEqual([], mod.PATCH_MISSES)


if __name__ == "__main__":
    unittest.main()
