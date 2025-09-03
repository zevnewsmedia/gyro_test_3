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
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    // Sensor-related fields
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private GyroDialView dialView;
    private float currentYaw = 0; // Will always be 0 for accelerometer
    private float currentPitch = 0;
    private float currentRoll = 0;
    private boolean dialActive = false;
    private String availableMotionSensors = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Lock orientation to landscape
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // Initialize sensors
        initializeSensors();

        // Create gyro dial view and set it as the main content
        dialView = new GyroDialView(this);
        setContentView(dialView);
        dialActive = true;
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
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
            dialPaint.setStrokeWidth(8); // Made thicker for bigger dials
            dialPaint.setAntiAlias(true);

            needlePaint = new Paint();
            needlePaint.setColor(Color.RED);
            needlePaint.setStrokeWidth(6); // Made thicker for bigger dials
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
        }

        public void setAngles(float yaw, float pitch, float roll) {
            currentYaw = yaw;
            currentPitch = pitch;
            currentRoll = roll;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int width = getWidth();
            int height = getHeight();
            int centerX = width / 2;
            int centerY = height / 2;
            // Reduced dial size by 15% (0.85 = 85% of original size)
            int radius = (int) (Math.min(width, height) / 4 * 0.85);

            // Clear background
            canvas.drawRect(0, 0, width, height, backgroundPaint);

            // Draw three dials arranged for landscape layout - all at same height
            int leftDialX = width / 4;
            int rightDialX = 3 * width / 4;
            int dialY = height / 2;

            drawYawDial(canvas, leftDialX, dialY, radius);
            drawPitchDial(canvas, rightDialX, dialY, radius);
            drawRollDial(canvas, centerX, dialY, radius);

            // Draw pitch and roll values at the bottom
            drawPitchRollValues(canvas, centerX, height - 80);
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

            // Draw pitch and roll values with labels
            canvas.drawText(String.format("Pitch: %.0f°", currentPitch), centerX - 200, startY, valuePaint);
            canvas.drawText(String.format("Roll: %.0f°", currentRoll), centerX + 200, startY, valuePaint);
        }
    }
}