package com.vladurares.tcpclient.ui.activities;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;
import com.vladurares.tcpclient.R;
import com.vladurares.tcpclient.network.TcpConnection;
import com.vladurares.tcpclient.utils.ConfigReader;

import chat.network.ChatDtos;
import chat.network.NetworkPacket;
import chat.network.PacketType;

/**
 * Flow:
 *   1. User introduces email → FORGOT_PASSWORD_REQUEST
 *   2. Server trimite cod pe email → FORGOT_PASSWORD_RESPONSE "SENT"
 *   3. UI switch: arată câmpurile de cod + parolă nouă
 *   4. User introduce cod + parolă → RESET_PASSWORD_REQUEST
 *   5. Server verifică cod → RESET_PASSWORD_RESPONSE "OK" / "INVALID"
 *   6. Succes → finish(), înapoi la LoginActivity
 */
public class ForgotPasswordActivity extends AppCompatActivity {
    private static final String TAG = "ForgotPassword";
    private ConfigReader config;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        config = new ConfigReader(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        findViewById(R.id.btnSendCode).setOnClickListener(v -> handleSendCode());
        findViewById(R.id.btnResetPassword).setOnClickListener(v -> handleResetPassword());
    }

    private void handleSendCode() {
        EditText emailField = findViewById(R.id.editEmail);
        String email = emailField.getText().toString().trim();

        if (email.isEmpty()) {
            showSnackbar("Please enter your email.");
            return;
        }

        findViewById(R.id.btnSendCode).setEnabled(false);
        Toast.makeText(this, "Sending reset code...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                // Conectare temporară la server (nu suntem logați)
                TcpConnection.close();
                TcpConnection.connect(this, config.getServerIp(), config.getServerPort());

                TcpConnection.sendPacket(new NetworkPacket(
                        PacketType.FORGOT_PASSWORD_REQUEST, 0, email
                ));

                NetworkPacket response = TcpConnection.readNextPacket();
                TcpConnection.close();

                runOnUiThread(() -> {
                    findViewById(R.id.btnSendCode).setEnabled(true);

                    if (response != null && response.getType() == PacketType.FORGOT_PASSWORD_RESPONSE) {
                        // Mereu arată succes (nu dezvăluim dacă emailul există)
                        Toast.makeText(this, "If the email exists, a code was sent.", Toast.LENGTH_LONG).show();

                        // Switch la state 2
                        findViewById(R.id.layoutEmail).setVisibility(View.GONE);
                        findViewById(R.id.layoutReset).setVisibility(View.VISIBLE);
                    } else {
                        showSnackbar("Connection error. Try again.");
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error sending reset code", e);
                TcpConnection.close();
                runOnUiThread(() -> {
                    findViewById(R.id.btnSendCode).setEnabled(true);
                    showSnackbar("Connection error: " + e.getMessage());
                });
            }
        }).start();
    }

    private void handleResetPassword() {
        EditText codeField = findViewById(R.id.editCode);
        EditText passwordField = findViewById(R.id.editNewPassword);
        EditText confirmField = findViewById(R.id.editConfirmPassword);

        String code = codeField.getText().toString().trim();
        String newPassword = passwordField.getText().toString().trim();
        String confirmPassword = confirmField.getText().toString().trim();

        if (code.isEmpty() || code.length() != 6) {
            showSnackbar("Please enter the 6-digit code.");
            return;
        }

        if (newPassword.isEmpty() || newPassword.length() < 6) {
            showSnackbar("Password must be at least 6 characters.");
            return;
        }

        if (!newPassword.equals(confirmPassword)) {
            showSnackbar("Passwords don't match.");
            return;
        }

        findViewById(R.id.btnResetPassword).setEnabled(false);
        Toast.makeText(this, "Resetting password...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                TcpConnection.close();
                TcpConnection.connect(this, config.getServerIp(), config.getServerPort());

                ChatDtos.ResetPasswordDto dto = new ChatDtos.ResetPasswordDto(code, newPassword);
                TcpConnection.sendPacket(new NetworkPacket(
                        PacketType.RESET_PASSWORD_REQUEST, 0, dto
                ));

                NetworkPacket response = TcpConnection.readNextPacket();
                TcpConnection.close();

                runOnUiThread(() -> {
                    findViewById(R.id.btnResetPassword).setEnabled(true);

                    if (response != null && response.getType() == PacketType.RESET_PASSWORD_RESPONSE) {
                        String result = new com.google.gson.Gson().fromJson(
                                response.getPayload(), String.class);

                        if ("OK".equals(result)) {
                            Toast.makeText(this, "Password reset! You can now log in.", Toast.LENGTH_LONG).show();
                            finish();
                        } else {
                            showSnackbar("Invalid code. Please try again.");
                        }
                    } else {
                        showSnackbar("Connection error. Try again.");
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error resetting password", e);
                TcpConnection.close();
                runOnUiThread(() -> {
                    findViewById(R.id.btnResetPassword).setEnabled(true);
                    showSnackbar("Connection error: " + e.getMessage());
                });
            }
        }).start();
    }

    private void showSnackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG)
                .setBackgroundTint(Color.RED)
                .setTextColor(Color.WHITE)
                .show();
    }
}

