package com.vladurares.tcpclient.ui.activities;

import android.os.Bundle;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.vladurares.tcpclient.R;
import com.vladurares.tcpclient.network.PacketRouter;
import com.vladurares.tcpclient.network.TcpConnection;
import com.vladurares.tcpclient.ui.adapters.UserListAdapter;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import chat.network.NetworkPacket;
import chat.network.PacketType;

public class NewChatActivity extends AppCompatActivity {
    private static final String TAG = "NewChatActivity";
    private final Gson gson = new Gson();
    private UserListAdapter userAdapter;
    private final List<int[]> userList = new ArrayList<>();
    private final List<String> userNames = new ArrayList<>();

    private final PacketRouter.PacketCallback onUsers = this::handleGetUsers;
    private final PacketRouter.PacketCallback onInviteResult = this::handleInviteResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_chat);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        RecyclerView recyclerView = findViewById(R.id.recyclerViewUsers);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        userAdapter = new UserListAdapter(this, userNames, this::onUserSelected);
        recyclerView.setAdapter(userAdapter);

        EditText searchBar = findViewById(R.id.searchUsers);
        searchBar.addTextChangedListener(new android.text.TextWatcher() {
            private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            private Runnable searchRunnable;

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            public void afterTextChanged(android.text.Editable s) {
                if (searchRunnable != null) handler.removeCallbacks(searchRunnable);
                searchRunnable = () -> {
                    String query = s.toString().trim();
                    TcpConnection.sendPacket(new NetworkPacket(
                            PacketType.GET_USERS_REQUEST,
                            TcpConnection.getCurrentUserId(),
                            query.isEmpty() ? null : query
                    ));
                };
                handler.postDelayed(searchRunnable, 500);
            }
        });

        TcpConnection.sendPacket(new NetworkPacket(
                PacketType.GET_USERS_REQUEST,
                TcpConnection.getCurrentUserId()
        ));
    }

    @Override
    protected void onResume() {
        super.onResume();
        PacketRouter r = PacketRouter.getInstance();
        r.on(PacketType.GET_USERS_RESPONSE, onUsers);
        r.on(PacketType.CHAT_INVITE_RESULT, onInviteResult);
    }

    @Override
    protected void onPause() {
        super.onPause();
        PacketRouter r = PacketRouter.getInstance();
        r.off(PacketType.GET_USERS_RESPONSE, onUsers);
        r.off(PacketType.CHAT_INVITE_RESULT, onInviteResult);
    }

    private void handleGetUsers(NetworkPacket packet) {
        runOnUiThread(() -> {
            try {
                Type listType = new TypeToken<List<String>>(){}.getType();
                List<String> serverList = gson.fromJson(packet.getPayload(), listType);
                userList.clear();
                userNames.clear();
                for (String raw : serverList) {
                    String[] parts = raw.split(",");
                    if (parts.length >= 2) {
                        int userId = Integer.parseInt(parts[0]);
                        if (userId != TcpConnection.getCurrentUserId()) {
                            userList.add(new int[]{userId});
                            userNames.add(parts[1]);
                        }
                    }
                }
                userAdapter.notifyDataSetChanged();
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse users", e);
            }
        });
    }

    private void onUserSelected(int position) {
        if (position < 0 || position >= userList.size()) return;
        int targetUserId = userList.get(position)[0];
        String targetName = userNames.get(position);

        new androidx.appcompat.app.AlertDialog.Builder(this, R.style.DialogSmecher)
                .setTitle("Send invite")
                .setMessage("Send a chat invite to " + targetName + "?")
                .setPositiveButton("Send", (d, w) -> {
                    TcpConnection.sendPacket(new NetworkPacket(
                            PacketType.CHAT_INVITE_SEND,
                            TcpConnection.getCurrentUserId(),
                            targetUserId
                    ));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void handleInviteResult(NetworkPacket packet) {
        runOnUiThread(() -> {
            try {
                String result = gson.fromJson(packet.getPayload(), String.class);
                switch (result) {
                    case "SENT":
                        Toast.makeText(this, "Invite sent!", Toast.LENGTH_SHORT).show();
                        finish();
                        break;
                    case "ALREADY_CHATTING":
                        Toast.makeText(this, "You already have a chat with this user.", Toast.LENGTH_SHORT).show();
                        break;
                    case "ALREADY_INVITED":
                        Toast.makeText(this, "Invite already pending.", Toast.LENGTH_SHORT).show();
                        break;
                    default:
                        Toast.makeText(this, "Failed to send invite.", Toast.LENGTH_SHORT).show();
                        break;
                }
            } catch (Exception e) {
                Log.d(TAG, "Non-string invite result (ignored)");
            }
        });
    }
}

