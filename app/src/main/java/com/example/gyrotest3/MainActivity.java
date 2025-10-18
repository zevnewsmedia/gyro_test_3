package com.example.gyrotest3;

import android.content.pm.ActivityInfo;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import android.widget.Button;
import android.widget.LinearLayout;
import android.view.Gravity;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import io.socket.client.IO;
import io.socket.client.Socket;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;

import android.content.SharedPreferences;
import java.util.UUID;
import androidx.appcompat.app.AlertDialog;
import android.widget.EditText;
import android.text.InputType;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/**
 * MainActivity - Android Gyroscope + GPS Data Transmission App
 *
 * Hybrid speed measurement: GPS for accuracy, accelerometer for smooth real-time updates
 * Jump detection: Monitors vertical acceleration to detect airtime
 */
public class MainActivity extends AppCompatActivity implements SensorEventListener {

    // ========================================
    // CONSTANTS
    // ========================================

    private static final String TAG = "GyroSocket";
    private static final String SERVER_URL = "http://3.91.244.249:5000/";
    private static final long SEND_INTERVAL = 100;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 100;

    // ========================================
    // DEVICE & USER MANAGEMENT
    // ========================================

    private String deviceId;
    private String riderName;

    // ========================================
    // SENSOR COMPONENTS
    // ========================================

    private float currentGForce = 0;
    private boolean wasAirborne = false;
    private long airborneStartTime = 0;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor magnetometer;
    private GyroDialView dialView;
    private float currentYaw = 0;
    private float currentPitch = 0;
    private float currentRoll = 0;
    private boolean dialActive = false;
    private String availableMotionSensors = "";

    private float[] accelerometerValues = new float[3];
    private float[] magnetometerValues = new float[3];
    private boolean hasAccelerometerData = false;
    private boolean hasMagnetometerData = false;
    private float[] rotationMatrix = new float[9];
    private float[] orientationValues = new float[3];

    // ========================================
    // GPS & SPEED (HYBRID APPROACH)
    // ========================================

    private LocationManager locationManager;
    private float currentSpeed = 0; // km/h (display speed)
    private float smoothedSpeed = 0; // Internal smoothed value
    private float lastGPSSpeed = 0; // Last GPS reading
    private long lastGPSUpdateTime = 0; // Timestamp of last GPS update
    private static final float SPEED_SMOOTHING = 0.15f; // GPS blend factor

    private LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            lastGPSSpeed = location.getSpeed() * 3.6f; // m/s to km/h
            lastGPSUpdateTime = System.currentTimeMillis();
            smoothedSpeed = lastGPSSpeed; // Anchor to GPS
            Log.d(TAG, "GPS Speed Update: " + String.format("%.2f", lastGPSSpeed) + " km/h");
        }

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {}

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}
    };

    // ========================================
    // NETWORK & SOCKET.IO
    // ========================================

    private Socket socket;
    private boolean socketConnected = false;
    private long lastSendTime = 0;
    private boolean deviceState = true;

    // ========================================
    // UI COMPONENTS
    // ========================================

    private Button connectionButton;
    private LinearLayout mainLayout;

    // ========================================
    // LIFECYCLE METHODS
    // ========================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initializeDeviceAndRider();
        setupUI();
        initializeComponents();
        connectToServer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerSensorListener();
        registerLocationListener();

        if (socket != null && !socket.connected()) {
            connectToServer();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterSensorListener();
        unregisterLocationListener();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanup();
    }

    // ========================================
    // INITIALIZATION METHODS
    // ========================================

    private void saveDeviceToServer() {
        if (socket == null || !socketConnected || riderName == null || riderName.isEmpty()) {
            return;
        }

        try {
            JSONObject deviceData = new JSONObject();
            deviceData.put("deviceId", deviceId);
            deviceData.put("rider", riderName);
            deviceData.put("state", deviceState ? "on" : "off");

            socket.emit("save_device", deviceData);
            Log.d(TAG, "Device saved: " + deviceId + " - " + riderName);
        } catch (JSONException e) {
            Log.e(TAG, "Error saving device", e);
        }
    }

    private void initializeDeviceAndRider() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        initializeDeviceId(prefs);
        initializeRiderName(prefs);
    }

    private void initializeDeviceId(SharedPreferences prefs) {
        deviceId = prefs.getString("device_id", null);

        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", deviceId).apply();
            Log.d("DeviceID", "New device ID generated: " + deviceId);
        } else {
            Log.d("DeviceID", "Existing device ID found: " + deviceId);
        }
    }

    private void initializeRiderName(SharedPreferences prefs) {
        riderName = prefs.getString("rider_name", null);

        if (riderName == null || riderName.isEmpty()) {
            showRiderNameDialog(prefs);
        } else {
            Log.d("RiderName", "Using rider name: " + riderName);
        }
    }

    private void showRiderNameDialog(SharedPreferences prefs) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter your name")
                .setCancelable(false);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        builder.setView(input);

        builder.setPositiveButton("OK", (dialog, which) -> {
            String name = input.getText().toString().trim();
            riderName = name.isEmpty() ? "New Rider" : name;
            prefs.edit().putString("rider_name", riderName).apply();
            Log.d("RiderName", "New rider name entered: " + riderName);

            saveDeviceToServer();

            if (dialView != null) {
                dialView.invalidate();
            }
        });

        builder.show();
    }

    private void setupUI() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setBackgroundColor(Color.WHITE);

        setupConnectionButton();
        connectionButton.setVisibility(View.GONE);

        dialView = new GyroDialView(this);
        LinearLayout.LayoutParams dialParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        );
        dialView.setLayoutParams(dialParams);
        dialActive = true;

        mainLayout.addView(dialView);
        setContentView(mainLayout);
    }

    private void setupConnectionButton() {
        connectionButton = new Button(this);
        connectionButton.setText("CONNECT");
        connectionButton.setTextSize(12);
        connectionButton.setBackgroundColor(Color.BLUE);
        connectionButton.setTextColor(Color.WHITE);
        connectionButton.setPadding(15, 8, 15, 8);

        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                300,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        buttonParams.setMargins(20, 5, 0, 5);
        buttonParams.gravity = Gravity.LEFT;
        connectionButton.setLayoutParams(buttonParams);

        connectionButton.setOnClickListener(v -> toggleConnection());
    }

    private void initializeComponents() {
        initializeSensors();
        initializeSocket();
    }

    // ========================================
    // CONNECTION MANAGEMENT
    // ========================================

    private void toggleConnection() {
        if (socketConnected) {
            disconnectFromServer();
        } else {
            connectToServer();
        }
    }

    private void updateConnectionButton() {
        runOnUiThread(() -> {
            if (socketConnected) {
                connectionButton.setText("DISCONNECT");
                connectionButton.setBackgroundColor(Color.rgb(244, 67, 54));
            } else {
                connectionButton.setText("CONNECT");
                connectionButton.setBackgroundColor(Color.BLUE);
            }
        });
    }

    private void connectToServer() {
        if (socket != null && !socket.connected()) {
            Log.d(TAG, "Connecting to server...");
            connectionButton.setEnabled(false);
            connectionButton.setText("CONNECTING...");
            socket.connect();
        }
    }

    private void disconnectFromServer() {
        if (socket != null && socket.connected()) {
            Log.d(TAG, "Disconnecting from server...");
            connectionButton.setEnabled(false);
            connectionButton.setText("DISCONNECTING...");
            socket.disconnect();
        }
    }

    // ========================================
    // SENSOR MANAGEMENT
    // ========================================

    private void initializeSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        listAvailableMotionSensors();

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        if (accelerometer == null) {
            showToast("Accelerometer not available", Toast.LENGTH_LONG);
        }

        if (magnetometer == null) {
            Log.w(TAG, "Magnetometer not available - Yaw will not work");
            showToast("Magnetometer not available - Yaw disabled", Toast.LENGTH_SHORT);
        } else {
            Log.d(TAG, "Magnetometer available - Full orientation tracking enabled");
        }
    }

    private void registerSensorListener() {
        if (accelerometer != null && sensorManager != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            Log.d(TAG, "Accelerometer listener registered");
        }

        if (magnetometer != null && sensorManager != null) {
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI);
            Log.d(TAG, "Magnetometer listener registered");
        }
    }

    private void registerLocationListener() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000, // Update every 1 second (typical GPS rate)
                    0,
                    locationListener
            );
            Log.d(TAG, "GPS listener registered");
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    private void unregisterSensorListener() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    private void unregisterLocationListener() {
        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    private void listAvailableMotionSensors() {
        if (sensorManager == null) return;

        java.util.List<Sensor> sensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
        StringBuilder sensorList = new StringBuilder("Motion-Related Sensors:\n");

        for (Sensor sensor : sensors) {
            if (isMotionSensor(sensor.getType())) {
                sensorList.append("• ").append(getSensorTypeName(sensor.getType())).append("\n");
            }
        }

        availableMotionSensors = sensorList.toString();
    }

    private boolean isMotionSensor(int sensorType) {
        switch (sensorType) {
            case Sensor.TYPE_ACCELEROMETER:
            case Sensor.TYPE_GYROSCOPE:
            case Sensor.TYPE_MAGNETIC_FIELD:
            case Sensor.TYPE_ROTATION_VECTOR:
            case Sensor.TYPE_ORIENTATION:
            case Sensor.TYPE_GRAVITY:
            case Sensor.TYPE_LINEAR_ACCELERATION:
            case Sensor.TYPE_GAME_ROTATION_VECTOR:
            case Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR:
            case Sensor.TYPE_STEP_COUNTER:
            case Sensor.TYPE_STEP_DETECTOR:
                return true;
            default:
                return false;
        }
    }

    private String getSensorTypeName(int sensorType) {
        switch (sensorType) {
            case Sensor.TYPE_ACCELEROMETER: return "Accelerometer";
            case Sensor.TYPE_GYROSCOPE: return "Gyroscope";
            case Sensor.TYPE_MAGNETIC_FIELD: return "Magnetometer";
            case Sensor.TYPE_ROTATION_VECTOR: return "Rotation Vector";
            case Sensor.TYPE_ORIENTATION: return "Orientation (Deprecated)";
            case Sensor.TYPE_GRAVITY: return "Gravity";
            case Sensor.TYPE_LINEAR_ACCELERATION: return "Linear Acceleration";
            case Sensor.TYPE_GAME_ROTATION_VECTOR: return "Game Rotation Vector";
            case Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR: return "Geomagnetic Rotation Vector";
            case Sensor.TYPE_STEP_COUNTER: return "Step Counter";
            case Sensor.TYPE_STEP_DETECTOR: return "Step Detector";
            default: return "Unknown Motion Sensor (Type: " + sensorType + ")";
        }
    }

    // ========================================
    // SENSOR EVENT HANDLING
    // ========================================

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerValues, 0, 3);
            hasAccelerometerData = true;
            processAccelerometerData(event.values);
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerValues, 0, 3);
            hasMagnetometerData = true;
        }

        if (hasAccelerometerData && hasMagnetometerData) {
            calculateOrientation();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        String sensorName = (sensor.getType() == Sensor.TYPE_ACCELEROMETER) ? "Accelerometer" :
                (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) ? "Magnetometer" : "Unknown";
        Log.d(TAG, sensorName + " accuracy changed to: " + accuracy);
    }

    private void processAccelerometerData(float[] values) {
        float x = values[0];
        float y = values[1];
        float z = values[2];

        currentRoll = (float) Math.toDegrees(Math.atan2(-y, Math.sqrt(x * x + z * z)));
        currentPitch = (float) Math.toDegrees(Math.atan2(x, Math.sqrt(y * y + z * z)));

        float totalAccel = (float) Math.sqrt(x * x + y * y + z * z);
        currentGForce = totalAccel / 9.81f;

        if (magnetometer == null) {
            currentYaw = 0;
        }

        // Update smoothed speed (hybrid GPS + accelerometer)
        updateSmoothedSpeed();

        // Detect jumps/airtime
        detectJump();

        updateDialView();
        sendAttitudeData();
    }

    /**
     * Hybrid speed calculation: GPS for truth, accelerometer for smooth updates
     */
    private void updateSmoothedSpeed() {
        long timeSinceGPS = System.currentTimeMillis() - lastGPSUpdateTime;

        if (timeSinceGPS < 2000) {
            // Recent GPS update - blend GPS with current estimate
            smoothedSpeed = lastGPSSpeed * SPEED_SMOOTHING + smoothedSpeed * (1 - SPEED_SMOOTHING);
        } else {
            // No recent GPS - gently decay speed
            smoothedSpeed *= 0.98f;
        }

        // Clamp small values to zero
        if (smoothedSpeed < 0.1f) {
            smoothedSpeed = 0;
        }

        currentSpeed = smoothedSpeed;
    }

    /**
     * Detects jump/airtime based on vertical acceleration
     */
    private void detectJump() {
        // Vertical acceleration indicates airtime
        float verticalAccel = accelerometerValues[2]; // Z-axis

        if (Math.abs(verticalAccel) < 1.5f) { // ~0G = airborne
            if (!wasAirborne) {
                wasAirborne = true;
                airborneStartTime = System.currentTimeMillis();
                Log.d(TAG, "AIRBORNE - Jump detected!");
            }
        } else {
            if (wasAirborne) {
                long flightTime = System.currentTimeMillis() - airborneStartTime;
                Log.d(TAG, "LANDED - Flight time: " + flightTime + "ms");
                wasAirborne = false;
            }
        }
    }

    private void calculateOrientation() {
        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magnetometerValues)) {
            SensorManager.getOrientation(rotationMatrix, orientationValues);

            float azimuth = (float) Math.toDegrees(orientationValues[0]);
            if (azimuth < 0) {
                azimuth += 360;
            }

            currentYaw = azimuth;

            updateDialView();
            sendAttitudeData();
        }
    }

    private void updateDialView() {
        if (dialView != null && dialActive) {
            dialView.setAngles(currentYaw, currentPitch, currentRoll);
        }
    }

    // ========================================
    // SOCKET.IO MANAGEMENT
    // ========================================

    private void initializeSocket() {
        try {
            Log.d(TAG, "Initializing Socket.IO connection to: " + SERVER_URL);

            IO.Options opts = new IO.Options();
            opts.transports = new String[]{"websocket"};
            socket = IO.socket(SERVER_URL, opts);

            setupSocketEventListeners();

        } catch (URISyntaxException e) {
            Log.e(TAG, "URI Syntax error", e);
            showToast("Invalid server URL", Toast.LENGTH_LONG);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing socket", e);
        }
    }

    private void setupSocketEventListeners() {
        socket.on(Socket.EVENT_CONNECT, args -> runOnUiThread(() -> {
            socketConnected = true;
            Log.d(TAG, "✓ Connected to server");
            showToast("✓ Connected to server", Toast.LENGTH_SHORT);

            saveDeviceToServer();
            requestDeviceState();

            if (dialView != null) {
                dialView.setConnectionStatus(true);
            }
            updateConnectionButton();
            connectionButton.setEnabled(true);
        }));

        socket.on(Socket.EVENT_DISCONNECT, args -> runOnUiThread(() -> {
            socketConnected = false;
            Log.d(TAG, "✗ Disconnected from server");
            showToast("✗ Disconnected from server", Toast.LENGTH_SHORT);
            if (dialView != null) {
                dialView.setConnectionStatus(false);
            }
            updateConnectionButton();
            connectionButton.setEnabled(true);
            scheduleReconnection();
        }));

        socket.on(Socket.EVENT_CONNECT_ERROR, args -> runOnUiThread(() -> {
            socketConnected = false;
            String error = args.length > 0 ? args[0].toString() : "Unknown error";
            Log.e(TAG, "✗ Connection error: " + error);
            showToast("Connection failed - retrying...", Toast.LENGTH_SHORT);
            if (dialView != null) {
                dialView.setConnectionStatus(false);
            }
            updateConnectionButton();
            connectionButton.setEnabled(true);
            scheduleReconnection();
        }));

        socket.on("device_state_updated", args -> runOnUiThread(() -> {
            try {
                JSONObject data = (JSONObject) args[0];
                String receivedDeviceId = data.getString("deviceId");
                String newState = data.getString("state");

                if (deviceId.equals(receivedDeviceId)) {
                    deviceState = "on".equals(newState);
                    showToast("Device state changed to: " + newState, Toast.LENGTH_SHORT);
                    Log.d(TAG, "State updated from web: " + newState);

                    if (dialView != null) {
                        dialView.invalidate();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error parsing state update", e);
            }
        }));

        socket.on("device_state_response", args -> runOnUiThread(() -> {
            try {
                JSONObject data = (JSONObject) args[0];
                String state = data.getString("state");
                deviceState = "on".equals(state);
                Log.d(TAG, "Current device state: " + state);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing state response", e);
            }
        }));
    }

    private void sendAttitudeData() {
        if (socket == null || !socketConnected) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSendTime < SEND_INTERVAL) {
            return;
        }
        lastSendTime = currentTime;

        String displayName = (riderName != null && !riderName.isEmpty()) ? riderName : "Unknown Rider";

        try {
            JSONObject attitudeData = createAttitudeDataJson(displayName);
            socket.emit("attitude_update", attitudeData);
            logDataTransmission(currentTime, displayName);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating attitude JSON", e);
        }
    }

    private JSONObject createAttitudeDataJson(String displayName) throws JSONException {
        JSONObject attitudeData = new JSONObject();
        attitudeData.put("pitch", Math.round(currentPitch * 10.0) / 10.0);
        attitudeData.put("yaw", Math.round(currentYaw * 10.0) / 10.0);
        attitudeData.put("roll", Math.round(currentRoll * 10.0) / 10.0);
        attitudeData.put("stream", deviceState ? "on" : "off");
        attitudeData.put("rider", "gyro_app");
        attitudeData.put("riderDisplayName", displayName);
        attitudeData.put("gforce", Math.round(currentGForce * 100.0) / 100.0);
        attitudeData.put("speed", Math.round(currentSpeed * 100.0) / 100.0);
        attitudeData.put("airborne", wasAirborne);

        return attitudeData;
    }

    private void logDataTransmission(long currentTime, String displayName) {
        if (currentTime % 1000 < SEND_INTERVAL) {
            Log.d(TAG, String.format("Sent [%s]: P=%.1f°, Y=%.1f°, R=%.1f°, Speed=%.1f km/h, Stream=%s",
                    displayName, currentPitch, currentYaw, currentRoll, currentSpeed, deviceState ? "on" : "off"));
        }
    }

    // ========================================
    // UTILITY METHODS
    // ========================================

    private void showToast(String message, int duration) {
        Toast.makeText(this, message, duration).show();
    }

    private void scheduleReconnection() {
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            if (!socketConnected && socket != null) {
                Log.d(TAG, "Attempting automatic reconnection...");
                connectToServer();
            }
        }, 3000);
    }

    private void cleanup() {
        if (socket != null) {
            socket.disconnect();
            Log.d(TAG, "Socket disconnected in onDestroy");
        }
    }

    private void requestDeviceState() {
        if (socket == null || !socketConnected) {
            return;
        }

        try {
            JSONObject requestData = new JSONObject();
            requestData.put("deviceId", deviceId);

            socket.emit("get_device_state", requestData);
        } catch (JSONException e) {
            Log.e(TAG, "Error requesting device state", e);
        }
    }

    // ========================================
    // CUSTOM VIEW - GYRO DIAL DISPLAY
    // ========================================

    private class GyroDialView extends View {

        private final Paint backgroundPaint;
        private final Paint backgroundCirclePaint;
        private final Paint progressPaint;
        private final Paint textPaint;
        private final Paint valuePaint;
        private final Paint statusPaint;
        private final Paint titlePaint;
        private final Paint labelPaint;

        private Bitmap logoBitmap;
        private boolean connected = false;

        private final int[] gaugeColors = {
                Color.rgb(33, 150, 243),
                Color.rgb(255, 193, 7),
                Color.rgb(158, 158, 158)
        };

        public GyroDialView(Context context) {
            super(context);

            logoBitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.logo_or);

            backgroundPaint = createBackgroundPaint();
            backgroundCirclePaint = createBackgroundCirclePaint();
            progressPaint = createProgressPaint();
            textPaint = createTextPaint();
            valuePaint = createValuePaint();
            statusPaint = createStatusPaint();
            titlePaint = createTitlePaint();
            labelPaint = createLabelPaint();

            setBackgroundColor(Color.WHITE);
        }

        private Paint createBackgroundPaint() {
            Paint paint = new Paint();
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            return paint;
        }

        private Paint createBackgroundCirclePaint() {
            Paint paint = new Paint();
            paint.setColor(Color.rgb(230, 230, 230));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(20);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createProgressPaint() {
            Paint paint = new Paint();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(20);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createTextPaint() {
            Paint paint = new Paint();
            paint.setColor(Color.rgb(158, 158, 158));
            paint.setTextSize(90);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setAntiAlias(true);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            return paint;
        }

        private Paint createValuePaint() {
            Paint paint = new Paint();
            paint.setColor(Color.rgb(33, 33, 33));
            paint.setTextSize(64);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setAntiAlias(true);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            return paint;
        }

        private Paint createStatusPaint() {
            Paint paint = new Paint();
            paint.setTextSize(20);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createTitlePaint() {
            Paint paint = new Paint();
            paint.setColor(Color.rgb(33, 33, 33));
            paint.setTextSize(70);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setAntiAlias(true);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            return paint;
        }

        private Paint createLabelPaint() {
            Paint paint = new Paint();
            paint.setColor(Color.rgb(158, 158, 158));
            paint.setTextSize(18);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setAntiAlias(true);
            return paint;
        }

        public void setAngles(float yaw, float pitch, float roll) {
            currentYaw = yaw;
            currentPitch = pitch;
            currentRoll = roll;
            invalidate();
        }

        public void setConnectionStatus(boolean connected) {
            this.connected = connected;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int width = getWidth();
            int height = getHeight();

            canvas.drawRect(0, 0, width, height, backgroundPaint);

            int logoHeight = (logoBitmap != null) ? 94 : 0;
            int circleRadius = Math.min(width / 6, height / 4) - 20;
            int circleAreaHeight = circleRadius * 2 + 100;
            int connectionStatusHeight = 60;

            int totalContentHeight = logoHeight + circleAreaHeight + connectionStatusHeight + 60;

            int startY = Math.max(20, (height - totalContentHeight) / 2);
            int currentY = startY;

            currentY = drawCenteredLogo(canvas, width, currentY);
            currentY = drawCenteredProgressCircles(canvas, width, currentY, circleRadius);
            currentY = drawCenteredConnectionStatus(canvas, width, currentY);
        }

        private int drawCenteredLogo(Canvas canvas, int width, int startY) {
            int currentY = startY;

            if (logoBitmap != null) {
                int logoWidth = 360;
                int logoHeight = 94;

                Bitmap scaledLogo = Bitmap.createScaledBitmap(logoBitmap, logoWidth, logoHeight, true);

                int logoX = (width - logoWidth) / 2;
                canvas.drawBitmap(scaledLogo, logoX, currentY, null);

                currentY += logoHeight + 40;
            }

            return currentY;
        }

        private int drawCenteredConnectionStatus(Canvas canvas, int width, int startY) {
            startY += 20;

            int spacing = width / 4;
            int pos1X = spacing / 2;
            int pos2X = spacing + spacing / 2;
            int pos3X = 2 * spacing + spacing / 2;
            int pos4X = 3 * spacing + spacing / 2;

            if (riderName != null && !riderName.isEmpty()) {
                statusPaint.setColor(Color.rgb(158, 158, 158));
                statusPaint.setTextSize(20);
                statusPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("RIDER: " + riderName.toUpperCase(), pos1X, startY, statusPaint);
            }

            String statusText;
            int statusColor;

            if (connected) {
                statusText = "● CONNECTED";
                statusColor = Color.rgb(76, 175, 80);
            } else {
                statusText = "● DISCONNECTED";
                statusColor = Color.rgb(244, 67, 54);
            }

            statusPaint.setColor(statusColor);
            statusPaint.setTextSize(20);
            statusPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(statusText, pos2X, startY, statusPaint);

            String speedText = String.format("%.1f km/h", currentSpeed);
            int speedColor = currentSpeed > 1.0f ? Color.rgb(33, 150, 243) : Color.rgb(158, 158, 158);

            statusPaint.setColor(speedColor);
            statusPaint.setTextSize(20);
            statusPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(speedText, pos3X, startY, statusPaint);

            float gForce = 0;
            if (accelerometerValues != null) {
                float x = accelerometerValues[0];
                float y = accelerometerValues[1];
                float z = accelerometerValues[2];
                float totalAccel = (float) Math.sqrt(x * x + y * y + z * z);
                gForce = totalAccel / 9.81f;
            }

            String gForceText = String.format("%.2f G", gForce);
            int gForceColor = gForce > 1.5f ? Color.rgb(255, 152, 0) : Color.rgb(158, 158, 158);

            statusPaint.setColor(gForceColor);
            statusPaint.setTextSize(20);
            statusPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(gForceText, pos4X, startY, statusPaint);

            return startY + 30;
        }

        private int drawCenteredProgressCircles(Canvas canvas, int width, int startY, int circleRadius) {
            int centerY = startY + circleRadius + 60;

            int leftX = width / 4;
            int centerX = width / 2;
            int rightX = 3 * width / 4;

            drawPitchCircle(canvas, leftX, centerY, circleRadius);
            drawRollCircle(canvas, centerX, centerY, circleRadius);
            drawYawCircle(canvas, rightX, centerY, circleRadius);

            return centerY + circleRadius + 80;
        }

        private void drawPitchCircle(Canvas canvas, int centerX, int centerY, int radius) {
            canvas.drawCircle(centerX, centerY, radius, backgroundCirclePaint);

            float normalizedPitch = Math.abs(currentPitch) / 90f;
            normalizedPitch = Math.min(1.0f, normalizedPitch);

            if (normalizedPitch > 0) {
                progressPaint.setColor(gaugeColors[0]);

                float sweepAngle = normalizedPitch * 90f;

                if (currentPitch >= 0) {
                    canvas.drawArc(
                            centerX - radius, centerY - radius,
                            centerX + radius, centerY + radius,
                            -90f - sweepAngle, sweepAngle, false, progressPaint
                    );

                    canvas.drawArc(
                            centerX - radius, centerY - radius,
                            centerX + radius, centerY + radius,
                            -90f, sweepAngle, false, progressPaint
                    );

                } else {
                    canvas.drawArc(
                            centerX - radius, centerY - radius,
                            centerX + radius, centerY + radius,
                            90f, sweepAngle, false, progressPaint
                    );

                    canvas.drawArc(
                            centerX - radius, centerY - radius,
                            centerX + radius, centerY + radius,
                            90f - sweepAngle, sweepAngle, false, progressPaint
                    );
                }
            }

            valuePaint.setTextSize(48);
            canvas.drawText(String.format("%.0f", currentPitch), centerX, centerY + 8, valuePaint);

            textPaint.setTextSize(16);
            canvas.drawText("degrees", centerX, centerY + 30, textPaint);
            textPaint.setTextSize(14);
            canvas.drawText("Range: ±90°", centerX, centerY + 50, textPaint);

            titlePaint.setTextSize(24);
            canvas.drawText("TILT (TURNUP)", centerX, centerY - radius - 50, titlePaint);
        }

        private void drawRollCircle(Canvas canvas, int centerX, int centerY, int radius) {
            canvas.drawCircle(centerX, centerY, radius, backgroundCirclePaint);

            float normalizedRoll = Math.abs(currentRoll) / 90f;
            normalizedRoll = Math.min(1.0f, normalizedRoll);

            if (normalizedRoll > 0) {
                progressPaint.setColor(gaugeColors[1]);

                float sweepAngle = normalizedRoll * 90f;

                if (currentRoll >= 0) {
                    canvas.drawArc(
                            centerX - radius, centerY - radius,
                            centerX + radius, centerY + radius,
                            0f - sweepAngle, sweepAngle, false, progressPaint
                    );

                    canvas.drawArc(
                            centerX - radius, centerY - radius,
                            centerX + radius, centerY + radius,
                            0f, sweepAngle, false, progressPaint
                    );

                } else {
                    canvas.drawArc(
                            centerX - radius, centerY - radius,
                            centerX + radius, centerY + radius,
                            180f, sweepAngle, false, progressPaint
                    );

                    canvas.drawArc(
                            centerX - radius, centerY - radius,
                            centerX + radius, centerY + radius,
                            180f - sweepAngle, sweepAngle, false, progressPaint
                    );
                }
            }

            valuePaint.setTextSize(48);
            canvas.drawText(String.format("%.0f", currentRoll), centerX, centerY + 8, valuePaint);

            textPaint.setTextSize(16);
            canvas.drawText("degrees", centerX, centerY + 30, textPaint);
            textPaint.setTextSize(14);
            canvas.drawText("Range: ±90°", centerX, centerY + 50, textPaint);

            titlePaint.setTextSize(24);
            canvas.drawText("LEAN (TABLE TOP)", centerX, centerY - radius - 50, titlePaint);
        }

        private void drawYawCircle(Canvas canvas, int centerX, int centerY, int radius) {
            if (magnetometer == null) {
                canvas.drawCircle(centerX, centerY, radius, backgroundCirclePaint);

                valuePaint.setTextSize(36);
                valuePaint.setColor(Color.rgb(158, 158, 158));
                canvas.drawText("N/A", centerX, centerY + 8, valuePaint);

                textPaint.setTextSize(14);
                canvas.drawText("Magnetometer", centerX, centerY + 30, textPaint);
                canvas.drawText("Not Available", centerX, centerY + 48, textPaint);

                valuePaint.setColor(Color.rgb(33, 33, 33));
            } else {
                int yawColor = Color.rgb(76, 175, 80);

                drawYawProgressCircle(canvas, centerX, centerY, radius, currentYaw, yawColor);

                valuePaint.setTextSize(48);
                canvas.drawText(String.format("%.0f", currentYaw), centerX, centerY + 8, valuePaint);

                textPaint.setTextSize(16);
                canvas.drawText("degrees", centerX, centerY + 30, textPaint);
                textPaint.setTextSize(14);
                canvas.drawText("Range: 0-360°", centerX, centerY + 50, textPaint);
            }

            titlePaint.setTextSize(24);
            canvas.drawText("TURN (TURNDOWN)", centerX, centerY - radius - 50, titlePaint);
        }

        private void drawYawProgressCircle(Canvas canvas, int centerX, int centerY, int radius, float yaw, int color) {
            canvas.drawCircle(centerX, centerY, radius, backgroundCirclePaint);

            progressPaint.setColor(color);

            float needleAngle = yaw - 90f;

            canvas.drawArc(
                    centerX - radius, centerY - radius,
                    centerX + radius, centerY + radius,
                    needleAngle - 5, 10, false, progressPaint
            );

            Paint centerDotPaint = new Paint();
            centerDotPaint.setColor(color);
            centerDotPaint.setStyle(Paint.Style.FILL);
            centerDotPaint.setAntiAlias(true);
            canvas.drawCircle(centerX, centerY, 8, centerDotPaint);

            Paint cardinalPaint = new Paint();
            cardinalPaint.setColor(Color.rgb(158, 158, 158));
            cardinalPaint.setTextSize(16);
            cardinalPaint.setTextAlign(Paint.Align.CENTER);
            cardinalPaint.setAntiAlias(true);

            canvas.drawText("N", centerX, centerY - radius - 10, cardinalPaint);
            cardinalPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("E", centerX + radius + 10, centerY + 5, cardinalPaint);
            cardinalPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("S", centerX, centerY + radius + 25, cardinalPaint);
            cardinalPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("W", centerX - radius - 10, centerY + 5, cardinalPaint);
        }
    }
}