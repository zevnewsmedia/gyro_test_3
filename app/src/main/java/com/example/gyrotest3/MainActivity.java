package com.example.gyrotest3;

import android.content.pm.ActivityInfo;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URISyntaxException;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.content.SharedPreferences;
import java.util.UUID;
import androidx.appcompat.app.AlertDialog;
import android.widget.EditText;
import android.text.InputType;

/**
 * MainActivity - Android Gyroscope Data Transmission App
 *
 * This application reads accelerometer data, displays it in custom dials,
 * and transmits the attitude data to a remote server via Socket.IO.
 *
 * Features:
 * - Real-time accelerometer data visualization
 * - Socket.IO connection for data transmission
 * - User rider name management
 * - Custom dial view for pitch, roll, and yaw display
 * - Landscape orientation lock
 */
public class MainActivity extends AppCompatActivity implements SensorEventListener {

    // ========================================
    // CONSTANTS
    // ========================================

    private static final String TAG = "GyroSocket";
    private static final String SERVER_URL = "http://3.91.244.249:5000/";
    private static final long SEND_INTERVAL = 100; // Send data every 100ms (10Hz)

    // ========================================
    // DEVICE & USER MANAGEMENT
    // ========================================

    private String deviceId;
    private String riderName; // This will be the primary rider name used throughout the app

    // ========================================
    // SENSOR COMPONENTS
    // ========================================

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private GyroDialView dialView;
    private float currentYaw = 0;    // Always 0 for accelerometer (requires magnetometer)
    private float currentPitch = 0;  // Forward/backward tilt
    private float currentRoll = 0;   // Left/right tilt
    private boolean dialActive = false;
    private String availableMotionSensors = "";

    // ========================================
    // NETWORK & SOCKET.IO
    // ========================================

    private Socket socket;
    private boolean socketConnected = false;
    private long lastSendTime = 0;

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
        reconnectSocketIfNeeded();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterSensorListener();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanup();
    }

    // ========================================
    // INITIALIZATION METHODS
    // ========================================

    /**
     * Initialize device ID and rider name from SharedPreferences or prompt user
     */
    private void initializeDeviceAndRider() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);

        // Initialize device ID
        initializeDeviceId(prefs);

        // Initialize rider name
        initializeRiderName(prefs);
    }

    /**
     * Generate or retrieve stored device ID
     */
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

    /**
     * Get rider name from preferences or show input dialog
     */
    private void initializeRiderName(SharedPreferences prefs) {
        riderName = prefs.getString("rider_name", null);

        if (riderName == null || riderName.isEmpty()) {
            showRiderNameDialog(prefs);
        } else {
            Log.d("RiderName", "Using rider name: " + riderName);
        }
    }

    /**
     * Show dialog for user to enter their name
     */
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

            // Refresh the dial view to show the new name
            if (dialView != null) {
                dialView.invalidate();
            }
        });

        builder.show();
    }

    /**
     * Setup UI components and orientation
     */
    private void setupUI() {
        // Hide action bar
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // Lock to landscape orientation
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // Create and set custom dial view
        dialView = new GyroDialView(this);
        setContentView(dialView);
        dialActive = true;
    }

    /**
     * Initialize all components (sensors, socket)
     */
    private void initializeComponents() {
        initializeSensors();
        initializeSocket();
    }

    // ========================================
    // SENSOR MANAGEMENT
    // ========================================

    /**
     * Initialize sensor manager and accelerometer
     */
    private void initializeSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        listAvailableMotionSensors();

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer == null) {
            showToast("Accelerometer not available", Toast.LENGTH_LONG);
        }
    }

    /**
     * Register sensor listener for accelerometer updates
     */
    private void registerSensorListener() {
        if (accelerometer != null && sensorManager != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
    }

    /**
     * Unregister sensor listener to save battery
     */
    private void unregisterSensorListener() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    /**
     * Get list of available motion sensors on this device
     */
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

    /**
     * Check if sensor type is motion-related
     */
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

    /**
     * Get human-readable sensor type name
     */
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
            processAccelerometerData(event.values);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used in this implementation
    }

    /**
     * Process accelerometer data and calculate tilt angles
     */
    private void processAccelerometerData(float[] values) {
        float x = values[0]; // Left/Right tilt
        float y = values[1]; // Forward/Backward tilt
        float z = values[2]; // Up/Down (gravity when stationary)

        // Calculate tilt angles from gravity vector (adjusted for landscape mode)
        currentRoll = (float) Math.toDegrees(Math.atan2(-y, Math.sqrt(x * x + z * z)));
        currentPitch = (float) Math.toDegrees(Math.atan2(x, Math.sqrt(y * y + z * z)));
        currentYaw = 0; // Cannot be determined from accelerometer alone

        updateDialView();
        sendAttitudeData();
    }

    /**
     * Update the dial view with new angle values
     */
    private void updateDialView() {
        if (dialView != null && dialActive) {
            dialView.setAngles(currentYaw, currentPitch, currentRoll);
        }
    }

    // ========================================
    // SOCKET.IO MANAGEMENT
    // ========================================

    /**
     * Initialize Socket.IO connection with event listeners
     */
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

    /**
     * Setup all Socket.IO event listeners
     */
    private void setupSocketEventListeners() {
        socket.on(Socket.EVENT_CONNECT, args -> runOnUiThread(() -> {
            socketConnected = true;
            Log.d(TAG, "✓ Connected to server");
            showToast("✓ Connected to server", Toast.LENGTH_SHORT);
            if (dialView != null) {
                dialView.setConnectionStatus(true);
            }
        }));

        socket.on(Socket.EVENT_DISCONNECT, args -> runOnUiThread(() -> {
            socketConnected = false;
            Log.d(TAG, "✗ Disconnected from server");
            showToast("✗ Disconnected from server", Toast.LENGTH_SHORT);
            if (dialView != null) {
                dialView.setConnectionStatus(false);
            }
        }));

        socket.on(Socket.EVENT_CONNECT_ERROR, args -> runOnUiThread(() -> {
            socketConnected = false;
            String error = args.length > 0 ? args[0].toString() : "Unknown error";
            Log.e(TAG, "✗ Connection error: " + error);
            showToast("Connection failed", Toast.LENGTH_LONG);
            if (dialView != null) {
                dialView.setConnectionStatus(false);
            }
        }));

        socket.on("attitude_data", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                String rider = data.optString("riderDisplayName", "");
                Log.d(TAG, "Server broadcast from: " + rider);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing server response", e);
            }
        });
    }

    /**
     * Connect to server if not already connected
     */
    private void connectToServer() {
        if (socket != null && !socket.connected()) {
            Log.d(TAG, "Connecting to server...");
            socket.connect();
        }
    }

    /**
     * Reconnect socket if needed (called in onResume)
     */
    private void reconnectSocketIfNeeded() {
        if (socket != null && !socket.connected()) {
            connectToServer();
        }
    }

    /**
     * Send attitude data to server with throttling
     */
    private void sendAttitudeData() {
        if (socket == null || !socketConnected) {
            return;
        }

        // Throttle sending to avoid overwhelming the server
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSendTime < SEND_INTERVAL) {
            return;
        }
        lastSendTime = currentTime;

        // Ensure we have a rider name (use default if somehow empty)
        String displayName = (riderName != null && !riderName.isEmpty()) ? riderName : "Unknown Rider";

        try {
            JSONObject attitudeData = createAttitudeDataJson(displayName);
            socket.emit("attitude_update", attitudeData);
            logDataTransmission(currentTime, displayName);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating attitude JSON", e);
        }
    }

    /**
     * Create JSON object with attitude data
     */
    private JSONObject createAttitudeDataJson(String displayName) throws JSONException {
        JSONObject attitudeData = new JSONObject();
        attitudeData.put("pitch", Math.round(currentPitch * 10.0) / 10.0);
        attitudeData.put("yaw", Math.round(currentYaw * 10.0) / 10.0);
        attitudeData.put("roll", Math.round(currentRoll * 10.0) / 10.0);
        attitudeData.put("rider", "gyro_app");
        attitudeData.put("riderDisplayName", displayName);
        return attitudeData;
    }

    /**
     * Log data transmission (throttled to avoid spam)
     */
    private void logDataTransmission(long currentTime, String displayName) {
        if (currentTime % 1000 < SEND_INTERVAL) {
            Log.d(TAG, String.format("Sent [%s]: P=%.1f°, Y=%.1f°, R=%.1f°",
                    displayName, currentPitch, currentYaw, currentRoll));
        }
    }

    // ========================================
    // UTILITY METHODS
    // ========================================

    /**
     * Show toast message on UI thread
     */
    private void showToast(String message, int duration) {
        Toast.makeText(this, message, duration).show();
    }

    /**
     * Clean up resources on app destruction
     */
    private void cleanup() {
        if (socket != null) {
            socket.disconnect();
            Log.d(TAG, "Socket disconnected in onDestroy");
        }
    }

    // ========================================
    // CUSTOM VIEW - GYRO DIAL DISPLAY
    // ========================================

    /**
     * Custom view class for displaying gyroscope data with three circular dials
     * Shows Yaw (not available), Pitch, and Roll with visual indicators
     */
    private class GyroDialView extends View {

        // Paint objects for different UI elements
        private final Paint dialPaint;
        private final Paint needlePaint;
        private final Paint textPaint;
        private final Paint centerPaint;
        private final Paint backgroundPaint;
        private final Paint statusPaint;

        private boolean connected = false;

        public GyroDialView(Context context) {
            super(context);

            // Initialize all paint objects
            backgroundPaint = createBackgroundPaint();
            dialPaint = createDialPaint();
            needlePaint = createNeedlePaint();
            textPaint = createTextPaint();
            centerPaint = createCenterPaint();
            statusPaint = createStatusPaint();

            setBackgroundColor(Color.WHITE);
        }

        // Paint factory methods for cleaner initialization
        private Paint createBackgroundPaint() {
            Paint paint = new Paint();
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            return paint;
        }

        private Paint createDialPaint() {
            Paint paint = new Paint();
            paint.setColor(Color.GRAY);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(8);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createNeedlePaint() {
            Paint paint = new Paint();
            paint.setColor(Color.RED);
            paint.setStrokeWidth(6);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createTextPaint() {
            Paint paint = new Paint();
            paint.setColor(Color.BLACK);
            paint.setTextSize(48);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createCenterPaint() {
            Paint paint = new Paint();
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.FILL);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createStatusPaint() {
            Paint paint = new Paint();
            paint.setTextSize(24);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setAntiAlias(true);
            return paint;
        }

        /**
         * Update angles and refresh display
         */
        public void setAngles(float yaw, float pitch, float roll) {
            currentYaw = yaw;
            currentPitch = pitch;
            currentRoll = roll;
            invalidate();
        }

        /**
         * Update connection status and refresh display
         */
        public void setConnectionStatus(boolean connected) {
            this.connected = connected;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int width = getWidth();
            int height = getHeight();
            int centerX = width / 2;
            int centerY = height / 2;
            int radius = (int) (Math.min(width, height) / 4 * 0.85);

            // Clear background
            canvas.drawRect(0, 0, width, height, backgroundPaint);

            // Draw UI elements from top to bottom
            drawRiderInformation(canvas, width);
            drawConnectionStatus(canvas, 20, 170);
            drawThreeDials(canvas, width, centerY, radius);
            drawPitchRollValues(canvas, centerX, height - 80);
        }

        /**
         * Draw rider information at the top
         */
        private void drawRiderInformation(Canvas canvas, int width) {
            // Display the user's rider name
            if (riderName != null && !riderName.isEmpty()) {
                Paint riderPaint = createRiderNamePaint(Color.BLUE, 40);
                canvas.drawText("Rider on Course: " + riderName, width / 2, 50, riderPaint);
            }
        }

        /**
         * Create paint for rider name display
         */
        private Paint createRiderNamePaint(int color, int textSize) {
            Paint paint = new Paint();
            paint.setColor(color);
            paint.setTextSize(textSize);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setAntiAlias(true);
            return paint;
        }

        /**
         * Draw connection status indicator
         */
        private void drawConnectionStatus(Canvas canvas, int x, int y) {
            if (connected) {
                statusPaint.setColor(Color.GREEN);
                canvas.drawText("Connected to Server", x, y, statusPaint);
                canvas.drawText("Sending gyro data...", x, y + 30, statusPaint);
            } else {
                statusPaint.setColor(Color.RED);
                canvas.drawText("Disconnected", x, y, statusPaint);
                canvas.drawText("Retrying connection...", x, y + 30, statusPaint);
            }
        }

        /**
         * Draw three dials arranged for landscape layout
         */
        private void drawThreeDials(Canvas canvas, int width, int centerY, int radius) {
            int leftDialX = width / 4;
            int rightDialX = 3 * width / 4;
            int centerX = width / 2;

            drawYawDial(canvas, leftDialX, centerY, radius);
            drawPitchDial(canvas, rightDialX, centerY, radius);
            drawRollDial(canvas, centerX, centerY, radius);
        }

        /**
         * Draw yaw dial (shows N/A since accelerometer can't measure yaw)
         */
        private void drawYawDial(Canvas canvas, int centerX, int centerY, int radius) {
            canvas.drawCircle(centerX, centerY, radius, dialPaint);

            // Draw X pattern to indicate unavailable
            Paint unavailablePaint = new Paint();
            unavailablePaint.setColor(Color.GRAY);
            unavailablePaint.setStrokeWidth(6);
            unavailablePaint.setAntiAlias(true);

            int halfRadius = radius / 2;
            canvas.drawLine(centerX - halfRadius, centerY - halfRadius,
                    centerX + halfRadius, centerY + halfRadius, unavailablePaint);
            canvas.drawLine(centerX + halfRadius, centerY - halfRadius,
                    centerX - halfRadius, centerY + halfRadius, unavailablePaint);

            drawDialLabel(canvas, centerX, centerY, radius, "YAW", "(N/A)");
        }

        /**
         * Draw pitch dial with needle indicator
         */
        private void drawPitchDial(Canvas canvas, int centerX, int centerY, int radius) {
            canvas.drawCircle(centerX, centerY, radius, dialPaint);
            drawPitchMarkers(canvas, centerX, centerY, radius);
            drawNeedle(canvas, centerX, centerY, radius, currentPitch);
            drawDialLabel(canvas, centerX, centerY, radius, "PITCH", null);
        }

        /**
         * Draw roll dial with needle indicator
         */
        private void drawRollDial(Canvas canvas, int centerX, int centerY, int radius) {
            canvas.drawCircle(centerX, centerY, radius, dialPaint);
            drawRollMarkers(canvas, centerX, centerY, radius);
            drawNeedle(canvas, centerX, centerY, radius, currentRoll);
            drawDialLabel(canvas, centerX, centerY, radius, "ROLL", null);
        }

        /**
         * Draw dial label and optional subtitle
         */
        private void drawDialLabel(Canvas canvas, int centerX, int centerY, int radius,
                                   String label, String subtitle) {
            textPaint.setTextSize(24);
            canvas.drawText(label, centerX, centerY - radius - 30, textPaint);

            if (subtitle != null) {
                textPaint.setTextSize(18);
                canvas.drawText(subtitle, centerX, centerY - radius - 8, textPaint);
            }
        }

        /**
         * Draw needle indicator on dial
         */
        private void drawNeedle(Canvas canvas, int centerX, int centerY, int radius, float angle) {
            float angleRad = (float) Math.toRadians(angle);
            float needleLength = radius - 20;
            float needleX = centerX + needleLength * (float) Math.sin(angleRad);
            float needleY = centerY - needleLength * (float) Math.cos(angleRad);

            canvas.drawLine(centerX, centerY, needleX, needleY, needlePaint);
            canvas.drawCircle(centerX, centerY, 10, centerPaint);
        }

        /**
         * Draw pitch markers with directional labels (Up, Right, Down, Left)
         */
        private void drawPitchMarkers(Canvas canvas, int centerX, int centerY, int radius) {
            Paint markerPaint = createMarkerPaint();
            Paint labelPaint = createLabelPaint();

            String[] labels = {"U", "R", "D", "L"};

            for (int i = 0; i < 4; i++) {
                float angle = (float) Math.toRadians(i * 90);
                drawMarkerLine(canvas, centerX, centerY, radius, angle, markerPaint);
                drawMarkerLabel(canvas, centerX, centerY, radius, angle, labels[i], labelPaint);
            }
        }

        /**
         * Draw roll markers with degree labels (0, 90, 180, 270)
         */
        private void drawRollMarkers(Canvas canvas, int centerX, int centerY, int radius) {
            Paint markerPaint = createMarkerPaint();
            Paint labelPaint = createLabelPaint();

            String[] labels = {"0", "90", "180", "270"};

            for (int i = 0; i < 4; i++) {
                float angle = (float) Math.toRadians(i * 90);
                drawMarkerLine(canvas, centerX, centerY, radius, angle, markerPaint);
                drawMarkerLabel(canvas, centerX, centerY, radius, angle, labels[i], labelPaint);
            }
        }

        /**
         * Create paint for dial markers
         */
        private Paint createMarkerPaint() {
            Paint paint = new Paint();
            paint.setColor(Color.BLACK);
            paint.setStrokeWidth(4);
            paint.setAntiAlias(true);
            return paint;
        }

        /**
         * Create paint for marker labels
         */
        private Paint createLabelPaint() {
            Paint paint = new Paint();
            paint.setColor(Color.BLACK);
            paint.setTextSize(18);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setAntiAlias(true);
            return paint;
        }

        /**
         * Draw marker line on dial circumference
         */
        private void drawMarkerLine(Canvas canvas, int centerX, int centerY, int radius,
                                    float angle, Paint markerPaint) {
            float innerRadius = radius - 20;

            float x1 = centerX + innerRadius * (float) Math.sin(angle);
            float y1 = centerY - innerRadius * (float) Math.cos(angle);
            float x2 = centerX + radius * (float) Math.sin(angle);
            float y2 = centerY - radius * (float) Math.cos(angle);

            canvas.drawLine(x1, y1, x2, y2, markerPaint);
        }

        /**
         * Draw label outside dial circumference
         */
        private void drawMarkerLabel(Canvas canvas, int centerX, int centerY, int radius,
                                     float angle, String label, Paint labelPaint) {
            float labelRadius = radius + 30;
            float labelX = centerX + labelRadius * (float) Math.sin(angle);
            float labelY = centerY - labelRadius * (float) Math.cos(angle) + 8;

            canvas.drawText(label, labelX, labelY, labelPaint);
        }

        /**
         * Draw pitch and roll values at the bottom of screen
         */
        private void drawPitchRollValues(Canvas canvas, int centerX, int startY) {
            Paint valuePaint = createValuePaint();

            // Draw pitch and roll as rounded integer values
            String pitchText = "Pitch: " + Math.round(currentPitch) + "°";
            String rollText = "Roll: " + Math.round(currentRoll) + "°";

            canvas.drawText(pitchText, centerX - 200, startY, valuePaint);
            canvas.drawText(rollText, centerX + 200, startY, valuePaint);
        }

        /**
         * Create paint for value display
         */
        private Paint createValuePaint() {
            Paint paint = new Paint();
            paint.setColor(Color.BLACK);
            paint.setTextSize(32);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setAntiAlias(true);
            return paint;
        }
    }
}