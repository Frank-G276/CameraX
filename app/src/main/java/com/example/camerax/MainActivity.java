package com.example.camerax;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.media.Image;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.example.camerax.databinding.ActivityMainBinding;
import com.google.android.gms.tasks.Task;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "CameraXApp";
    private static final String FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS";
    private static final int REQUEST_CODE_PERMISSIONS = 10;

    // Permisos actualizados según versión de Android
    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            return new String[] {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            return new String[] {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            return new String[] {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            };
        } else {
            // Android 13 (API 33) y superior usa permisos granulares
            return new String[] {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
            };
        }
    }

    private ActivityMainBinding viewBinding;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private ExecutorService cameraExecutor;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private TextView cameraStatus;
    private Button videoCaptureButton;

    // Variables para detección de rostros - CORREGIDO: Usar FaceDetector de ML Kit
    private FaceDetector faceDetector;
    private int facesDetected = 0;

    // Nuevo: ActivityResultLauncher para manejo de permisos moderno
    private ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    permissions -> {
                        boolean allGranted = true;
                        for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
                            if (!entry.getValue()) {
                                allGranted = false;
                                break;
                            }
                        }

                        if (allGranted) {
                            startCamera();
                        } else {
                            Toast.makeText(this,
                                    "Los permisos no fueron concedidos",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewBinding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());

        // Inicializar el detector de rostros
        setupFaceDetector();

        // Verificar permisos con el nuevo enfoque
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            requestPermissions();
        }

        // Inicializar componentes
        cameraStatus = viewBinding.cameraStatus;
        videoCaptureButton = viewBinding.videoCaptureButton;

        // Configurar botones
        viewBinding.imageCaptureButton.setOnClickListener(v -> takePhoto());
        viewBinding.videoCaptureButton.setOnClickListener(v -> captureVideo());
        viewBinding.cameraToggleButton.setOnClickListener(v -> toggleCamera());

        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    // Configurar el detector de rostros de ML Kit
    private void setupFaceDetector() {
        // Opciones de alta precisión para detección de rostros
        FaceDetectorOptions highAccuracyOpts =
                new FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                        .enableTracking()
                        .build();

        // Crear el detector con las opciones configuradas
        faceDetector = FaceDetection.getClient(highAccuracyOpts);
    }

    private void requestPermissions() {
        // Usar el método moderno de solicitud de permisos
        requestPermissionLauncher.launch(getRequiredPermissions());
    }

    private void toggleCamera() {
        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            lensFacing = CameraSelector.LENS_FACING_FRONT;
        } else {
            lensFacing = CameraSelector.LENS_FACING_BACK;
        }
        startCamera();
    }

    private void startCamera() {
        // Verificar permisos antes de iniciar la cámara
        if (!allPermissionsGranted()) {
            requestPermissions();
            return;
        }

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                // Obtener el proveedor de cámara
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Configurar la vista previa
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewBinding.viewFinder.getSurfaceProvider());

                // Configurar la captura de imagen
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();

                // Configurar el análisis de imagen con detección de rostros
                ImageAnalysis imageAnalyzer = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalyzer.setAnalyzer(cameraExecutor, this::analyzeFaces);

                // Configurar la captura de video
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                // Seleccionar cámara según lensFacing
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build();

                // Vincular todos los casos de uso a la cámara
                cameraProvider.unbindAll();
                Camera camera = cameraProvider.bindToLifecycle(
                        (LifecycleOwner) this, cameraSelector, preview, imageCapture, videoCapture, imageAnalyzer);

                cameraStatus.setText(R.string.ready);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error al iniciar la cámara: ", e);
                Toast.makeText(this, "Error al iniciar la cámara: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // Método de análisis para detectar rostros en imágenes de la cámara
    @SuppressLint("UnsafeOptInUsageError")
    private void analyzeFaces(ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();
        if (mediaImage != null) {
            InputImage image = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.getImageInfo().getRotationDegrees()
            );

            // Procesar la imagen con el detector de rostros
            Task<List<Face>> result = faceDetector.process(image)
                    .addOnSuccessListener(faces -> {
                        facesDetected = faces.size();

                        // Actualizar la interfaz con información sobre los rostros detectados
                        runOnUiThread(() -> {
                            if (facesDetected > 0) {
                                cameraStatus.setText("Rostros detectados: " + facesDetected);

                                // Obtener información adicional del primer rostro
                                if (faces.size() > 0) {
                                    Face face = faces.get(0);

                                    // Si el seguimiento está activado, puedes obtener el ID
                                    if (face.getTrackingId() != null) {
                                        Log.d(TAG, "ID de seguimiento: " + face.getTrackingId());
                                    }

                                    // Obtener los límites del rostro
                                    Rect bounds = face.getBoundingBox();
                                    Log.d(TAG, "Rostro en: " + bounds.toString());

                                    // También puedes detectar sonrisas, ojos abiertos, etc. si lo configuraste
                                    // float smileProb = face.getSmilingProbability();
                                    // float rightEyeOpenProb = face.getRightEyeOpenProbability();
                                }
                            } else {
                                cameraStatus.setText(R.string.ready);
                            }
                        });
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Error en detección de rostros: ", e);
                    })
                    .addOnCompleteListener(task -> {
                        // Importante: cerrar el imageProxy cuando termines
                        imageProxy.close();
                    });
        } else {
            imageProxy.close();
        }
    }

    private void takePhoto() {
        // Verificar que la captura de imagen está inicializada
        if (imageCapture == null) {
            return;
        }

        // Verificar permisos explícitamente antes de tomar una foto
        if (!allPermissionsGranted()) {
            requestPermissions();
            Toast.makeText(this, "Se requieren permisos para tomar fotos", Toast.LENGTH_SHORT).show();
            return;
        }

        // Preparar metadatos para el archivo de salida
        String name = new SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");

        // Actualizado para compatibilidad con versiones recientes de Android
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image");
        }

        // Crear opciones de salida
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions
                .Builder(getContentResolver(),
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
                .build();

        try {
            // Configurar el callback de captura
            imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(this),
                    new ImageCapture.OnImageSavedCallback() {
                        @Override
                        public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                            String msg = "Foto guardada: " +
                                    outputFileResults.getSavedUri();
                            if (facesDetected > 0) {
                                msg += " - Rostros detectados: " + facesDetected;
                            }
                            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                            Log.d(TAG, msg);
                        }

                        @Override
                        public void onError(@NonNull ImageCaptureException exception) {
                            Log.e(TAG, "Error al guardar la foto: ", exception);
                            Toast.makeText(MainActivity.this,
                                    "Error al guardar la foto: " + exception.getMessage(),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
            );
        } catch (SecurityException e) {
            Log.e(TAG, "Error de seguridad al tomar foto: " + e.getMessage());
            Toast.makeText(this, "Permiso de cámara denegado", Toast.LENGTH_SHORT).show();
        }
    }

    private void captureVideo() {
        if (videoCapture == null) {
            return;
        }

        // Si hay una grabación en curso, detenerla
        if (recording != null) {
            recording.stop();
            recording = null;
            videoCaptureButton.setText(R.string.start_video);
            cameraStatus.setText(R.string.ready);
            return;
        }

        // Verificar permisos explícitamente antes de grabar
        if (!allPermissionsGranted()) {
            requestPermissions();
            Toast.makeText(this, "Se requieren permisos para grabar video", Toast.LENGTH_SHORT).show();
            return;
        }

        // Preparar metadatos para el archivo de salida
        String name = new SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");

        // Actualizado para compatibilidad con versiones recientes de Android
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video");
        }

        MediaStoreOutputOptions mediaStoreOutputOptions = new MediaStoreOutputOptions
                .Builder(getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build();

        try {
            // Configurar el callback de grabación
            recording = videoCapture.getOutput()
                    .prepareRecording(this, mediaStoreOutputOptions)
                    .withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(this), videoRecordEvent -> {
                        if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                            videoCaptureButton.setText(R.string.stop_video);

                            // Actualizar el estado si hay rostros detectados
                            if (facesDetected > 0) {
                                cameraStatus.setText("Grabando - Rostros: " + facesDetected);
                            } else {
                                cameraStatus.setText("Grabando...");
                            }
                        } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                            VideoRecordEvent.Finalize finalizeEvent =
                                    (VideoRecordEvent.Finalize) videoRecordEvent;
                            if (!finalizeEvent.hasError()) {
                                String msg = "Video guardado en: " +
                                        finalizeEvent.getOutputResults().getOutputUri();
                                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                                Log.d(TAG, msg);
                            } else {
                                Log.e(TAG, "Error al grabar video: " +
                                        finalizeEvent.getError());
                                recording = null;
                                videoCaptureButton.setText(R.string.start_video);
                            }
                        }
                    });
        } catch (SecurityException e) {
            Log.e(TAG, "Error de seguridad al iniciar la grabación: " + e.getMessage());
            Toast.makeText(this, "Permiso de grabación denegado", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean allPermissionsGranted() {
        String[] requiredPermissions = getRequiredPermissions();
        for (String permission : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) !=
                    PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // Este método se mantiene para compatibilidad con versiones anteriores
    // pero ya no es el principal método para manejar permisos
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this,
                        "Los permisos no fueron concedidos",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}