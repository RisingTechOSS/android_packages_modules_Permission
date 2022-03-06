/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.safetycenter;

import static android.Manifest.permission.SEND_SAFETY_CENTER_UPDATE;
import static android.content.Intent.FLAG_RECEIVER_FOREGROUND;
import static android.os.Build.VERSION_CODES.TIRAMISU;
import static android.os.PowerExemptionManager.REASON_REFRESH_SAFETY_SOURCES;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED;
import static android.safetycenter.SafetyCenterManager.ACTION_REFRESH_SAFETY_SOURCES;
import static android.safetycenter.SafetyCenterManager.EXTRA_REFRESH_REQUEST_TYPE_FETCH_FRESH_DATA;
import static android.safetycenter.SafetyCenterManager.EXTRA_REFRESH_REQUEST_TYPE_GET_DATA;
import static android.safetycenter.SafetyCenterManager.EXTRA_REFRESH_SAFETY_SOURCES_REQUEST_TYPE;
import static android.safetycenter.SafetyCenterManager.REFRESH_REASON_PAGE_OPEN;
import static android.safetycenter.SafetyCenterManager.REFRESH_REASON_RESCAN_BUTTON_CLICK;

import android.annotation.NonNull;
import android.app.BroadcastOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.UserHandle;
import android.safetycenter.SafetyCenterManager.RefreshReason;
import android.safetycenter.SafetyCenterManager.RefreshRequestType;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.android.safetycenter.SafetyCenterConfigReader.Broadcast;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Class to manage and track refresh broadcasts sent by {@link SafetyCenterService}.
 *
 * <p>This class isn't thread safe. Thread safety must be handled by the caller.
 */
@RequiresApi(TIRAMISU)
final class SafetyCenterRefreshManager {

    private static final String TAG = "SafetyCenterRefreshMana";

    /**
     * Time for which an app, upon receiving a particular broadcast, will be placed on a temporary
     * power allowlist allowing it to start a foreground service from the background.
     */
    // TODO(b/219553295): Use a Device Config value instead, so that this duration can be
    //  easily adjusted.
    private static final Duration ALLOWLIST_DURATION = Duration.ofSeconds(20);

    private final List<ComponentName> mAdditionalSafetySourceBroadcastReceiverComponents =
            new ArrayList<>();
    @NonNull
    private final Context mContext;
    @NonNull
    private final SafetyCenterConfigReader mSafetyCenterConfigReader;

    /**
     * Creates a {@link SafetyCenterRefreshManager} using the given {@link Context} and {@link
     * SafetyCenterConfigReader}.
     */
    SafetyCenterRefreshManager(
            @NonNull Context context,
            @NonNull SafetyCenterConfigReader safetyCenterConfigReader) {
        mContext = context;
        mSafetyCenterConfigReader = safetyCenterConfigReader;
    }

    /** Adds a broadcast receiver component representing a source to refresh. */
    // TODO(b/218157907): Remove this method and use a SafetyCenterConfigReader field in
    //  SafetyCenterRefreshManager instead once ag/16834483 is submitted.
    void addAdditionalSafetySourceBroadcastReceiverComponent(@NonNull ComponentName componentName) {
        mAdditionalSafetySourceBroadcastReceiverComponents.add(componentName);
    }

    /** Removes all additional broadcast receiver components representing sources to refresh. */
    // TODO(b/218157907): Remove this method and use a SafetyCenterConfigReader field in
    //  SafetyCenterRefreshManager instead once ag/16834483 is submitted.
    void clearAdditionalSafetySourceBroadcastReceiverComponents() {
        mAdditionalSafetySourceBroadcastReceiverComponents.clear();
    }

    /**
     * Triggers a refresh of safety sources by sending them broadcasts with action
     * {@link android.safetycenter.SafetyCenterManager#ACTION_REFRESH_SAFETY_SOURCES}.
     */
    void refreshSafetySources(
            @RefreshReason int refreshReason,
            @NonNull UserProfiles userProfiles) {
        SafetyCenterConfigReader.Config config = mSafetyCenterConfigReader.getConfig();
        if (config == null) {
            Log.w(TAG, "SafetyCenterConfigReader.Config unavailable, ignoring refresh");
            return;
        }

        // TODO(b/218157907): Do not recompute this, SafetyCenterConfigReader needs to be the
        // source of truth for additional sources.
        List<Broadcast> broadcasts = new ArrayList<>(config.getBroadcasts());
        for (int i = 0; i < mAdditionalSafetySourceBroadcastReceiverComponents.size(); i++) {
            ComponentName componentName = mAdditionalSafetySourceBroadcastReceiverComponents.get(i);

            broadcasts.add(Broadcast.from(componentName));
        }

        sendRefreshBroadcasts(broadcasts, toRefreshRequestType(refreshReason), userProfiles);
    }

    private void sendRefreshBroadcasts(
            @NonNull List<Broadcast> broadcasts,
            @RefreshRequestType int requestType,
            @NonNull UserProfiles userProfiles) {
        BroadcastOptions broadcastOptions = BroadcastOptions.makeBasic();
        // The following operation requires START_FOREGROUND_SERVICES_FROM_BACKGROUND
        // permission.
        final long callingId = Binder.clearCallingIdentity();
        try {
            broadcastOptions.setTemporaryAppAllowlist(ALLOWLIST_DURATION.toMillis(),
                    TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                    REASON_REFRESH_SAFETY_SOURCES,
                    "Safety Center is requesting data from safety sources");
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
        for (int i = 0; i < broadcasts.size(); i++) {
            Broadcast broadcast = broadcasts.get(i);

            sendRefreshBroadcast(broadcast, broadcastOptions, requestType, userProfiles);
        }
    }

    private void sendRefreshBroadcast(
            @NonNull Broadcast broadcast,
            @NonNull BroadcastOptions broadcastOptions,
            @RefreshRequestType int requestType,
            @NonNull UserProfiles userProfiles) {
        if (!broadcast.getSourceIdsForProfileOwner().isEmpty()) {
            // TODO(b/220826153): Add source ids to intent.
            Intent broadcastIntent = createBaseIntent(requestType, broadcast);

            sendRefreshBroadcast(broadcastIntent, broadcastOptions,
                    UserHandle.of(userProfiles.getProfileOwnerUserId()));
        }
        if (!broadcast.getSourceIdsForWorkProfiles().isEmpty()) {
            // TODO(b/220826153): Add source ids to intent.
            Intent broadcastIntent = createBaseIntent(requestType, broadcast);

            int[] workProfilesUserIds = userProfiles.getWorkProfilesUserIds();
            for (int i = 0; i < workProfilesUserIds.length; i++) {
                UserHandle userHandle = UserHandle.of(workProfilesUserIds[i]);

                sendRefreshBroadcast(broadcastIntent, broadcastOptions, userHandle);
            }
        }
    }

    private void sendRefreshBroadcast(
            @NonNull Intent broadcastIntent,
            @NonNull BroadcastOptions broadcastOptions,
            @NonNull UserHandle userHandle) {
        // The following operation requires INTERACT_ACROSS_USERS permission.
        final long callingId = Binder.clearCallingIdentity();
        try {
            mContext.sendBroadcastAsUser(broadcastIntent,
                    userHandle,
                    SEND_SAFETY_CENTER_UPDATE,
                    broadcastOptions.toBundle());
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @NonNull
    private static Intent createBaseIntent(
            @RefreshRequestType int requestType,
            @NonNull Broadcast broadcast) {
        return new Intent(ACTION_REFRESH_SAFETY_SOURCES)
                .putExtra(EXTRA_REFRESH_SAFETY_SOURCES_REQUEST_TYPE, requestType)
                .setFlags(FLAG_RECEIVER_FOREGROUND)
                .setComponent(broadcast.getComponentName());
    }

    @RefreshRequestType
    private static int toRefreshRequestType(@RefreshReason int refreshReason) {
        switch (refreshReason) {
            case REFRESH_REASON_RESCAN_BUTTON_CLICK:
                return EXTRA_REFRESH_REQUEST_TYPE_FETCH_FRESH_DATA;
            case REFRESH_REASON_PAGE_OPEN:
                return EXTRA_REFRESH_REQUEST_TYPE_GET_DATA;
        }
        throw new IllegalArgumentException("Invalid refresh reason: " + refreshReason);
    }
}