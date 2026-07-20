package com.vladurares.tcpclient.ui.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.messaging.FirebaseMessaging;
import com.vladurares.tcpclient.utils.ClientKeyManager;
import com.vladurares.tcpclient.utils.ConfigReader;
import com.vladurares.tcpclient.R;
import com.vladurares.tcpclient.storage.SecureStorage;
import com.vladurares.tcpclient.network.TcpConnection;
import com.google.android.material.snackbar.Snackbar;
import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.security.KeyPair;

import chat.models.User;
import chat.network.ChatDtos;
import chat.network.NetworkPacket;
import chat.network.PacketType;
import crypto.api.CryptoHelper;


public class LoginActivity extends AppCompatActivity {
    private ConfigReader config;
    private SharedPreferences preferences;
    private final Gson gson = new Gson();
    private static final String TAG = "LoginActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_login);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.loginLayout), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        config = new ConfigReader(this);

        try {
            preferences = SecureStorage.getEncryptedPrefs(getApplicationContext());
        } catch (Exception e) {
            preferences = getSharedPreferences("ChatPrefs", MODE_PRIVATE);
        }

        CheckBox checkBox = findViewById(R.id.checkBoxKeepSignedIn);
        String savedEmail = preferences.getString("email", null);
        String savedPass = preferences.getString("password", null);

        if (savedEmail != null && savedPass != null) {
            checkBox.setChecked(true);
            doLogin(savedEmail, savedPass, true);
        }
    }

    public void handleLogin(View view) {
        EditText emailField = findViewById(R.id.textInputEditText);
        EditText passwordField = findViewById(R.id.editTextTextPassword);

        String email = emailField.getText().toString().trim();
        String password = passwordField.getText().toString().trim();

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show();
            return;
        }

        CheckBox checkBox = findViewById(R.id.checkBoxKeepSignedIn);
        doLogin(email, password, checkBox.isChecked());
    }

    private void doLogin(String email, String password, boolean keepSignedIn) {
        setButtonsEnabled(false);
        Toast.makeText(this, "Connecting...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                TcpConnection.close();
                TcpConnection.connect(LoginActivity.this, config.getServerIp(), config.getServerPort());

                ChatDtos.AuthDto loginData = new ChatDtos.AuthDto(email, password);
                TcpConnection.sendPacket(new NetworkPacket(PacketType.LOGIN_REQUEST, 0, loginData));

                NetworkPacket response = TcpConnection.readNextPacket();

                runOnUiThread(() -> {
                    setButtonsEnabled(true);

                    if (response == null || response.getType() != PacketType.LOGIN_RESPONSE) {
                        showSnackbar("Invalid response from server");
                        TcpConnection.close();
                        return;
                    }

                    JsonElement payload = response.getPayload();

                    if (payload.isJsonObject()) {
                        User user = gson.fromJson(payload, User.class);
                        TcpConnection.setCurrentUserId(user.getId());
                        TcpConnection.setCurrentUsername(user.getUsername());
                        TcpConnection.setCurrentEmail(user.getEmail());

                        SharedPreferences.Editor editor = preferences.edit();
                        if (keepSignedIn) {
                            editor.putString("email", email);
                            editor.putString("password", password);
                        } else {
                            editor.remove("email");
                            editor.remove("password");
                        }
                        editor.apply();

                        if (user.getIdentityKey() == null) {
                            generateAndPublishKeys(user.getId());
                        }

                        sendFcmTokenToServer(user.getId());

                        startActivity(new Intent(this, MainActivity.class));
                        finish();

                    } else {
                        String error = gson.fromJson(payload, String.class);
                        TcpConnection.close();

                        if ("NOT_CONFIRMED".equals(error)) {
                            showSnackbar("Account not confirmed. Check your email.");
                        } else if ("RATE_LIMITED".equals(error)) {
                            showSnackbar("Too many attempts. Try again in 15 minutes.");
                        } else {
                            showSnackbar("Login failed");
                        }
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Connection error during login", e);
                TcpConnection.close();
                runOnUiThread(() -> {
                    setButtonsEnabled(true);
                    showSnackbar("Connection Error: " + e.getMessage());
                });
            }
        }).start();
    }

    private void sendFcmTokenToServer(int userId) {
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> {
                    Log.i(TAG, "[FCM] Token obtained, sending to server...");
                    NetworkPacket packet = new NetworkPacket(
                            PacketType.REGISTER_FCM_TOKEN,
                            userId,
                            token
                    );
                    TcpConnection.sendPacket(packet);
                })
                .addOnFailureListener(e ->
                        Log.w(TAG, "[FCM] Failed to get FCM token: " + e.getMessage())
                );
    }

    public void handleRegister(View view) {
        startActivity(new Intent(this, RegisterActivity.class));
    }

    private void generateAndPublishKeys(int userId) {
        new Thread(() -> {
            try {
                ClientKeyManager keyManager = new ClientKeyManager(this, userId);

                KeyPair identityKP = CryptoHelper.generateDilithiumKeys();
                KeyPair preKeyKP = CryptoHelper.generateKyberKeys();

                String ikPub = Base64.encodeToString(identityKP.getPublic().getEncoded(), Base64.NO_WRAP);
                String ikPriv = Base64.encodeToString(identityKP.getPrivate().getEncoded(), Base64.NO_WRAP);
                String spkPub = Base64.encodeToString(preKeyKP.getPublic().getEncoded(), Base64.NO_WRAP);
                String spkPriv = Base64.encodeToString(preKeyKP.getPrivate().getEncoded(), Base64.NO_WRAP);

                byte[] sigBytes = CryptoHelper.signData(identityKP.getPrivate(), preKeyKP.getPublic().getEncoded());
                String sigB64 = Base64.encodeToString(sigBytes, Base64.NO_WRAP);

                keyManager.saveMyIdentityKeys(ikPub, ikPriv, spkPub, spkPriv);

                ChatDtos.PublishKeysDto dto = new ChatDtos.PublishKeysDto(ikPub, spkPub, sigB64);
                TcpConnection.sendPacket(new NetworkPacket(PacketType.PUBLISH_KEYS, userId, dto));

                Log.i(TAG, "Keys generated and published for user " + userId);
            } catch (Exception e) {
                Log.e(TAG, "Error generating keys", e);
            }
        }).start();
    }

    private void setButtonsEnabled(boolean enabled) {
        findViewById(R.id.button).setEnabled(enabled);
        findViewById(R.id.button2).setEnabled(enabled);
    }

    private void showSnackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG)
                .setBackgroundTint(Color.RED)
                .setTextColor(Color.WHITE)
                .show();
    }
}


