package com.vladurares.tcpclient.ui.activities;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
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

import javax.crypto.SecretKey;

import chat.models.GroupChat;
import chat.models.User;
import chat.network.ChatDtos;
import chat.network.NetworkPacket;
import chat.network.PacketType;
import crypto.api.CryptoHelper;


public class MainActivity extends AppCompatActivity {
    RecyclerView recyclerView;
    ConversationAdapter adapter;
    private final Gson gson = new Gson();
    AlertDialog dialog;
    private Spinner pendingSpinner;
    private List<String> pendingRawUsers;
    private int pendingChatTargetId = -1;
    private String pendingChatName = null;
    private static final String TAG = "MainActivity";
    private final PacketRouter.PacketCallback onChats = this::handleGetChats;
    private final PacketRouter.PacketCallback onUsers = this::handleGetUsers;
    private final PacketRouter.PacketCallback onBundle = this::handleBundleResponse;
    private final PacketRouter.PacketCallback onCreate = this::handleCreateBroadcast;
    private final PacketRouter.PacketCallback onRename = this::handleRenameBroadcast;
    private final PacketRouter.PacketCallback onDelete = this::handleDeleteBroadcast;

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

        pendingSpinner = null;
        pendingRawUsers = null;

        PacketRouter r = PacketRouter.getInstance();
        r.on(PacketType.GET_CHATS_RESPONSE, onChats);
        r.on(PacketType.GET_USERS_RESPONSE, onUsers);
        r.on(PacketType.GET_BUNDLE_RESPONSE, onBundle);
        r.on(PacketType.CREATE_CHAT_BROADCAST, onCreate);
        r.on(PacketType.RENAME_CHAT_BROADCAST, onRename);
        r.on(PacketType.DELETE_CHAT_BROADCAST, onDelete);

        Socket socket = TcpConnection.socket;
        if (socket == null || socket.isClosed() || !socket.isConnected()) {
            attemptAutoReconnect();
        } else {
            refreshConversations();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        PacketRouter r = PacketRouter.getInstance();
        r.off(PacketType.GET_CHATS_RESPONSE, onChats);
        r.off(PacketType.GET_USERS_RESPONSE, onUsers);
        r.off(PacketType.GET_BUNDLE_RESPONSE, onBundle);
        r.off(PacketType.CREATE_CHAT_BROADCAST, onCreate);
        r.off(PacketType.RENAME_CHAT_BROADCAST, onRename);
        r.off(PacketType.DELETE_CHAT_BROADCAST, onDelete);
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

    private void handleGetUsers(NetworkPacket packet) {
        runOnUiThread(() -> {
            try {
                Type userListType = new TypeToken<List<String>>(){}.getType();
                List<String> serverList = gson.fromJson(packet.getPayload(), userListType);
                updateSpinnerData(serverList);
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse users list.", e);
            }
        });
    }

    private void handleBundleResponse(NetworkPacket packet) {
        runOnUiThread(() -> {
            try {
                ChatDtos.GetBundleResponseDto bundle = gson.fromJson(packet.getPayload(), ChatDtos.GetBundleResponseDto.class);
                if (bundle.targetUserId != pendingChatTargetId) return;

                java.security.PublicKey bobIdentityKey = CryptoHelper.stringToDilithiumPublic(bundle.identityKeyPublic);
                java.security.PublicKey bobPreKey = CryptoHelper.stringToKyberPublic(bundle.signedPreKeyPublic);
                byte[] bobSignature = android.util.Base64.decode(bundle.signature, android.util.Base64.NO_WRAP);

                boolean isSigValid = CryptoHelper.verifySignature(bobIdentityKey, bobPreKey.getEncoded(), bobSignature);
                if (!isSigValid) {
                    Toast.makeText(this, "SECURITY ALERT: Invalid signature!", Toast.LENGTH_LONG).show();
                    Log.e(TAG, "SECURITY ALERT: Dilithium signature validation failed for target ID: " + pendingChatTargetId);
                    return;
                }

                CryptoHelper.KEMResult kemResult = CryptoHelper.encapsulate(bobPreKey);
                String ciphertextBase64 = android.util.Base64.encodeToString(kemResult.wrappedKey, android.util.Base64.NO_WRAP);

                LocalStorage.pendingSecretKey = android.util.Base64.encodeToString(kemResult.aesKey.getEncoded(), android.util.Base64.NO_WRAP);

                ChatDtos.CreateGroupDto createDto = new ChatDtos.CreateGroupDto(pendingChatTargetId, pendingChatName, ciphertextBase64);
                NetworkPacket createReq = new NetworkPacket(PacketType.CREATE_CHAT_REQUEST, TcpConnection.getCurrentUserId(), createDto);
                TcpConnection.sendPacket(createReq);

            } catch (Exception e) {
                Log.e(TAG, "Critical Crypto error while processing Post-Quantum bundle.", e);
                Toast.makeText(this, "Crypto Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                goToLogin();
            });
        }, 300);
    }

    private void loadUsersForSpinner(Spinner spinner, List<String> rawUserStrings) {
        this.pendingSpinner = spinner;
        this.pendingRawUsers = rawUserStrings;
        NetworkPacket req = new NetworkPacket(PacketType.GET_USERS_REQUEST, TcpConnection.getCurrentUserId());
        TcpConnection.sendPacket(req);
    }

    private void updateSpinnerData(List<String> serverList) {
        if (pendingSpinner == null || pendingRawUsers == null) return;
        pendingRawUsers.clear();
        pendingRawUsers.addAll(serverList);
        List<String> displayNames = new ArrayList<>();
        for (String s : serverList) {
            String[] parts = s.split(",");
            if (parts.length > 1) displayNames.add(parts[1]);
            else displayNames.add(s);
        }
        setupSpinner(pendingSpinner, displayNames.toArray(new String[0]));
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
        adapter.setEnabled(false);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_with_spinner, null);
        EditText editGroupName = dialogView.findViewById(R.id.editGroupName);
        Spinner spinner = dialogView.findViewById(R.id.mySpinner);
        editGroupName.setTextColor(Color.WHITE);
        editGroupName.setHintTextColor(Color.LTGRAY);
        setupSpinner(spinner, new String[]{"Loading...", "Please wait"});
        List<String> rawUserStrings = new ArrayList<>();

        dialog = new AlertDialog.Builder(MainActivity.this, R.style.DialogSmecher)
                .setTitle("Add a new conversation")
                .setView(dialogView)
                .setNegativeButton("Cancel", (d, w) -> { adapter.setEnabled(true); d.cancel(); })
                .setPositiveButton("OK", (d, w) -> {
                    String groupName = editGroupName.getText().toString().trim();
                    if (groupName.isEmpty()) { Toast.makeText(this, "Enter a group name!", Toast.LENGTH_SHORT).show(); adapter.setEnabled(true); return; }
                    int index = spinner.getSelectedItemPosition();
                    if (index < 0 || index >= rawUserStrings.size()) { Toast.makeText(this, "No user selected!", Toast.LENGTH_SHORT).show(); adapter.setEnabled(true); return; }
                    String selectedRaw = rawUserStrings.get(index);
                    int targetId = Integer.parseInt(selectedRaw.split(",")[0]);
                    this.pendingChatTargetId = targetId;
                    this.pendingChatName = groupName;

                    Toast.makeText(this, "Handshake: Requesting keys...", Toast.LENGTH_SHORT).show();
                    NetworkPacket bundleReq = new NetworkPacket(PacketType.GET_BUNDLE_REQUEST,
                            TcpConnection.getCurrentUserId(),
                            new ChatDtos.GetBundleRequestDto(targetId));
                    TcpConnection.sendPacket(bundleReq);
                }).create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        loadUsersForSpinner(spinner, rawUserStrings);
    }

    private void setupSpinner(Spinner spinner, String[] items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items) {
            @NonNull
            @Override public View getView(int position, View convertView, @NonNull android.view.ViewGroup parent) {
                android.widget.TextView view = (android.widget.TextView) super.getView(position, convertView, parent);
                view.setTextColor(Color.WHITE);
                return view;
            }
            @Override public View getDropDownView(int position, View convertView, @NonNull android.view.ViewGroup parent) {
                android.widget.TextView view = (android.widget.TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(Color.WHITE);
                view.setBackgroundColor(Color.parseColor("#1c2630"));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
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
