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
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import android.widget.Button;
import android.widget.LinearLayout;
import android.view.Gravity;

import androidx.appcompat.app.AppCompatActivity;

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

// Add these imports at the top of your MainActivity class
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/**
 * MainActivity - Android Gyroscope Data Transmission App
 *
 * This application reads accelerometer and magnetometer data, displays it in custom dials,
 * and transmits the attitude data to a remote server via Socket.IO.
 *
 * Features:
 * - Real-time accelerometer and magnetometer data visualization
 * - Manual Socket.IO connection with connect/disconnect button
 * - User rider name management
 * - Custom dial view for pitch, roll, and yaw display
 * - Landscape orientation lock
 * - Graceful degradation when magnetometer is not available
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
    private Sensor magnetometer;
    private GyroDialView dialView;
    private float currentYaw = 0;    // Compass heading (requires magnetometer)
    private float currentPitch = 0;  // Forward/backward tilt
    private float currentRoll = 0;   // Left/right tilt
    private boolean dialActive = false;
    private String availableMotionSensors = "";

    // Sensor data storage for orientation calculation
    private float[] accelerometerValues = new float[3];
    private float[] magnetometerValues = new float[3];
    private boolean hasAccelerometerData = false;
    private boolean hasMagnetometerData = false;
    private float[] rotationMatrix = new float[9];
    private float[] orientationValues = new float[3];

    // ========================================
    // NETWORK & SOCKET.IO
    // ========================================

    private Socket socket;
    private boolean socketConnected = false;
    private long lastSendTime = 0;

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
        // Removed automatic connection - now manual via button
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerSensorListener();
        // Removed automatic socket reconnection
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

        // Create main layout
        mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setBackgroundColor(Color.WHITE);

        // Create connection button
        setupConnectionButton();

        // Create and add custom dial view (takes up most of the space)
        dialView = new GyroDialView(this);
        LinearLayout.LayoutParams dialParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, // Height will be determined by weight
                1.0f // Take up all remaining space
        );
        dialView.setLayoutParams(dialParams);
        dialActive = true;

        // Add views to layout
        mainLayout.addView(dialView);
        mainLayout.addView(connectionButton);

        setContentView(mainLayout);
    }

    /**
     * Setup the connection/disconnection button
     */
    private void setupConnectionButton() {
        connectionButton = new Button(this);
        connectionButton.setText("CONNECT TO SERVER");
        connectionButton.setTextSize(16);
        connectionButton.setBackgroundColor(Color.BLUE); // Blue
        connectionButton.setTextColor(Color.WHITE);
        connectionButton.setPadding(20, 15, 20, 15);

        // Set layout parameters for the button
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        buttonParams.setMargins(20, 10, 20, 10);
        connectionButton.setLayoutParams(buttonParams);

        connectionButton.setOnClickListener(v -> toggleConnection());
    }

    /**
     * Initialize all components (sensors, socket)
     */
    private void initializeComponents() {
        initializeSensors();
        initializeSocket();
    }

    // ========================================
    // CONNECTION MANAGEMENT
    // ========================================

    /**
     * Toggle connection state (connect/disconnect)
     */
    private void toggleConnection() {
        if (socketConnected) {
            disconnectFromServer();
        } else {
            connectToServer();
        }
    }

    /**
     * Update connection button appearance based on connection state
     */
    private void updateConnectionButton() {
        runOnUiThread(() -> {
            if (socketConnected) {
                connectionButton.setText("DISCONNECT FROM SERVER");
                connectionButton.setBackgroundColor(Color.rgb(244, 67, 54)); // Red
            } else {
                connectionButton.setText("CONNECT TO SERVER");
                connectionButton.setBackgroundColor(Color.BLUE);
            }
        });
    }

    /**
     * Connect to server manually
     */
    private void connectToServer() {
        if (socket != null && !socket.connected()) {
            Log.d(TAG, "Connecting to server...");
            connectionButton.setEnabled(false);
            connectionButton.setText("CONNECTING...");
            socket.connect();
        }
    }

    /**
     * Disconnect from server manually
     */
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

    /**
     * Initialize sensor manager, accelerometer, and magnetometer
     */
    private void initializeSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        listAvailableMotionSensors();

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

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

    /**
     * Register sensor listeners for available sensors
     */
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
            System.arraycopy(event.values, 0, accelerometerValues, 0, 3);
            hasAccelerometerData = true;
            processAccelerometerData(event.values);
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerValues, 0, 3);
            hasMagnetometerData = true;
        }

        // Calculate orientation if we have both sensors
        if (hasAccelerometerData && hasMagnetometerData) {
            calculateOrientation();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Log accuracy changes for debugging
        String sensorName = (sensor.getType() == Sensor.TYPE_ACCELEROMETER) ? "Accelerometer" :
                (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) ? "Magnetometer" : "Unknown";
        Log.d(TAG, sensorName + " accuracy changed to: " + accuracy);
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

        // If magnetometer is not available, keep yaw at 0
        if (magnetometer == null) {
            currentYaw = 0;
        }

        updateDialView();
        sendAttitudeData();
    }

    /**
     * Calculate orientation using both accelerometer and magnetometer
     */
    private void calculateOrientation() {
        // Get rotation matrix from accelerometer and magnetometer
        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magnetometerValues)) {
            // Get orientation values (azimuth, pitch, roll)
            SensorManager.getOrientation(rotationMatrix, orientationValues);

            // Convert from radians to degrees
            float azimuth = (float) Math.toDegrees(orientationValues[0]); // Yaw
            float pitch = (float) Math.toDegrees(orientationValues[1]);   // Pitch
            float roll = (float) Math.toDegrees(orientationValues[2]);    // Roll

            // Normalize azimuth to 0-360 degrees
            if (azimuth < 0) {
                azimuth += 360;
            }

            // Update yaw (azimuth) - now we have real compass heading!
            currentYaw = azimuth;

            // You can also use the calculated pitch and roll if you prefer them over accelerometer-only calculations
            // currentPitch = pitch;
            // currentRoll = roll;

            updateDialView();
            sendAttitudeData();
        }
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
        }));

        socket.on(Socket.EVENT_CONNECT_ERROR, args -> runOnUiThread(() -> {
            socketConnected = false;
            String error = args.length > 0 ? args[0].toString() : "Unknown error";
            Log.e(TAG, "✗ Connection error: " + error);
            showToast("Connection failed", Toast.LENGTH_LONG);
            if (dialView != null) {
                dialView.setConnectionStatus(false);
            }
            updateConnectionButton();
            connectionButton.setEnabled(true);
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
     * Send attitude data to server with throttling (only when connected)
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
     * Shows Yaw, Pitch, and Roll with visual indicators
     * All content is vertically centered in the view
     * Gracefully handles missing magnetometer
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

        // Logo bitmap for header
        private Bitmap logoBitmap;

        private boolean connected = false;

        // Colors for each gauge
        private final int[] gaugeColors = {
                Color.rgb(33, 150, 243),   // Blue for Pitch
                Color.rgb(255, 193, 7),    // Amber/Yellow for Roll
                Color.rgb(158, 158, 158)   // Gray for YAW when N/A, will be changed to green when available
        };

        public GyroDialView(Context context) {
            super(context);

            // Load the logo bitmap from drawable
            logoBitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.logo_or);

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

            // Calculate total content height to center everything vertically
            int logoHeight = (logoBitmap != null) ? 94 : 0;
            int riderNameHeight = (riderName != null && !riderName.isEmpty()) ? 40 : 0;
            int circleRadius = Math.min(width / 6, height / 4) - 20;
            int circleAreaHeight = circleRadius * 2 + 100; // Circle + titles + labels
            int connectionStatusHeight = 30;

            int totalContentHeight = logoHeight + riderNameHeight + circleAreaHeight + connectionStatusHeight + 60; // 60 for spacing

            // Calculate starting Y position to center all content
            int startY = Math.max(20, (height - totalContentHeight) / 2);

            int currentY = startY;

            // Draw all components with calculated positions
            currentY = drawCenteredHeader(canvas, width, currentY);
            currentY = drawCenteredConnectionStatus(canvas, width, currentY);
            currentY = drawCenteredProgressCircles(canvas, width, currentY, circleRadius);
        }

        /**
         * Draw header with logo and rider name (centered)
         */
        private int drawCenteredHeader(Canvas canvas, int width, int startY) {
            int currentY = startY;

            // Draw logo at the top if available
            if (logoBitmap != null) {
                int logoWidth = 360;
                int logoHeight = 94;

                // Scale bitmap to desired size
                Bitmap scaledLogo = Bitmap.createScaledBitmap(logoBitmap, logoWidth, logoHeight, true);

                // Draw logo centered
                int logoX = (width - logoWidth) / 2;
                canvas.drawBitmap(scaledLogo, logoX, currentY, null);

                currentY += logoHeight + 20;
            }

            // Draw rider name
            if (riderName != null && !riderName.isEmpty()) {
                currentY += 50; // Creates 50px margin ABOVE the text

                textPaint.setTextSize(44);
                textPaint.setColor(Color.rgb(158, 158, 158));
                canvas.drawText("RIDER: " + riderName.toUpperCase(), width / 2, currentY, textPaint);
                currentY += 40; // Space after the text
            }

            return currentY;
        }

        /**
         * Draw connection status (centered)
         */
        private int drawCenteredConnectionStatus(Canvas canvas, int width, int startY) {
            // Center the connection status horizontally
            String statusText;
            int statusColor;

            if (connected) {
                statusText = "● Connected";
                statusColor = Color.rgb(76, 175, 80);
            } else {
                statusText = "● Disconnected";
                statusColor = Color.rgb(244, 67, 54);
            }

            statusPaint.setColor(statusColor);
            statusPaint.setTextAlign(Paint.Align.CENTER); // Center align the status text
            canvas.drawText(statusText, width / 2, startY, statusPaint);

            return startY + 40;
        }

        /**
         * Draw three clean progress circles (centered)
         */
        private int drawCenteredProgressCircles(Canvas canvas, int width, int startY, int circleRadius) {
            int centerY = startY + circleRadius + 60; // Add space for titles above circles

            // Calculate positions for three circles
            int leftX = width / 4;
            int centerX = width / 2;
            int rightX = 3 * width / 4;

            // Draw circles
            drawPitchCircle(canvas, leftX, centerY, circleRadius);
            drawRollCircle(canvas, centerX, centerY, circleRadius);
            drawYawCircle(canvas, rightX, centerY, circleRadius);

            return centerY + circleRadius + 80; // Return position after circles
        }

        /**
         * Draw pitch progress circle - moves left for negative, right for positive
         */
        /**
         * Draw pitch progress circle - bidirectional arcs from center points
         * Positive: arcs from top center going left and right
         * Negative: arcs from bottom center going left and right
         */
        private void drawPitchCircle(Canvas canvas, int centerX, int centerY, int radius) {
            // Draw background circle
            canvas.drawCircle(centerX, centerY, radius, backgroundCirclePaint);

            // Normalize pitch to 0-100% (assuming ±90° range)
            float normalizedPitch = Math.abs(currentPitch) / 90f;
            normalizedPitch = Math.min(1.0f, normalizedPitch);

            // Draw progress arcs if there's any pitch value
            if (normalizedPitch > 0) {
                progressPaint.setColor(gaugeColors[0]); // Blue color for pitch

                // Calculate the sweep angle for each arc (max 90° per side)
                float sweepAngle = normalizedPitch * 90f;

                if (currentPitch >= 0) {
                    // Positive pitch: draw two arcs from top center (-90°) going left and right

                    // Left arc: from -90° going counter-clockwise
                    canvas.drawArc(
                            centerX - radius, centerY - radius,
                            centerX + radius, centerY + radius,
                            -90f - sweepAngle, sweepAngle, false, progressPaint
                    );

                    // Right arc: from -90° going clockwise
                    canvas.drawArc(
                            centerX - radius, centerY - radius,
                            centerX + radius, centerY + radius,
                            -90f, sweepAngle, false, progressPaint
                    );

                } else {
                    // Negative pitch: draw two arcs from bottom center (90°) going left and right

                    // Left arc: from 90° going clockwise (towards left)
                    canvas.drawArc(
                            centerX - radius, centerY - radius,
                            centerX + radius, centerY + radius,
                            90f, sweepAngle, false, progressPaint
                    );

                    // Right arc: from 90° going counter-clockwise (towards right)
                    canvas.drawArc(
                            centerX - radius, centerY - radius,
                            centerX + radius, centerY + radius,
                            90f - sweepAngle, sweepAngle, false, progressPaint
                    );
                }
            }

            // Draw value and labels (show actual value with sign)
            valuePaint.setTextSize(48);
            canvas.drawText(String.format("%.0f", currentPitch), centerX, centerY + 8, valuePaint);

            textPaint.setTextSize(16);
            canvas.drawText("degrees", centerX, centerY + 30, textPaint);
            textPaint.setTextSize(14);
            canvas.drawText("Range: ±90°", centerX, centerY + 50, textPaint);

            // Title above circle
            titlePaint.setTextSize(24);
            canvas.drawText("TILT (TURNUP)", centerX, centerY - radius - 50, titlePaint);
        }
        private void drawRollCircle(Canvas canvas, int centerX, int centerY, int radius) {
            // Normalize roll to 0-100% (assuming ±90° range)
            float normalizedRoll = Math.abs(currentRoll) / 90f;
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
            titlePaint.setTextSize(24);
            canvas.drawText("LEAN (TABLE TOP)", centerX, centerY - radius - 50, titlePaint);
        }

        /**
         * Draw yaw circle - shows actual compass heading when magnetometer is available
         */
        private void drawYawCircle(Canvas canvas, int centerX, int centerY, int radius) {
            if (magnetometer == null) {
                // Draw empty circle for yaw (not available)
                drawDirectionalProgressCircle(canvas, centerX, centerY, radius, 0f, gaugeColors[2], true);

                // Draw N/A text
                valuePaint.setTextSize(36);
                valuePaint.setColor(Color.rgb(158, 158, 158));
                canvas.drawText("N/A", centerX, centerY + 8, valuePaint);

                textPaint.setTextSize(14);
                canvas.drawText("Magnetometer", centerX, centerY + 30, textPaint);
                canvas.drawText("Not Available", centerX, centerY + 48, textPaint);

                valuePaint.setColor(Color.rgb(33, 33, 33)); // Reset color
            } else {
                // Magnetometer is available - show yaw as compass heading
                int yawColor = Color.rgb(76, 175, 80); // Green for active yaw

                // Normalize yaw to 0-100% for visual representation
                float normalizedYaw = currentYaw / 360f;

                drawYawProgressCircle(canvas, centerX, centerY, radius, currentYaw, yawColor);

                // Draw value and labels
                valuePaint.setTextSize(48);
                canvas.drawText(String.format("%.0f", currentYaw), centerX, centerY + 8, valuePaint);

                textPaint.setTextSize(16);
                canvas.drawText("degrees", centerX, centerY + 30, textPaint);
                textPaint.setTextSize(14);
                canvas.drawText("Range: 0-360°", centerX, centerY + 50, textPaint);
            }

            // Title above circle
            titlePaint.setTextSize(24);
            canvas.drawText("TURN (TURNDOWN)", centerX, centerY - radius - 50, titlePaint);
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
         * Draw yaw as a compass-style progress circle (full 360° range)
         */
        private void drawYawProgressCircle(Canvas canvas, int centerX, int centerY, int radius, float yaw, int color) {
            // Draw background circle
            canvas.drawCircle(centerX, centerY, radius, backgroundCirclePaint);

            // Draw compass needle/indicator
            progressPaint.setColor(color);

            // Calculate the angle for the compass needle
            // Start from North (top) and rotate based on yaw
            float needleAngle = yaw - 90f; // -90 to start from top instead of right

            // Draw a thicker arc to represent the compass direction
            canvas.drawArc(
                    centerX - radius, centerY - radius,
                    centerX + radius, centerY + radius,
                    needleAngle - 5, 10, false, progressPaint // 10-degree wide indicator
            );

            // Optional: Draw a small circle at center to represent compass center
            Paint centerDotPaint = new Paint();
            centerDotPaint.setColor(color);
            centerDotPaint.setStyle(Paint.Style.FILL);
            centerDotPaint.setAntiAlias(true);
            canvas.drawCircle(centerX, centerY, 8, centerDotPaint);

            // Draw cardinal direction markers (N, E, S, W)
            Paint cardinalPaint = new Paint();
            cardinalPaint.setColor(Color.rgb(158, 158, 158));
            cardinalPaint.setTextSize(16);
            cardinalPaint.setTextAlign(Paint.Align.CENTER);
            cardinalPaint.setAntiAlias(true);

            // North
            canvas.drawText("N", centerX, centerY - radius - 10, cardinalPaint);
            // East
            cardinalPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("E", centerX + radius + 10, centerY + 5, cardinalPaint);
            // South
            cardinalPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("S", centerX, centerY + radius + 25, cardinalPaint);
            // West
            cardinalPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("W", centerX - radius - 10, centerY + 5, cardinalPaint);
        }
    }
}