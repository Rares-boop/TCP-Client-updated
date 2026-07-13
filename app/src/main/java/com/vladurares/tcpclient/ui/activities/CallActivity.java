package com.vladurares.tcpclient.ui.activities;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.vladurares.tcpclient.network.PacketRouter;
import com.vladurares.tcpclient.network.VideoCallManager;
import com.vladurares.tcpclient.utils.ClientKeyManager;
import com.vladurares.tcpclient.R;
import com.vladurares.tcpclient.network.VoiceCallManager;
import com.vladurares.tcpclient.network.TcpConnection;
import com.vladurares.tcpclient.utils.ConfigReader;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import javax.crypto.SecretKey;

import chat.network.ChatDtos;
import chat.network.NetworkPacket;
import chat.network.PacketType;

public class CallActivity extends AppCompatActivity {
    private VoiceCallManager voiceManager;
    private VideoCallManager videoManager;
    private DatagramSocket audioSocket;
    private DatagramSocket videoSocket;
    private int UDP_SERVER_AUDIO_PORT;
    private int UDP_SERVER_VIDEO_PORT;
    private int targetUserId;
    private int currentChatId;
    private String serverIp;
    private androidx.camera.lifecycle.ProcessCameraProvider cameraProvider;
    private volatile boolean isCallActive = true;
    private final com.google.gson.Gson gson = new com.google.gson.Gson();
    private ClientKeyManager keyManager;
    private final PacketRouter.PacketCallback onCallEnd = this::handleCallEnd;
    private final PacketRouter.PacketCallback onCallDeny = this::handleCallDeny;
    private final PacketRouter.PacketCallback onDeleteChat = this::handleDeleteChat;
    private final PacketRouter.PacketCallback onRenameChat = this::handleRenameChat;

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        targetUserId = getIntent().getIntExtra("TARGET_USER_ID", -1);
        currentChatId = getIntent().getIntExtra("CHAT_ID", -1);
        String username = getIntent().getStringExtra("USERNAME");
        serverIp = getIntent().getStringExtra("SERVER_IP");
        int myUserId = getIntent().getIntExtra("MY_USER_ID", -1);

        ConfigReader configReader = new ConfigReader(this);
        UDP_SERVER_AUDIO_PORT = configReader.getUdpAudioPort();
        UDP_SERVER_VIDEO_PORT = configReader.getUdpVideoPort();

        TextView txtName = findViewById(R.id.txtCallName);
        txtName.setText(username);

        startNetworkStack(myUserId, targetUserId);

        FloatingActionButton btnEndCall = findViewById(R.id.btnEndCall);
        btnEndCall.setOnClickListener(v -> hangUp());

        boolean[] isMuted = {false};
        FloatingActionButton btnMute = findViewById(R.id.btnMute);
        btnMute.setOnClickListener(v -> {
            isMuted[0] = !isMuted[0];
            if (voiceManager != null) {
                voiceManager.setMuted(isMuted[0]);
            }
            btnMute.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            isMuted[0] ? 0xFF4A90D9 : 0xFF1E3A52
                    )
            );
        });

        keyManager = new ClientKeyManager(this, myUserId);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void startNetworkStack(int myId, int targetId) {
        new Thread(() -> {
            try {
                if (initUdpSockets()) {
                    isCallActive = true;
                    startPeriodicHolePunch(myId, targetId);

                    boolean isAudioOnly = getIntent().getBooleanExtra("IS_AUDIO", true);
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (!isCallActive) return;

                        initVoiceCall(myId);
                        if (!isAudioOnly) {
                            initVideoCall(myId);
                        }
                        startAudioReceiver();
                        startVideoReceiver();
                        }, 500);
                }
            } catch (Exception e) { Log.e("UDP", "Failed", e); }
        }).start();
    }

    private boolean initUdpSockets() {
        try {
            audioSocket = new DatagramSocket();
            videoSocket = new DatagramSocket();

            try { audioSocket.setSendBufferSize(256 * 1024); } catch (Exception ignored) {}
            try { videoSocket.setSendBufferSize(1024 * 1024); } catch (Exception ignored) {}
            try { audioSocket.setReceiveBufferSize(256 * 1024); } catch (Exception ignored) {}
            try { videoSocket.setReceiveBufferSize(1024 * 1024); } catch (Exception ignored) {}
            try { audioSocket.setReuseAddress(true); } catch (Exception ignored) {}
            try { videoSocket.setReuseAddress(true); } catch (Exception ignored) {}
            try { audioSocket.setTrafficClass(0xB8); } catch (Exception ignored) {}
            try { videoSocket.setTrafficClass(0x88); } catch (Exception ignored) {}

            return true;
        } catch (Exception e) {
            Log.e("UDP", "Socket init failed", e);
            return false;
        }
    }

    private void startPeriodicHolePunch(int myId, int targetId) {
        new Thread(() -> {
            try {
                ByteBuffer buffer = ByteBuffer.allocate(8);
                buffer.putInt(myId);
                buffer.putInt(targetId);
                byte[] data = buffer.array();

                InetAddress serverAddr = InetAddress.getByName(serverIp);
                DatagramPacket audioPunch = new DatagramPacket(data, data.length, serverAddr, UDP_SERVER_AUDIO_PORT);
                DatagramPacket videoPunch = new DatagramPacket(data, data.length, serverAddr, UDP_SERVER_VIDEO_PORT);

                for (int i = 0; i < 5; i++) {
                    if (audioSocket != null && !audioSocket.isClosed()) audioSocket.send(audioPunch);
                    if (videoSocket != null && !videoSocket.isClosed()) videoSocket.send(videoPunch);
                    Thread.sleep(200);
                }

                while (isCallActive) {
                    if (audioSocket != null && !audioSocket.isClosed()) {
                        audioSocket.send(audioPunch);
                    }
                    if (videoSocket != null && !videoSocket.isClosed()) {
                        videoSocket.send(videoPunch);
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception ignored) {}
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PacketRouter r = PacketRouter.getInstance();
        r.on(PacketType.CALL_END, onCallEnd);
        r.on(PacketType.CALL_DENY, onCallDeny);
        r.on(PacketType.DELETE_CHAT_BROADCAST, onDeleteChat);
        r.on(PacketType.RENAME_CHAT_BROADCAST, onRenameChat);
    }

    @Override
    protected void onPause() {
        super.onPause();
        PacketRouter r = PacketRouter.getInstance();
        r.off(PacketType.CALL_END, onCallEnd);
        r.off(PacketType.CALL_DENY, onCallDeny);
        r.off(PacketType.DELETE_CHAT_BROADCAST, onDeleteChat);
        r.off(PacketType.RENAME_CHAT_BROADCAST, onRenameChat);
    }

    private void handleCallEnd(NetworkPacket packet) {
        runOnUiThread(() -> {
            android.widget.Toast.makeText(this, "Call ended by partner.", android.widget.Toast.LENGTH_SHORT).show();
            closeCallScreen();
        });
    }

    private void handleCallDeny(NetworkPacket packet) {
        runOnUiThread(() -> {
            android.widget.Toast.makeText(this, "Call ended by partner.", android.widget.Toast.LENGTH_SHORT).show();
            closeCallScreen();
        });
    }

    private void handleDeleteChat(NetworkPacket packet) {
        runOnUiThread(() -> {
            int deletedChatId = gson.fromJson(packet.getPayload(), Integer.class);
            if (deletedChatId == currentChatId) {
                android.widget.Toast.makeText(this, "Chat was deleted! Ending call.", android.widget.Toast.LENGTH_SHORT).show();
                closeCallScreen();
            }
        });
    }

    private void handleRenameChat(NetworkPacket packet) {
        runOnUiThread(() -> {
            ChatDtos.RenameGroupDto renameDto = gson.fromJson(packet.getPayload(), ChatDtos.RenameGroupDto.class);
            if (renameDto.chatId == currentChatId) {
                TextView txtName = findViewById(R.id.txtCallName);
                if (txtName != null) {
                    txtName.setText(renameDto.newName);
                }
            }
        });
    }

    private void closeCallScreen() {
        isCallActive = false;
        if (cameraProvider != null) {
            try { cameraProvider.unbindAll(); } catch (Exception ignored) {}
        }
        if (voiceManager != null) {
            voiceManager.endCall();
        }
        if (videoManager != null) {
            videoManager.endVideo();
        }
        finish();
    }

    private void initVoiceCall(int myUserId) {
        SecretKey sessionKey = this.keyManager.getKey(currentChatId);

        if (sessionKey != null) {
            voiceManager = new VoiceCallManager(this, serverIp, myUserId, UDP_SERVER_AUDIO_PORT);
            voiceManager.startCall(targetUserId, sessionKey, audioSocket);
        } else { hangUp(); }
    }

    private void initVideoCall(int myUserId) {
        SecretKey sessionKey = this.keyManager.getKey(currentChatId);

        if (sessionKey != null) {
            findViewById(R.id.remoteVideo).setVisibility(android.view.View.VISIBLE);
            findViewById(R.id.cardPreview).setVisibility(android.view.View.VISIBLE);
            findViewById(R.id.previewView).setVisibility(android.view.View.VISIBLE);
            findViewById(R.id.cardAvatar).setVisibility(android.view.View.GONE);

            android.view.TextureView remoteVideoView = findViewById(R.id.remoteVideo);
            remoteVideoView.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull android.graphics.SurfaceTexture surface, int w, int h) {
                    android.view.Surface s = new android.view.Surface(surface);
                    videoManager = new VideoCallManager(serverIp, myUserId, s, UDP_SERVER_VIDEO_PORT);
                    videoManager.startVideo(targetUserId, sessionKey, videoSocket);

                    android.graphics.Matrix matrix = new android.graphics.Matrix();

                    matrix.postScale(1280f / w, 720f / h, w / 2f, h / 2f);
                    matrix.postRotate(270, w / 2f, h / 2f);

                    float sx = (float) w / 720f;
                    float sy = (float) h / 1280f;
                    float scale = Math.max(sx, sy);
                    matrix.postScale(scale, scale, w / 2f, h / 2f);
                    remoteVideoView.setTransform(matrix);

                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (videoManager != null) {
                            startCameraX();
                        }
                    }, 300);
                }
                @Override public void onSurfaceTextureSizeChanged(@NonNull android.graphics.SurfaceTexture s, int w, int h) {}
                @Override public boolean onSurfaceTextureDestroyed(@NonNull android.graphics.SurfaceTexture s) { return true; }
                @Override public void onSurfaceTextureUpdated(@NonNull android.graphics.SurfaceTexture s) {}
            });
        }
    }

    private void hangUp() {
        TcpConnection.sendPacket(new NetworkPacket(PacketType.CALL_END, TcpConnection.getCurrentUserId(), targetUserId));
        closeCallScreen();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isCallActive = false;
        if (voiceManager != null) {
            voiceManager.endCall();
        }
        if (videoManager != null) {
            videoManager.endVideo();
        }
        if (audioSocket != null && !audioSocket.isClosed()) {
            audioSocket.close();
        }
        if (videoSocket != null && !videoSocket.isClosed()) {
            videoSocket.close();
        }
    }

    private void startAudioReceiver() {
        new Thread(() -> {
            byte[] buffer = new byte[65000];
            while (audioSocket != null && !audioSocket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    audioSocket.receive(packet);
                    if (packet.getLength() > 8) {
                        byte[] audioEncrypted = new byte[packet.getLength() - 8];
                        System.arraycopy(buffer, 8, audioEncrypted, 0, audioEncrypted.length);
                        if (voiceManager != null) {
                            voiceManager.receiveAudioData(audioEncrypted);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private void startVideoReceiver() {
        new Thread(() -> {
            byte[] buffer = new byte[65000];
            while (videoSocket != null && !videoSocket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    videoSocket.receive(packet);
                    if (packet.getLength() >= 17) {
                        byte[] videoData = new byte[packet.getLength()];
                        System.arraycopy(buffer, 0, videoData, 0, packet.getLength());
                        if (videoManager != null) {
                            videoManager.receiveVideoSlice(videoData);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private void startCameraX() {
        com.google.common.util.concurrent.ListenableFuture<androidx.camera.lifecycle.ProcessCameraProvider> cameraProviderFuture =
                androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this);

        Executor mainExecutor = androidx.core.content.ContextCompat.getMainExecutor(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                int rotation;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    android.view.Display display = getDisplay();
                    rotation = (display != null) ? display.getRotation() : android.view.Surface.ROTATION_0;
                } else {
                    rotation = getWindowManager().getDefaultDisplay().getRotation();
                }

                androidx.camera.core.resolutionselector.ResolutionSelector resolutionSelector =
                        new androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setResolutionStrategy(new androidx.camera.core.resolutionselector.ResolutionStrategy(
                                new android.util.Size(720, 1280),
                                androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        ))
                        .build();

                androidx.camera.core.Preview preview = new androidx.camera.core.Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setTargetRotation(rotation)
                        .build();

                preview.setSurfaceProvider(
                        ((androidx.camera.view.PreviewView) findViewById(R.id.previewView)).getSurfaceProvider()
                );

                androidx.camera.core.Preview encoderPreview = new androidx.camera.core.Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setTargetRotation(rotation)
                        .build();

                android.view.Surface encoderSurface = videoManager.getEncoderSurface();
                if (encoderSurface != null) {
                    encoderPreview.setSurfaceProvider(request ->
                        request.provideSurface(
                                encoderSurface,
                                mainExecutor,
                                result -> Log.d("CAMERA", "Encoder surface result: " + result.getResultCode())
                        )
                    );
                }

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        encoderPreview
                );

                Log.d("CAMERA", "CameraX started with Surface mode!");
            } catch (Exception e) {
                Log.e("CAMERA", "CameraX failed", e);
            }
        }, mainExecutor);
    }
}

