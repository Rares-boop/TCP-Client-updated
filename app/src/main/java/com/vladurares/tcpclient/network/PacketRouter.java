package com.vladurares.tcpclient.network;

import android.util.Log;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import chat.network.NetworkPacket;
import chat.network.PacketType;

public class PacketRouter implements TcpConnection.PacketListener {
    private static final String TAG = "PacketRouter";
    private static final PacketRouter instance = new PacketRouter();

    public interface PacketCallback {
        void onPacket(NetworkPacket packet);
    }

    private final Map<PacketType, List<PacketCallback>> handlers = new ConcurrentHashMap<>();

    public static PacketRouter getInstance() {
        return instance;
    }

    public void on(PacketType type, PacketCallback callback) {
        handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(callback);
    }

    public void off(PacketType type, PacketCallback callback) {
        List<PacketCallback> list = handlers.get(type);
        if (list != null) {
            list.remove(callback);
        }
    }

    public void offAll(PacketCallback callback) {
        for (List<PacketCallback> list : handlers.values()) {
            list.remove(callback);
        }
    }

    @Override
    public void onPacketReceived(NetworkPacket packet) {
        List<PacketCallback> list = handlers.get(packet.getType());

        if (list != null && !list.isEmpty()) {
            for (PacketCallback cb : list) {
                cb.onPacket(packet);
            }
        } else {
            Log.w(TAG, "No handler for: " + packet.getType());
        }
    }

    private PacketRouter() {}
}
