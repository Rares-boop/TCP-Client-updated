package com.vladurares.tcpclient.ui.activities;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.vladurares.tcpclient.R;
import com.vladurares.tcpclient.network.GlobalPacketHandlers;
import com.vladurares.tcpclient.network.PacketRouter;
import com.vladurares.tcpclient.network.TcpConnection;
import com.vladurares.tcpclient.storage.SecureStorage;
import com.vladurares.tcpclient.utils.ConfigReader;

import chat.models.User;
import chat.network.ChatDtos;
import chat.network.NetworkPacket;
import chat.network.PacketType;


public class IncomingCallActivity extends AppCompatActivity {
    private Ringtone ringtone;
    private Vibrator vibrator;
    private int callerId;
    private String callerName;
    private int chatId;
    private String serverIp;
    private boolean isAudio;
    private static final String TAG = "IncomingCallActivity";
    private final com.google.gson.Gson gson = new com.google.gson.Gson();
    private final PacketRouter.PacketCallback onCallEnd = this::handleCallEnd;
    private final PacketRouter.PacketCallback onDeleteChat = this::handleDeleteChat;
    private final PacketRouter.PacketCallback onRenameChat = this::handleRenameChat;
    private boolean fromFcm = false;
    private volatile boolean tcpReady = false;
    private volatile boolean reconnecting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_incoming_call);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            );
        }

        callerId = getIntent().getIntExtra("CALLER_ID", -1);
        callerName = getIntent().getStringExtra("CALLER_NAME");
        chatId = getIntent().getIntExtra("CHAT_ID", -1);
        serverIp = getIntent().getStringExtra("SERVER_IP");
        isAudio = getIntent().getBooleanExtra("IS_AUDIO", true);
        fromFcm = getIntent().getBooleanExtra("FROM_FCM", false);

        if (fromFcm) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(999);

            ConfigReader config = new ConfigReader(this);
            serverIp = config.getServerIp();

            startBackgroundReconnect();
        } else {
            tcpReady = true;
        }

        TextView txtName = findViewById(R.id.txtCallerName);
        txtName.setText(callerName != null ? callerName : "Unknown Caller");

        startRinging();

        findViewById(R.id.btnAnswer).setOnClickListener(v -> answerCall());
        findViewById(R.id.btnDecline).setOnClickListener(v -> declineCall());

    }

    private void startBackgroundReconnect() {
        if (reconnecting) return;
        reconnecting = true;

        new Thread(() -> {
            try {
                var prefs = SecureStorage.getEncryptedPrefs(getApplicationContext());
                String email = prefs.getString("email", null);
                String password = prefs.getString("password", null);

                if (email == null || password == null) {
                    Log.e(TAG, "[FCM-CALL] No saved credentials. Can't reconnect.");
                    return;
                }

                ConfigReader config = new ConfigReader(this);

                TcpConnection.close();

                TcpConnection.connect(this, config.getServerIp(), config.getServerPort());

                ChatDtos.AuthDto auth = new ChatDtos.AuthDto(email, password);
                TcpConnection.sendPacket(new NetworkPacket(PacketType.LOGIN_REQUEST, 0, auth));

                NetworkPacket response = TcpConnection.readNextPacket();

                if (response != null && response.getType() == PacketType.LOGIN_RESPONSE
                        && response.getPayload().isJsonObject()) {

                    User user = gson.fromJson(response.getPayload(), User.class);
                    TcpConnection.setCurrentUserId(user.getId());
                    TcpConnection.setContext(getApplicationContext());
                    TcpConnection.setPacketListener(PacketRouter.getInstance());
                    TcpConnection.startReading();
                    GlobalPacketHandlers.register(this);

                    tcpReady = true;
                    Log.i(TAG, "[FCM-CALL] TCP reconnected. Ready to accept call.");
                } else {
                    Log.e(TAG, "[FCM-CALL] Login failed during call reconnect.");
                }

            } catch (Exception e) {
                Log.e(TAG, "[FCM-CALL] Reconnect failed", e);
            } finally {
                reconnecting = false;
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PacketRouter r = PacketRouter.getInstance();
        r.on(PacketType.CALL_END, onCallEnd);
        r.on(PacketType.DELETE_CHAT_BROADCAST, onDeleteChat);
        r.on(PacketType.RENAME_CHAT_BROADCAST, onRenameChat);
    }

    @Override
    protected void onPause() {
        super.onPause();
        PacketRouter r = PacketRouter.getInstance();
        r.off(PacketType.CALL_END, onCallEnd);
        r.off(PacketType.DELETE_CHAT_BROADCAST, onDeleteChat);
        r.off(PacketType.RENAME_CHAT_BROADCAST, onRenameChat);
    }

    private void handleCallEnd(NetworkPacket packet) {
        runOnUiThread(() -> {
            android.widget.Toast.makeText(this, "Call cancelled by caller.", android.widget.Toast.LENGTH_SHORT).show();
            stopRinging();
            finish();
        });
    }

    private void handleDeleteChat(NetworkPacket packet) {
        runOnUiThread(() -> {
            int deletedChatId = gson.fromJson(packet.getPayload(), Integer.class);
            if (deletedChatId == chatId) {
                android.widget.Toast.makeText(this, "Chat was deleted! Cancelling call.", android.widget.Toast.LENGTH_SHORT).show();
                stopRinging();
                finish();
            }
        });
    }

    private void handleRenameChat(NetworkPacket packet) {
        runOnUiThread(() -> {
            ChatDtos.RenameGroupDto renameDto = gson.fromJson(packet.getPayload(), ChatDtos.RenameGroupDto.class);
            if (renameDto.chatId == chatId) {
                callerName = renameDto.newName;
                TextView txtName = findViewById(R.id.txtCallerName);
                txtName.setText(callerName);
                Log.i(TAG, "New name: " + callerName);
            }
        });
    }

    private void startRinging() {
        try {
            Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtone = RingtoneManager.getRingtone(getApplicationContext(), notification);
            ringtone.play();

            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) {
                long[] pattern = {0, 1000, 1000};
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start ringtone or vibrator", e);
        }
    }

    private void stopRinging() {
        if (ringtone != null && ringtone.isPlaying()) {
            ringtone.stop();
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
    }

    private void answerCall() {
        stopRinging();

        if (tcpReady) {
            doAcceptCall();
        } else {
            Toast.makeText(this, "Connecting...", Toast.LENGTH_SHORT).show();
            findViewById(R.id.btnAnswer).setEnabled(false);

            new Thread(() -> {
                for (int i = 0; i < 30 && !tcpReady; i++) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                }

                runOnUiThread(() -> {
                    if (tcpReady) {
                        doAcceptCall();
                    } else {
                        Toast.makeText(this, "Connection failed. Try again.", Toast.LENGTH_LONG).show();
                        findViewById(R.id.btnAnswer).setEnabled(true);
                    }
                });
            }).start();
        }
    }

    private void doAcceptCall() {
        sendTcpResponse(PacketType.CALL_ACCEPT);

        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra("TARGET_USER_ID", callerId);
        intent.putExtra("CHAT_ID", chatId);
        intent.putExtra("USERNAME", callerName);
        intent.putExtra("SERVER_IP", serverIp);
        intent.putExtra("MY_USER_ID", TcpConnection.getCurrentUserId());
        intent.putExtra("IS_AUDIO", isAudio);

        startActivity(intent);
        finish();
    }

    private void declineCall() {
        stopRinging();

        if (tcpReady) {
            sendTcpResponse(PacketType.CALL_DENY);
        } else {
            Log.w(TAG, "Declined call before TCP reconnect. Caller will timeout.");
        }

        finish();
    }

    private void sendTcpResponse(PacketType type) {
        TcpConnection.sendPacket(
                new NetworkPacket(type, TcpConnection.getCurrentUserId(), callerId)
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRinging();
    }
}
