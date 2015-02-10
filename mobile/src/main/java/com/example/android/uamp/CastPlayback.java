/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.example.android.uamp;

import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.text.TextUtils;

import com.example.android.uamp.model.MusicProvider;
import com.example.android.uamp.utils.LogHelper;
import com.example.android.uamp.utils.MediaIDHelper;
import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.common.images.WebImage;
import com.google.sample.castcompanionlibrary.cast.VideoCastManager;
import com.google.sample.castcompanionlibrary.cast.callbacks.VideoCastConsumerImpl;
import com.google.sample.castcompanionlibrary.cast.exceptions.CastException;
import com.google.sample.castcompanionlibrary.cast.exceptions.NoConnectionException;
import com.google.sample.castcompanionlibrary.cast.exceptions.TransientNetworkDisconnectionException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * An implementation of Playback that talks to Cast.
 */
public class CastPlayback implements Playback {

    private static final String TAG = LogHelper.makeLogTag(CastPlayback.class);

    private static final String MIME_TYPE_AUDIO_MPEG = "audio/mpeg";
    private static final String ITEM_ID = "itemId";

    private final MusicProvider mMusicProvider;
    private final MusicService mService;
    private final VideoCastConsumerImpl mCastConsumer = new VideoCastConsumerImpl() {

        @Override
        public void onApplicationConnected(ApplicationMetadata appMetadata, String sessionId,
                                           boolean wasLaunched) {
            LogHelper.d(TAG,
                    "onApplicationConnected called wasLaunched ", wasLaunched, "sessionId ");
            try {
                MediaInfo info = mCastManager.getRemoteMediaInformation();
                if (info != null) {
                    long currentMediaPos = mCastManager.getCurrentMediaPosition();
                    LogHelper.d(TAG, "***** MediaInfo onApplicationConnected ", info.getContentId(),
                            " CurrentMediaPosition ", currentMediaPos);

                }
            } catch (TransientNetworkDisconnectionException | NoConnectionException e) {
                LogHelper.e(TAG, e, "Exception getting media information");
            }
        }

        @Override
        public boolean onApplicationConnectionFailed(int errorCode) {
            LogHelper.d(TAG, "onApplicationConnectionFailed ", errorCode);
            return true;
        }

        @Override
        public void onApplicationDisconnected(int errorCode) {
            LogHelper.d(TAG, "onApplicationDisconnected ", errorCode);
        }

        @Override
        public void onRemoteMediaPlayerMetadataUpdated() {
            updateMetadata();
        }

        @Override
        public void onRemoteMediaPlayerStatusUpdated() {
            int status = mCastManager.getPlaybackStatus();
            int idleReason = mCastManager.getIdleReason();

            LogHelper.d(TAG, "onRemoteMediaPlayerStatusUpdated ", status);

            // Convert the remote playback states to media playback states.
            switch (status) {
                case MediaStatus.PLAYER_STATE_IDLE:
                    if (idleReason == MediaStatus.IDLE_REASON_FINISHED) {
                        if (mCallback != null) {
                            mCallback.onCompletion();
                        }
                    }
                    break;
                case MediaStatus.PLAYER_STATE_BUFFERING:
                    mState = PlaybackState.STATE_BUFFERING;
                    if (mCallback != null) {
                        mCallback.onPlaybackStatusChanged(mState);
                    }
                    break;
                case MediaStatus.PLAYER_STATE_PLAYING:
                    mState = PlaybackState.STATE_PLAYING;
                    updateMetadata();
                    if (mCallback != null) {
                        mCallback.onPlaybackStatusChanged(mState);
                    }
                    break;
                case MediaStatus.PLAYER_STATE_PAUSED:
                    mState = PlaybackState.STATE_PAUSED;
                    updateMetadata();
                    if (mCallback != null) {
                        mCallback.onPlaybackStatusChanged(mState);
                    }
                    break;
                default: // case unknown
                    break;
            }
        }

        @Override
        public void onDisconnected() {
            LogHelper.d(TAG, "onDisconnected called on castPlayback");
        }
    };

    /** The current PlaybackState*/
    private int mState;
    /** Callback for making completion/error calls on */
    private Callback mCallback;
    private VideoCastManager mCastManager;
    private volatile int mCurrentPosition;
    private volatile String mCurrentMediaId;
    private OnMediaLoadedStatusListener mPauseOnMediaLoadedStatusListener;
    private OnMediaLoadedStatusListener mPlayOnMediaLoadedStatusListener;

    public CastPlayback(MusicService service, MusicProvider musicProvider) {
        this.mMusicProvider = musicProvider;
        this.mService = service;
    }

    @Override
    public void start() {
        mCastManager = ((UAMPApplication) mService.getApplication())
                .getCastManager(mService.getApplicationContext());

        mCastManager.addVideoCastConsumer(mCastConsumer);
        mPauseOnMediaLoadedStatusListener = new OnMediaLoadedStatusListener(mCastManager, false);
        mPlayOnMediaLoadedStatusListener = new OnMediaLoadedStatusListener(mCastManager, true);
    }

    @Override
    public void stop(boolean notifyListeners) {
        mCastManager.removeVideoCastConsumer(mCastConsumer);
        mState = PlaybackState.STATE_STOPPED;
        if (notifyListeners && mCallback != null) {
            mCallback.onPlaybackStatusChanged(mState);
        }
    }

    @Override
    public void setState(int state) {
        this.mState = state;
    }

    @Override
    public int getCurrentStreamPosition() {
        if (!mCastManager.isConnected()) {
            return mCurrentPosition;
        }
        try {
            return (int)mCastManager.getCurrentMediaPosition();
        } catch (TransientNetworkDisconnectionException | NoConnectionException e) {
            LogHelper.e(TAG, e, "Exception getting media position");
        }
        return -1;
    }

    @Override
    public void setCurrentStreamPosition(int pos) {
        this.mCurrentPosition = pos;
    }

    @Override
    public void play(MediaSession.QueueItem item) {
        try {
            if (mPlayOnMediaLoadedStatusListener != null) {
                mCastManager.addVideoCastConsumer(mPlayOnMediaLoadedStatusListener);
            }
            loadMedia(item.getDescription().getMediaId(), true);
            mState = PlaybackState.STATE_BUFFERING;
            if (mCallback != null) {
                mCallback.onPlaybackStatusChanged(mState);
            }
        } catch (TransientNetworkDisconnectionException | NoConnectionException | JSONException e) {
            LogHelper.e(TAG, "Exception loading media ", e, null);
            if (mCallback != null) {
                mCallback.onError(e.getMessage());
            }
        }
    }

    @Override
    public void pause() {
        try {
            if (mCastManager.isRemoteMediaLoaded()) {
                mCastManager.pause();
                mCurrentPosition = (int) mCastManager.getCurrentMediaPosition();
            } else {
                if (mPauseOnMediaLoadedStatusListener != null) {
                    mCastManager.addVideoCastConsumer(mPauseOnMediaLoadedStatusListener);
                }
                loadMedia(mCurrentMediaId, false);
            }
        } catch (JSONException | CastException | TransientNetworkDisconnectionException
                | NoConnectionException e) {
            LogHelper.e(TAG, e, "Exception pausing cast playback");
            if (mCallback != null) {
                mCallback.onError(e.getMessage());
            }
        }
    }

    @Override
    public void setCurrentMediaId(String mediaId) {
        this.mCurrentMediaId = mediaId;
    }

    @Override
    public String getCurrentMediaId() {
        return mCurrentMediaId;
    }

    @Override
    public void setCallback(Callback callback) {
        this.mCallback = callback;
    }

    @Override
    public boolean isConnected() {
        return mCastManager.isConnected();
    }

    @Override
    public boolean isPlaying() {
        try {
            return mCastManager.isConnected() && mCastManager.isRemoteMoviePlaying();
        } catch (TransientNetworkDisconnectionException | NoConnectionException e) {
            LogHelper.e(TAG, e, "Exception calling isRemoteMoviePlaying");
        }
        return false;
    }

    @Override
    public int getState() {
        return mState;
    }

    private void loadMedia(String mediaId, boolean autoPlay) throws
        TransientNetworkDisconnectionException, NoConnectionException, JSONException {
        String musicId = MediaIDHelper.extractMusicIDFromMediaID(mediaId);
        android.media.MediaMetadata track = mMusicProvider.getMusic(musicId);

        if (!TextUtils.equals(mediaId, mCurrentMediaId)) {
            mCurrentMediaId = mediaId;
            mCurrentPosition = 0;
        }
        JSONObject customData = new JSONObject();
        customData.put(ITEM_ID, mediaId);
        MediaInfo media = toCastMediaMetadata(track, customData);
        mCastManager.loadMedia(media, autoPlay, mCurrentPosition, customData);
    }

    /**
     * Helper method to convert a {@link android.media.MediaMetadata} to a
     * {@link com.google.android.gms.cast.MediaInfo} used for sending media to the receiver app.
     *
     * @param track {@link com.google.android.gms.cast.MediaMetadata}
     * @param customData custom data specifies the local mediaId used by the player.
     * @return mediaInfo {@link com.google.android.gms.cast.MediaInfo}
     */
    private static MediaInfo toCastMediaMetadata(android.media.MediaMetadata track,
                                                 JSONObject customData) {
        MediaMetadata mediaMetadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);
        mediaMetadata.putString(MediaMetadata.KEY_TITLE,
                track.getDescription().getTitle().toString());
        mediaMetadata.putString(MediaMetadata.KEY_SUBTITLE,
                track.getDescription().getSubtitle().toString());
        mediaMetadata.putString(MediaMetadata.KEY_ALBUM_ARTIST,
                track.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM));
        mediaMetadata.putString(MediaMetadata.KEY_ALBUM_TITLE,
                track.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM));
        WebImage image = new WebImage(
                new Uri.Builder().encodedPath(
                        track.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART_URI))
                        .build());
        // First image is used by the receiver for showing the audio album art.
        mediaMetadata.addImage(image);
        // Second image is used by Cast Companion Library on the full screen activity that is shown
        // when the cast dialog is clicked.
        mediaMetadata.addImage(image);

        return new MediaInfo.Builder(track.getString(MusicProvider.CUSTOM_METADATA_TRACK_SOURCE))
                .setContentType(MIME_TYPE_AUDIO_MPEG)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setMetadata(mediaMetadata)
                .setCustomData(customData)
                .build();
    }

    private void updateMetadata() {
        // Sync: We get the customData from the remote media information and update the local
        // metadata if it happens to be different from the one we are currently using.
        // This can happen when the app was either restarted/diconnected + connected, or if the
        // app joins an existing session while the Chromecast was playing a queue.
        try {
            MediaInfo mediaInfo = mCastManager.getRemoteMediaInformation();
            if (mediaInfo == null) {
                return;
            }
            JSONObject customData = mediaInfo.getCustomData();

            if (customData != null && customData.has(ITEM_ID)) {
                String remoteMediaId = customData.getString(ITEM_ID);
                if (!TextUtils.equals(mCurrentMediaId, remoteMediaId)) {
                    mCurrentMediaId = remoteMediaId;
                    if (mCallback != null) {
                        mCallback.onMetadataChanged(remoteMediaId);
                    }
                    mCurrentPosition = getCurrentStreamPosition();
                }
            }
        } catch (TransientNetworkDisconnectionException | NoConnectionException | JSONException e) {
            LogHelper.e(TAG, e, "Exception processing update metadata");
        }

    }

    /**
     * A listener that handles playing or pausing depending on the boolean
     * provided in the constructor.
     */
    private static class OnMediaLoadedStatusListener extends VideoCastConsumerImpl {
        private final VideoCastManager mCastManager;
        private final boolean mPlay;

        private OnMediaLoadedStatusListener(VideoCastManager manager, boolean play) {
            mCastManager = manager;
            mPlay = play;
        }

        @Override
        public void onMediaLoadRequestStatus(boolean success, Integer failureCode) {
            if (success) {
                // Remove the listener as soon as we're done with the action.
                mCastManager.removeVideoCastConsumer(this);

                LogHelper.d(TAG, "onMediaLoaded called mPlay =", mPlay);
                try {
                    if (mPlay) {
                        mCastManager.play();
                    } else {
                        mCastManager.pause();
                    }
                } catch (CastException | TransientNetworkDisconnectionException |
                        NoConnectionException e) {
                    LogHelper.e(TAG, e, "Exception pausing stream");
                }
            } else {
                LogHelper.e(TAG, "Error calling loadMedia with status ", failureCode);
            }
        }
    }
}
