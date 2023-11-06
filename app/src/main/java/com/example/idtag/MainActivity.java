package com.example.idtag;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.core.Point;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextureView textureView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureRequestBuilder;

    private static final String TAG = "MainActivity"; // For logging

    private ImageView processImageView;

    private boolean isFlashOn = false;

    private Mat objectToTrack; // Store the object to track
    private Rect objectRect;   // Store the object's position


    static {
        if (!OpenCVLoader.initDebug()) {
            Log.e("MainActivity", "OpenCV not loaded");
        } else {
            Log.i("MainActivity", "OpenCV loaded successfully");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.textureView);
        processImageView = findViewById(R.id.processImageView);

        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "OpenCV not loaded", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "OpenCV loaded successfully", Toast.LENGTH_SHORT).show();
        }

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    setupCamera();
                } else {
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.CAMERA}, 200);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                Bitmap textureBitmap = textureView.getBitmap();
                if (textureBitmap == null) {
                    Log.e(TAG, "Bitmap from TextureView is null!");
                    return;
                }

                Mat frame = new Mat(textureBitmap.getHeight(), textureBitmap.getWidth(), CvType.CV_8UC4);
                Utils.bitmapToMat(textureBitmap, frame);

                // Convert RGBA image to RGB
                Mat rgb = new Mat();
                Imgproc.cvtColor(frame, rgb, Imgproc.COLOR_RGBA2RGB);

                // Convert the RGB image to HSV
                Mat hsv = new Mat();
                Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV);

                // Extract the Value channel
                List<Mat> hsvChannels = new ArrayList<>();
                Core.split(hsv, hsvChannels);
                Mat valueChannel = hsvChannels.get(2);

                // Threshold the Value channel for high light intensity
                double highIntensityThreshold = 50; // Adjust based on the intensity of the reflection
                Mat highIntensityAreas = new Mat();
                Imgproc.threshold(valueChannel, highIntensityAreas, highIntensityThreshold, 255, Imgproc.THRESH_BINARY);

                // Adaptive threshold
//                Imgproc.adaptiveThreshold(valueChannel, highIntensityAreas, 255, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY, 1001, 5);

                // Use morphological operations to close gaps and remove noise
                Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(15, 15));  // Adjust the size for desired dilation/erosion
//                Imgproc.morphologyEx(highIntensityAreas, highIntensityAreas, Imgproc.MORPH_OPEN, kernel);
                Imgproc.morphologyEx(highIntensityAreas, highIntensityAreas, Imgproc.MORPH_CLOSE, kernel);


                // Overlay these high-intensity areas on the original frame using a color to highlight
                frame.setTo(new Scalar(0, 255, 0), highIntensityAreas);

                // Convert the processed frame back to Bitmap and update ImageView
                Bitmap processedBitmap = Bitmap.createBitmap(frame.cols(), frame.rows(), Bitmap.Config.ARGB_8888);
                Utils.matToBitmap(frame, processedBitmap);
                runOnUiThread(() -> processImageView.setImageBitmap(processedBitmap));

                // Release memory
                rgb.release();
                hsv.release();
                valueChannel.release();
                highIntensityAreas.release();
            }




        });

        Button flashlightButton = findViewById(R.id.flashlight_button);
        flashlightButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isFlashOn) {
                    turnOffFlash();
                } else {
                    turnOnFlash();
                }
                isFlashOn = !isFlashOn;
            }
        });
    }

    private void setupCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = cameraManager.getCameraIdList()[0];
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

            // Check if the camera has a flash unit
            Boolean hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (hasFlash == null || !hasFlash) {
                showToast("No flash available");
                return;
            }
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    startCameraPreview();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.e(TAG, "Camera disconnected");
                    cameraDevice.close();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    cameraDevice.close();
                }
            }, null);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "Camera access error", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 200) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupCamera();
            } else {
                showToast("Camera permission is required");
            }
        }
    }

    private void startCameraPreview() {
        try {
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            Surface previewSurface = new Surface(surfaceTexture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);

            captureRequestBuilder.addTarget(previewSurface);
            cameraDevice.createCaptureSession(Collections.singletonList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            try {
                                captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Failed to start camera preview", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Failed to configure camera");
                        }
                    }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access error during preview start", e);
        }
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void turnOnFlash() {
        if (captureRequestBuilder != null) {
            captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            try {
                captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    private void turnOffFlash() {
        if (captureRequestBuilder != null) {
            captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            try {
                captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        super.onDestroy();
    }
}
