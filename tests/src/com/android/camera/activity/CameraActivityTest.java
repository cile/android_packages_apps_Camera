/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.camera.activity;

import android.hardware.Camera.Parameters;
import android.test.suitebuilder.annotation.LargeTest;

import com.android.camera.Camera;
import com.android.camera.CameraHolder;
import com.android.camera.R;

import static com.google.testing.littlemock.LittleMock.doReturn;

public class CameraActivityTest extends CameraTestCase <Camera> {
    public CameraActivityTest() {
        super(Camera.class);
    }

    @LargeTest
    public void testFailToConnect() throws Exception {
        super.internalTestFailToConnect();
    }

    @LargeTest
    public void testRestoreDefault() throws Exception {
        super.internalTestRestoreDefault();
    }

    @LargeTest
    public void testOneCamera() throws Exception {
        super.internalTestOneCamera();
    }

    @LargeTest
    public void testSwitchCamera() throws Exception {
        super.internalTestSwitchCamera();
    }

    @LargeTest
    public void testPriorityIndicators() throws Exception {
        // Remove parameters.
        Parameters param = getParameters();
        String[] keys = {"scene-mode", "whitebalance", "flash-mode"};
        for (String key : keys) {
            param.remove(key);
            param.remove(key + "-values");
        }
        param.set("focus-mode", "infinity");
        param.set("focus-mode-values", "infinity");

        doReturn(param).when(mOneMockCamera[0]).getParameters();
        CameraHolder.injectMockCamera(mOneCameraInfo, mOneMockCamera);

        assertViewNotVisible(R.id.onscreen_scene_indicator);
        assertViewNotVisible(R.id.onscreen_white_balance_indicator);
        assertViewNotVisible(R.id.onscreen_flash_indicator);
        assertViewNotVisible(R.id.onscreen_focus_indicator);
    }

    @LargeTest
    public void testTakePicture() throws Exception {
        CameraHolder.injectMockCamera(mCameraInfo, mOneMockCamera);

        getActivity();
        getInstrumentation().waitForIdleSync();

        // Press shutter button to take a picture.
        performClick(R.id.shutter_button);
        getInstrumentation().waitForIdleSync();

        // Force the activity to finish.
        getActivity().finish();
        getInstrumentation().waitForIdleSync();
    }
}
