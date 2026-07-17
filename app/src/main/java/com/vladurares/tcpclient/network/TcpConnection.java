package com.vladurares.tcpclient.network;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.vladurares.tcpclient.R;
import com.vladurares.tcpclient.utils.ConfigReader;
import com.vladurares.tcpclient.ui.activities.IncomingCallActivity;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.security.PublicKey;
import java.util.Base64;

import chat.network.ChatDtos;
import chat.network.NetworkPacket;
import chat.network.PacketType;
import crypto.api.CryptoHelper;
import tcpsecure.protocol.SecureSocket;


public class TcpConnection {
    public static Socket socket;
    private static int currentUserId;
    private static Context appContext;
    private static final String TAG = "TcpConnection";
    private static final Object writeLock = new Object();

    public interface PacketListener {
        void onPacketReceived(NetworkPacket packet);
    }
    private static PacketListener currentListener;
    private static Thread readingThread;
    private static volatile boolean isReading = false;
    private static final java.util.concurrent.ConcurrentLinkedQueue<NetworkPacket> packetBuffer =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public static void setPacketListener(PacketListener listener) {
        currentListener = listener;
        if (listener != null) {
            NetworkPacket buffered;
            while ((buffered = packetBuffer.poll()) != null) {
                listener.onPacketReceived(buffered);
            }
        }
        Log.d(TAG, "Packet listener set: " + (listener == null ? "NULL" : listener.getClass().getSimpleName()));
    }

    public static void setContext(Context context) {
        appContext = context.getApplicationContext();
    }

    public static void startReading() {
        if (isReading) return;
        isReading = true;

        readingThread = new Thread(() -> {
            Log.d(TAG, "Network reading thread STARTED.");
            try {
                while (isReading && socket != null && !socket.isClosed()) {
                    NetworkPacket packet = readNextPacket();

                    if (packet == null) {
                        Log.e(TAG, "Received NULL packet. Connection is likely dead.");
                        close();
                        break;
                    }

                    if (packet.getType() == PacketType.CALL_REQUEST) {
                        handleIncomingCall(packet);
                        continue;
                    }

                    if (currentListener != null) {
                        currentListener.onPacketReceived(packet);
                    } else {
                        packetBuffer.add(packet);
                        Log.w(TAG, "Packet ignored (no active listener for type): " + packet.getType());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Critical error in reading thread", e);
                close();
            }
        });
        readingThread.start();
    }
    private static void handleIncomingCall(NetworkPacket packet) {
        if (appContext == null) {
            return;
        }
        int callerId = packet.getSenderId();

        ChatDtos.CallRequestDto incomingDto = new Gson().fromJson(packet.getPayload(), ChatDtos.CallRequestDto.class);
        int foundChatId = incomingDto.chatId;

        String callerName = incomingDto.callerName != null ? incomingDto.callerName : "User " + callerId;
        Log.d(TAG, "Incoming Call from " + callerId + ". Chat ID found: " + foundChatId);

        String serverIp = "127.0.0.1";
        try {
            if (socket != null) {
                serverIp = socket.getInetAddress().getHostAddress();
            }
            else{
                ConfigReader configReader = new ConfigReader(appContext);
                serverIp = configReader.getServerIp();
            }
        } catch (Exception e) {Log.w(TAG, "Failed to resolve server IP for call UI.");}

        Intent intent = new Intent(appContext, IncomingCallActivity.class);

        intent.putExtra("CALLER_ID", callerId);
        intent.putExtra("CALLER_NAME", callerName);
        intent.putExtra("CHAT_ID", foundChatId);
        intent.putExtra("SERVER_IP", serverIp);

        intent.putExtra("IS_AUDIO", incomingDto.isAudio);

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        appContext.startActivity(intent);
    }

    public static void stopReading() {
        isReading = false;
        if(readingThread != null){
            readingThread.interrupt();
        }
    }

    public static void connect(Context context, String host, int port) throws Exception {
        PublicKey serverPub = loadPinnedKey(context);
        socket = new SecureSocket(host, port, serverPub);
        socket.setTcpNoDelay(true);
        out = new PrintWriter(socket.getOutputStream(), true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
    }

    private static PublicKey loadPinnedKey(Context context) {
        try {
            InputStream is = context.getResources().openRawResource(R.raw.server_dilithium);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int len;
            while ((len = is.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            is.close();
            byte[] keyBytes = bos.toByteArray();
            String keyBase64 = Base64.getEncoder().encodeToString(keyBytes);
            return CryptoHelper.stringToDilithiumPublic(keyBase64);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load pinned server key", e);
        }
    }

    private static PrintWriter out;
    private static BufferedReader in;

    private static final java.util.concurrent.ExecutorService sendExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    public static void sendPacket(NetworkPacket packet) {
        sendExecutor.submit(() -> {
            try {
                synchronized (writeLock) {
                    if (socket != null && !socket.isClosed()) {
                        out.println(packet.toJson());
                        out.flush();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to send packet of type: " + packet.getType(), e);
            }
        });
    }

    public static NetworkPacket readNextPacket() throws Exception {
        String jsonRaw = in.readLine();
        if (jsonRaw == null) return null;
        return NetworkPacket.fromJson(jsonRaw);
    }

    public static void close() {
        try {
            isReading = false;
            if (out != null) { out.close(); out = null; }
            if (in != null) { in.close(); in = null; }
            if (socket != null) { socket.close(); socket = null; }
            Log.i(TAG, "TCP Connection closed and resources released.");
        } catch (IOException e) {
            Log.e(TAG, "Error during connection shutdown", e);
        }
    }
    public static void setCurrentUserId(int id) {
        currentUserId = id;
    }
    public static int getCurrentUserId() {
        return currentUserId;
    }
    public static java.net.Socket getSocket() {
        return socket;
    }
}

