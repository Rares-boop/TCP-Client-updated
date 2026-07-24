package com.vladurares.tcpclient.ui.activities;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.vladurares.tcpclient.R;
import com.vladurares.tcpclient.network.PacketRouter;
import com.vladurares.tcpclient.network.TcpConnection;
import com.vladurares.tcpclient.storage.LocalStorage;
import com.vladurares.tcpclient.ui.adapters.InviteListAdapter;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import chat.network.ChatDtos;
import chat.network.NetworkPacket;
import chat.network.PacketType;
import crypto.api.CryptoHelper;

public class PendingInvitesActivity extends AppCompatActivity {
    private static final String TAG = "PendingInvites";
    private final Gson gson = new Gson();
    private InviteListAdapter adapter;
    private final List<InviteItem> invites = new ArrayList<>();
    private TextView txtEmpty;

    private int pendingChatTargetId = -1;
    private String pendingChatName = null;

    private final PacketRouter.PacketCallback onPendingInvites = this::handlePendingInvites;
    private final PacketRouter.PacketCallback onBundleResponse = this::handleBundleResponse;
    private final PacketRouter.PacketCallback onInviteResult = packet -> {};

    public static class InviteItem {
        public int inviteId;
        public int senderId;
        public String senderName;
        public long createdAt;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pending_invites);

        txtEmpty = findViewById(R.id.txtEmpty);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        RecyclerView recyclerView = findViewById(R.id.recyclerViewInvites);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new InviteListAdapter(this, invites,
                this::onAccept,
                this::onDeny
        );
        recyclerView.setAdapter(adapter);

        TcpConnection.sendPacket(new NetworkPacket(
                PacketType.GET_PENDING_INVITES_REQUEST,
                TcpConnection.getCurrentUserId()
        ));
    }

    @Override
    protected void onResume() {
        super.onResume();
        PacketRouter r = PacketRouter.getInstance();
        r.on(PacketType.GET_PENDING_INVITES_RESPONSE, onPendingInvites);
        r.on(PacketType.GET_BUNDLE_RESPONSE, onBundleResponse);
        r.on(PacketType.CHAT_INVITE_RESULT, onInviteResult);
    }

    @Override
    protected void onPause() {
        super.onPause();
        PacketRouter r = PacketRouter.getInstance();
        r.off(PacketType.GET_PENDING_INVITES_RESPONSE, onPendingInvites);
        r.off(PacketType.GET_BUNDLE_RESPONSE, onBundleResponse);
        r.off(PacketType.CHAT_INVITE_RESULT, onInviteResult);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void handlePendingInvites(NetworkPacket packet) {
        runOnUiThread(() -> {
            try {
                Type listType = new TypeToken<List<Map<String, Object>>>(){}.getType();
                List<Map<String, Object>> raw = gson.fromJson(packet.getPayload(), listType);

                invites.clear();
                for (Map<String, Object> m : raw) {
                    InviteItem item = new InviteItem();
                    item.inviteId = ((Number) m.get("inviteId")).intValue();
                    item.senderId = ((Number) m.get("senderId")).intValue();
                    item.senderName = (String) m.get("senderName");
                    item.createdAt = ((Number) m.get("createdAt")).longValue();
                    invites.add(item);
                }

                adapter.notifyDataSetChanged();
                txtEmpty.setVisibility(invites.isEmpty() ? View.VISIBLE : View.GONE);

            } catch (Exception e) {
                Log.e(TAG, "Error parsing invites", e);
            }
        });
    }

    private void onAccept(int position) {
        if (position < 0 || position >= invites.size()) return;
        InviteItem invite = invites.get(position);

        TcpConnection.sendPacket(new NetworkPacket(
                PacketType.CHAT_INVITE_ACCEPT,
                TcpConnection.getCurrentUserId(),
                invite.inviteId
        ));

        this.pendingChatTargetId = invite.senderId;
        this.pendingChatName = invite.senderName;

        invites.remove(position);
        adapter.notifyItemRemoved(position);
        txtEmpty.setVisibility(invites.isEmpty() ? View.VISIBLE : View.GONE);

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            Toast.makeText(this, "Handshake: Requesting keys...", Toast.LENGTH_SHORT).show();
            TcpConnection.sendPacket(new NetworkPacket(
                    PacketType.GET_BUNDLE_REQUEST,
                    TcpConnection.getCurrentUserId(),
                    new ChatDtos.GetBundleRequestDto(pendingChatTargetId)
            ));
        }, 500);
    }

    private void onDeny(int position) {
        if (position < 0 || position >= invites.size()) return;
        InviteItem invite = invites.get(position);

        TcpConnection.sendPacket(new NetworkPacket(
                PacketType.CHAT_INVITE_DENY,
                TcpConnection.getCurrentUserId(),
                invite.inviteId
        ));

        invites.remove(position);
        adapter.notifyItemRemoved(position);
        txtEmpty.setVisibility(invites.isEmpty() ? View.VISIBLE : View.GONE);

        Toast.makeText(this, "Invite declined.", Toast.LENGTH_SHORT).show();
    }

    private void handleBundleResponse(NetworkPacket packet) {
        runOnUiThread(() -> {
            try {
                ChatDtos.GetBundleResponseDto bundle = gson.fromJson(packet.getPayload(), ChatDtos.GetBundleResponseDto.class);
                if (bundle.targetUserId != pendingChatTargetId) return;

                java.security.PublicKey identityKey = CryptoHelper.stringToDilithiumPublic(bundle.identityKeyPublic);
                java.security.PublicKey preKey = CryptoHelper.stringToKyberPublic(bundle.signedPreKeyPublic);
                byte[] signature = android.util.Base64.decode(bundle.signature, android.util.Base64.NO_WRAP);

                boolean valid = CryptoHelper.verifySignature(identityKey, preKey.getEncoded(), signature);
                if (!valid) {
                    Toast.makeText(this, "SECURITY ALERT: Invalid signature!", Toast.LENGTH_LONG).show();
                    Log.e(TAG, "Dilithium signature validation FAILED for user " + pendingChatTargetId);
                    return;
                }

                CryptoHelper.KEMResult kemResult = CryptoHelper.encapsulate(preKey);
                String ciphertextBase64 = android.util.Base64.encodeToString(kemResult.wrappedKey, android.util.Base64.NO_WRAP);

                LocalStorage.pendingSecretKey = android.util.Base64.encodeToString(
                        kemResult.aesKey.getEncoded(), android.util.Base64.NO_WRAP);

                ChatDtos.CreateGroupDto createDto = new ChatDtos.CreateGroupDto(
                        pendingChatTargetId, pendingChatName, ciphertextBase64);
                TcpConnection.sendPacket(new NetworkPacket(
                        PacketType.CREATE_CHAT_REQUEST,
                        TcpConnection.getCurrentUserId(),
                        createDto
                ));

                Toast.makeText(this, "Creating chat...", Toast.LENGTH_SHORT).show();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::finish, 1000);

                Log.i(TAG, "Key exchange complete. CREATE_CHAT_REQUEST sent for " + pendingChatName);

            } catch (Exception e) {
                Log.e(TAG, "Key exchange failed", e);
                Toast.makeText(this, "Crypto Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

}
