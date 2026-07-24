package com.vladurares.tcpclient.ui.activities;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.messaging.FirebaseMessaging;
import com.vladurares.tcpclient.network.GlobalPacketHandlers;
import com.vladurares.tcpclient.network.PacketRouter;
import com.vladurares.tcpclient.storage.ProfilePictureCache;
import com.vladurares.tcpclient.utils.ClientKeyManager;
import com.vladurares.tcpclient.utils.ConfigReader;
import com.vladurares.tcpclient.ui.adapters.ConversationAdapter;
import com.vladurares.tcpclient.storage.LocalStorage;
import com.vladurares.tcpclient.R;
import com.vladurares.tcpclient.storage.SecureStorage;
import com.vladurares.tcpclient.network.TcpConnection;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;

import chat.models.GroupChat;
import chat.models.User;
import chat.network.ChatDtos;
import chat.network.NetworkPacket;
import chat.network.PacketType;


public class MainActivity extends AppCompatActivity {
    RecyclerView recyclerView;
    ConversationAdapter adapter;
    private final Gson gson = new Gson();
    AlertDialog dialog;
    private static final String TAG = "MainActivity";
    private final PacketRouter.PacketCallback onChats = this::handleGetChats;
    private final PacketRouter.PacketCallback onCreate = this::handleCreateBroadcast;
    private final PacketRouter.PacketCallback onRename = this::handleRenameBroadcast;
    private final PacketRouter.PacketCallback onDelete = this::handleDeleteBroadcast;
    private final PacketRouter.PacketCallback onPartnerPics = this::handlePartnerPictures;
    private final PacketRouter.PacketCallback onMyProfilePic = this::handleMyProfilePic;
    private final PacketRouter.PacketCallback onInviteReceived = this::handleInviteReceived;
    private final PacketRouter.PacketCallback onPendingCount = this::handlePendingCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.loginLayout), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        TcpConnection.setContext(this);

        List<String> permissionsToRequest = new ArrayList<>();
        permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO);
        permissionsToRequest.add(android.Manifest.permission.CAMERA);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }

        String[] requiredPermissions = permissionsToRequest.toArray(new String[0]);

        boolean needsPermissions = false;
        for (String permission : requiredPermissions) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, permission)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                needsPermissions = true;
                break;
            }
        }

        if (needsPermissions) {
            androidx.core.app.ActivityCompat.requestPermissions(this, requiredPermissions, 100);
        }

        recyclerView = findViewById(R.id.recyclerViewConversations);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new ConversationAdapter(
                this,
                LocalStorage.getCurrentUserGroupChats(),
                this::handleChatClick,
                this::handleLongChatClick
        );
        recyclerView.setAdapter(adapter);
        ProfilePictureCache.loadFromDisk(this);

        findViewById(R.id.btnProfile).setOnClickListener(v -> {
            Intent profileIntent = new Intent(this, ProfileActivity.class);
            profileIntent.putExtra("USERNAME", TcpConnection.getCurrentUsername());
            profileIntent.putExtra("EMAIL", TcpConnection.getCurrentEmail());
            profileIntent.putExtra("USER_ID", TcpConnection.getCurrentUserId());
            startActivity(profileIntent);
        });

        findViewById(R.id.btnInvites).setOnClickListener(v -> {
            startActivity(new Intent(this, PendingInvitesActivity.class));
        });

        String myPic = TcpConnection.getCurrentProfilePic();
        if (myPic != null && !myPic.isEmpty()) {
            showHeaderAvatar(myPic);
        }

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (dialog != null && dialog.isShowing()) {
                    Toast.makeText(MainActivity.this, "Please finish the current action before exiting!", Toast.LENGTH_SHORT).show();
                } else {
                    handleLogout();
                }
            }
        });

        TcpConnection.setPacketListener(PacketRouter.getInstance());
        GlobalPacketHandlers.register(this);

        Socket s = TcpConnection.socket;
        if (s != null && !s.isClosed() && s.isConnected()) {
            TcpConnection.startReading();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        PacketRouter r = PacketRouter.getInstance();
        r.on(PacketType.GET_CHATS_RESPONSE, onChats);
        r.on(PacketType.CREATE_CHAT_BROADCAST, onCreate);
        r.on(PacketType.RENAME_CHAT_BROADCAST, onRename);
        r.on(PacketType.DELETE_CHAT_BROADCAST, onDelete);
        r.on(PacketType.GET_PARTNERS_PICTURES_RESPONSE, onPartnerPics);
        r.on(PacketType.GET_PROFILE_PICTURE_RESPONSE, onMyProfilePic);
        r.on(PacketType.CHAT_INVITE_RECEIVED, onInviteReceived);
        r.on(PacketType.GET_PENDING_INVITES_RESPONSE, onPendingCount);

        Socket socket = TcpConnection.socket;
        if (socket == null || socket.isClosed() || !socket.isConnected()) {
            attemptAutoReconnect();
        } else {
            refreshConversations();
        }

        String myPic = TcpConnection.getCurrentProfilePic();
        if (myPic != null && !myPic.isEmpty()) {
            showHeaderAvatar(myPic);
        }

    }

    @Override
    protected void onPause() {
        super.onPause();

        PacketRouter r = PacketRouter.getInstance();
        r.off(PacketType.GET_CHATS_RESPONSE, onChats);
        r.off(PacketType.CREATE_CHAT_BROADCAST, onCreate);
        r.off(PacketType.RENAME_CHAT_BROADCAST, onRename);
        r.off(PacketType.DELETE_CHAT_BROADCAST, onDelete);
        r.off(PacketType.GET_PARTNERS_PICTURES_RESPONSE, onPartnerPics);
        r.off(PacketType.GET_PROFILE_PICTURE_RESPONSE, onMyProfilePic);
        r.off(PacketType.CHAT_INVITE_RECEIVED, onInviteReceived);
        r.off(PacketType.GET_PENDING_INVITES_RESPONSE, onPendingCount);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            TcpConnection.stopReading();
            TcpConnection.close();
        } catch (Exception e) {
            Log.e(TAG, "Error during onDestroy cleanup", e);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void handleGetChats(NetworkPacket packet) {
        runOnUiThread(() -> {
            try {
                Type listType = new TypeToken<List<GroupChat>>(){}.getType();
                List<GroupChat> groupChats = gson.fromJson(packet.getPayload(), listType);

                if (groupChats == null) groupChats = new ArrayList<>();

                LocalStorage.setCurrentUserGroupChats(groupChats);
                adapter.setGroupChats(groupChats);
                adapter.notifyDataSetChanged();

                List<Integer> chatIds = new ArrayList<>();
                for (GroupChat chat : groupChats) {
                    chatIds.add(chat.getId());
                }
                if (!chatIds.isEmpty()) {
                    TcpConnection.sendPacket(new NetworkPacket(
                            PacketType.GET_PARTNERS_PICTURES_REQUEST,
                            TcpConnection.getCurrentUserId(),
                            chatIds
                    ));
                }

                TcpConnection.sendPacket(new NetworkPacket(
                        PacketType.GET_PROFILE_PICTURE_REQUEST,
                        TcpConnection.getCurrentUserId(),
                        TcpConnection.getCurrentUserId()
                ));

                TcpConnection.sendPacket(new NetworkPacket(
                        PacketType.GET_PENDING_INVITES_REQUEST,
                        TcpConnection.getCurrentUserId(),
                        TcpConnection.getCurrentUserId()
                ));

                int openChatId = getIntent().getIntExtra("OPEN_CHAT_ID", -1);
                if (openChatId > 0) {
                    getIntent().removeExtra("OPEN_CHAT_ID");
                    for (GroupChat chat : groupChats) {
                        if (chat.getId() == openChatId) {
                            handleChatClick(chat);
                            break;
                        }
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Failed to parse chat list from server.", e);
            }
        });
    }

    @SuppressLint("NotifyDataSetChanged")
    private void handleCreateBroadcast(NetworkPacket packet) {
        runOnUiThread(() -> {
            if (adapter == null) return;
            adapter.setEnabled(true);
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
            adapter.setGroupChats(LocalStorage.getCurrentUserGroupChats());
            adapter.notifyDataSetChanged();
            recyclerView.scrollToPosition(0);
        });
    }

    @SuppressLint("NotifyDataSetChanged")
    private void handleRenameBroadcast(NetworkPacket packet) {
        runOnUiThread(() -> {
            adapter.setGroupChats(LocalStorage.getCurrentUserGroupChats());
            adapter.notifyDataSetChanged();
        });
    }

    @SuppressLint("NotifyDataSetChanged")
    private void handleDeleteBroadcast(NetworkPacket packet) {
        runOnUiThread(() -> {
            adapter.setGroupChats(LocalStorage.getCurrentUserGroupChats());
            adapter.notifyDataSetChanged();
        });
    }

    private void refreshConversations() {
        NetworkPacket req = new NetworkPacket(PacketType.GET_CHATS_REQUEST, TcpConnection.getCurrentUserId());
        TcpConnection.sendPacket(req);
    }

    private void performRename(GroupChat chat, String newName) {
        ChatDtos.RenameGroupDto dto = new ChatDtos.RenameGroupDto(chat.getId(), newName);
        NetworkPacket packet = new NetworkPacket(PacketType.RENAME_CHAT_REQUEST, TcpConnection.getCurrentUserId(), dto);
        TcpConnection.sendPacket(packet);
    }

    private void performDelete(GroupChat chat) {
        NetworkPacket packet = new NetworkPacket(PacketType.DELETE_CHAT_REQUEST, TcpConnection.getCurrentUserId(), chat.getId());
        TcpConnection.sendPacket(packet);
    }

    private void performLogout() {
        NetworkPacket p = new NetworkPacket(PacketType.LOGOUT, TcpConnection.getCurrentUserId());
        TcpConnection.sendPacket(p);

        new android.os.Handler().postDelayed(() -> {
            TcpConnection.stopReading();
            TcpConnection.close();
            runOnUiThread(() -> {
                LocalStorage.setCurrentUserGroupChats(new ArrayList<>());
                SharedPreferences prefs = SecureStorage.getEncryptedPrefs(MainActivity.this);
                prefs.edit().clear().apply();

                ProfilePictureCache.clear(MainActivity.this);
                TcpConnection.setCurrentProfilePic(null);

                goToLogin();
            });
        }, 300);
    }

    public void handleChatClick(GroupChat chat) {
        ClientKeyManager keyMgr = new ClientKeyManager(this, TcpConnection.getCurrentUserId());
        SecretKey key = keyMgr.getKey(chat.getId());
        if (key == null) {
            Toast.makeText(this, "Key exchange in progress, please wait...", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, ConversationActivity.class);
        intent.putExtra("CHAT_ID", chat.getId());
        intent.putExtra("CHAT_NAME", chat.getName());
        startActivity(intent);
    }

    public void handleLongChatClick(GroupChat chat) {
        String[] options = {"Rename ", "Delete "};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.dialog_option_item, options);
        new AlertDialog.Builder(MainActivity.this, R.style.DialogSmecher)
                .setTitle(chat.getName())
                .setAdapter(adapter, (dialog, which) -> {
                    if (which == 0) renameChat(chat);
                    else deleteChat(chat);
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.cancel())
                .show();
    }

    public void renameChat(GroupChat chat) {
        EditText input = new EditText(this);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.LTGRAY);
        input.setHint("Enter the new name ");
        new AlertDialog.Builder(this, R.style.DialogSmecher)
                .setTitle("Rename " + chat.getName())
                .setView(input)
                .setPositiveButton("Save ", (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (!newName.isEmpty()) performRename(chat, newName);
                })
                .setNegativeButton("Cancel ", (dialog, which) -> dialog.cancel())
                .show();
    }

    public void deleteChat(GroupChat chat) {
        new AlertDialog.Builder(this, R.style.DialogSmecher)
                .setTitle("Delete chat ")
                .setMessage("Are you sure you want to delete \"" + chat.getName() + "\"?")
                .setPositiveButton("Delete ", (dialog, which) -> performDelete(chat))
                .setNegativeButton("Cancel ", (dialog, which) -> dialog.cancel())
                .show();
    }

    public void handleAddConversation(View view) {
        startActivity(new Intent(this, NewChatActivity.class));
    }

    public void handleLogout() {
        if (adapter != null) adapter.setEnabled(false);
        AlertDialog d = new AlertDialog.Builder(MainActivity.this, R.style.DialogSmecher)
                .setTitle("Do you wish to logout ")
                .setNegativeButton("NO ", (dialog, which) -> { if (adapter != null) adapter.setEnabled(true); dialog.cancel(); })
                .setPositiveButton("YES ", (dialog, which) -> performLogout()).create();
        d.setCancelable(false);
        d.show();
    }

    private void goToLogin() {
        Intent intent = new Intent(MainActivity.this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private void attemptAutoReconnect() {
        SharedPreferences preferences = SecureStorage.getEncryptedPrefs(getApplicationContext());
        String savedEmail = preferences.getString("email", null);
        String savedPassword = preferences.getString("password", null);
        if (savedEmail == null || savedPassword == null) { goToLogin(); return; }

        new Thread(() -> {
            try {
                ConfigReader configReader = new ConfigReader(this);
                TcpConnection.connect(MainActivity.this, configReader.getServerIp(), configReader.getServerPort());

                ChatDtos.AuthDto authDto = new ChatDtos.AuthDto(savedEmail, savedPassword);
                NetworkPacket req = new NetworkPacket(PacketType.LOGIN_REQUEST, 0, authDto);
                TcpConnection.sendPacket(req);

                NetworkPacket resp = TcpConnection.readNextPacket();

                if (resp != null && resp.getType() == PacketType.LOGIN_RESPONSE) {
                    User user = gson.fromJson(resp.getPayload(), User.class);
                    if (user != null) {
                        TcpConnection.setCurrentUserId(user.getId());
                        TcpConnection.setCurrentUsername(user.getUsername());
                        TcpConnection.setCurrentEmail(user.getEmail());
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Auto-reconnected!", Toast.LENGTH_SHORT).show();

                            TcpConnection.setPacketListener(PacketRouter.getInstance());
                            TcpConnection.startReading();
                            GlobalPacketHandlers.register(this);
                            refreshConversations();

                            sendFcmTokenAfterReconnect(user.getId());
                        });
                    } else runOnUiThread(this::goToLogin);
                } else runOnUiThread(this::goToLogin);

            } catch (Exception e) {
                Log.e(TAG, "Critical error during auto-reconnect sequence.", e);
                runOnUiThread(this::goToLogin);
            }
        }).start();
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void handlePartnerPictures(NetworkPacket packet) {
        runOnUiThread(() -> {
            try {
                java.lang.reflect.Type mapType = new com.google.gson.reflect.TypeToken<java.util.Map<String, String>>(){}.getType();
                java.util.Map<String, String> raw = new com.google.gson.Gson().fromJson(packet.getPayload(), mapType);

                java.util.Map<Integer, String> parsed = new java.util.HashMap<>();
                for (java.util.Map.Entry<String, String> e : raw.entrySet()) {
                    parsed.put(Integer.parseInt(e.getKey()), e.getValue());
                }

                ProfilePictureCache.updateFromServer(this, parsed);
                adapter.notifyDataSetChanged();

                Log.i(TAG, "[CACHE] Updated " + parsed.size() + " partner profile pics.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse partner pictures", e);
            }
        });
    }

    private void showHeaderAvatar(String base64) {
        try {
            ImageView img = findViewById(R.id.imgHeaderAvatar);
            byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP);
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp != null) {
                img.setImageBitmap(bmp);
                img.setPadding(0, 0, 0, 0);
                img.setImageTintList(null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to show header avatar", e);
        }
    }

    private void handleMyProfilePic(NetworkPacket packet) {
        runOnUiThread(() -> {
            try {
                String base64 = gson.fromJson(packet.getPayload(), String.class);
                if (base64 != null && !base64.isEmpty()) {
                    TcpConnection.setCurrentProfilePic(base64);
                    showHeaderAvatar(base64);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to load own profile pic", e);
            }
        });
    }

    private void handleInviteReceived(NetworkPacket packet) {
        runOnUiThread(() -> {
            View badge = findViewById(R.id.badgeInvites);
            badge.setVisibility(View.VISIBLE);
            Toast.makeText(this, "New chat invite!", Toast.LENGTH_SHORT).show();
        });
    }

    private void handlePendingCount(NetworkPacket packet) {
        runOnUiThread(() -> {
            try {
                Type listType = new TypeToken<List<Map<String, Object>>>(){}.getType();
                List<Map<String, Object>> invites = gson.fromJson(packet.getPayload(), listType);
                View badge = findViewById(R.id.badgeInvites);
                badge.setVisibility(invites.isEmpty() ? View.GONE : View.VISIBLE);
            } catch (Exception e) {
                Log.e(TAG, "Error checking pending invites", e);
            }
        });
    }

    private void sendFcmTokenAfterReconnect(int userId) {
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> {
                    Log.i(TAG, "[FCM] Token sent after auto-reconnect.");
                    NetworkPacket packet = new NetworkPacket(
                            PacketType.REGISTER_FCM_TOKEN,
                            userId,
                            token
                    );
                    TcpConnection.sendPacket(packet);
                })
                .addOnFailureListener(e ->
                        Log.w(TAG, "[FCM] Failed to get token on reconnect: " + e.getMessage())
                );
    }
}
