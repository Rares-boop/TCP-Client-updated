package com.vladurares.tcpclient.network;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.google.gson.Gson;
import com.vladurares.tcpclient.storage.LocalStorage;
import com.vladurares.tcpclient.utils.ClientKeyManager;

import java.security.PrivateKey;
import java.util.List;

import javax.crypto.SecretKey;

import chat.models.GroupChat;
import chat.network.ChatDtos;
import chat.network.NetworkPacket;
import chat.network.PacketType;
import crypto.api.CryptoHelper;

public class GlobalPacketHandlers {
    private static final String TAG = "GlobalHandlers";
    private static final Gson gson = new Gson();
    private static boolean registered = false;
    private static Context appContext;

    public static void register(Context context) {
        if (registered) return;
        registered = true;
        appContext = context.getApplicationContext();

        PacketRouter r = PacketRouter.getInstance();
        r.on(PacketType.DELETE_CHAT_BROADCAST, GlobalPacketHandlers::handleDelete);
        r.on(PacketType.RENAME_CHAT_BROADCAST, GlobalPacketHandlers::handleRename);
        r.on(PacketType.CREATE_CHAT_BROADCAST, GlobalPacketHandlers::handleCreate);
    }

    private static void handleDelete(NetworkPacket packet) {
        int deletedId = gson.fromJson(packet.getPayload(), Integer.class);
        List<GroupChat> list = LocalStorage.getCurrentUserGroupChats();
        list.removeIf(c -> c.getId() == deletedId);
        Log.i(TAG, "Chat deleted: " + deletedId);
    }

    private static void handleRename(NetworkPacket packet) {
        ChatDtos.RenameGroupDto dto = gson.fromJson(packet.getPayload(), ChatDtos.RenameGroupDto.class);
        for (GroupChat c : LocalStorage.getCurrentUserGroupChats()) {
            if (c.getId() == dto.chatId) {
                c.setName(dto.newName);
                break;
            }
        }
        Log.i(TAG, "Chat renamed: " + dto.chatId);
    }

    private static void handleCreate(NetworkPacket packet) {
        ChatDtos.NewChatBroadcastDto dto = gson.fromJson(packet.getPayload(), ChatDtos.NewChatBroadcastDto.class);
        GroupChat newChat = dto.groupInfo;

        if (newChat.getId() <= 0) return;

        List<GroupChat> list = LocalStorage.getCurrentUserGroupChats();
        boolean exists = false;
        for (GroupChat c : list) {
            if (c.getId() == newChat.getId()) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            list.add(0, newChat);
        }

        ClientKeyManager keyMgr = new ClientKeyManager(appContext, TcpConnection.getCurrentUserId());

        if (dto.keyCiphertext != null && !dto.keyCiphertext.isEmpty()) {
            try {
                byte[] cipherBytes = Base64.decode(dto.keyCiphertext, Base64.NO_WRAP);
                String myPrivStr = keyMgr.getMyPreKeyPrivateKey();
                PrivateKey myPriv = CryptoHelper.stringToKyberPrivate(myPrivStr);
                SecretKey shared = CryptoHelper.decapsulate(myPriv, cipherBytes);
                String keyBase64 = Base64.encodeToString(shared.getEncoded(), Base64.NO_WRAP);
                keyMgr.saveKey(newChat.getId(), keyBase64);
                Log.i(TAG, "[BOB] Post-Quantum Key saved for chat " + newChat.getId());
            } catch (Exception e) {
                Log.e(TAG, "Error saving key for new chat", e);
            }
        } else if (LocalStorage.pendingSecretKey != null) {
            SecretKey existingKey = keyMgr.getKey(newChat.getId());
            if (existingKey == null) {
                keyMgr.saveKey(newChat.getId(), LocalStorage.pendingSecretKey);
                Log.i(TAG, "[ALICE] Key saved for chat " + newChat.getId());
            }
            LocalStorage.pendingSecretKey = null;
        }
    }
}
