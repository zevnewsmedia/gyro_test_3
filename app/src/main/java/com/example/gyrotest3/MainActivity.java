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
import android.provider.Settings;
import android.content.SharedPreferences;
import java.util.UUID;
import androidx.appcompat.app.AlertDialog;
import android.widget.EditText;
import android.text.InputType;



public class MainActivity extends AppCompatActivity implements SensorEventListener {


    private String deviceId;
    private String riderName;

    // Sensor-related fields
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private GyroDialView dialView;
    private float currentYaw = 0; // Will always be 0 for accelerometer
    private float currentPitch = 0;
    private float currentRoll = 0;
    private boolean dialActive = false;
    private String availableMotionSensors = "";

    // Socket.IO fields
    private Socket socket;
    private static final String SERVER_URL = "http://3.91.244.249:5000/";
    private static final String RIDER_API_URL = "http://3.91.244.249:5000/get_rider/3";
    private static final String TAG = "GyroSocket";
    private boolean socketConnected = false;
    private long lastSendTime = 0;
    private static final long SEND_INTERVAL = 100; // Send data every 100ms (10 times per second)

    // Rider management
    private String currentRiderName = "";
    private String currentAppId = "";
    private long lastRiderFetchTime = 0;
    private static final long RIDER_FETCH_INTERVAL = 30000; // Fetch rider every 30 seconds
    private ExecutorService executorService;

    // Fallback fictional riders (in case API fails)
    private String[] fallbackRiders = {
            "Lightning McQueen",
            "Speed Racer",
            "Ghost Rider",
            "Easy Rider",
            "Storm Chaser"
    };
    private Random random = new Random();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        // Optional: Clear previous rider name for testing
         prefs.edit().remove("rider_name").apply();

        // --- Device ID ---
        deviceId = prefs.getString("device_id", null);
        if (deviceId == null) {
            // Generate new UUID if none exists
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", deviceId).apply();
        }

        // --- Rider Name ---
        riderName = prefs.getString("rider_name", null);
        if (riderName == null) {
            // Show a dialog to ask the user for their name
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Enter your name");

            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
            builder.setView(input);

            builder.setPositiveButton("OK", (dialog, which) -> {
                String name = input.getText().toString().trim();
                if (!name.isEmpty()) {
                    riderName = name;
                } else {
                    riderName = "New Rider"; // fallback
                }
                prefs.edit().putString("rider_name", riderName).apply();

                Log.d("RiderName", riderName);
                Log.d("DeviceID", deviceId);
            });

            builder.setNegativeButton("Cancel", (dialog, which) -> {
                dialog.cancel();
                riderName = "New Rider"; // fallback
                prefs.edit().putString("rider_name", riderName).apply();

                Log.d("RiderName", riderName);
                Log.d("DeviceID", deviceId);
            });

            builder.show();
        } else {
            // Rider already stored
            Log.d("RiderName", riderName);
            Log.d("DeviceID", deviceId);
        }

        // Hide the ActionBar
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }


        // Lock orientation to landscape
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // Initialize executor service for API calls
        executorService = Executors.newSingleThreadExecutor();

        // Initialize sensors
        initializeSensors();

        // Initialize Socket.IO
        initializeSocket();

        // Create gyro dial view and set it as the main content
        dialView = new GyroDialView(this);
        setContentView(dialView);
        dialActive = true;

        // Fetch initial rider data
        fetchRiderFromAPI();

        // Auto-connect to server
        connectToServer();
    }

    private void fetchRiderFromAPI() {
        executorService.execute(() -> {
            try {
                Log.d(TAG, "Fetching rider from API: " + RIDER_API_URL);

                URL url = new URL(RIDER_API_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000); // 5 second timeout
                connection.setReadTimeout(5000);

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    // Parse JSON response
                    JSONObject jsonResponse = new JSONObject(response.toString());
                    String appId = jsonResponse.optString("appId", "");
                    String rider = jsonResponse.optString("rider", "");

                    // Update on main thread
                    runOnUiThread(() -> {
                        if (!rider.isEmpty()) {
                            currentRiderName = rider;
                            currentAppId = appId;
                            lastRiderFetchTime = System.currentTimeMillis();
                            Log.d(TAG, "✓ Fetched rider from API: " + rider + " (" + appId + ")");

                            if (dialView != null) {
                                dialView.invalidate(); // Refresh UI
                            }
                        } else {
                            Log.w(TAG, "Empty rider name from API, using fallback");
                            useFallbackRider();
                        }
                    });

                } else {
                    Log.e(TAG, "API request failed with code: " + responseCode);
                    runOnUiThread(() -> useFallbackRider());
                }

                connection.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Error fetching rider from API", e);
                runOnUiThread(() -> useFallbackRider());
            }
        });
    }

    private void useFallbackRider() {
        if (currentRiderName.isEmpty()) {
            currentRiderName = fallbackRiders[random.nextInt(fallbackRiders.length)];
            currentAppId = "fallback_rider";
            Log.d(TAG, "Using fallback rider: " + currentRiderName);
        }
    }

    private void checkAndUpdateRider() {
        long currentTime = System.currentTimeMillis();

        // Fetch new rider data every 30 seconds or if current rider is empty
        if (currentRiderName.isEmpty() || (currentTime - lastRiderFetchTime > RIDER_FETCH_INTERVAL)) {
            fetchRiderFromAPI();
        }
    }

    private void initializeSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        // Get list of available motion sensors
        listAvailableMotionSensors();

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer == null) {
            Toast.makeText(this, "Accelerometer not available", Toast.LENGTH_LONG).show();
        }
    }

    private void initializeSocket() {
        try {
            Log.d(TAG, "Initializing Socket.IO connection to: " + SERVER_URL);

            IO.Options opts = new IO.Options();
            opts.transports = new String[]{"websocket"};
            socket = IO.socket(SERVER_URL, opts);

            // Connection events
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    runOnUiThread(() -> {
                        socketConnected = true;
                        Log.d(TAG, "✓ Connected to server");
                        Toast.makeText(MainActivity.this, "✓ Connected to server", Toast.LENGTH_SHORT).show();
                        if (dialView != null) {
                            dialView.setConnectionStatus(true);
                        }
                    });
                }
            });

            socket.on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    runOnUiThread(() -> {
                        socketConnected = false;
                        Log.d(TAG, "✗ Disconnected from server");
                        Toast.makeText(MainActivity.this, "✗ Disconnected from server", Toast.LENGTH_SHORT).show();
                        if (dialView != null) {
                            dialView.setConnectionStatus(false);
                        }
                    });
                }
            });

            socket.on(Socket.EVENT_CONNECT_ERROR, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    runOnUiThread(() -> {
                        socketConnected = false;
                        String error = args.length > 0 ? args[0].toString() : "Unknown error";
                        Log.e(TAG, "✗ Connection error: " + error);
                        Toast.makeText(MainActivity.this, "Connection failed", Toast.LENGTH_LONG).show();
                        if (dialView != null) {
                            dialView.setConnectionStatus(false);
                        }
                    });
                }
            });

            // Listen for server responses
            socket.on("attitude_data", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    try {
                        JSONObject data = (JSONObject) args[0];
                        String rider = data.optString("riderDisplayName", "");
                        Log.d(TAG, "Server broadcast from: " + rider);
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing server response", e);
                    }
                }
            });

        } catch (URISyntaxException e) {
            Log.e(TAG, "URI Syntax error", e);
            Toast.makeText(this, "Invalid server URL", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Error initializing socket", e);
        }
    }

    private void connectToServer() {
        if (socket != null && !socket.connected()) {
            Log.d(TAG, "Connecting to server...");
            socket.connect();
        }
    }

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

        // Check if we need to update rider info
        checkAndUpdateRider();

        // Ensure we have a rider name (use fallback if needed)
        if (currentRiderName.isEmpty()) {
            useFallbackRider();
        }

        try {
            JSONObject attitudeData = new JSONObject();
            attitudeData.put("pitch", Math.round(currentPitch * 10.0) / 10.0); // Round to 1 decimal place
            attitudeData.put("yaw", Math.round(currentYaw * 10.0) / 10.0);
            attitudeData.put("roll", Math.round(currentRoll * 10.0) / 10.0);
            attitudeData.put("rider", "gyro_app");
            attitudeData.put("riderDisplayName", currentRiderName); // Use API-fetched rider name

            socket.emit("attitude_update", attitudeData);

            // Log every 1 second (10 sends) to avoid spam
            if (currentTime % 1000 < SEND_INTERVAL) {
                Log.d(TAG, String.format("Sent [%s]: P=%.1f°, Y=%.1f°, R=%.1f°",
                        currentRiderName, currentPitch, currentYaw, currentRoll));
            }

        } catch (JSONException e) {
            Log.e(TAG, "Error creating attitude JSON", e);
        }
    }

    private void listAvailableMotionSensors() {
        if (sensorManager != null) {
            java.util.List<Sensor> sensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
            StringBuilder sensorList = new StringBuilder();

            sensorList.append("Motion-Related Sensors:\n");
            for (Sensor sensor : sensors) {
                if (isMotionSensor(sensor.getType())) {
                    String sensorType = getSensorTypeName(sensor.getType());
                    sensorList.append("• ").append(sensorType).append("\n");
                }
            }

            availableMotionSensors = sensorList.toString();
        }
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
            case Sensor.TYPE_ACCELEROMETER:
                return "Accelerometer";
            case Sensor.TYPE_GYROSCOPE:
                return "Gyroscope";
            case Sensor.TYPE_MAGNETIC_FIELD:
                return "Magnetometer";
            case Sensor.TYPE_ROTATION_VECTOR:
                return "Rotation Vector";
            case Sensor.TYPE_ORIENTATION:
                return "Orientation (Deprecated)";
            case Sensor.TYPE_GRAVITY:
                return "Gravity";
            case Sensor.TYPE_LINEAR_ACCELERATION:
                return "Linear Acceleration";
            case Sensor.TYPE_GAME_ROTATION_VECTOR:
                return "Game Rotation Vector";
            case Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR:
                return "Geomagnetic Rotation Vector";
            case Sensor.TYPE_STEP_COUNTER:
                return "Step Counter";
            case Sensor.TYPE_STEP_DETECTOR:
                return "Step Detector";
            default:
                return "Unknown Motion Sensor (Type: " + sensorType + ")";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
        // Reconnect socket if needed
        if (socket != null && !socket.connected()) {
            connectToServer();
        }
        // Refresh rider data when app resumes
        fetchRiderFromAPI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (socket != null) {
            socket.disconnect();
            Log.d(TAG, "Socket disconnected in onDestroy");
        }
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // Get accelerometer values (m/s²)
            float x = event.values[0]; // Left/Right tilt
            float y = event.values[1]; // Forward/Backward tilt
            float z = event.values[2]; // Up/Down (gravity when stationary)

            // Calculate tilt angles from gravity vector
            // In landscape mode, we need to adjust the axis mapping:
            // Roll: rotation that would normally change left/right tilt
            currentRoll = (float) Math.toDegrees(Math.atan2(-y, Math.sqrt(x * x + z * z)));

            // Pitch: rotation that would normally change forward/backward tilt
            currentPitch = (float) Math.toDegrees(Math.atan2(x, Math.sqrt(y * y + z * z)));

            // Yaw: Cannot be determined from accelerometer alone
            currentYaw = 0;

            if (dialView != null && dialActive) {
                dialView.setAngles(currentYaw, currentPitch, currentRoll);
            }

            // Send data to server
            sendAttitudeData();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // Custom view class for drawing the gyroscope data
    private class GyroDialView extends View {
        private Paint dialPaint;
        private Paint needlePaint;
        private Paint textPaint;
        private Paint centerPaint;
        private Paint backgroundPaint;
        private Paint statusPaint;
        private boolean connected = false;

        public GyroDialView(Context context) {
            super(context);
            initPaints();
            setBackgroundColor(Color.WHITE);
        }

        private void initPaints() {
            backgroundPaint = new Paint();
            backgroundPaint.setColor(Color.WHITE);
            backgroundPaint.setStyle(Paint.Style.FILL);

            dialPaint = new Paint();
            dialPaint.setColor(Color.GRAY);
            dialPaint.setStyle(Paint.Style.STROKE);
            dialPaint.setStrokeWidth(8);
            dialPaint.setAntiAlias(true);

            needlePaint = new Paint();
            needlePaint.setColor(Color.RED);
            needlePaint.setStrokeWidth(6);
            needlePaint.setAntiAlias(true);

            textPaint = new Paint();
            textPaint.setColor(Color.BLACK);
            textPaint.setTextSize(48);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setAntiAlias(true);

            centerPaint = new Paint();
            centerPaint.setColor(Color.BLACK);
            centerPaint.setStyle(Paint.Style.FILL);
            centerPaint.setAntiAlias(true);

            statusPaint = new Paint();
            statusPaint.setTextSize(24);
            statusPaint.setTextAlign(Paint.Align.LEFT);
            statusPaint.setAntiAlias(true);
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

        private void drawTopRiderPitch(Canvas canvas, int width) {
            Paint topPaint = new Paint();
            topPaint.setColor(Color.BLUE);
            topPaint.setTextSize(36); // Adjust size if needed
            topPaint.setTextAlign(Paint.Align.CENTER);
            topPaint.setAntiAlias(true);

            String text = currentRiderName.isEmpty() ? "No Rider" : currentRiderName;
            text += " - Pitch: " + Math.round(currentPitch) + "°";

            // Draw at top with padding
            canvas.drawText(text, width / 2, 40, topPaint);
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

            // Draw current rider name at the very top, centered
            drawTopRider(canvas, width);

            // Draw connection status below the top label
            drawConnectionStatus(canvas, 20, 80);

            // Draw three dials arranged for landscape layout
            int leftDialX = width / 4;
            int rightDialX = 3 * width / 4;
            int dialY = centerY;

            drawYawDial(canvas, leftDialX, dialY, radius);
            drawPitchDial(canvas, rightDialX, dialY, radius);
            drawRollDial(canvas, centerX, dialY, radius);

            // Draw pitch and roll values at the bottom
            drawPitchRollValues(canvas, centerX, height - 80);
        }

        // Helper method for top rider name
        private void drawTopRider(Canvas canvas, int width) {
            Paint topPaint = new Paint();
            topPaint.setColor(Color.BLUE);
            topPaint.setTextSize(40);
            topPaint.setTextAlign(Paint.Align.CENTER);
            topPaint.setAntiAlias(true);

            if (!currentRiderName.isEmpty()) {
                canvas.drawText("Rider: " + currentRiderName, width / 2, 50, topPaint);
            }
        }



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

        private void drawCurrentRider(Canvas canvas, int x, int y) {
            statusPaint.setColor(Color.BLUE);
            statusPaint.setTextSize(20);
            if (!currentRiderName.isEmpty()) {
                String displayText = "Current Rider: " + currentRiderName;
                if (!currentAppId.isEmpty() && !currentAppId.equals("fallback_rider")) {
                    displayText += " (" + currentAppId + ")";
                }
                canvas.drawText(displayText, x, y, statusPaint);
            }
            statusPaint.setTextSize(24); // Reset to original size
        }

        private void drawYawDial(Canvas canvas, int centerX, int centerY, int radius) {
            canvas.drawCircle(centerX, centerY, radius, dialPaint);

            // Draw a simple cross pattern since yaw is not available
            Paint unavailablePaint = new Paint();
            unavailablePaint.setColor(Color.GRAY);
            unavailablePaint.setStrokeWidth(6);
            unavailablePaint.setAntiAlias(true);

            canvas.drawLine(centerX - radius/2, centerY - radius/2, centerX + radius/2, centerY + radius/2, unavailablePaint);
            canvas.drawLine(centerX + radius/2, centerY - radius/2, centerX - radius/2, centerY + radius/2, unavailablePaint);

            textPaint.setTextSize(24);
            canvas.drawText("YAW", centerX, centerY - radius - 30, textPaint);

            textPaint.setTextSize(18);
            canvas.drawText("(N/A)", centerX, centerY - radius - 8, textPaint);
        }

        private void drawPitchDial(Canvas canvas, int centerX, int centerY, int radius) {
            canvas.drawCircle(centerX, centerY, radius, dialPaint);
            drawPitchMarkers(canvas, centerX, centerY, radius);

            float angle = (float) Math.toRadians(currentPitch);
            float needleX = centerX + (radius - 20) * (float) Math.sin(angle);
            float needleY = centerY - (radius - 20) * (float) Math.cos(angle);
            canvas.drawLine(centerX, centerY, needleX, needleY, needlePaint);
            canvas.drawCircle(centerX, centerY, 10, centerPaint);

            textPaint.setTextSize(24);
            canvas.drawText("PITCH", centerX, centerY - radius - 30, textPaint);
        }

        private void drawRollDial(Canvas canvas, int centerX, int centerY, int radius) {
            canvas.drawCircle(centerX, centerY, radius, dialPaint);
            drawRollMarkers(canvas, centerX, centerY, radius);

            float angle = (float) Math.toRadians(currentRoll);
            float needleX = centerX + (radius - 20) * (float) Math.sin(angle);
            float needleY = centerY - (radius - 20) * (float) Math.cos(angle);
            canvas.drawLine(centerX, centerY, needleX, needleY, needlePaint);
            canvas.drawCircle(centerX, centerY, 10, centerPaint);

            textPaint.setTextSize(24);
            canvas.drawText("ROLL", centerX, centerY - radius - 30, textPaint);
        }

        private void drawPitchMarkers(Canvas canvas, int centerX, int centerY, int radius) {
            Paint markerPaint = new Paint();
            markerPaint.setColor(Color.BLACK);
            markerPaint.setStrokeWidth(4);
            markerPaint.setAntiAlias(true);

            Paint labelPaint = new Paint();
            labelPaint.setColor(Color.BLACK);
            labelPaint.setTextSize(20);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setAntiAlias(true);

            String[] labels = {"U", "R", "D", "L"};
            for (int i = 0; i < 4; i++) {
                float angle = (float) Math.toRadians(i * 90);
                float x1 = centerX + (radius - 20) * (float) Math.sin(angle);
                float y1 = centerY - (radius - 20) * (float) Math.cos(angle);
                float x2 = centerX + radius * (float) Math.sin(angle);
                float y2 = centerY - radius * (float) Math.cos(angle);

                canvas.drawLine(x1, y1, x2, y2, markerPaint);

                float labelX = centerX + (radius + 30) * (float) Math.sin(angle);
                float labelY = centerY - (radius + 30) * (float) Math.cos(angle) + 8;
                canvas.drawText(labels[i], labelX, labelY, labelPaint);
            }
        }

        private void drawRollMarkers(Canvas canvas, int centerX, int centerY, int radius) {
            Paint markerPaint = new Paint();
            markerPaint.setColor(Color.BLACK);
            markerPaint.setStrokeWidth(4);
            markerPaint.setAntiAlias(true);

            Paint labelPaint = new Paint();
            labelPaint.setColor(Color.BLACK);
            labelPaint.setTextSize(18);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setAntiAlias(true);

            String[] labels = {"0", "90", "180", "270"};
            for (int i = 0; i < 4; i++) {
                float angle = (float) Math.toRadians(i * 90);
                float x1 = centerX + (radius - 20) * (float) Math.sin(angle);
                float y1 = centerY - (radius - 20) * (float) Math.cos(angle);
                float x2 = centerX + radius * (float) Math.sin(angle);
                float y2 = centerY - radius * (float) Math.cos(angle);

                canvas.drawLine(x1, y1, x2, y2, markerPaint);

                float labelX = centerX + (radius + 30) * (float) Math.sin(angle);
                float labelY = centerY - (radius + 30) * (float) Math.cos(angle) + 8;
                canvas.drawText(labels[i], labelX, labelY, labelPaint);
            }
        }

        private void drawPitchRollValues(Canvas canvas, int centerX, int startY) {
            Paint valuePaint = new Paint();
            valuePaint.setColor(Color.BLACK);
            valuePaint.setTextSize(32);
            valuePaint.setTextAlign(Paint.Align.CENTER);
            valuePaint.setAntiAlias(true);

            // Draw pitch and roll as whole numbers
            canvas.drawText("Pitch: " + Math.round(currentPitch) + "°", centerX - 200, startY, valuePaint);
            canvas.drawText("Roll: " + Math.round(currentRoll) + "°", centerX + 200, startY, valuePaint);
        }

    }
}