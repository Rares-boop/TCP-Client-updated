package com.vladurares.tcpclient.ui.activities;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.TextView;
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

import com.vladurares.tcpclient.network.PacketRouter;
import com.vladurares.tcpclient.storage.LocalStorage;
import com.vladurares.tcpclient.utils.ClientKeyManager;
import com.vladurares.tcpclient.utils.ConfigReader;
import com.vladurares.tcpclient.ui.adapters.MessageAdapter;
import com.vladurares.tcpclient.R;
import com.vladurares.tcpclient.network.TcpConnection;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.SecretKey;

import chat.models.GroupChat;
import chat.models.Message;
import chat.network.ChatDtos;
import chat.network.NetworkPacket;
import chat.network.PacketType;
import chat.security.CryptoHelper;


public class ConversationActivity extends AppCompatActivity {
    public volatile List<Message> messages = new ArrayList<>();
    RecyclerView recyclerView;
    MessageAdapter messageAdapter;
    private int currentChatId = -1;
    private final Gson gson = new Gson();
    private ClientKeyManager keyManager;
    private String chatName;
    private int targetUserId;
    private static final String TAG = "ConversationActivity";
    private final PacketRouter.PacketCallback onMessages = this::handleMessages;
    private final PacketRouter.PacketCallback onReceive = this::handleReceiveMessage;
    private final PacketRouter.PacketCallback onEdit = this::handleEditBroadcast;
    private final PacketRouter.PacketCallback onDeleteMsg = this::handleDeleteMessageBroadcast;
    private final PacketRouter.PacketCallback onMembers = this::handleMembers;
    private final PacketRouter.PacketCallback onDeleteChat = this::handleDeleteChat;
    private final PacketRouter.PacketCallback onRenameChat = this::handleRenameChat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_conversation);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            return insets;
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.inputLayout), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), systemBars.bottom + 8);
            return insets;
        });

        TcpConnection.setContext(this);

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackPress();
            }
        });

        keyManager = new ClientKeyManager(this, TcpConnection.getCurrentUserId());

        Intent intent = getIntent();
        this.chatName = intent.getStringExtra("CHAT_NAME");
        this.currentChatId = intent.getIntExtra("CHAT_ID", -1);

        this.targetUserId = intent.getIntExtra("TARGET_USER_ID", -1);

        TextView txtChatName = findViewById(R.id.txtChatName);
        if(chatName != null) txtChatName.setText(chatName);

        recyclerView = findViewById(R.id.recyclerViewMessages);
        messageAdapter = new MessageAdapter(this, messages, TcpConnection.getCurrentUserId(), this::handleLongMessageClick);

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(messageAdapter);

        View btnBack = findViewById(R.id.btnBackArrow);
        btnBack.setOnClickListener(v -> handleBackPress());

    }

    @Override
    protected void onResume() {
        super.onResume();
        PacketRouter r = PacketRouter.getInstance();
        r.on(PacketType.GET_MESSAGES_RESPONSE, onMessages);
        r.on(PacketType.RECEIVE_MESSAGE, onReceive);
        r.on(PacketType.EDIT_MESSAGE_BROADCAST, onEdit);
        r.on(PacketType.DELETE_MESSAGE_BROADCAST, onDeleteMsg);
        r.on(PacketType.GET_CHAT_MEMBERS_RESPONSE, onMembers);
        r.on(PacketType.DELETE_CHAT_BROADCAST, onDeleteChat);
        r.on(PacketType.RENAME_CHAT_BROADCAST, onRenameChat);
        sendEnterChatRequest();
    }

    @Override
    protected void onPause() {
        super.onPause();
        PacketRouter r = PacketRouter.getInstance();
        r.off(PacketType.GET_MESSAGES_RESPONSE, onMessages);
        r.off(PacketType.RECEIVE_MESSAGE, onReceive);
        r.off(PacketType.EDIT_MESSAGE_BROADCAST, onEdit);
        r.off(PacketType.DELETE_MESSAGE_BROADCAST, onDeleteMsg);
        r.off(PacketType.GET_CHAT_MEMBERS_RESPONSE, onMembers);
        r.off(PacketType.DELETE_CHAT_BROADCAST, onDeleteChat);
        r.off(PacketType.RENAME_CHAT_BROADCAST, onRenameChat);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    public void handleMessage(View view) {
        EditText messageBox = findViewById(R.id.editTextMessage);
        String text = messageBox.getText().toString().trim();

        if (text.isEmpty()) return;

        try {
            SecretKey chatKey = keyManager.getKey(currentChatId);
            if (chatKey == null) {
                Toast.makeText(this, "Key missing! Handshake incomplete.", Toast.LENGTH_SHORT).show();
                return;
            }

            byte[] encryptedData = CryptoHelper.encryptAndPack(chatKey, text);

            Message msg = new Message(0, encryptedData,0, TcpConnection.getCurrentUserId(), currentChatId);
            NetworkPacket packet = new NetworkPacket(PacketType.SEND_MESSAGE, TcpConnection.getCurrentUserId(), msg);
            TcpConnection.sendPacket(packet);

            messageBox.setText("");
        } catch (Exception e) {
            Log.e(TAG, "Encryption failed for outgoing message", e);
            Toast.makeText(this, "Encryption Error!", Toast.LENGTH_SHORT).show();
        }
    }

    private void performEdit(int messageId, String newText) {
        try {
            SecretKey chatKey = keyManager.getKey(currentChatId);
            byte[] encryptedNewContent = CryptoHelper.encryptAndPack(chatKey, newText);

            ChatDtos.EditMessageDto dto = new ChatDtos.EditMessageDto(messageId, encryptedNewContent);
            NetworkPacket packet = new NetworkPacket(PacketType.EDIT_MESSAGE_REQUEST, TcpConnection.getCurrentUserId(), dto);
            TcpConnection.sendPacket(packet);
        } catch (Exception e) {
            Log.e(TAG, "Failed to encrypt edit request", e);
            Toast.makeText(this, "Edit Error!", Toast.LENGTH_SHORT).show();
        }
    }

    private void performDelete(int messageId) {
        NetworkPacket packet = new NetworkPacket(PacketType.DELETE_MESSAGE_REQUEST, TcpConnection.getCurrentUserId(), messageId);
        TcpConnection.sendPacket(packet);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void handleMessages(NetworkPacket packet) {
        runOnUiThread(() -> {
            try {
                SecretKey chatKey = keyManager.getKey(currentChatId);
                Type listType = new TypeToken<List<Message>>(){}.getType();
                List<Message> history = gson.fromJson(packet.getPayload(), listType);

                messages.clear();
                if (history != null) {
                    for (Message m : history) {
                        try {
                            String decryptedText = CryptoHelper.unpackAndDecrypt(chatKey, m.getContent());
                            m.setContent(decryptedText.getBytes());
                        } catch (Exception e) {
                            Log.e(TAG, "Decryption failed for history message ID: " + m.getId());
                            m.setContent("[Decryption Error]".getBytes());
                        }
                    }
                    messages.addAll(history);
                }
                messageAdapter.notifyDataSetChanged();
                scrollToBottom();
            } catch (Exception e) {
                Log.e(TAG, "Error parsing messages", e);
            }
        });
    }

    private void handleReceiveMessage(NetworkPacket packet) {
        runOnUiThread(() -> {
            try {
                SecretKey chatKey = keyManager.getKey(currentChatId);
                Message msg = gson.fromJson(packet.getPayload(), Message.class);
                if (msg != null && msg.getGroupId() == currentChatId) {
                    try {
                        String decryptedText = CryptoHelper.unpackAndDecrypt(chatKey, msg.getContent());
                        msg.setContent(decryptedText.getBytes());
                    } catch (Exception e) {
                        Log.e(TAG, "Decryption failed for new incoming message");
                        msg.setContent("[Decryption Error]".getBytes());
                    }
                    messages.add(msg);
                    messageAdapter.notifyItemInserted(messages.size() - 1);
                    scrollToBottom();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error receiving message", e);
            }
        });
    }

    private void handleEditBroadcast(NetworkPacket packet) {
        runOnUiThread(() -> {
            try {
                SecretKey chatKey = keyManager.getKey(currentChatId);
                ChatDtos.EditMessageDto editDto = gson.fromJson(packet.getPayload(), ChatDtos.EditMessageDto.class);
                for (int i = 0; i < messages.size(); i++) {
                    if (messages.get(i).getId() == editDto.messageId) {
                        try {
                            String decryptedEdit = CryptoHelper.unpackAndDecrypt(chatKey, editDto.newContent);
                            messages.get(i).setContent(decryptedEdit.getBytes());
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to decrypt edited message", e);
                            messages.get(i).setContent("[Decryption Error on Edit]".getBytes());
                        }
                        messageAdapter.notifyItemChanged(i);
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling edit", e);
            }
        });
    }

    private void handleDeleteMessageBroadcast(NetworkPacket packet) {
        runOnUiThread(() -> {
            int deletedId = gson.fromJson(packet.getPayload(), Integer.class);
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).getId() == deletedId) {
                    messages.remove(i);
                    messageAdapter.notifyItemRemoved(i);
                    break;
                }
            }
        });
    }

    private void handleMembers(NetworkPacket packet) {
        runOnUiThread(() -> {
            Type idListType = new TypeToken<List<Integer>>(){}.getType();
            List<Integer> memberIds = gson.fromJson(packet.getPayload(), idListType);

            if (memberIds != null) {
                int myId = TcpConnection.getCurrentUserId();
                for (Integer uid : memberIds) {
                    if (uid != myId) {
                        targetUserId = uid;
                        Log.i(TAG, "Partner ID retrieved for calls: " + targetUserId);
                        break;
                    }
                }
            }
        });
    }

    private void handleDeleteChat(NetworkPacket packet) {
        int deletedChatId = gson.fromJson(packet.getPayload(), Integer.class);
        if (deletedChatId == currentChatId) {
            runOnUiThread(() -> {
                Toast.makeText(this, "This chat was deleted!", Toast.LENGTH_SHORT).show();
                finish();
            });
        }
    }

    private void handleRenameChat(NetworkPacket packet) {
        runOnUiThread(() -> {
            try {
                ChatDtos.RenameGroupDto renameDto = gson.fromJson(packet.getPayload(), ChatDtos.RenameGroupDto.class);

                if (renameDto.chatId == currentChatId) {
                    chatName = renameDto.newName;
                    TextView txtChatName = findViewById(R.id.txtChatName);
                    txtChatName.setText(chatName);

                    List<GroupChat> globalChats = LocalStorage.getCurrentUserGroupChats();
                    if (globalChats != null) {
                        for (GroupChat chat : globalChats) {
                            if (chat.getId() == currentChatId) {
                                chat.setName(chatName);
                                break;
                            }
                        }
                    }
                    Log.i(TAG, "Chat rename live in: " + chatName);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error RENAME_CHAT_BROADCAST in ConversationActivity", e);
            }
        });
    }

    private void sendEnterChatRequest() {
        if (currentChatId != -1) {
            NetworkPacket packet = new NetworkPacket(PacketType.ENTER_CHAT_REQUEST, TcpConnection.getCurrentUserId(), currentChatId);
            TcpConnection.sendPacket(packet);

            NetworkPacket membersReq = new NetworkPacket(PacketType.GET_CHAT_MEMBERS_REQUEST, TcpConnection.getCurrentUserId(), currentChatId);
            TcpConnection.sendPacket(membersReq);
        }
    }

    private void sendExitChatRequest() {
        NetworkPacket packet = new NetworkPacket(PacketType.EXIT_CHAT_REQUEST, TcpConnection.getCurrentUserId());
        TcpConnection.sendPacket(packet);
    }

    public void handleBackPress() {
        sendExitChatRequest();
        finish();
    }

    private void scrollToBottom() {
        if (!messages.isEmpty()) {
            recyclerView.post(() -> recyclerView.smoothScrollToPosition(messages.size() - 1));
        }
    }

    private ArrayAdapter<String> createDialogAdapter(String[] options) {
        return new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, options) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView textView = view.findViewById(android.R.id.text1);
                textView.setTextColor(Color.WHITE);
                return view;
            }
        };
    }

    public void handleLongMessageClick(Message message) {
        if (message.getSenderId() != TcpConnection.getCurrentUserId()) return;

        android.text.SpannableString btnCancel = new android.text.SpannableString("Cancel");
        btnCancel.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor("#137fec")), 0, btnCancel.length(), 0);

        String[] options = {"Modify", "Delete"};
        ArrayAdapter<String> adapter = createDialogAdapter(options);

        new AlertDialog.Builder(ConversationActivity.this, R.style.DialogSmecher)
                .setTitle("Options")
                .setAdapter(adapter, (dialog, which) -> {
                    if (which == 0) modifyMessage(message);
                    else deleteMessage(message);
                })
                .setNegativeButton(btnCancel, (dialog, which) -> dialog.cancel())
                .show();
    }

    public void modifyMessage(Message message) {
        EditText input = new EditText(this);
        String currentContent = new String(message.getContent());
        input.setTextColor(Color.WHITE);
        input.setText(currentContent);
        input.setSelection(currentContent.length());

        new AlertDialog.Builder(this, R.style.DialogSmecher)
                .setTitle("Modify message")
                .setView(input)
                .setPositiveButton("OK", (dialog, which) -> {
                    String newText = input.getText().toString().trim();
                    if (!newText.isEmpty()) performEdit(message.getId(), newText);
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.cancel())
                .show();
    }

    public void deleteMessage(Message message) {
        new AlertDialog.Builder(this, R.style.DialogSmecher)
                .setTitle("Delete Message")
                .setMessage("Are you sure?")
                .setPositiveButton("DELETE", (dialog, which) -> performDelete(message.getId()))
                .setNegativeButton("Cancel", (dialog, which) -> dialog.cancel())
                .show();
    }

    public void handleAudioCallClick(View view) {
        initiateCall(true);
    }

    public void handleVideoCallClick(View view) {
        initiateCall(false);
    }

    private void initiateCall(boolean isAudioCall) {
        if (targetUserId == -1) {
            Toast.makeText(this, "Error: Partner user unknown", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(ConversationActivity.this, CallActivity.class);
        intent.putExtra("TARGET_USER_ID", targetUserId);
        intent.putExtra("CHAT_ID", currentChatId);
        intent.putExtra("USERNAME", chatName);
        intent.putExtra("MY_USER_ID", TcpConnection.getCurrentUserId());

        intent.putExtra("IS_AUDIO", isAudioCall);

        try {
            String serverIp = TcpConnection.getSocket().getInetAddress().getHostAddress();
            intent.putExtra("SERVER_IP", serverIp);
        } catch (Exception e) {
            ConfigReader configReader = new ConfigReader(this);
            intent.putExtra("SERVER_IP", configReader.getServerIp());
            Toast.makeText(this, "Server IP retrieval error!", Toast.LENGTH_SHORT).show();
        }

        ChatDtos.CallRequestDto callDto = new ChatDtos.CallRequestDto(targetUserId, currentChatId, isAudioCall, chatName);
        NetworkPacket callRequest = new NetworkPacket(PacketType.CALL_REQUEST, TcpConnection.getCurrentUserId(), callDto);
        TcpConnection.sendPacket(callRequest);

        startActivity(intent);
    }
}
