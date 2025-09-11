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
        // Optional: Clear previous rider name for testing
        //prefs.edit().remove("rider_name").apply();

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
    /**
     * Clean progress circle GyroDialView class
     * Modified to show progress bars starting from center top and extending left and right
     */
    private class GyroDialView extends View {

        // Paint objects for different UI elements
        private final Paint backgroundPaint;
        private final Paint backgroundCirclePaint;
        private final Paint progressPaint;
        private final Paint textPaint;
        private final Paint valuePaint;
        private final Paint statusPaint;
        private final Paint titlePaint;
        private final Paint labelPaint;

        private boolean connected = false;

        // Colors for each gauge
        private final int[] gaugeColors = {
                Color.rgb(33, 150, 243),   // Blue for Pitch
                Color.rgb(255, 193, 7),    // Amber/Yellow for Roll
                Color.rgb(158, 158, 158)   // Gray for YAW (N/A)
        };

        public GyroDialView(Context context) {
            super(context);

            // Initialize all paint objects
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

        // Paint factory methods
        private Paint createBackgroundPaint() {
            Paint paint = new Paint();
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            return paint;
        }

        private Paint createBackgroundCirclePaint() {
            Paint paint = new Paint();
            paint.setColor(Color.rgb(230, 230, 230)); // Light gray background circle
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
            paint.setTextSize(24);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setAntiAlias(true);
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
            paint.setTextSize(28);
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

            // Clear background
            canvas.drawRect(0, 0, width, height, backgroundPaint);

            // Draw UI elements
            drawHeader(canvas, width);
            drawConnectionStatus(canvas, 30, 80);
            drawCleanProgressCircles(canvas, width, height);
            drawLegend(canvas, width, height);
        }

        /**
         * Draw header with rider name
         */
        private void drawHeader(Canvas canvas, int width) {
            titlePaint.setTextSize(32);
            titlePaint.setColor(Color.rgb(33, 33, 33));
            canvas.drawText("Gyroscope Data", width / 2, 50, titlePaint);

            if (riderName != null && !riderName.isEmpty()) {
                textPaint.setTextSize(40);
                textPaint.setColor(Color.rgb(158, 158, 158));
                canvas.drawText("Rider: " + riderName, width / 2, 80, textPaint);
            }
        }

        /**
         * Draw connection status
         */
        private void drawConnectionStatus(Canvas canvas, int x, int y) {
            if (connected) {
                statusPaint.setColor(Color.rgb(76, 175, 80));
                canvas.drawText("● Connected", x, y, statusPaint);
            } else {
                statusPaint.setColor(Color.rgb(244, 67, 54));
                canvas.drawText("● Disconnected", x, y, statusPaint);
            }
        }

        /**
         * Draw three clean progress circles
         */
        private void drawCleanProgressCircles(Canvas canvas, int width, int height) {
            int circleRadius = Math.min(width / 6, height / 4) - 20;
            int centerY = height / 2;

            // Calculate positions for three circles
            int leftX = width / 4;
            int centerX = width / 2;
            int rightX = 3 * width / 4;

            // Draw circles
            drawPitchCircle(canvas, leftX, centerY, circleRadius);
            drawRollCircle(canvas, centerX, centerY, circleRadius);
            drawYawCircle(canvas, rightX, centerY, circleRadius);
        }

        /**
         * Draw pitch progress circle - moves left for negative, right for positive
         */
        private void drawPitchCircle(Canvas canvas, int centerX, int centerY, int radius) {
            // Normalize pitch to 0-100% (assuming ±90° range)
            float normalizedPitch = Math.abs(currentPitch) / 90f; // 0.0 to 1.0
            normalizedPitch = Math.min(1.0f, normalizedPitch);

            // Determine direction based on sign
            boolean isPositive = currentPitch >= 0;

            drawDirectionalProgressCircle(canvas, centerX, centerY, radius, normalizedPitch, gaugeColors[0], isPositive);

            // Draw value and labels (show actual value with sign)
            valuePaint.setTextSize(48);
            canvas.drawText(String.format("%.0f", currentPitch), centerX, centerY + 8, valuePaint);

            textPaint.setTextSize(16);
            canvas.drawText("degrees", centerX, centerY + 30, textPaint);
            textPaint.setTextSize(14);
            canvas.drawText("Range: ±90°", centerX, centerY + 50, textPaint);

            // Title above circle
            titlePaint.setTextSize(18);
            //canvas.drawText("PITCH", centerX, centerY - radius - 20, titlePaint);
            canvas.drawText("TILT (TURNUP)", centerX, centerY - radius - 20, titlePaint);
        }

        /**
         * Draw roll progress circle - moves left for negative, right for positive
         */
        private void drawRollCircle(Canvas canvas, int centerX, int centerY, int radius) {
            // Normalize roll to 0-100% (assuming ±90° range)
            float normalizedRoll = Math.abs(currentRoll) / 90f; // 0.0 to 1.0
            normalizedRoll = Math.min(1.0f, normalizedRoll);

            // Determine direction based on sign
            boolean isPositive = currentRoll >= 0;

            drawDirectionalProgressCircle(canvas, centerX, centerY, radius, normalizedRoll, gaugeColors[1], isPositive);

            // Draw value and labels (show actual value with sign)
            valuePaint.setTextSize(48);
            canvas.drawText(String.format("%.0f", currentRoll), centerX, centerY + 8, valuePaint);

            textPaint.setTextSize(16);
            canvas.drawText("degrees", centerX, centerY + 30, textPaint);
            textPaint.setTextSize(14);
            canvas.drawText("Range: ±90°", centerX, centerY + 50, textPaint);

            // Title above circle
            titlePaint.setTextSize(18);
            //canvas.drawText("ROLL", centerX, centerY - radius - 20, titlePaint);
           // canvas.drawText("ROLL", centerX, centerY - radius - 20, titlePaint);
            canvas.drawText("LEAN (TABLE TOP)", centerX, centerY - radius - 20, titlePaint);

        }

        /**
         * Draw yaw circle (shows N/A)
         */
        private void drawYawCircle(Canvas canvas, int centerX, int centerY, int radius) {
            // Draw empty circle for yaw (not available)
            drawDirectionalProgressCircle(canvas, centerX, centerY, radius, 0f, gaugeColors[2], true);

            // Draw N/A text
            valuePaint.setTextSize(36);
            valuePaint.setColor(Color.rgb(158, 158, 158));
            canvas.drawText("N/A", centerX, centerY + 8, valuePaint);

            textPaint.setTextSize(14);
            canvas.drawText("Magnetometer", centerX, centerY + 30, textPaint);
            canvas.drawText("Required", centerX, centerY + 48, textPaint);

            valuePaint.setColor(Color.rgb(33, 33, 33)); // Reset color

            // Title above circle
            titlePaint.setTextSize(18);
            //canvas.drawText("YAW", centerX, centerY - radius - 20, titlePaint);
            canvas.drawText("TURN (TURNDOWN)", centerX, centerY - radius - 20, titlePaint);
        }

        /**
         * Draw a progress circle that moves in one direction based on the sign
         * Positive values go clockwise (right), negative values go counter-clockwise (left)
         */
        private void drawDirectionalProgressCircle(Canvas canvas, int centerX, int centerY, int radius,
                                                   float progress, int color, boolean isPositive) {

            // Draw background circle
            canvas.drawCircle(centerX, centerY, radius, backgroundCirclePaint);

            // Draw progress arc in one direction based on sign
            if (progress > 0) {
                progressPaint.setColor(color);

                // Calculate the sweep angle (max 90° in one direction)
                float sweepAngle = progress * 90f; // Max 90° in one direction

                if (isPositive) {
                    // Positive: draw arc from top going clockwise (to the right)
                    canvas.drawArc(
                            centerX - radius, centerY - radius,
                            centerX + radius, centerY + radius,
                            -90, sweepAngle, false, progressPaint
                    );
                } else {
                    // Negative: draw arc from top going counter-clockwise (to the left)
                    canvas.drawArc(
                            centerX - radius, centerY - radius,
                            centerX + radius, centerY + radius,
                            -90 - sweepAngle, sweepAngle, false, progressPaint
                    );
                }
            }
        }

        /**
         * Draw legend at bottom
         */
        private void drawLegend(Canvas canvas, int width, int height) {
            int legendY = height - 60;
            int legendSpacing = width / 3;

            // Draw colored dots and labels
            Paint dotPaint = new Paint();
            dotPaint.setStyle(Paint.Style.FILL);
            dotPaint.setAntiAlias(true);

            // Pitch legend
            dotPaint.setColor(gaugeColors[0]);
            //canvas.drawCircle(width / 4 - 60, legendY - 5, 8, dotPaint);
            labelPaint.setColor(Color.rgb(33, 33, 33));
           // canvas.drawText("pitch", width / 4 - 40, legendY, labelPaint);

            // Roll legend
            dotPaint.setColor(gaugeColors[1]);
          //  canvas.drawCircle(width / 2 - 30, legendY - 5, 8, dotPaint);
          //  canvas.drawText("roll", width / 2 - 10, legendY, labelPaint);

            // Yaw legend
            dotPaint.setColor(gaugeColors[2]);
           // canvas.drawCircle(3 * width / 4 - 30, legendY - 5, 8, dotPaint);
          //  canvas.drawText("yaw", 3 * width / 4 - 10, legendY, labelPaint);
        }
    }}