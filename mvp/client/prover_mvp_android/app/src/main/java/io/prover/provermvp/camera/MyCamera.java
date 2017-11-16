package io.prover.provermvp.camera;

import android.content.ContentValues;
import android.content.Context;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.util.List;

import io.prover.provermvp.Const;
import io.prover.provermvp.Settings;
import io.prover.provermvp.transport.BufferHolder;

/**
 * Created by babay on 09.12.2016.
 */

public class MyCamera implements BufferHolder.OnBufferReleasedListener {
    public static final String TAG = Const.TAG + "Camera";
    public final int id;
    private final Camera.CameraInfo cameraInfo;
    private final BufferHolder bufferHolder = new BufferHolder();
    private final ResolutionSelector resolutionSelector = new ResolutionSelector();
    private Camera camera;
    private List<Size> availableResolutions;
    private Camera.PreviewCallback previewCallback;

    private MyCamera(int id, Camera camera, Camera.CameraInfo cameraInfo) {
        this.id = id;
        this.camera = camera;
        this.cameraInfo = cameraInfo;
        if (camera != null) {
            availableResolutions = resolutionSelector.getSuitableResolutions(camera, null);
        }
    }

    private MyCamera(int id, Camera.CameraInfo cameraInfo) {
        this.id = id;
        this.cameraInfo = cameraInfo;
        open();
    }

    public static MyCamera openBackCamera() {
        Log.d(TAG, "Open back camera");
        int numberOfCameras = Camera.getNumberOfCameras();
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                return new MyCamera(i, cameraInfo);
            }
        }
        return null;
    }

    public static int getDisplayRotation(int rotationDirection) {
        switch (rotationDirection) {
            case Surface.ROTATION_0:
                return 0;
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
        }
        return 0;
    }

    public synchronized Camera open() {
        if (camera != null)
            return camera;
        try {
            Log.d(TAG, "Camera.open");
            camera = Camera.open(id);
            availableResolutions = resolutionSelector.getSuitableResolutions(camera, null);
        } catch (Exception e) {
            Log.e(TAG, e.getMessage(), e);
            camera = null;
        }
        return camera;
    }

    public Camera getCamera() {
        return camera;
    }

    public void release() {
        if (camera != null) {
            Log.d(TAG, "releasing camera");
            camera.setPreviewCallback(null);
            camera.release();
            camera = null;
        }
        bufferHolder.setBufferReleasedListener(null);
    }

    public int getDisplayOrientation(int degrees) {
        // See android.hardware.Camera.setDisplayOrientation for
        // documentation.
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(id, info);
        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }

    public Size selectResolution(Size selectedResolution, Size surfaceSize, Context context) {
        if (availableResolutions == null)
            return null;
        return resolutionSelector.selectResolution(selectedResolution, availableResolutions, surfaceSize, context);
    }

    public List<Size> getAvailableResolutions() {
        return availableResolutions;
    }

    public void updateDisplayOrientation(int displayOrientation) {
        int degrees = getDisplayRotation(displayOrientation);
        int orientation = getDisplayOrientation(degrees);
        camera.setDisplayOrientation(orientation);
    }

    public MediaRecorder prepareRecording(File file, Surface previewSurface) {
        if (camera == null)
            return null;

        MediaRecorder mediaRecorder = new MediaRecorder();
        mediaRecorder.setCamera(camera);

        // Step 2: Set sources
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

        // Step 3: Set a CamcorderProfile (requires API Level 8 or higher)
        mediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH));

        // Step 4: Set output file
        mediaRecorder.setOutputFile(file.getPath());

        // Step 5: Set the preview output
        mediaRecorder.setPreviewDisplay(previewSurface);

        // Step 6: Prepare configured MediaRecorder
        try {
            mediaRecorder.prepare();
        } catch (IllegalStateException | IOException e) {
            Log.d(ContentValues.TAG, "IllegalStateException preparing MediaRecorder: " + e.getMessage());
            try {
                mediaRecorder.reset();
            } catch (Exception ignored) {
            }
            try {
                mediaRecorder.release();
            } catch (Exception ignored) {
            }
            return null;
        }

        return mediaRecorder;
    }

    @Override
    public boolean onBufferReleased(byte[] buffer) {
        if (camera != null) {
            if (Settings.REUSE_PREVIEW_BUFFERS) {
                camera.addCallbackBuffer(buffer);
                bufferHolder.onBufferAddedToCamera();
            }
            return true;
        }
        return false;
    }

    public void onStartPreview(int previewWidth, int previewHeight) {
        bufferHolder.setSize(previewWidth, previewHeight);
        bufferHolder.setBufferReleasedListener(this);
        if (Settings.REUSE_PREVIEW_BUFFERS) {
            camera.addCallbackBuffer(bufferHolder.getBuffer());
            camera.addCallbackBuffer(bufferHolder.getBuffer());
            camera.addCallbackBuffer(bufferHolder.getBuffer());
            camera.addCallbackBuffer(bufferHolder.getBuffer());
            bufferHolder.onBufferAddedToCamera();
            bufferHolder.onBufferAddedToCamera();
            bufferHolder.onBufferAddedToCamera();
            bufferHolder.onBufferAddedToCamera();
        }
    }

    public BufferHolder getBufferHolder() {
        return bufferHolder;
    }

    public void stopPreview() {
        if (camera != null) {
            camera.stopPreview();
            camera.setPreviewCallbackWithBuffer(null);
            camera.setPreviewCallback(null);
            try {
                camera.setPreviewDisplay(null);
            } catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }
        bufferHolder.setBufferReleasedListener(null);
    }

    public void updateCallback() {
        if (camera != null) {
            Log.d(TAG, "updating buffer callback");
            if (Settings.REUSE_PREVIEW_BUFFERS) {
                camera.setPreviewCallbackWithBuffer(previewCallback);
            } else {
                camera.setPreviewCallback(previewCallback);
            }
        }
    }

    public void setRecording(boolean recording) {
        bufferHolder.setRecording(recording);
        if (recording) {
            updateCallback();
        }
    }

    public void setPreviewCallback(Camera.PreviewCallback previewCallback) {
        this.previewCallback = previewCallback;
    }
}
