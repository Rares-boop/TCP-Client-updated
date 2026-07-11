package com.vladurares.tcpclient.ui.activities;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.Patterns;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.vladurares.tcpclient.utils.ConfigReader;
import com.vladurares.tcpclient.R;
import com.vladurares.tcpclient.network.TcpConnection;
import com.google.android.material.snackbar.Snackbar;
import com.google.gson.Gson;


import chat.network.ChatDtos;
import chat.network.NetworkPacket;
import chat.network.PacketType;


public class RegisterActivity extends AppCompatActivity {
    private ConfigReader config;
    private final Gson gson = new Gson();
    private static final String TAG = "RegisterActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_register);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        config = new ConfigReader(this);

    }

    public void handleAccount(View view) {
        EditText usernameField = findViewById(R.id.editTextText);
        EditText emailField = findViewById(R.id.editTextEmail);
        EditText passwordField = findViewById(R.id.editTextTextPassword2);
        EditText confirmedPasswordField = findViewById(R.id.editTextTextPassword3);

        String username = usernameField.getText().toString().trim();
        String email = emailField.getText().toString().trim();
        String password = passwordField.getText().toString().trim();
        String confirmedPassword = confirmedPasswordField.getText().toString().trim();

        if (username.isEmpty() || password.isEmpty() || confirmedPassword.isEmpty()) {
            Toast.makeText(this, "Please fill in all fields!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "Please enter a valid email", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!password.equals(confirmedPassword)) {
            Toast.makeText(this, "Passwords do not match!", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Registering...", Toast.LENGTH_SHORT).show();
        view.setEnabled(false);

        new Thread(() -> {
            try {
                TcpConnection.close();

                TcpConnection.connect(config.getServerIp(), config.getServerPort());

                ChatDtos.AuthDto registerData = new ChatDtos.AuthDto(username, email, password);
                NetworkPacket request = new NetworkPacket(PacketType.REGISTER_REQUEST, 0, registerData);

                TcpConnection.sendPacket(request);

                NetworkPacket responsePacket = TcpConnection.readNextPacket();

                runOnUiThread(() -> {
                    view.setEnabled(true);

                    if (responsePacket != null && responsePacket.getType() == PacketType.REGISTER_RESPONSE) {
                        handleRegisterResponse(responsePacket, email);
                    } else {
                        showSnackbar("Server Error: Invalid response.");
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Connection error during registration", e);
                TcpConnection.close();
                runOnUiThread(() -> {
                    view.setEnabled(true);
                    showSnackbar("Connection Error: " + e.getMessage());
                });
            }
        }).start();
    }

    private void handleRegisterResponse(NetworkPacket packet, String email) {
        String response = gson.fromJson(packet.getPayload(), String.class);

        switch (response) {
            case "CHECK_EMAIL":
                Toast.makeText(this, "Verifică emailul pentru cod!", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(this, ConfirmEmailActivity.class);
                intent.putExtra("EMAIL", email);
                startActivity(intent);
                finish();
                break;

            case "EXISTS":
                showSnackbar("Un cont cu acest email există deja.");
                break;

            case "FAIL":
                showSnackbar("Eroare la înregistrare. Încearcă din nou.");
                break;

            default:
                showSnackbar(response);
                break;
        }
    }

    private void showSnackbar(String message) {
        Snackbar.make(
                findViewById(android.R.id.content),
                message,
                Snackbar.LENGTH_LONG
        ).setBackgroundTint(Color.RED).setTextColor(Color.WHITE).show();
    }
}

