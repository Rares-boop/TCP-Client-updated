package com.vladurares.tcpclient.network;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.vladurares.tcpclient.R;
import com.vladurares.tcpclient.ui.activities.IncomingCallActivity;
import com.vladurares.tcpclient.ui.activities.MainActivity;

import java.util.Map;

import chat.network.NetworkPacket;
import chat.network.PacketType;

public class MyFirebaseService extends FirebaseMessagingService {

    private static final String TAG = "MyFirebaseService";
    private static final String CHANNEL_MESSAGES = "tcpsecure_messages";
    private static final String CHANNEL_CALLS = "tcpsecure_calls";
    private static int notificationIdCounter = 0;

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.i(TAG, "[FCM] New token generated.");

        // Dacă TCP e activ, trimite tokenul direct
        if (TcpConnection.getSocket() != null && !TcpConnection.getSocket().isClosed()) {
            TcpConnection.sendPacket(new NetworkPacket(
                    PacketType.REGISTER_FCM_TOKEN,
                    TcpConnection.getCurrentUserId(),
                    token
            ));
        }
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        Map<String, String> data = remoteMessage.getData();
        if (data.isEmpty()) return;

        String type = data.getOrDefault("type", "NEW_MESSAGE");
        Log.i(TAG, "[FCM] Push received — type=" + type);

        switch (type) {
            case "INCOMING_CALL":
                handleIncomingCall(data);
                break;
            case "NEW_MESSAGE":
            default:
                handleNewMessage(data);
                break;
        }
    }

    private void handleIncomingCall(Map<String, String> data) {
        createCallNotificationChannel();

        if (!hasNotificationPermission()) return;

        int callerId = parseInt(data.getOrDefault("callerId", "-1"));
        String callerName = data.getOrDefault("callerName", "Unknown");
        int chatId = parseInt(data.getOrDefault("chatId", "-1"));
        boolean isAudio = Boolean.parseBoolean(data.getOrDefault("isAudio", "true"));

        // Intent pentru IncomingCallActivity cu toată metadata
        Intent callIntent = new Intent(this, IncomingCallActivity.class);
        callIntent.putExtra("CALLER_ID", callerId);
        callIntent.putExtra("CALLER_NAME", callerName);
        callIntent.putExtra("CHAT_ID", chatId);
        callIntent.putExtra("IS_AUDIO", isAudio);
        callIntent.putExtra("FROM_FCM", true);   // ◄── flag ca să știe să facă reconnect
        callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent fullScreenPending = PendingIntent.getActivity(
                this, 1, callIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Full-screen notification — comportament identic cu un apel telefonic
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_CALLS)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Incoming call")
                .setContentText(callerName + " is calling you")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setOngoing(true)
                .setFullScreenIntent(fullScreenPending, true);  // ◄── full-screen!

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            // ID fix (999) ca să putem cancela notificarea din IncomingCallActivity
            manager.notify(999, builder.build());
        }
    }
    // ════════════════════════════════════════════════════════════════════════

    private void handleNewMessage(Map<String, String> data) {
        createMessageNotificationChannel();
        if (!hasNotificationPermission()) return;

        String senderName = data.getOrDefault("senderName", "New message");
        String chatIdStr = data.getOrDefault("chatId", "-1");

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            int chatId = Integer.parseInt(chatIdStr);
            if (chatId > 0) intent.putExtra("OPEN_CHAT_ID", chatId);
        } catch (NumberFormatException ignored) {}

        PendingIntent pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_MESSAGES)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(senderName)
                .setContentText("New encrypted message")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(notificationIdCounter++, builder.build());
    }

    private void createMessageNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("New message notifications");
        NotificationManager m = getSystemService(NotificationManager.class);
        if (m != null) m.createNotificationChannel(ch);
    }

    private void createCallNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Incoming call notifications");
        NotificationManager m = getSystemService(NotificationManager.class);
        if (m != null) m.createNotificationChannel(ch);
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }
}
