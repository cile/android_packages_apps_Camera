/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.camera;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Camera.Area;
import android.hardware.Camera.Parameters;
import android.media.MediaActionSound;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import com.android.camera.ui.FaceView;
import com.android.camera.ui.FocusIndicator;
import com.android.camera.ui.FocusIndicatorRotateLayout;

import java.util.ArrayList;
import java.util.List;

/* A class that handles everything about focus in still picture mode.
 * This also handles the metering area because it is the same as focus area.
 *
 * The test cases:
 * (1) The camera has continuous autofocus. Move the camera. Take a picture when
 *     CAF is not in progress.
 * (2) The camera has continuous autofocus. Move the camera. Take a picture when
 *     CAF is in progress.
 * (3) The camera has face detection. Point the camera at some faces. Hold the
 *     shutter. Release to take a picture.
 * (4) The camera has face detection. Point the camera at some faces. Single tap
 *     the shutter to take a picture.
 * (5) The camera has autofocus. Single tap the shutter to take a picture.
 * (6) The camera has autofocus. Hold the shutter. Release to take a picture.
 * (7) The camera has no autofocus. Single tap the shutter and take a picture.
 * (8) The camera has autofocus and supports focus area. Touch the screen to
 *     trigger autofocus. Take a picture.
 * (9) The camera has autofocus and supports focus area. Touch the screen to
 *     trigger autofocus. Wait until it times out.
 * (10) The camera has no autofocus and supports metering area. Touch the screen
 *     to change metering area.
 */
public class FocusManager {
    private static final String TAG = "FocusManager";

    private static final int RESET_TOUCH_FOCUS = 0;

    private int mState = STATE_IDLE;
    private static final int STATE_IDLE = 0; // Focus is not active.
    private static final int STATE_FOCUSING = 1; // Focus is in progress.
    // Focus is in progress and the camera should take a picture after focus finishes.
    private static final int STATE_FOCUSING_SNAP_ON_FINISH = 2;
    private static final int STATE_SUCCESS = 3; // Focus finishes and succeeds.
    private static final int STATE_FAIL = 4; // Focus finishes and fails.

    private boolean mInitialized;
    private boolean mFocusAreaSupported;
    private boolean mLockAeAwbNeeded;
    private boolean mAeAwbLock;
    private Matrix mMatrix;

    // The parent layout that includes only the focus indicator.
    private FocusIndicatorRotateLayout mFocusIndicatorRotateLayout;
    // The focus indicator view that holds the image resource.
    private View mFocusIndicator;
    private int mPreviewWidth; // The width of the preview frame layout.
    private int mPreviewHeight; // The height of the preview frame layout.
    private boolean mMirror; // true if the camera is front-facing.
    private int mDisplayOrientation;
    private FaceView mFaceView;
    private List<Area> mFocusArea; // focus area in driver format
    private List<Area> mMeteringArea; // metering area in driver format
    private String mFocusMode;
    private String[] mDefaultFocusModes;
    private String mOverrideFocusMode;
    private Parameters mParameters;
    private ComboPreferences mPreferences;
    private Handler mHandler;
    Listener mListener;

    public interface Listener {
        public void autoFocus();
        public void cancelAutoFocus();
        public boolean capture();
        public void startFaceDetection();
        public void stopFaceDetection();
        public void setFocusParameters();
        public void playSound(int soundId);
    }

    private class MainHandler extends Handler {
        public MainHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case RESET_TOUCH_FOCUS: {
                    cancelAutoFocus();
                    mListener.startFaceDetection();
                    break;
                }
            }
        }
    }

    public void setFocusAreaIndicator(View l) {
        mFocusIndicatorRotateLayout = (FocusIndicatorRotateLayout) l;
        mFocusIndicator = l.findViewById(R.id.focus_indicator);
    }

    public FocusManager(ComboPreferences preferences, String[] defaultFocusModes,
            View focusIndicatorRotate, Parameters parameters, Listener listener,
            boolean mirror, Looper looper) {
        mHandler = new MainHandler(looper);
        mMatrix = new Matrix();

        mPreferences = preferences;
        mDefaultFocusModes = defaultFocusModes;
        setFocusAreaIndicator(focusIndicatorRotate);
        setParameters(parameters);
        mListener = listener;
        setMirror(mirror);
    }

    public void setParameters(Parameters parameters) {
        mParameters = parameters;
        mFocusAreaSupported = (mParameters.getMaxNumFocusAreas() > 0
                && isSupported(Parameters.FOCUS_MODE_AUTO,
                        mParameters.getSupportedFocusModes()));
        mLockAeAwbNeeded = (mParameters.isAutoExposureLockSupported() ||
                mParameters.isAutoWhiteBalanceLockSupported());
    }

    public void setPreviewSize(int previewWidth, int previewHeight) {
        if (mPreviewWidth != previewWidth || mPreviewHeight != previewHeight) {
            mPreviewWidth = previewWidth;
            mPreviewHeight = previewHeight;
            setMatrix();

            // Set the length of focus indicator according to preview frame size.
            int len = Math.min(mPreviewWidth, mPreviewHeight) / 4;
            ViewGroup.LayoutParams layout = mFocusIndicator.getLayoutParams();
            layout.width = len;
            layout.height = len;
        }
    }

    public void setMirror(boolean mirror) {
        mMirror = mirror;
        setMatrix();
    }

    public void setDisplayOrientation(int displayOrientation) {
        mDisplayOrientation = displayOrientation;
        setMatrix();
    }

    public void setFaceView(FaceView faceView) {
        mFaceView = faceView;
    }

    private void setMatrix() {
        if (mPreviewWidth != 0 && mPreviewHeight != 0) {
            Matrix matrix = new Matrix();
            Util.prepareMatrix(matrix, mMirror, mDisplayOrientation,
                    mPreviewWidth, mPreviewHeight);
            // In face detection, the matrix converts the driver coordinates to UI
            // coordinates. In tap focus, the inverted matrix converts the UI
            // coordinates to driver coordinates.
            matrix.invert(mMatrix);
            mInitialized = true;
        }
    }

    public void onShutterDown() {
        if (!mInitialized) return;

        // Lock AE and AWB so users can half-press shutter and recompose.
        if (mLockAeAwbNeeded && !mAeAwbLock) {
            mAeAwbLock = true;
            mListener.setFocusParameters();
        }

        if (needAutoFocusCall()) {
            // Do not focus if touch focus has been triggered.
            if (mState != STATE_SUCCESS && mState != STATE_FAIL) {
                autoFocus();
            }
        }
    }

    public void onShutterUp() {
        if (!mInitialized) return;

        if (needAutoFocusCall()) {
            // User releases half-pressed focus key.
            if (mState == STATE_FOCUSING || mState == STATE_SUCCESS
                    || mState == STATE_FAIL) {
                cancelAutoFocus();
            }
        }

        // Unlock AE and AWB after cancelAutoFocus. Camera API does not
        // guarantee setParameters can be called during autofocus.
        if (mLockAeAwbNeeded && mAeAwbLock && (mState != STATE_FOCUSING_SNAP_ON_FINISH)) {
            mAeAwbLock = false;
            mListener.setFocusParameters();
        }
    }

    public void doSnap() {
        if (!mInitialized) return;

        // If the user has half-pressed the shutter and focus is completed, we
        // can take the photo right away. If the focus mode is infinity, we can
        // also take the photo.
        if (!needAutoFocusCall() || (mState == STATE_SUCCESS || mState == STATE_FAIL)) {
            capture();
        } else if (mState == STATE_FOCUSING) {
            // Half pressing the shutter (i.e. the focus button event) will
            // already have requested AF for us, so just request capture on
            // focus here.
            mState = STATE_FOCUSING_SNAP_ON_FINISH;
        } else if (mState == STATE_IDLE) {
            // We didn't do focus. This can happen if the user press focus key
            // while the snapshot is still in progress. The user probably wants
            // the next snapshot as soon as possible, so we just do a snapshot
            // without focusing again.
            capture();
        }
    }

    public void onAutoFocus(boolean focused) {
        if (mState == STATE_FOCUSING_SNAP_ON_FINISH) {
            // Take the picture no matter focus succeeds or fails. No need
            // to play the AF sound if we're about to play the shutter
            // sound.
            if (focused) {
                mState = STATE_SUCCESS;
            } else {
                mState = STATE_FAIL;
            }
            updateFocusUI();
            capture();
        } else if (mState == STATE_FOCUSING) {
            // This happens when (1) user is half-pressing the focus key or
            // (2) touch focus is triggered. Play the focus tone. Do not
            // take the picture now.
            if (focused) {
                mState = STATE_SUCCESS;
                // Do not play the sound in continuous autofocus mode. It does
                // not do a full scan. The focus callback arrives before doSnap
                // so the state is always STATE_FOCUSING.
                if (!Parameters.FOCUS_MODE_CONTINUOUS_PICTURE.
                        equals(mFocusMode)) {
                    mListener.playSound(MediaActionSound.FOCUS_COMPLETE);
                }
            } else {
                mState = STATE_FAIL;
            }
            updateFocusUI();
            // If this is triggered by touch focus, cancel focus after a
            // while.
            if ((mFocusArea != null) && (ActivityBase.mFocusTime != 0)) {
                mHandler.sendEmptyMessageDelayed(RESET_TOUCH_FOCUS, ActivityBase.mFocusTime);
            }
        } else if (mState == STATE_IDLE) {
            // User has released the focus key before focus completes.
            // Do nothing.
        }
    }

    public void onAutoFocusMoving(boolean moving) {
        // Ignore if the camera has detected some faces.
        if (mFaceView != null && mFaceView.faceExists()) return;

        // Ignore if we have requested autofocus. This method only handles
        // continuous autofocus.
        if (mState != STATE_IDLE) return;

        if (moving) {
            mFocusIndicatorRotateLayout.showStart();
        } else {
            mFocusIndicatorRotateLayout.showSuccess(true);
        }
    }

    public void onSingleTapUp(int x, int y) {
        if (!mInitialized || mState == STATE_FOCUSING_SNAP_ON_FINISH) return;

        // Let users be able to cancel previous touch focus.
        if ((mFocusArea != null) && (mState == STATE_FOCUSING ||
                    mState == STATE_SUCCESS || mState == STATE_FAIL)) {
            cancelAutoFocus();
        }

        // Initialize variables.
        int focusWidth = mFocusIndicatorRotateLayout.getWidth();
        int focusHeight = mFocusIndicatorRotateLayout.getHeight();
        int previewWidth = mPreviewWidth;
        int previewHeight = mPreviewHeight;
        if (mFocusArea == null) {
            mFocusArea = new ArrayList<Area>();
            mFocusArea.add(new Area(new Rect(), 1));
            mMeteringArea = new ArrayList<Area>();
            mMeteringArea.add(new Area(new Rect(), 1));
        }

        // Convert the coordinates to driver format.
        // AE area is bigger because exposure is sensitive and
        // easy to over- or underexposure if area is too small.
        calculateTapArea(focusWidth, focusHeight, 1f, x, y, previewWidth, previewHeight,
                mFocusArea.get(0).rect);
        calculateTapArea(focusWidth, focusHeight, 1.5f, x, y, previewWidth, previewHeight,
                mMeteringArea.get(0).rect);

        // Use margin to set the focus indicator to the touched area.
        RelativeLayout.LayoutParams p =
                (RelativeLayout.LayoutParams) mFocusIndicatorRotateLayout.getLayoutParams();
        int left = Util.clamp(x - focusWidth / 2, 0, previewWidth - focusWidth);
        int top = Util.clamp(y - focusHeight / 2, 0, previewHeight - focusHeight);
        p.setMargins(left, top, 0, 0);
        // Disable "center" rule because we no longer want to put it in the center.
        int[] rules = p.getRules();
        rules[RelativeLayout.CENTER_IN_PARENT] = 0;
        mFocusIndicatorRotateLayout.requestLayout();

        // Stop face detection because we want to specify focus and metering area.
        mListener.stopFaceDetection();

        // Set the focus area and metering area.
        mListener.setFocusParameters();
        if (mFocusAreaSupported) {
            autoFocus();
        } else {  // Just show the indicator in all other cases.
            updateFocusUI();
            mHandler.removeMessages(RESET_TOUCH_FOCUS);
            if (ActivityBase.mFocusTime != 0) {
                // Reset the metering area in 3 seconds.
                mHandler.sendEmptyMessageDelayed(RESET_TOUCH_FOCUS, ActivityBase.mFocusTime);
            }
        }
    }

    public void onPreviewStarted() {
        mState = STATE_IDLE;
    }

    public void onPreviewStopped() {
        mState = STATE_IDLE;
        resetTouchFocus();
        // If auto focus was in progress, it would have been canceled.
        updateFocusUI();
    }

    public void onCameraReleased() {
        onPreviewStopped();
    }

    private void autoFocus() {
        Log.v(TAG, "Start autofocus.");
        mListener.autoFocus();
        mState = STATE_FOCUSING;
        // Pause the face view because the driver will keep sending face
        // callbacks after the focus completes.
        if (mFaceView != null) mFaceView.pause();
        updateFocusUI();
        mHandler.removeMessages(RESET_TOUCH_FOCUS);
    }

    private void cancelAutoFocus() {
        Log.v(TAG, "Cancel autofocus.");

        // Reset the tap area before calling mListener.cancelAutofocus.
        // Otherwise, focus mode stays at auto and the tap area passed to the
        // driver is not reset.
        resetTouchFocus();
        mListener.cancelAutoFocus();
        if (mFaceView != null) mFaceView.resume();
        mState = STATE_IDLE;
        updateFocusUI();
        mHandler.removeMessages(RESET_TOUCH_FOCUS);
    }

    private void capture() {
        if (mListener.capture()) {
            mState = STATE_IDLE;
            mHandler.removeMessages(RESET_TOUCH_FOCUS);
        }
    }

    public String getFocusMode() {
        if (mOverrideFocusMode != null) return mOverrideFocusMode;
        List<String> supportedFocusModes = mParameters.getSupportedFocusModes();

        if (mFocusAreaSupported && mFocusArea != null) {
            // Always use autofocus in tap-to-focus.
            mFocusMode = Parameters.FOCUS_MODE_AUTO;
        } else {
            // The default is continuous autofocus.
            mFocusMode = mPreferences.getString(
                    CameraSettings.KEY_FOCUS_MODE, null);

            // Try to find a supported focus mode from the default list.
            if (mFocusMode == null) {
                for (int i = 0; i < mDefaultFocusModes.length; i++) {
                    String mode = mDefaultFocusModes[i];
                    if (isSupported(mode, supportedFocusModes)) {
                        mFocusMode = mode;
                        break;
                    }
                }
            }
        }
        if (!isSupported(mFocusMode, supportedFocusModes)) {
            // For some reasons, the driver does not support the current
            // focus mode. Fall back to auto.
            if (isSupported(Parameters.FOCUS_MODE_AUTO,
                    mParameters.getSupportedFocusModes())) {
                mFocusMode = Parameters.FOCUS_MODE_AUTO;
            } else {
                mFocusMode = mParameters.getFocusMode();
            }
        }
        return mFocusMode;
    }

    public List<Area> getFocusAreas() {
        return mFocusArea;
    }

    public List<Area> getMeteringAreas() {
        return mMeteringArea;
    }

    public void updateFocusUI() {
        if (!mInitialized) return;

        // Show only focus indicator or face indicator.
        boolean faceExists = (mFaceView != null && mFaceView.faceExists());
        FocusIndicator focusIndicator = (faceExists) ? mFaceView : mFocusIndicatorRotateLayout;

        if (mState == STATE_IDLE) {
            if (mFocusArea == null) {
                focusIndicator.clear();
            } else {
                // Users touch on the preview and the indicator represents the
                // metering area. Either focus area is not supported or
                // autoFocus call is not required.
                focusIndicator.showStart();
            }
        } else if (mState == STATE_FOCUSING || mState == STATE_FOCUSING_SNAP_ON_FINISH) {
            focusIndicator.showStart();
        } else {
            if (Parameters.FOCUS_MODE_CONTINUOUS_PICTURE.equals(mFocusMode)) {
                // TODO: check HAL behavior and decide if this can be removed.
                focusIndicator.showSuccess(false);
            } else if (mState == STATE_SUCCESS) {
                focusIndicator.showSuccess(false);
            } else if (mState == STATE_FAIL) {
                focusIndicator.showFail(false);
            }
        }
    }

    public void resetTouchFocus() {
        if (!mInitialized) return;

        // Put focus indicator to the center.
        RelativeLayout.LayoutParams p =
                (RelativeLayout.LayoutParams) mFocusIndicatorRotateLayout.getLayoutParams();
        int[] rules = p.getRules();
        rules[RelativeLayout.CENTER_IN_PARENT] = RelativeLayout.TRUE;
        p.setMargins(0, 0, 0, 0);
        mFocusIndicatorRotateLayout.clear();

        mFocusArea = null;
        mMeteringArea = null;
    }

    public void calculateTapArea(int focusWidth, int focusHeight, float areaMultiple,
            int x, int y, int previewWidth, int previewHeight, Rect rect) {
        int areaWidth = (int) (focusWidth * areaMultiple);
        int areaHeight = (int) (focusHeight * areaMultiple);
        int left = Util.clamp(x - areaWidth / 2, 0, previewWidth - areaWidth);
        int top = Util.clamp(y - areaHeight / 2, 0, previewHeight - areaHeight);

        RectF rectF = new RectF(left, top, left + areaWidth, top + areaHeight);
        mMatrix.mapRect(rectF);
        Util.rectFToRect(rectF, rect);
    }

    public boolean isFocusCompleted() {
        return mState == STATE_SUCCESS || mState == STATE_FAIL;
    }

    public boolean isFocusingSnapOnFinish() {
        return mState == STATE_FOCUSING_SNAP_ON_FINISH;
    }

    public void removeMessages() {
        mHandler.removeMessages(RESET_TOUCH_FOCUS);
    }

    public void overrideFocusMode(String focusMode) {
        mOverrideFocusMode = focusMode;
    }

    public void setAeAwbLock(boolean lock) {
        mAeAwbLock = lock;
    }

    public boolean getAeAwbLock() {
        return mAeAwbLock;
    }

    private static boolean isSupported(String value, List<String> supported) {
        return supported == null ? false : supported.indexOf(value) >= 0;
    }

    private boolean needAutoFocusCall() {
        String focusMode = getFocusMode();
        return !(focusMode.equals(Parameters.FOCUS_MODE_INFINITY)
                || focusMode.equals(Parameters.FOCUS_MODE_FIXED)
                || focusMode.equals(Parameters.FOCUS_MODE_EDOF)
                || focusMode.equals(Parameters.FOCUS_MODE_CONTINUOUS_PICTURE));
    }
}
