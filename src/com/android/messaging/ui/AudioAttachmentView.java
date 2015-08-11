/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.messaging.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.ui.mediapicker.PausableChronometer;
import com.android.messaging.util.Assert;
import com.android.messaging.util.ContentType;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.UiUtils;

/**
 * A reusable widget that hosts an audio player for audio attachment playback. This widget is used
 * by both the media picker and the conversation message view to show audio attachments.
 */
public class AudioAttachmentView extends LinearLayout {
    /** The normal layout mode where we have the play button, timer and progress bar */
    private static final int LAYOUT_MODE_NORMAL = 0;

    /** The compact layout mode with only the play button and the timer beneath it. Suitable
     *  for displaying in limited space such as multi-attachment layout */
    private static final int LAYOUT_MODE_COMPACT = 1;

    /** The sub-compact layout mode with only the play button. */
    private static final int LAYOUT_MODE_SUB_COMPACT = 2;

    private static final int PLAY_BUTTON = 0;
    private static final int PAUSE_BUTTON = 1;

    private AudioAttachmentPlayPauseButton mPlayPauseButton;
    private PausableChronometer mChronometer;
    private AudioPlaybackProgressBar mProgressBar;
    private MediaPlayer mMediaPlayer;

    private Uri mDataSourceUri;

    // The corner radius for drawing rounded corners. The default value is zero (no rounded corners)
    private final int mCornerRadius;
    private final Path mRoundedCornerClipPath;
    private int mClipPathWidth;
    private int mClipPathHeight;

    // Indicates whether the attachment view is to be styled as a part of an incoming message.
    private boolean mShowAsIncoming;

    private boolean mPrepared;
    private boolean mPlaybackFinished;
    private final int mMode;

    public AudioAttachmentView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        final TypedArray typedAttributes =
                context.obtainStyledAttributes(attrs, R.styleable.AudioAttachmentView);
        mMode = typedAttributes.getInt(R.styleable.AudioAttachmentView_layoutMode,
                LAYOUT_MODE_NORMAL);
        final LayoutInflater inflater = LayoutInflater.from(getContext());
        inflater.inflate(R.layout.audio_attachment_view, this, true);
        typedAttributes.recycle();

        setWillNotDraw(mMode != LAYOUT_MODE_SUB_COMPACT);
        mRoundedCornerClipPath = new Path();
        mCornerRadius = context.getResources().getDimensionPixelSize(
                R.dimen.conversation_list_image_preview_corner_radius);
        setContentDescription(context.getString(R.string.audio_attachment_content_description));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mPlayPauseButton = (AudioAttachmentPlayPauseButton) findViewById(R.id.play_pause_button);
        mChronometer = (PausableChronometer) findViewById(R.id.timer);
        mProgressBar = (AudioPlaybackProgressBar) findViewById(R.id.progress);
        mPlayPauseButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View v) {
                setupMediaPlayer();
                if (mMediaPlayer != null && mPrepared) {
                    if (mMediaPlayer.isPlaying()) {
                        mMediaPlayer.pause();
                        mChronometer.pause();
                        mProgressBar.pause();
                    } else {
                        playAudio();
                    }
                }
                updatePlayPauseButtonState();
            }
        });
        updatePlayPauseButtonState();
        initializeViewsForMode();
    }

    /**
     * Bind the audio attachment view with a MessagePartData.
     * @param incoming indicates whether the attachment view is to be styled as a part of an
     *        incoming message.
     */
    public void bindMessagePartData(final MessagePartData messagePartData,
            final boolean incoming) {
        Assert.isTrue(messagePartData == null ||
                ContentType.isAudioType(messagePartData.getContentType()));
        final Uri contentUri = (messagePartData == null) ? null : messagePartData.getContentUri();
        bind(contentUri, incoming);
    }

    public void bind(final Uri dataSourceUri, final boolean incoming) {
        final String currentUriString = (mDataSourceUri == null) ? "" : mDataSourceUri.toString();
        final String newUriString = (dataSourceUri == null) ? "" : dataSourceUri.toString();
        mShowAsIncoming = incoming;
        if (!TextUtils.equals(currentUriString, newUriString)) {
            mDataSourceUri = dataSourceUri;
            resetToZeroState();
        }
    }

    private void playAudio() {
        Assert.notNull(mMediaPlayer);
        if (mPlaybackFinished) {
            mMediaPlayer.seekTo(0);
            mChronometer.restart();
            mProgressBar.restart();
            mPlaybackFinished = false;
        } else {
            mChronometer.resume();
            mProgressBar.resume();
        }
        mMediaPlayer.start();
    }

    private void onAudioReplayError(final int what, final int extra, final Exception exception) {
        if (exception == null) {
            LogUtil.e(LogUtil.BUGLE_TAG, "audio replay failed, what=" + what +
                    ", extra=" + extra);
        } else {
            LogUtil.e(LogUtil.BUGLE_TAG, "audio replay failed, exception=" + exception);
        }
        UiUtils.showToastAtBottom(R.string.audio_recording_replay_failed);
        releaseMediaPlayer();
    }

    private void setupMediaPlayer() {
        Assert.notNull(mDataSourceUri);
        if (mMediaPlayer == null) {
            Assert.isTrue(!mPrepared);
            mMediaPlayer = new MediaPlayer();
            try {
                mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                mMediaPlayer.setDataSource(Factory.get().getApplicationContext(), mDataSourceUri);
                mMediaPlayer.setOnCompletionListener(new OnCompletionListener() {
                    @Override
                    public void onCompletion(final MediaPlayer mp) {
                        updatePlayPauseButtonState();
                        mChronometer.reset();
                        mChronometer.setBase(SystemClock.elapsedRealtime() -
                                mMediaPlayer.getDuration());
                        mProgressBar.reset();

                        mPlaybackFinished = true;
                    }
                });

                mMediaPlayer.setOnPreparedListener(new OnPreparedListener() {
                    @Override
                    public void onPrepared(final MediaPlayer mp) {
                        // Set base on the chronometer so we can show the full length of the audio.
                        mChronometer.setBase(SystemClock.elapsedRealtime() -
                                mMediaPlayer.getDuration());
                        mProgressBar.setDuration(mMediaPlayer.getDuration());
                        mMediaPlayer.seekTo(0);
                        mPrepared = true;
                    }
                });

                mMediaPlayer.setOnErrorListener(new OnErrorListener() {
                    @Override
                    public boolean onError(final MediaPlayer mp, final int what, final int extra) {
                        onAudioReplayError(what, extra, null);
                        return true;
                    }
                });
                mMediaPlayer.prepareAsync();
            } catch (final Exception exception) {
                onAudioReplayError(0, 0, exception);
                releaseMediaPlayer();
            }
        }
    }

    private void releaseMediaPlayer() {
        if (mMediaPlayer != null) {
            mMediaPlayer.release();
            mMediaPlayer = null;
            mPrepared = false;
            mPlaybackFinished = false;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        releaseMediaPlayer();
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        if (mMode != LAYOUT_MODE_SUB_COMPACT) {
            return;
        }

        final int currentWidth = this.getWidth();
        final int currentHeight = this.getHeight();
        if (mClipPathWidth != currentWidth || mClipPathHeight != currentHeight) {
            final RectF rect = new RectF(0, 0, currentWidth, currentHeight);
            mRoundedCornerClipPath.reset();
            mRoundedCornerClipPath.addRoundRect(rect, mCornerRadius, mCornerRadius,
                    Path.Direction.CW);
            mClipPathWidth = currentWidth;
            mClipPathHeight = currentHeight;
        }

        canvas.clipPath(mRoundedCornerClipPath);
        super.onDraw(canvas);
    }

    private void updatePlayPauseButtonState() {
        if (mMediaPlayer == null || !mMediaPlayer.isPlaying()) {
            mPlayPauseButton.setDisplayedChild(PLAY_BUTTON);
        } else {
            mPlayPauseButton.setDisplayedChild(PAUSE_BUTTON);
        }
    }

    private void resetToZeroState() {
        // Release the media player so it may be set up with the new audio source.
        releaseMediaPlayer();
        mChronometer.reset();
        mProgressBar.reset();
        updateVisualStyle();

        if (mDataSourceUri != null) {
            // Re-ensure the media player, so we can read the duration of the audio.
            setupMediaPlayer();
        }
    }

    private void updateVisualStyle() {
        if (mMode == LAYOUT_MODE_SUB_COMPACT) {
            // Sub-compact mode has static visual appearance already set up during initialization.
            return;
        }

        if (mShowAsIncoming) {
            mChronometer.setTextColor(getResources().getColor(R.color.message_text_color_incoming));
        } else {
            mChronometer.setTextColor(getResources().getColor(R.color.message_text_color_outgoing));
        }
        mProgressBar.setVisualStyle(mShowAsIncoming);
        mPlayPauseButton.setVisualStyle(mShowAsIncoming);
        updatePlayPauseButtonState();
    }

    private void initializeViewsForMode() {
        switch (mMode) {
            case LAYOUT_MODE_NORMAL:
                setOrientation(HORIZONTAL);
                mProgressBar.setVisibility(VISIBLE);
                break;

            case LAYOUT_MODE_COMPACT:
                setOrientation(VERTICAL);
                mProgressBar.setVisibility(GONE);
                ((MarginLayoutParams) mPlayPauseButton.getLayoutParams()).setMargins(0, 0, 0, 0);
                ((MarginLayoutParams) mChronometer.getLayoutParams()).setMargins(0, 0, 0, 0);
                break;

            case LAYOUT_MODE_SUB_COMPACT:
                setOrientation(VERTICAL);
                mProgressBar.setVisibility(GONE);
                mChronometer.setVisibility(GONE);
                ((MarginLayoutParams) mPlayPauseButton.getLayoutParams()).setMargins(0, 0, 0, 0);
                final ImageView playButton = (ImageView) findViewById(R.id.play_button);
                playButton.setImageDrawable(
                        getResources().getDrawable(R.drawable.ic_preview_play));
                final ImageView pauseButton = (ImageView) findViewById(R.id.pause_button);
                pauseButton.setImageDrawable(
                        getResources().getDrawable(R.drawable.ic_preview_pause));
                break;

            default:
                Assert.fail("Unsupported mode for AudioAttachmentView!");
                break;
        }
    }
}