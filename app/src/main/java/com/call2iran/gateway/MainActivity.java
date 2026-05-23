package com.call2iran.gateway;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {

    private static final int REQUEST_PERMISSIONS = 100;
    private static final int REQUEST_DEFAULT_DIALER = 101;
    private static final int REQUEST_PICK_AUDIO = 102;
    private static final int REQUEST_BATTERY_OPTIMIZATION = 103;

    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.READ_EXTERNAL_STORAGE
    };

    private AppSettings settings;

    private EditText editTelnyxNumber;
    private EditText editPollInterval;
    private EditText editPhoneId;
    private EditText editBaleBotToken;
    private EditText editBaleChatId;
    private EditText editBaleKey;
    private EditText editBaleTimeout;
    private Button btnPickVoice;
    private TextView txtVoiceFile;
    private CheckBox chkTestMode;
    private Button btnToggleService;
    private Button btnSetDefaultDialer;
    private TextView txtState;
    private TextView txtChannel;
    private TextView txtLastPoll;
    private TextView txtLastJob;
    private TextView txtLastDuration;
    private TextView txtErrorLog;

    private boolean serviceRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = new AppSettings(this);

        editTelnyxNumber = findViewById(R.id.editTelnyxNumber);
        editPollInterval = findViewById(R.id.editPollInterval);
        editPhoneId = findViewById(R.id.editPhoneId);
        editBaleBotToken = findViewById(R.id.editBaleBotToken);
        editBaleChatId = findViewById(R.id.editBaleChatId);
        editBaleKey = findViewById(R.id.editBaleKey);
        editBaleTimeout = findViewById(R.id.editBaleTimeout);
        btnPickVoice = findViewById(R.id.btnPickVoice);
        txtVoiceFile = findViewById(R.id.txtVoiceFile);
        chkTestMode = findViewById(R.id.chkTestMode);
        btnToggleService = findViewById(R.id.btnToggleService);
        btnSetDefaultDialer = findViewById(R.id.btnSetDefaultDialer);
        txtState = findViewById(R.id.txtState);
        txtChannel = findViewById(R.id.txtChannel);
        txtLastPoll = findViewById(R.id.txtLastPoll);
        txtLastJob = findViewById(R.id.txtLastJob);
        txtLastDuration = findViewById(R.id.txtLastDuration);
        txtErrorLog = findViewById(R.id.txtErrorLog);

        loadSettings();
        setupListeners();
        requestPermissions();
        requestBatteryOptimizationExemption();
    }

    @Override
    protected void onResume() {
        super.onResume();

        serviceRunning = settings.isServiceRunning();
        updateToggleButton();

        GatewayService service = GatewayService.getInstance();
        if (service != null) {
            service.setStatusListener(statusListener);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSettings();

        GatewayService service = GatewayService.getInstance();
        if (service != null) {
            service.setStatusListener(null);
        }
    }

    private void loadSettings() {
        editTelnyxNumber.setText(settings.getTelnyxNumber());

        int interval = settings.getPollInterval();
        editPollInterval.setText(String.valueOf(interval));

        editPhoneId.setText(settings.getPhoneId());

        editBaleBotToken.setText(settings.getBaleBotToken());
        editBaleChatId.setText(settings.getBaleChatId());
        editBaleKey.setText(settings.getBaleEncryptionKey());
        editBaleTimeout.setText(String.valueOf(settings.getBaleTimeout()));

        String voicePath = settings.getVoiceFilePath();
        if (!voicePath.isEmpty()) {
            File f = new File(voicePath);
            txtVoiceFile.setText(f.getName());
        }

        chkTestMode.setChecked(settings.isTestMode());
        serviceRunning = settings.isServiceRunning();
        updateToggleButton();
    }

    private void saveSettings() {
        settings.setTelnyxNumber(editTelnyxNumber.getText().toString().trim());

        String intervalStr = editPollInterval.getText().toString().trim();
        if (!intervalStr.isEmpty()) {
            try {
                int interval = Integer.parseInt(intervalStr);
                if (interval >= 5) {
                    settings.setPollInterval(interval);
                }
            } catch (NumberFormatException ignored) {
            }
        }

        settings.setPhoneId(editPhoneId.getText().toString().trim());
        settings.setBaleBotToken(editBaleBotToken.getText().toString().trim());
        settings.setBaleChatId(editBaleChatId.getText().toString().trim());
        settings.setBaleEncryptionKey(editBaleKey.getText().toString().trim());

        String timeoutStr = editBaleTimeout.getText().toString().trim();
        if (!timeoutStr.isEmpty()) {
            try {
                int timeout = Integer.parseInt(timeoutStr);
                if (timeout >= 1) {
                    settings.setBaleTimeout(timeout);
                }
            } catch (NumberFormatException ignored) {
            }
        }

        settings.setTestMode(chkTestMode.isChecked());
    }

    private void setupListeners() {
        btnToggleService.setOnClickListener(v -> toggleService());

        btnPickVoice.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("audio/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, "Select Voice File"), REQUEST_PICK_AUDIO);
        });

        btnSetDefaultDialer.setOnClickListener(v -> requestDefaultDialer());

        chkTestMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settings.setTestMode(isChecked);
        });
    }

    private void toggleService() {
        saveSettings();

        if (serviceRunning) {
            stopGatewayService();
        } else {
            boolean hasTelnyx = !settings.getTelnyxNumber().isEmpty();
            boolean hasBale = !settings.getBaleBotToken().isEmpty()
                    && !settings.getBaleChatId().isEmpty()
                    && settings.getBaleEncryptionKey().length() == 64;

            if (!settings.isTestMode() && !hasTelnyx && !hasBale) {
                Toast.makeText(this,
                        "Set Bale bot or Telnyx number (or enable Test Mode)",
                        Toast.LENGTH_LONG).show();
                return;
            }
            startGatewayService();
        }
    }

    private void startGatewayService() {
        Intent intent = new Intent(this, GatewayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        serviceRunning = true;
        updateToggleButton();

        GatewayService service = GatewayService.getInstance();
        if (service != null) {
            service.setStatusListener(statusListener);
        }
    }

    private void stopGatewayService() {
        Intent intent = new Intent(this, GatewayService.class);
        stopService(intent);
        serviceRunning = false;
        settings.setServiceRunning(false);
        updateToggleButton();
        txtState.setText("IDLE");
        txtChannel.setText("-");
    }

    private void updateToggleButton() {
        if (serviceRunning) {
            btnToggleService.setText("STOP SERVICE");
            btnToggleService.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(getColor(R.color.errorRed)));
        } else {
            btnToggleService.setText("START SERVICE");
            btnToggleService.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(getColor(R.color.activeGreen)));
        }
    }

    private final GatewayService.StatusUpdateListener statusListener =
            new GatewayService.StatusUpdateListener() {
                @Override
                public void onStatusUpdate(GatewayState state, String pollTime,
                                           String jobInfo, String duration, String errors,
                                           String channel) {
                    runOnUiThread(() -> {
                        txtState.setText(state.getLabel());
                        txtChannel.setText(channel != null ? channel : "-");
                        txtLastPoll.setText(pollTime);
                        txtLastJob.setText(jobInfo);
                        txtLastDuration.setText(duration);
                        if (errors != null && !errors.isEmpty()) {
                            txtErrorLog.setText(errors);
                        }
                    });
                }
            };

    private void requestPermissions() {
        boolean needRequest = false;
        for (String perm : REQUIRED_PERMISSIONS) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }

        if (needRequest) {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                new AlertDialog.Builder(this)
                        .setTitle("Permissions Required")
                        .setMessage("All permissions are required for the gateway to function. " +
                                "Please grant them in Settings.")
                        .setPositiveButton("OK", null)
                        .show();
            }
        }
    }

    private void requestDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = getSystemService(RoleManager.class);
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                    Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                    startActivityForResult(intent, REQUEST_DEFAULT_DIALER);
                } else {
                    Toast.makeText(this, "Already set as default dialer", Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            TelecomManager telecomManager = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (telecomManager != null) {
                String currentDialer = telecomManager.getDefaultDialerPackage();
                if (!getPackageName().equals(currentDialer)) {
                    Intent intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
                    intent.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName());
                    startActivityForResult(intent, REQUEST_DEFAULT_DIALER);
                } else {
                    Toast.makeText(this, "Already set as default dialer", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void requestBatteryOptimizationExemption() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            try {
                startActivityForResult(intent, REQUEST_BATTERY_OPTIMIZATION);
            } catch (Exception e) {
                Toast.makeText(this, "Please disable battery optimization for this app manually",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_DEFAULT_DIALER) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Set as default dialer", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Default dialer not changed", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_PICK_AUDIO && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                String copiedPath = copyAudioFileToInternal(uri);
                if (copiedPath != null) {
                    settings.setVoiceFilePath(copiedPath);
                    txtVoiceFile.setText(new File(copiedPath).getName());
                } else {
                    Toast.makeText(this, "Failed to load audio file", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private String copyAudioFileToInternal(Uri uri) {
        try {
            String filename = "hold_message.audio";
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    filename = cursor.getString(nameIndex);
                }
                cursor.close();
            }

            File destFile = new File(getFilesDir(), filename);
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return null;

            FileOutputStream out = new FileOutputStream(destFile);
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            in.close();
            out.close();

            return destFile.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }
}
