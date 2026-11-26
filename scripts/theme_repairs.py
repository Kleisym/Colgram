"""Pinned, component-level theme fixes; no global override of other theme keys."""
from pathlib import Path

STORY_HINT_MARKER = "// COLGRAM_STORY_HINT_PALETTE = 1"
VIDEO_WP_VIEW_MARKER = "public static boolean isVideoUri("
VIDEO_WP_GALLERY_MARKER = "// COLGRAM_VIDEO_WP_GALLERY_FIX = 1"
VIDEO_WP_LIST_MARKER = "// COLGRAM_VIDEO_WP_LIST_FIX = 1"
VIDEO_WP_PREVIEW_MARKER = "// COLGRAM_VIDEO_WP_PREVIEW_FIX = 1"
VIDEO_WP_CHAT_MARKER = "// COLGRAM_VIDEO_WP_CHAT_FIX = 1"


def _replace_once(source, anchor, replacement, detail):
    if source.count(anchor) != 1:
        raise ValueError(f"Missing or ambiguous story hint anchor: {detail}")
    return source.replace(anchor, replacement, 1)


def _replace_exact_once(source, anchor, replacement, detail):
    if source.count(anchor) != 1:
        raise ValueError(f"Missing or ambiguous video wallpaper anchor: {detail}")
    return source.replace(anchor, replacement, 1)


def patch_story_hints(source):
    if STORY_HINT_MARKER in source:
        return source

    regular_init = "                storyHint = new HintView2(context, HintView2.DIRECTION_RIGHT)"
    source = _replace_once(
        source, regular_init,
        "                final org.colgram.core.ColgramThemePolicy.HintPalette hintColors = colgramStoryHintColors();\n"
        + regular_init, "regular constructor",
    )
    source = _replace_once(
        source,
        "                        .setBgColor(getThemedColor(Theme.key_undo_background))\n"
        "                        .setOnHiddenListener(",
        "                        .setBgColor(hintColors.background)\n"
        "                        .setTextColor(hintColors.text)\n"
        "                        .setOnHiddenListener(",
        "regular paints",
    )
    # Replace the stock link helper with its explicit-color variant. Stock type 0
    # sets a global link color rather than the requested undo key.
    source = _replace_once(
        source,
        '            Spannable text = AndroidUtilities.replaceSingleTag(LocaleController.getString("StoriesPremiumHint2")'
        ".replace('\\n', ' '), Theme.key_undo_cancelColor, 0, () -> {",
        "            final org.colgram.core.ColgramThemePolicy.HintPalette hintColors = colgramStoryHintColors();\n"
        '            Spannable text = AndroidUtilities.replaceSingleLinkBold(LocaleController.getString("StoriesPremiumHint2")'
        ".replace('\\n', ' '), hintColors.link, () -> {",
        "premium text",
    )
    source = _replace_once(
        source,
        "                .setBgColor(getThemedColor(Theme.key_undo_background));",
        "                .setBgColor(hintColors.background)\n"
        "                .setTextColor(hintColors.text);",
        "premium paints",
    )

    show = "            storyHint.show();"
    for indent in ("            ", "                "):
        source = _replace_once(
            source,
            "\n" + indent + show.strip(),
            "\n" + indent + "showColgramStoryHint();",
            f"regular show ({len(indent)} spaces)",
        )
    method = "    private void updateStoriesPosting() {"
    return _replace_once(
        source, method,
        f"""    {STORY_HINT_MARKER}
    private org.colgram.core.ColgramThemePolicy.HintPalette colgramStoryHintColors() {{
        return org.colgram.core.ColgramThemePolicy.forStoryHint(
                getThemedColor(Theme.key_windowBackgroundWhite),
                getThemedColor(Theme.key_undo_background),
                getThemedColor(Theme.key_undo_cancelColor));
    }}

    private void showColgramStoryHint() {{
        final org.colgram.core.ColgramThemePolicy.HintPalette palette = colgramStoryHintColors();
        storyHint.setBgColor(palette.background).setTextColor(palette.text).show();
    }}

{method}""", "palette helper",
    )


def _patch_video_wallpaper_view_source(source):
    if (
        VIDEO_WP_VIEW_MARKER in source
        and "ColgramPowerGuard" not in source
        and "private String activePlayerPath;" in source
        and "mp.setAudioAttributes(" in source
        and "mp.setDataSource(fis.getFD())" in source
        and "handlePlayerFailure();" in source
        and "postDelayed(fallbackCheckRunnable, 120L);" in source
    ):
        return source

    source = _replace_exact_once(
        source,
        "import android.content.Context;\n"
        "import android.graphics.Bitmap;\n"
        "import android.graphics.BitmapFactory;\n"
        "import android.graphics.Matrix;\n"
        "import android.graphics.SurfaceTexture;\n"
        "import android.graphics.drawable.BitmapDrawable;\n"
        "import android.media.MediaMetadataRetriever;\n"
        "import android.media.MediaPlayer;\n"
        "import android.net.Uri;\n"
        "import android.util.LruCache;\n"
        "import android.view.Surface;\n"
        "import android.view.TextureView;\n"
        "import android.view.View;\n"
        "import android.widget.Toast;\n\n"
        "import androidx.annotation.NonNull;\n\n"
        "import org.colgram.core.ColgramConfig;\n"
        "import org.telegram.messenger.AndroidUtilities;\n"
        "import org.telegram.messenger.ApplicationLoader;\n"
        "import org.telegram.messenger.DispatchQueue;\n"
        "import org.telegram.messenger.NotificationCenter;\n"
        "import org.telegram.messenger.Utilities;\n\n"
        "import java.io.File;\n"
        "import java.io.FileInputStream;\n"
        "import java.io.FileOutputStream;\n"
        "import java.io.InputStream;\n"
        "import java.io.OutputStream;\n"
        "import java.util.concurrent.ConcurrentHashMap;",
        "import android.content.ContentResolver;\n"
        "import android.content.Context;\n"
        "import android.database.Cursor;\n"
        "import android.graphics.Bitmap;\n"
        "import android.graphics.BitmapFactory;\n"
        "import android.graphics.Matrix;\n"
        "import android.graphics.SurfaceTexture;\n"
        "import android.graphics.drawable.BitmapDrawable;\n"
        "import android.media.AudioAttributes;\n"
        "import android.media.MediaMetadataRetriever;\n"
        "import android.media.MediaPlayer;\n"
        "import android.net.Uri;\n"
        "import android.os.Build;\n"
        "import android.provider.OpenableColumns;\n"
        "import android.util.LruCache;\n"
        "import android.view.Surface;\n"
        "import android.view.TextureView;\n"
        "import android.view.View;\n"
        "import android.widget.Toast;\n\n"
        "import androidx.annotation.NonNull;\n\n"
        "import org.colgram.core.ColgramConfig;\n"
        "import org.telegram.messenger.AndroidUtilities;\n"
        "import org.telegram.messenger.ApplicationLoader;\n"
        "import org.telegram.messenger.DispatchQueue;\n"
        "import org.telegram.messenger.NotificationCenter;\n"
        "import org.telegram.messenger.Utilities;\n\n"
        "import java.io.File;\n"
        "import java.io.FileInputStream;\n"
        "import java.io.FileOutputStream;\n"
        "import java.io.InputStream;\n"
        "import java.io.OutputStream;\n"
        "import java.util.Locale;\n"
        "import java.util.concurrent.ConcurrentHashMap;",
        "ColgramVideoWallpaperView imports",
    )

    source = _replace_exact_once(
        source,
        "    private String videoPath;\n    private MediaPlayer mediaPlayer;",
        "    private String videoPath;\n    private String activePlayerPath;\n    private MediaPlayer mediaPlayer;",
        "ColgramVideoWallpaperView activePlayerPath field",
    )

    source = _replace_exact_once(
        source,
        "    private void applyValidatedVideoPath(String clean) {\n"
        "        if (clean.equals(this.videoPath) && mediaPlayer != null) {\n"
        "            setVisibility(View.VISIBLE);\n"
        "            if (shouldBePlaying()) {\n"
        "                if (renderSurface == null || !renderSurface.isValid()) {\n"
        "                    if (isAvailable() && getSurfaceTexture() != null) {\n"
        "                        surfaceReady = true;\n"
        "                        startPlayer(getSurfaceTexture());\n"
        "                    }\n"
        "                } else if (playerPrepared) {\n"
        "                    // CRITICAL: NEVER call start() while MediaPlayer is still in Preparing state!\n"
        "                    try {\n"
        "                        if (!mediaPlayer.isPlaying()) {\n"
        "                            mediaPlayer.start();\n"
        "                        }\n"
        "                        if (firstFrameRendered && getAlpha() < 1f) {\n"
        "                            setAlpha(1f);\n"
        "                        }\n"
        "                        scheduleStartFallbackCheck(playerGeneration);\n"
        "                    } catch (Throwable ignored) {}\n"
        "                }\n"
        "            }\n"
        "            return;\n"
        "        }\n"
        "        this.videoPath = clean;\n"
        "        errorRetryCount = 0;\n"
        "        stallRecoveryCount = 0;\n"
        "        setVisibility(View.VISIBLE);\n"
        "        if (shouldBePlaying() && isAvailable() && getSurfaceTexture() != null) {\n"
        "            surfaceReady = true;\n"
        "            startPlayer(getSurfaceTexture());\n"
        "        }\n"
        "    }\n\n"
        "    public void setHostPaused(boolean paused) {\n"
        "        pausedByHost = paused;\n"
        "        if (paused) {\n"
        "            pausePlaybackOnly();\n"
        "            return;\n"
        "        }\n"
        "        errorRetryCount = 0;\n"
        "        stallRecoveryCount = 0;\n"
        "        resumePlaybackIfReady();\n"
        "    }\n\n"
        "    public void onHostConfigurationChanged() {\n"
        "        applyCenterCropTransform();\n"
        "        if (shouldBePlaying()) {\n"
        "            resumePlaybackIfReady();\n"
        "        }\n"
        "    }\n\n"
        "    private boolean shouldBePlaying() {\n"
        "        Context ctx = getContext();\n"
        "        return !pausedByHost\n"
        "                && windowVisible\n"
        "                && isAttachedToWindow()\n"
        "                && getVisibility() == View.VISIBLE\n"
        "                && videoPath != null\n"
        "                && !videoPath.isEmpty()\n"
        "                && !org.colgram.core.ColgramPowerGuard.isPowerSaveMode(ctx)\n"
        "                && !org.colgram.core.ColgramPowerGuard.isThermalThrottled(ctx);\n"
        "    }",
        "    private void applyValidatedVideoPath(String clean) {\n"
        "        if (clean.equals(this.activePlayerPath) && mediaPlayer != null) {\n"
        "            setVisibility(View.VISIBLE);\n"
        "            if (shouldBePlaying()) {\n"
        "                if (renderSurface == null || !renderSurface.isValid()) {\n"
        "                    if (isAvailable() && getSurfaceTexture() != null) {\n"
        "                        surfaceReady = true;\n"
        "                        startPlayer(getSurfaceTexture());\n"
        "                    }\n"
        "                } else if (playerPrepared) {\n"
        "                    // CRITICAL: NEVER call start() while MediaPlayer is still in Preparing state!\n"
        "                    try {\n"
        "                        if (!mediaPlayer.isPlaying()) {\n"
        "                            mediaPlayer.start();\n"
        "                        }\n"
        "                        if ((firstFrameRendered || mediaPlayer.isPlaying()) && getAlpha() < 1f) {\n"
        "                            setAlpha(1f);\n"
        "                        }\n"
        "                        scheduleStartFallbackCheck(playerGeneration);\n"
        "                    } catch (Throwable ignored) {}\n"
        "                }\n"
        "            }\n"
        "            return;\n"
        "        }\n"
        "        this.videoPath = clean;\n"
        "        errorRetryCount = 0;\n"
        "        stallRecoveryCount = 0;\n"
        "        setVisibility(View.VISIBLE);\n"
        "        if (shouldBePlaying() && isAvailable() && getSurfaceTexture() != null) {\n"
        "            surfaceReady = true;\n"
        "            startPlayer(getSurfaceTexture());\n"
        "        }\n"
        "    }\n\n"
        "    public void setHostPaused(boolean paused) {\n"
        "        pausedByHost = paused;\n"
        "        if (paused) {\n"
        "            pausePlaybackOnly();\n"
        "            return;\n"
        "        }\n"
        "        errorRetryCount = 0;\n"
        "        stallRecoveryCount = 0;\n"
        "        resumePlaybackIfReady();\n"
        "    }\n\n"
        "    public void onHostConfigurationChanged() {\n"
        "        applyCenterCropTransform();\n"
        "        if (shouldBePlaying()) {\n"
        "            resumePlaybackIfReady();\n"
        "        }\n"
        "    }\n\n"
        "    private boolean shouldBePlaying() {\n"
        "        return !pausedByHost\n"
        "                && windowVisible\n"
        "                && isAttachedToWindow()\n"
        "                && getVisibility() == View.VISIBLE\n"
        "                && videoPath != null\n"
        "                && !videoPath.isEmpty();\n"
        "    }",
        "ColgramVideoWallpaperView shouldBePlaying and applyValidatedVideoPath",
    )

    source = _replace_exact_once(
        source,
        "        } else if (playerPrepared) {\n"
        "            try {\n"
        "                if (!mediaPlayer.isPlaying()) {\n"
        "                    mediaPlayer.start();\n"
        "                }\n"
        "                if (firstFrameRendered && getAlpha() < 1f) {\n"
        "                    setAlpha(1f);\n"
        "                }\n"
        "                scheduleStartFallbackCheck(playerGeneration);\n"
        "            } catch (Throwable ignored) {}\n"
        "        }\n"
        "    }\n\n"
        "    private void scheduleStartFallbackCheck(final int gen) {\n"
        "        cancelScheduledChecks();\n"
        "        fallbackCheckRunnable = () -> {\n"
        "            fallbackCheckRunnable = null;\n"
        "            if (gen != playerGeneration || mediaPlayer == null || !playerPrepared || !shouldBePlaying()) {\n"
        "                return;\n"
        "            }\n"
        "            try {\n"
        "                if (!mediaPlayer.isPlaying()) {\n"
        "                    mediaPlayer.start();\n"
        "                }\n"
        "                if (firstFrameRendered && mediaPlayer.isPlaying() && getAlpha() < 1f) {\n"
        "                    setAlpha(1f);\n"
        "                }\n"
        "            } catch (Throwable ignored) {}\n"
        "        };\n"
        "        watchdogCheckRunnable = () -> {\n"
        "            watchdogCheckRunnable = null;\n"
        "            if (gen != playerGeneration || mediaPlayer == null || !playerPrepared || !shouldBePlaying()) {\n"
        "                return;\n"
        "            }\n"
        "            try {\n"
        "                if (!firstFrameRendered || renderSurface == null || !renderSurface.isValid()) {\n"
        "                    if (stallRecoveryCount < 1 && isAvailable() && getSurfaceTexture() != null) {\n"
        "                        stallRecoveryCount++;\n"
        "                        startPlayer(getSurfaceTexture());\n"
        "                    }\n"
        "                    return;\n"
        "                }\n"
        "                if (!mediaPlayer.isPlaying()) {\n"
        "                    mediaPlayer.start();\n"
        "                }\n"
        "                if (mediaPlayer.isPlaying() && getAlpha() < 1f) {\n"
        "                    setAlpha(1f);\n"
        "                }\n"
        "            } catch (Throwable ignored) {}\n"
        "        };\n"
        "        postDelayed(fallbackCheckRunnable, 160L);\n"
        "        postDelayed(watchdogCheckRunnable, 900L);\n"
        "    }\n\n"
        "    public void releasePlayer() {\n"
        "        playerGeneration++;\n"
        "        playerPrepared = false;\n"
        "        firstFrameRendered = false;\n"
        "        cancelScheduledChecks();\n"
        "        final MediaPlayer mp = mediaPlayer;\n"
        "        mediaPlayer = null;\n"
        "        if (mp != null) {\n"
        "            try {\n"
        "                mp.setOnPreparedListener(null);\n"
        "                mp.setOnErrorListener(null);\n"
        "                mp.setOnInfoListener(null);\n"
        "                mp.setOnVideoSizeChangedListener(null);\n"
        "            } catch (Throwable ignored) {}\n"
        "            try {\n"
        "                mp.setSurface(null);\n"
        "            } catch (Throwable ignored) {}\n"
        "        }\n"
        "        Surface s = renderSurface;\n"
        "        renderSurface = null;\n"
        "        if (s != null) {\n"
        "            try {\n"
        "                s.release();\n"
        "            } catch (Throwable ignored) {}\n"
        "        }\n"
        "        if (mp != null) {\n"
        "            RELEASE_QUEUE.postRunnable(() -> {\n"
        "                try {\n"
        "                    mp.reset();\n"
        "                } catch (Throwable ignored) {}\n"
        "                try {\n"
        "                    mp.release();\n"
        "                } catch (Throwable ignored) {}\n"
        "            });\n"
        "        }\n"
        "    }\n\n"
        "    private void startPlayer(SurfaceTexture texture) {\n"
        "        releasePlayer();\n"
        "        if (texture == null || !shouldBePlaying()) {\n"
        "            return;\n"
        "        }\n"
        "        if (Boolean.FALSE.equals(FILE_EXISTS_CACHE.get(videoPath))) {\n"
        "            return;\n"
        "        }\n"
        "        final int gen = playerGeneration;\n"
        "        try {\n"
        "            // Keep underlying poster crisp during ~100ms codec warmup without triggering HWUI skip-draw (0.01f > 0).\n"
        "            setAlpha(0.01f);\n"
        "            firstFrameRendered = false;\n"
        "            renderSurface = new Surface(texture);\n"
        "            final MediaPlayer mp = new MediaPlayer();\n"
        "            mp.setSurface(renderSurface);\n"
        "            mp.setDataSource(videoPath);\n"
        "            mp.setLooping(true);\n"
        "            mp.setVolume(0f, 0f);\n"
        "            mp.setOnVideoSizeChangedListener((player, width, height) -> {\n"
        "                if (mediaPlayer != player) return;\n"
        "                videoWidth = width;\n"
        "                videoHeight = height;\n"
        "                applyCenterCropTransform();\n"
        "            });\n"
        "            mp.setOnInfoListener((player, what, extra) -> {\n"
        "                if (mediaPlayer == player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {\n"
        "                    firstFrameRendered = true;\n"
        "                    if (shouldBePlaying() && getAlpha() < 1f) {\n"
        "                        setAlpha(1f);\n"
        "                    }\n"
        "                    applyCenterCropTransform();\n"
        "                }\n"
        "                return false;\n"
        "            });\n"
        "            mp.setOnPreparedListener(player -> {\n"
        "                if (mediaPlayer != player) return;\n"
        "                playerPrepared = true;\n"
        "                videoWidth = player.getVideoWidth();\n"
        "                videoHeight = player.getVideoHeight();\n"
        "                applyCenterCropTransform();\n"
        "                if (shouldBePlaying()) {\n"
        "                    try {\n"
        "                        player.start();\n"
        "                        scheduleStartFallbackCheck(gen);\n"
        "                    } catch (Throwable ignored) {}\n"
        "                }\n"
        "            });\n"
        "            mp.setOnErrorListener((player, what, extra) -> {\n"
        "                if (mediaPlayer != player) return true;\n"
        "                releasePlayer();\n"
        "                setAlpha(0.01f);\n"
        "                if (shouldBePlaying() && errorRetryCount < 2) {\n"
        "                    errorRetryCount++;\n"
        "                    final int retryGen = playerGeneration;\n"
        "                    retryRunnable = () -> {\n"
        "                        retryRunnable = null;\n"
        "                        if (retryGen == playerGeneration && shouldBePlaying()\n"
        "                                && isAvailable() && getSurfaceTexture() != null && mediaPlayer == null) {\n"
        "                            startPlayer(getSurfaceTexture());\n"
        "                        }\n"
        "                    };\n"
        "                    postDelayed(retryRunnable, 250L * errorRetryCount);\n"
        "                } else {\n"
        "                    setVisibility(View.GONE);\n"
        "                }\n"
        "                return true;\n"
        "            });\n"
        "            mediaPlayer = mp;\n"
        "            mp.prepareAsync();\n"
        "        } catch (Throwable ignored) {\n"
        "            releasePlayer();\n"
        "            setAlpha(0.01f);\n"
        "            setVisibility(View.GONE);\n"
        "        }\n"
        "    }",
        "        } else if (playerPrepared) {\n"
        "            try {\n"
        "                if (!mediaPlayer.isPlaying()) {\n"
        "                    mediaPlayer.start();\n"
        "                }\n"
        "                if ((firstFrameRendered || mediaPlayer.isPlaying()) && getAlpha() < 1f) {\n"
        "                    setAlpha(1f);\n"
        "                }\n"
        "                scheduleStartFallbackCheck(playerGeneration);\n"
        "            } catch (Throwable ignored) {}\n"
        "        }\n"
        "    }\n\n"
        "    private void scheduleStartFallbackCheck(final int gen) {\n"
        "        cancelScheduledChecks();\n"
        "        fallbackCheckRunnable = () -> {\n"
        "            fallbackCheckRunnable = null;\n"
        "            if (gen != playerGeneration || mediaPlayer == null || !playerPrepared || !shouldBePlaying()) {\n"
        "                return;\n"
        "            }\n"
        "            try {\n"
        "                if (!mediaPlayer.isPlaying()) {\n"
        "                    mediaPlayer.start();\n"
        "                }\n"
        "                if ((firstFrameRendered || mediaPlayer.isPlaying()) && getAlpha() < 1f) {\n"
        "                    setAlpha(1f);\n"
        "                }\n"
        "            } catch (Throwable ignored) {}\n"
        "        };\n"
        "        watchdogCheckRunnable = () -> {\n"
        "            watchdogCheckRunnable = null;\n"
        "            if (gen != playerGeneration || mediaPlayer == null || !playerPrepared || !shouldBePlaying()) {\n"
        "                return;\n"
        "            }\n"
        "            try {\n"
        "                if (renderSurface == null || !renderSurface.isValid() || (!firstFrameRendered && !mediaPlayer.isPlaying())) {\n"
        "                    if (stallRecoveryCount < 1 && isAvailable() && getSurfaceTexture() != null) {\n"
        "                        stallRecoveryCount++;\n"
        "                        startPlayer(getSurfaceTexture());\n"
        "                    }\n"
        "                    return;\n"
        "                }\n"
        "                if (!mediaPlayer.isPlaying()) {\n"
        "                    mediaPlayer.start();\n"
        "                }\n"
        "                if ((firstFrameRendered || mediaPlayer.isPlaying()) && getAlpha() < 1f) {\n"
        "                    setAlpha(1f);\n"
        "                }\n"
        "            } catch (Throwable ignored) {}\n"
        "        };\n"
        "        postDelayed(fallbackCheckRunnable, 120L);\n"
        "        postDelayed(watchdogCheckRunnable, 900L);\n"
        "    }\n\n"
        "    public void releasePlayer() {\n"
        "        playerGeneration++;\n"
        "        activePlayerPath = null;\n"
        "        playerPrepared = false;\n"
        "        firstFrameRendered = false;\n"
        "        cancelScheduledChecks();\n"
        "        final MediaPlayer mp = mediaPlayer;\n"
        "        mediaPlayer = null;\n"
        "        if (mp != null) {\n"
        "            try {\n"
        "                mp.setOnPreparedListener(null);\n"
        "                mp.setOnCompletionListener(null);\n"
        "                mp.setOnErrorListener(null);\n"
        "                mp.setOnInfoListener(null);\n"
        "                mp.setOnVideoSizeChangedListener(null);\n"
        "            } catch (Throwable ignored) {}\n"
        "            try {\n"
        "                mp.setSurface(null);\n"
        "            } catch (Throwable ignored) {}\n"
        "        }\n"
        "        Surface s = renderSurface;\n"
        "        renderSurface = null;\n"
        "        if (s != null) {\n"
        "            try {\n"
        "                s.release();\n"
        "            } catch (Throwable ignored) {}\n"
        "        }\n"
        "        if (mp != null) {\n"
        "            RELEASE_QUEUE.postRunnable(() -> {\n"
        "                try {\n"
        "                    mp.reset();\n"
        "                } catch (Throwable ignored) {}\n"
        "                try {\n"
        "                    mp.release();\n"
        "                } catch (Throwable ignored) {}\n"
        "            });\n"
        "        }\n"
        "    }\n\n"
        "    private void startPlayer(SurfaceTexture texture) {\n"
        "        releasePlayer();\n"
        "        if (texture == null || !shouldBePlaying()) {\n"
        "            return;\n"
        "        }\n"
        "        if (Boolean.FALSE.equals(FILE_EXISTS_CACHE.get(videoPath))) {\n"
        "            return;\n"
        "        }\n"
        "        final int gen = playerGeneration;\n"
        "        try {\n"
        "            // Keep underlying poster crisp during ~100ms codec warmup without triggering HWUI skip-draw (0.01f > 0).\n"
        "            setAlpha(0.01f);\n"
        "            firstFrameRendered = false;\n"
        "            activePlayerPath = videoPath;\n"
        "            renderSurface = new Surface(texture);\n"
        "            final MediaPlayer mp = new MediaPlayer();\n"
        "            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {\n"
        "                mp.setAudioAttributes(new AudioAttributes.Builder()\n"
        "                        .setUsage(AudioAttributes.USAGE_MEDIA)\n"
        "                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)\n"
        "                        .build());\n"
        "            }\n"
        "            mp.setSurface(renderSurface);\n"
        "            try {\n"
        "                mp.setDataSource(videoPath);\n"
        "            } catch (Throwable primaryErr) {\n"
        "                try (FileInputStream fis = new FileInputStream(videoPath)) {\n"
        "                    mp.setDataSource(fis.getFD());\n"
        "                }\n"
        "            }\n"
        "            mp.setLooping(true);\n"
        "            mp.setVolume(0f, 0f);\n"
        "            mp.setOnVideoSizeChangedListener((player, width, height) -> {\n"
        "                if (mediaPlayer != player) return;\n"
        "                videoWidth = width;\n"
        "                videoHeight = height;\n"
        "                applyCenterCropTransform();\n"
        "            });\n"
        "            mp.setOnInfoListener((player, what, extra) -> {\n"
        "                if (mediaPlayer == player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {\n"
        "                    firstFrameRendered = true;\n"
        "                    if (shouldBePlaying() && getAlpha() < 1f) {\n"
        "                        setAlpha(1f);\n"
        "                    }\n"
        "                    applyCenterCropTransform();\n"
        "                }\n"
        "                return false;\n"
        "            });\n"
        "            mp.setOnCompletionListener(player -> {\n"
        "                if (mediaPlayer != player || !shouldBePlaying()) return;\n"
        "                try {\n"
        "                    player.seekTo(0);\n"
        "                    player.start();\n"
        "                } catch (Throwable ignored) {}\n"
        "            });\n"
        "            mp.setOnPreparedListener(player -> {\n"
        "                if (mediaPlayer != player) return;\n"
        "                playerPrepared = true;\n"
        "                try {\n"
        "                    player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);\n"
        "                } catch (Throwable ignored) {}\n"
        "                videoWidth = player.getVideoWidth();\n"
        "                videoHeight = player.getVideoHeight();\n"
        "                applyCenterCropTransform();\n"
        "                if (shouldBePlaying()) {\n"
        "                    try {\n"
        "                        player.start();\n"
        "                        if ((firstFrameRendered || player.isPlaying()) && getAlpha() < 1f) {\n"
        "                            setAlpha(1f);\n"
        "                        }\n"
        "                        scheduleStartFallbackCheck(gen);\n"
        "                    } catch (Throwable ignored) {}\n"
        "                }\n"
        "            });\n"
        "            mp.setOnErrorListener((player, what, extra) -> {\n"
        "                if (mediaPlayer != player) return true;\n"
        "                handlePlayerFailure();\n"
        "                return true;\n"
        "            });\n"
        "            mediaPlayer = mp;\n"
        "            mp.prepareAsync();\n"
        "        } catch (Throwable ignored) {\n"
        "            handlePlayerFailure();\n"
        "        }\n"
        "    }\n\n"
        "    private void handlePlayerFailure() {\n"
        "        releasePlayer();\n"
        "        setAlpha(0.01f);\n"
        "        if (shouldBePlaying() && errorRetryCount < 2) {\n"
        "            errorRetryCount++;\n"
        "            final int retryGen = playerGeneration;\n"
        "            retryRunnable = () -> {\n"
        "                retryRunnable = null;\n"
        "                if (retryGen == playerGeneration && shouldBePlaying()\n"
        "                        && isAvailable() && getSurfaceTexture() != null && mediaPlayer == null) {\n"
        "                    startPlayer(getSurfaceTexture());\n"
        "                }\n"
        "            };\n"
        "            postDelayed(retryRunnable, 250L * errorRetryCount);\n"
        "        } else {\n"
        "            setVisibility(View.GONE);\n"
        "        }\n"
        "    }",
        "ColgramVideoWallpaperView playback reveal and FD fallback",
    )

    source = _replace_exact_once(
        source,
        "    @Override\n"
        "    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {\n"
        "        firstFrameRendered = true;\n"
        "        if (getAlpha() < 1f && mediaPlayer != null && playerPrepared && shouldBePlaying()) {\n"
        "            setAlpha(1f);\n"
        "        }\n"
        "    }",
        "    @Override\n"
        "    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {\n"
        "        firstFrameRendered = true;\n"
        "        if (mediaPlayer != null && shouldBePlaying() && getAlpha() < 1f) {\n"
        "            setAlpha(1f);\n"
        "        }\n"
        "    }\n\n"
        "    public static boolean isVideoFileName(String pathOrName) {\n"
        "        if (pathOrName == null) {\n"
        "            return false;\n"
        "        }\n"
        "        String lower = pathOrName.trim().toLowerCase(Locale.US);\n"
        "        int q = lower.indexOf('?');\n"
        "        if (q >= 0) {\n"
        "            lower = lower.substring(0, q);\n"
        "        }\n"
        "        int h = lower.indexOf('#');\n"
        "        if (h >= 0) {\n"
        "            lower = lower.substring(0, h);\n"
        "        }\n"
        "        return lower.endsWith(\".mp4\")\n"
        "                || lower.endsWith(\".webm\")\n"
        "                || lower.endsWith(\".mkv\")\n"
        "                || lower.endsWith(\".mov\")\n"
        "                || lower.endsWith(\".m4v\")\n"
        "                || lower.endsWith(\".3gp\")\n"
        "                || lower.endsWith(\".avi\");\n"
        "    }\n\n"
        "    public static boolean isVideoUri(Context ctx, Uri uri) {\n"
        "        if (uri == null) {\n"
        "            return false;\n"
        "        }\n"
        "        if (isVideoFileName(uri.toString()) || isVideoFileName(uri.getLastPathSegment())) {\n"
        "            return true;\n"
        "        }\n"
        "        Context safeCtx = ctx != null ? ctx.getApplicationContext() : ApplicationLoader.applicationContext;\n"
        "        if (safeCtx == null) {\n"
        "            return false;\n"
        "        }\n"
        "        try {\n"
        "            ContentResolver cr = safeCtx.getContentResolver();\n"
        "            if (cr != null) {\n"
        "                String mime = cr.getType(uri);\n"
        "                if (mime != null && mime.toLowerCase(Locale.US).startsWith(\"video/\")) {\n"
        "                    return true;\n"
        "                }\n"
        "                try (Cursor cursor = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {\n"
        "                    if (cursor != null && cursor.moveToFirst()) {\n"
        "                        String name = cursor.getString(0);\n"
        "                        if (isVideoFileName(name)) {\n"
        "                            return true;\n"
        "                        }\n"
        "                    }\n"
        "                } catch (Throwable ignored) {}\n"
        "            }\n"
        "        } catch (Throwable ignored) {}\n"
        "        return false;\n"
        "    }",
        "ColgramVideoWallpaperView onSurfaceTextureUpdated and video helpers",
    )

    source = _replace_exact_once(
        source,
        "        MediaMetadataRetriever mmr = new MediaMetadataRetriever();\n"
        "        try {\n"
        "            mmr.setDataSource(videoPath);\n"
        "            Bitmap frame = mmr.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);",
        "        MediaMetadataRetriever mmr = new MediaMetadataRetriever();\n"
        "        try {\n"
        "            try {\n"
        "                mmr.setDataSource(videoPath);\n"
        "            } catch (Throwable primaryErr) {\n"
        "                try (FileInputStream fis = new FileInputStream(videoPath)) {\n"
        "                    mmr.setDataSource(fis.getFD());\n"
        "                }\n"
        "            }\n"
        "            Bitmap frame = mmr.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);",
        "ColgramVideoWallpaperView poster retriever FD fallback",
    )
    return source


def _patch_chat_theme_bottom_sheet_source(source):
    if VIDEO_WP_GALLERY_MARKER in source:
        return source

    source = _replace_exact_once(
        source,
        "    public static void openGalleryForBackground(\n"
        "            Activity activity,\n"
        "            BaseFragment fragment,\n"
        "            long dialogId,\n"
        "            Theme.ResourcesProvider resourcesProvider,\n"
        "            Utilities.Callback<TLRPC.WallPaper> onSet,\n"
        "            ThemePreviewActivity.DayNightSwitchDelegate toggleTheme,\n"
        "            TL_stories.TL_premium_boostsStatus cachedBoostsStatus\n"
        "    ) {\n"
        "        ChatAttachAlert chatAttachAlert = new ChatAttachAlert(activity, fragment, false, false, false, resourcesProvider);\n"
        "        chatAttachAlert.drawNavigationBar = true;\n"
        "        chatAttachAlert.setupPhotoPicker(LocaleController.getString(R.string.ChooseBackground));\n"
        "        chatAttachAlert.colgramEnableVideoWallpapers();\n"
        "        chatAttachAlert.setDelegate(new ChatAttachAlert.ChatAttachViewDelegate() {\n"
        "            long start;\n"
        "            @Override\n"
        "            public boolean selectItemOnClicking() {\n"
        "                start = System.currentTimeMillis();\n"
        "                return true;\n"
        "            }\n\n"
        "            @Override\n"
        "            public void didPressedButton(int button, boolean arg, boolean notify, int scheduleDate, int scheduleRepeatPeriod, long effectId, boolean invertMedia, boolean forceDocument, long payStars) {\n"
        "                try {\n"
        "                    HashMap<Object, Object> photos = chatAttachAlert.getPhotoLayout().getSelectedPhotos();\n"
        "                    if (!photos.isEmpty()) {\n"
        "                        MediaController.PhotoEntry entry = (MediaController.PhotoEntry) photos.values().iterator().next();\n"
        "                        String path;\n"
        "                        if (entry.imagePath != null) {\n"
        "                            path = entry.imagePath;\n"
        "                        } else {\n"
        "                            path = entry.path;\n"
        "                        }\n"
        "                        if (path != null) {\n"
        "                            if (entry.isVideo || path.toLowerCase().endsWith(\".mp4\") || path.toLowerCase().endsWith(\".webm\") || path.toLowerCase().endsWith(\".mkv\")) {\n"
        "                                org.telegram.ui.ColgramVideoWallpaperView.copyFileAndApplyVideoWallpaper(\n"
        "                                        activity, new File(path), dialogId, null);\n"
        "                                chatAttachAlert.dismissInternal();\n"
        "                                if (onSet != null) {\n"
        "                                    onSet.run(null);\n"
        "                                }\n"
        "                                return;\n"
        "                            }",
        f"    {VIDEO_WP_GALLERY_MARKER}\n"
        "    private static boolean colgramIsVideoPhotoEntry(MediaController.PhotoEntry entry) {\n"
        "        if (entry == null) {\n"
        "            return false;\n"
        "        }\n"
        "        return entry.isVideo\n"
        "                || org.telegram.ui.ColgramVideoWallpaperView.isVideoFileName(entry.path)\n"
        "                || org.telegram.ui.ColgramVideoWallpaperView.isVideoFileName(entry.imagePath);\n"
        "    }\n\n"
        "    private static String colgramResolvePickedMediaPath(MediaController.PhotoEntry entry, boolean isVideo) {\n"
        "        if (entry == null) {\n"
        "            return null;\n"
        "        }\n"
        "        if (isVideo) {\n"
        "            return (entry.path != null && !entry.path.isEmpty()) ? entry.path : entry.imagePath;\n"
        "        }\n"
        "        return (entry.imagePath != null && !entry.imagePath.isEmpty()) ? entry.imagePath : entry.path;\n"
        "    }\n\n"
        "    private static void colgramApplyPickedVideoPath(Activity activity, String path, long dialogId, Runnable onApplied) {\n"
        "        if (activity == null || path == null || path.isEmpty()) {\n"
        "            return;\n"
        "        }\n"
        "        if (path.startsWith(\"content://\")) {\n"
        "            org.telegram.ui.ColgramVideoWallpaperView.copyUriAndApplyVideoWallpaper(\n"
        "                    activity, Uri.parse(path), dialogId, onApplied);\n"
        "        } else {\n"
        "            org.telegram.ui.ColgramVideoWallpaperView.copyFileAndApplyVideoWallpaper(\n"
        "                    activity, new File(path), dialogId, onApplied);\n"
        "        }\n"
        "    }\n\n"
        "    public static void openGalleryForBackground(\n"
        "            Activity activity,\n"
        "            BaseFragment fragment,\n"
        "            long dialogId,\n"
        "            Theme.ResourcesProvider resourcesProvider,\n"
        "            Utilities.Callback<TLRPC.WallPaper> onSet,\n"
        "            ThemePreviewActivity.DayNightSwitchDelegate toggleTheme,\n"
        "            TL_stories.TL_premium_boostsStatus cachedBoostsStatus\n"
        "    ) {\n"
        "        ChatAttachAlert chatAttachAlert = new ChatAttachAlert(activity, fragment, false, false, false, resourcesProvider);\n"
        "        chatAttachAlert.drawNavigationBar = true;\n"
        "        chatAttachAlert.setupPhotoPicker(LocaleController.getString(R.string.ChooseBackground));\n"
        "        chatAttachAlert.colgramEnableVideoWallpapers();\n"
        "        if (chatAttachAlert.getPhotoLayout() != null) {\n"
        "            chatAttachAlert.getPhotoLayout().setIncludeVideosInGallery(true);\n"
        "        }\n"
        "        chatAttachAlert.setDelegate(new ChatAttachAlert.ChatAttachViewDelegate() {\n"
        "            long start;\n"
        "            @Override\n"
        "            public boolean selectItemOnClicking() {\n"
        "                start = System.currentTimeMillis();\n"
        "                return true;\n"
        "            }\n\n"
        "            @Override\n"
        "            public void didPressedButton(int button, boolean arg, boolean notify, int scheduleDate, int scheduleRepeatPeriod, long effectId, boolean invertMedia, boolean forceDocument, long payStars) {\n"
        "                try {\n"
        "                    HashMap<Object, Object> photos = chatAttachAlert.getPhotoLayout().getSelectedPhotos();\n"
        "                    if (!photos.isEmpty()) {\n"
        "                        MediaController.PhotoEntry entry = (MediaController.PhotoEntry) photos.values().iterator().next();\n"
        "                        boolean isVideo = colgramIsVideoPhotoEntry(entry);\n"
        "                        String path = colgramResolvePickedMediaPath(entry, isVideo);\n"
        "                        if (path != null && !path.isEmpty()) {\n"
        "                            if (isVideo) {\n"
        "                                colgramApplyPickedVideoPath(activity, path, dialogId, null);\n"
        "                                chatAttachAlert.dismissInternal();\n"
        "                                if (onSet != null) {\n"
        "                                    onSet.run(null);\n"
        "                                }\n"
        "                                return;\n"
        "                            }",
        "ChatThemeBottomSheet static openGalleryForBackground",
    )

    source = _replace_exact_once(
        source,
        "        chatAttachAlert.setMaxSelectedPhotos(1, false);\n"
        "        chatAttachAlert.init();\n"
        "        chatAttachAlert.getPhotoLayout().loadGalleryPhotos();\n"
        "        chatAttachAlert.show();\n"
        "    }\n\n\n"
        "    private void openGalleryForBackground() {\n"
        "        chatAttachAlert = new ChatAttachAlert(chatActivity.getParentActivity(), chatActivity, false, false, false, chatActivity.getResourceProvider());\n"
        "        chatAttachAlert.drawNavigationBar = true;\n"
        "        chatAttachAlert.setupPhotoPicker(LocaleController.getString(R.string.ChooseBackground));\n"
        "        chatAttachAlert.colgramEnableVideoWallpapers();\n"
        "        chatAttachAlert.setDelegate(new ChatAttachAlert.ChatAttachViewDelegate() {\n"
        "            long start;\n"
        "            @Override\n"
        "            public boolean selectItemOnClicking() {\n"
        "                start = System.currentTimeMillis();\n"
        "                return true;\n"
        "            }\n\n"
        "            @Override\n"
        "            public void didPressedButton(int button, boolean arg, boolean notify, int scheduleDate, int scheduleRepeatPeriod, long effectId, boolean invertMedia, boolean forceDocument, long payStars) {\n"
        "                try {\n"
        "                    HashMap<Object, Object> photos = chatAttachAlert.getPhotoLayout().getSelectedPhotos();\n"
        "                    if (!photos.isEmpty()) {\n"
        "                        MediaController.PhotoEntry entry = (MediaController.PhotoEntry) photos.values().iterator().next();\n"
        "                        String path;\n"
        "                        if (entry.imagePath != null) {\n"
        "                            path = entry.imagePath;\n"
        "                        } else {\n"
        "                            path = entry.path;\n"
        "                        }\n"
        "                        if (path != null) {\n"
        "                            if (entry.isVideo || path.toLowerCase().endsWith(\".mp4\") || path.toLowerCase().endsWith(\".webm\") || path.toLowerCase().endsWith(\".mkv\")) {\n"
        "                                org.telegram.ui.ColgramVideoWallpaperView.copyFileAndApplyVideoWallpaper(\n"
        "                                        chatActivity.getParentActivity(), new File(path), chatActivity.getDialogId(), null);\n"
        "                                isApplyClicked = true;\n"
        "                                chatAttachAlert.dismissInternal();\n"
        "                                dismiss();\n"
        "                                return;\n"
        "                            }",
        "        chatAttachAlert.setMaxSelectedPhotos(1, false);\n"
        "        chatAttachAlert.init();\n"
        "        chatAttachAlert.colgramEnableVideoWallpapers();\n"
        "        if (chatAttachAlert.getPhotoLayout() != null) {\n"
        "            chatAttachAlert.getPhotoLayout().setIncludeVideosInGallery(true);\n"
        "            chatAttachAlert.getPhotoLayout().onInit(true, true, false);\n"
        "        }\n"
        "        chatAttachAlert.getPhotoLayout().loadGalleryPhotos();\n"
        "        chatAttachAlert.show();\n"
        "    }\n\n\n"
        "    private void openGalleryForBackground() {\n"
        "        chatAttachAlert = new ChatAttachAlert(chatActivity.getParentActivity(), chatActivity, false, false, false, chatActivity.getResourceProvider());\n"
        "        chatAttachAlert.drawNavigationBar = true;\n"
        "        chatAttachAlert.setupPhotoPicker(LocaleController.getString(R.string.ChooseBackground));\n"
        "        chatAttachAlert.colgramEnableVideoWallpapers();\n"
        "        if (chatAttachAlert.getPhotoLayout() != null) {\n"
        "            chatAttachAlert.getPhotoLayout().setIncludeVideosInGallery(true);\n"
        "        }\n"
        "        chatAttachAlert.setDelegate(new ChatAttachAlert.ChatAttachViewDelegate() {\n"
        "            long start;\n"
        "            @Override\n"
        "            public boolean selectItemOnClicking() {\n"
        "                start = System.currentTimeMillis();\n"
        "                return true;\n"
        "            }\n\n"
        "            @Override\n"
        "            public void didPressedButton(int button, boolean arg, boolean notify, int scheduleDate, int scheduleRepeatPeriod, long effectId, boolean invertMedia, boolean forceDocument, long payStars) {\n"
        "                try {\n"
        "                    HashMap<Object, Object> photos = chatAttachAlert.getPhotoLayout().getSelectedPhotos();\n"
        "                    if (!photos.isEmpty()) {\n"
        "                        MediaController.PhotoEntry entry = (MediaController.PhotoEntry) photos.values().iterator().next();\n"
        "                        boolean isVideo = colgramIsVideoPhotoEntry(entry);\n"
        "                        String path = colgramResolvePickedMediaPath(entry, isVideo);\n"
        "                        if (path != null && !path.isEmpty()) {\n"
        "                            if (isVideo) {\n"
        "                                colgramApplyPickedVideoPath(\n"
        "                                        chatActivity.getParentActivity(), path, chatActivity.getDialogId(),\n"
        "                                        () -> chatActivity.colgramResumeVideoWallpaperFromPicker());\n"
        "                                isApplyClicked = true;\n"
        "                                chatAttachAlert.dismissInternal();\n"
        "                                dismiss();\n"
        "                                return;\n"
        "                            }",
        "ChatThemeBottomSheet instance openGalleryForBackground",
    )

    source = _replace_exact_once(
        source,
        "        chatAttachAlert.setMaxSelectedPhotos(1, false);\n"
        "        chatAttachAlert.init();\n"
        "        chatAttachAlert.getPhotoLayout().loadGalleryPhotos();\n"
        "        chatAttachAlert.show();",
        "        chatAttachAlert.setMaxSelectedPhotos(1, false);\n"
        "        chatAttachAlert.init();\n"
        "        chatAttachAlert.colgramEnableVideoWallpapers();\n"
        "        if (chatAttachAlert.getPhotoLayout() != null) {\n"
        "            chatAttachAlert.getPhotoLayout().setIncludeVideosInGallery(true);\n"
        "            chatAttachAlert.getPhotoLayout().onInit(true, true, false);\n"
        "        }\n"
        "        chatAttachAlert.getPhotoLayout().loadGalleryPhotos();\n"
        "        chatAttachAlert.show();",
        "ChatThemeBottomSheet instance post-init video enable",
    )
    return source


def _patch_wallpapers_list_activity_source(source):
    if VIDEO_WP_LIST_MARKER in source:
        return source

    source = _replace_exact_once(
        source,
        "            @Override\n"
        "            public void didSelectWallpaper(File file, Bitmap bitmap, boolean gallery) {\n"
        "                ThemePreviewActivity themePreviewActivity = new ThemePreviewActivity(new FileWallpaper(\"\", file, file), bitmap);",
        "            @Override\n"
        "            public void didSelectWallpaper(File file, Bitmap bitmap, boolean gallery) {\n"
        "                if (file != null && ColgramVideoWallpaperView.isVideoFileName(file.getName()) && getParentActivity() != null) {\n"
        "                    ColgramVideoWallpaperView.copyFileAndApplyVideoWallpaper(getParentActivity(), file, dialogId, WallpapersListActivity.this::updateRows);\n"
        "                    return;\n"
        "                }\n"
        "                ThemePreviewActivity themePreviewActivity = new ThemePreviewActivity(new FileWallpaper(\"\", file, file), bitmap);",
        "WallpapersListActivity didSelectWallpaper video guard",
    )

    source = _replace_exact_once(
        source,
        "            } else if (position == uploadVideoRow) {\n"
        "                try {\n"
        "                    Intent videoIntent = new Intent(Intent.ACTION_GET_CONTENT);\n"
        "                    videoIntent.setType(\"video/*\");\n"
        "                    videoIntent.addCategory(Intent.CATEGORY_OPENABLE);\n"
        "                    startActivityForResult(Intent.createChooser(videoIntent, \"Выбрать видео-обои\"), COLGRAM_REQ_PICK_VIDEO_WALLPAPER);",
        "            } else if (position == uploadVideoRow) {\n"
        "                try {\n"
        "                    Intent videoIntent = new Intent(Intent.ACTION_GET_CONTENT);\n"
        "                    videoIntent.setType(\"video/*\");\n"
        "                    videoIntent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{\n"
        "                            \"video/mp4\", \"video/webm\", \"video/x-matroska\", \"video/quicktime\", \"video/3gpp\", \"video/x-msvideo\", \"video/*\"\n"
        "                    });\n"
        "                    videoIntent.addCategory(Intent.CATEGORY_OPENABLE);\n"
        "                    videoIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);\n"
        "                    startActivityForResult(Intent.createChooser(videoIntent, \"Выбрать видео-обои\"), COLGRAM_REQ_PICK_VIDEO_WALLPAPER);",
        "WallpapersListActivity uploadVideoRow intent",
    )

    source = _replace_exact_once(
        source,
        "    @Override\n"
        "    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {\n"
        "        if (requestCode == COLGRAM_REQ_PICK_VIDEO_WALLPAPER) {\n"
        "            if (resultCode == android.app.Activity.RESULT_OK && data != null && data.getData() != null && getParentActivity() != null) {\n"
        "                ColgramVideoWallpaperView.copyUriAndApplyVideoWallpaper(getParentActivity(), data.getData(), dialogId, this::updateRows);\n"
        "            }\n"
        "            return;\n"
        "        }\n"
        "        updater.onActivityResult(requestCode, resultCode, data);\n"
        "    }",
        f"    {VIDEO_WP_LIST_MARKER}\n"
        "    private static android.net.Uri colgramExtractPickedUri(Intent data) {\n"
        "        if (data == null) {\n"
        "            return null;\n"
        "        }\n"
        "        if (data.getData() != null) {\n"
        "            return data.getData();\n"
        "        }\n"
        "        if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {\n"
        "            return data.getClipData().getItemAt(0).getUri();\n"
        "        }\n"
        "        return null;\n"
        "    }\n\n"
        "    @Override\n"
        "    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {\n"
        "        final android.net.Uri pickedUri = colgramExtractPickedUri(data);\n"
        "        if (requestCode == COLGRAM_REQ_PICK_VIDEO_WALLPAPER) {\n"
        "            if (resultCode == android.app.Activity.RESULT_OK && pickedUri != null && getParentActivity() != null) {\n"
        "                ColgramVideoWallpaperView.copyUriAndApplyVideoWallpaper(getParentActivity(), pickedUri, dialogId, this::updateRows);\n"
        "            }\n"
        "            return;\n"
        "        }\n"
        "        if (resultCode == android.app.Activity.RESULT_OK && pickedUri != null && getParentActivity() != null\n"
        "                && ColgramVideoWallpaperView.isVideoUri(getParentActivity(), pickedUri)) {\n"
        "            ColgramVideoWallpaperView.copyUriAndApplyVideoWallpaper(getParentActivity(), pickedUri, dialogId, this::updateRows);\n"
        "            return;\n"
        "        }\n"
        "        updater.onActivityResult(requestCode, resultCode, data);\n"
        "    }",
        "WallpapersListActivity onActivityResultFragment",
    )

    source = _replace_exact_once(
        source,
        "        } else if (id == NotificationCenter.didSetNewWallpapper) {\n"
        "            if (listView != null) {",
        "        } else if (id == NotificationCenter.didSetNewWallpapper) {\n"
        "            updateRows();\n"
        "            if (listView != null) {",
        "WallpapersListActivity didSetNewWallpapper updateRows",
    )
    return source


def _patch_theme_preview_activity_source(source):
    if VIDEO_WP_PREVIEW_MARKER in source:
        return source

    return _replace_exact_once(
        source,
        "        if (currentWallpaper instanceof WallpapersListActivity.EmojiWallpaper) {\n"
        "            if (delegate != null) {\n"
        "                delegate.didSetNewBackground(null);\n"
        "            }\n"
        "            finishFragment();\n"
        "            return;\n"
        "        }",
        "        if (currentWallpaper instanceof WallpapersListActivity.EmojiWallpaper) {\n"
        "            if (delegate != null) {\n"
        "                delegate.didSetNewBackground(null);\n"
        "            }\n"
        "            finishFragment();\n"
        "            return;\n"
        "        }\n"
        f"        {VIDEO_WP_PREVIEW_MARKER}\n"
        "        if (currentWallpaper instanceof WallpapersListActivity.FileWallpaper) {\n"
        "            WallpapersListActivity.FileWallpaper fw = (WallpapersListActivity.FileWallpaper) currentWallpaper;\n"
        "            File vidFile = (fw.originalPath != null && fw.originalPath.isFile()) ? fw.originalPath : fw.path;\n"
        "            if (vidFile != null && vidFile.isFile() && ColgramVideoWallpaperView.isVideoFileName(vidFile.getName())) {\n"
        "                android.content.Context ctx = getParentActivity() != null ? getParentActivity() : ApplicationLoader.applicationContext;\n"
        "                ColgramVideoWallpaperView.copyFileAndApplyVideoWallpaper(ctx, vidFile, dialogId, null);\n"
        "                setupFinished = true;\n"
        "                if (delegate != null) {\n"
        "                    delegate.didSetNewBackground(null);\n"
        "                }\n"
        "                finishFragment();\n"
        "                return;\n"
        "            }\n"
        "        }",
        "ThemePreviewActivity FileWallpaper video guard",
    )


def _patch_chat_activity_source(source):
    if VIDEO_WP_CHAT_MARKER in source:
        return source

    source = _replace_exact_once(
        source,
        "            if (pullingDownDrawable != null) {\n"
        "                pullingDownDrawable.onAttach();\n"
        "            }\n"
        "            emojiAnimationsOverlay.onAttachedToWindow();\n"
        "        }",
        "            if (pullingDownDrawable != null) {\n"
        "                pullingDownDrawable.onAttach();\n"
        "            }\n"
        "            emojiAnimationsOverlay.onAttachedToWindow();\n"
        "            colgramEnsureVideoWallpaperZOrder();\n"
        "            if (colgramVideoWallpaperView != null && colgramWallpaperHostActive) {\n"
        "                colgramVideoWallpaperView.setHostPaused(false);\n"
        "                colgramVideoWallpaperView.resumePlaybackIfReady();\n"
        "            }\n"
        "        }",
        "ChatActivity onAttachedToWindow video resume",
    )

    source = _replace_exact_once(
        source,
        "    public void colgramPickVideoWallpaper() {\n"
        "        try {\n"
        "            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);\n"
        "            intent.setType(\"video/*\");\n"
        "            intent.addCategory(Intent.CATEGORY_OPENABLE);\n"
        "            startActivityForResult(Intent.createChooser(intent, \"Выбрать видео-обои для чата\"), COLGRAM_REQ_PICK_VIDEO_WALLPAPER);\n"
        "        } catch (Throwable e) {\n"
        "            FileLog.e(e);\n"
        "        }\n"
        "    }\n\n"
        "    @Override\n"
        "    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {\n"
        "        if (org.colgram.core.ColgramFileImport.handleActivityResult(getParentActivity(), requestCode, resultCode, data)) {\n"
        "            return;\n"
        "        }\n"
        "        if (resultCode == Activity.RESULT_OK) {\n"
        "            if (requestCode == COLGRAM_REQ_PICK_VIDEO_WALLPAPER && data != null && data.getData() != null && getParentActivity() != null) {\n"
        "                ColgramVideoWallpaperView.copyUriAndApplyVideoWallpaper(getParentActivity(), data.getData(), dialog_id, this::updateBackground);\n"
        "                return;\n"
        "            }",
        f"    {VIDEO_WP_CHAT_MARKER}\n"
        "    public void colgramPickVideoWallpaper() {\n"
        "        try {\n"
        "            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);\n"
        "            intent.setType(\"video/*\");\n"
        "            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{\n"
        "                    \"video/mp4\", \"video/webm\", \"video/x-matroska\", \"video/quicktime\", \"video/3gpp\", \"video/x-msvideo\", \"video/*\"\n"
        "            });\n"
        "            intent.addCategory(Intent.CATEGORY_OPENABLE);\n"
        "            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);\n"
        "            startActivityForResult(Intent.createChooser(intent, \"Выбрать видео-обои для чата\"), COLGRAM_REQ_PICK_VIDEO_WALLPAPER);\n"
        "        } catch (Throwable e) {\n"
        "            FileLog.e(e);\n"
        "        }\n"
        "    }\n\n"
        "    public void colgramResumeVideoWallpaperFromPicker() {\n"
        "        colgramWallpaperHostActive = true;\n"
        "        updateBackground();\n"
        "        colgramEnsureVideoWallpaperZOrder();\n"
        "        if (colgramVideoWallpaperView != null) {\n"
        "            colgramVideoWallpaperView.setHostPaused(false);\n"
        "            colgramVideoWallpaperView.resumePlaybackIfReady();\n"
        "        }\n"
        "    }\n\n"
        "    @Override\n"
        "    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {\n"
        "        if (org.colgram.core.ColgramFileImport.handleActivityResult(getParentActivity(), requestCode, resultCode, data)) {\n"
        "            return;\n"
        "        }\n"
        "        if (resultCode == Activity.RESULT_OK) {\n"
        "            if (requestCode == COLGRAM_REQ_PICK_VIDEO_WALLPAPER && data != null && getParentActivity() != null) {\n"
        "                Uri pickedUri = data.getData();\n"
        "                if (pickedUri == null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {\n"
        "                    pickedUri = data.getClipData().getItemAt(0).getUri();\n"
        "                }\n"
        "                if (pickedUri != null) {\n"
        "                    colgramWallpaperHostActive = true;\n"
        "                    if (colgramVideoWallpaperView != null) {\n"
        "                        colgramVideoWallpaperView.setHostPaused(false);\n"
        "                    }\n"
        "                    ColgramVideoWallpaperView.copyUriAndApplyVideoWallpaper(\n"
        "                            getParentActivity(), pickedUri, dialog_id, this::colgramResumeVideoWallpaperFromPicker);\n"
        "                    return;\n"
        "                }\n"
        "            }",
        "ChatActivity colgramPickVideoWallpaper and onActivityResultFragment",
    )

    source = _replace_exact_once(
        source,
        "        if (isOpen) {\n"
        "            checkShowBlur(false);\n"
        "            openAnimationEnded = true;",
        "        if (isOpen) {\n"
        "            checkShowBlur(false);\n"
        "            openAnimationEnded = true;\n"
        "            if (!paused) {\n"
        "                colgramWallpaperHostActive = true;\n"
        "                colgramEnsureVideoWallpaperZOrder();\n"
        "                if (colgramVideoWallpaperView != null) {\n"
        "                    colgramVideoWallpaperView.setHostPaused(false);\n"
        "                    colgramVideoWallpaperView.resumePlaybackIfReady();\n"
        "                }\n"
        "            }",
        "ChatActivity onTransitionAnimationEnd video resume",
    )

    source = _replace_exact_once(
        source,
        "        if (colgramVideoWallpaperView != null) {\n"
        "            updateBackground();\n"
        "            colgramVideoWallpaperView.setHostPaused(false);\n"
        "        }",
        "        if (colgramVideoWallpaperView != null) {\n"
        "            updateBackground();\n"
        "            colgramEnsureVideoWallpaperZOrder();\n"
        "            colgramVideoWallpaperView.setHostPaused(false);\n"
        "            colgramVideoWallpaperView.resumePlaybackIfReady();\n"
        "        }",
        "ChatActivity onResume video z-order and resume",
    )

    source = _replace_exact_once(
        source,
        "    private void colgramEnsureVideoWallpaperZOrder() {\n"
        "        if (contentView == null || colgramVideoWallpaperView == null || contentView.backgroundView == null) {\n"
        "            return;\n"
        "        }\n"
        "        int bgIdx = contentView.indexOfChild(contentView.backgroundView);\n"
        "        int vidIdx = contentView.indexOfChild(colgramVideoWallpaperView);\n"
        "        if (bgIdx >= 0 && vidIdx >= 0 && vidIdx < bgIdx) {\n"
        "            contentView.removeView(colgramVideoWallpaperView);\n"
        "            contentView.addView(colgramVideoWallpaperView, Math.min(bgIdx + 1, contentView.getChildCount()),\n"
        "                    LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));\n"
        "        }\n"
        "    }",
        "    private void colgramEnsureVideoWallpaperZOrder() {\n"
        "        if (contentView == null || colgramVideoWallpaperView == null || contentView.backgroundView == null) {\n"
        "            return;\n"
        "        }\n"
        "        int bgIdx = contentView.indexOfChild(contentView.backgroundView);\n"
        "        if (bgIdx < 0) {\n"
        "            return;\n"
        "        }\n"
        "        int vidIdx = contentView.indexOfChild(colgramVideoWallpaperView);\n"
        "        int chatIdx = chatListView != null ? contentView.indexOfChild(chatListView) : -1;\n"
        "        if (vidIdx == bgIdx + 1 || (vidIdx > bgIdx && (chatIdx < 0 || vidIdx < chatIdx))) {\n"
        "            return;\n"
        "        }\n"
        "        if (vidIdx >= 0) {\n"
        "            contentView.removeView(colgramVideoWallpaperView);\n"
        "        }\n"
        "        int targetBgIdx = contentView.indexOfChild(contentView.backgroundView);\n"
        "        int targetIdx = targetBgIdx >= 0 ? Math.min(targetBgIdx + 1, contentView.getChildCount()) : 0;\n"
        "        contentView.addView(colgramVideoWallpaperView, targetIdx,\n"
        "                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));\n"
        "    }",
        "ChatActivity colgramEnsureVideoWallpaperZOrder",
    )

    source = _replace_exact_once(
        source,
        "        if (colgramVideoWallpaperView != null\n"
        "                && colgramVideoWallpaperView.getVideoPath() != null\n"
        "                && !colgramVideoWallpaperView.getVideoPath().isEmpty()) {\n"
        "            colgramVideoWallpaperView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);\n"
        "        }",
        "        if (colgramVideoWallpaperView != null\n"
        "                && colgramVideoWallpaperView.getVideoPath() != null\n"
        "                && !colgramVideoWallpaperView.getVideoPath().isEmpty()) {\n"
        "            if (visible) {\n"
        "                colgramEnsureVideoWallpaperZOrder();\n"
        "            }\n"
        "            if (colgramVideoWallpaperView.getVisibility() != View.GONE) {\n"
        "                colgramVideoWallpaperView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);\n"
        "            }\n"
        "            if (visible && colgramWallpaperHostActive) {\n"
        "                colgramVideoWallpaperView.setHostPaused(false);\n"
        "            }\n"
        "        }",
        "ChatActivity checkUi_backgroundViewVisible",
    )
    return source


def _patch_text_preserving_eol(path: Path, transform_fn):
    with open(path, "r", encoding="utf-8", errors="ignore", newline="") as f:
        raw = f.read()
    eol = "\r\n" if "\r\n" in raw else "\n"
    normalized = raw.replace("\r\n", "\n")
    updated = transform_fn(normalized)
    if updated == normalized:
        return False
    out = updated.replace("\n", eol) if eol == "\r\n" else updated
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(out)
    return True


def patch_video_wallpaper_fixes(repo_path):
    """Idempotently repair gallery video wallpaper picking, alpha reveal, thermal throttling, and Z-order."""
    root = Path(repo_path)
    tg_ui = root / "TMessagesProj" / "src" / "main" / "java" / "org" / "telegram" / "ui"
    targets = (
        (tg_ui / "ColgramVideoWallpaperView.java", _patch_video_wallpaper_view_source),
        (tg_ui / "Components" / "ChatThemeBottomSheet.java", _patch_chat_theme_bottom_sheet_source),
        (tg_ui / "WallpapersListActivity.java", _patch_wallpapers_list_activity_source),
        (tg_ui / "ThemePreviewActivity.java", _patch_theme_preview_activity_source),
        (tg_ui / "ChatActivity.java", _patch_chat_activity_source),
    )
    changed = []
    for file_path, fn in targets:
        if file_path.is_file() and _patch_text_preserving_eol(file_path, fn):
            changed.append(str(file_path))
    return changed
