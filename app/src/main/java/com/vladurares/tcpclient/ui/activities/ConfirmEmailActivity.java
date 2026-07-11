package com.vladurares.tcpclient.ui.activities;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.vladurares.tcpclient.R;
import com.vladurares.tcpclient.network.TcpConnection;
import com.vladurares.tcpclient.utils.ConfigReader;
import com.google.android.material.snackbar.Snackbar;
import com.google.gson.Gson;

import chat.network.NetworkPacket;
import chat.network.PacketType;

public class ConfirmEmailActivity extends AppCompatActivity {
    private ConfigReader config;
    private final Gson gson = new Gson();
    private static final String TAG = "ConfirmEmailActivity";
    private String email;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_confirm_email);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.confirmLayout), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        config = new ConfigReader(this);
        email = getIntent().getStringExtra("EMAIL");
    }

    public void handleConfirm(View view) {
        EditText codeField = findViewById(R.id.editTextCode);
        String code = codeField.getText().toString().trim();

        if (code.isEmpty() || code.length() != 6) {
            Toast.makeText(this, "Enter the 6 digits code", Toast.LENGTH_SHORT).show();
            return;
        }

        view.setEnabled(false);
        Toast.makeText(this, "Checking code", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                if (TcpConnection.getSocket() == null || TcpConnection.getSocket().isClosed()) {
                    TcpConnection.connect(config.getServerIp(), config.getServerPort());
                }

                TcpConnection.sendPacket(new NetworkPacket(PacketType.CONFIRM_EMAIL_REQUEST, 0, code));

                NetworkPacket response = TcpConnection.readNextPacket();

                runOnUiThread(() -> {
                    view.setEnabled(true);

                    if (response == null || response.getType() != PacketType.CONFIRM_EMAIL_RESPONSE) {
                        showSnackbar("Server communication error");
                        return;
                    }

                    String result = gson.fromJson(response.getPayload(), String.class);

                    if ("OK".equals(result)) {
                        Toast.makeText(this, "Account confirmed. You can now log in", Toast.LENGTH_LONG).show();
                        TcpConnection.close();
                        startActivity(new Intent(this, LoginActivity.class));
                        finish();
                    } else {
                        showSnackbar("Invalid code. Try again ");
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error confirming email", e);
                runOnUiThread(() -> {
                    view.setEnabled(true);
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
