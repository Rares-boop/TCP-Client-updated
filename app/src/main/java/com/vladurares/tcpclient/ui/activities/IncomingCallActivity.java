package com.vladurares.tcpclient.ui.activities;

import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.vladurares.tcpclient.R;
import com.vladurares.tcpclient.network.PacketRouter;
import com.vladurares.tcpclient.network.TcpConnection;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_incoming_call);

        callerId = getIntent().getIntExtra("CALLER_ID", -1);
        callerName = getIntent().getStringExtra("CALLER_NAME");
        chatId = getIntent().getIntExtra("CHAT_ID", -1);
        serverIp = getIntent().getStringExtra("SERVER_IP");
        isAudio = getIntent().getBooleanExtra("IS_AUDIO", true);

        TextView txtName = findViewById(R.id.txtCallerName);
        txtName.setText(callerName != null ? callerName : "Unknown Caller");

        startRinging();

        findViewById(R.id.btnAnswer).setOnClickListener(v -> answerCall());
        findViewById(R.id.btnDecline).setOnClickListener(v -> declineCall());

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
        sendTcpResponse(PacketType.CALL_DENY);

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
