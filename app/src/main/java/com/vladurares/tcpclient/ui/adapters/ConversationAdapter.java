package com.vladurares.tcpclient.ui.adapters;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.vladurares.tcpclient.R;
import com.vladurares.tcpclient.storage.ProfilePictureCache;

import java.util.List;

import chat.models.GroupChat;


public class ConversationAdapter extends RecyclerView.Adapter<ConversationAdapter.ConversationViewHolder> {
    private final Context context;
    private List<GroupChat> conversations;
    private final OnConversationClickListener listener;
    private boolean enabled = true;
    private final OnConversationLongClickListener longClickListener;

    public interface OnConversationClickListener {
        void onConversationClick(GroupChat chat);
    }

    public interface OnConversationLongClickListener {
        void onConversationLongClick(GroupChat chat);
    }

    public ConversationAdapter(Context context, List<GroupChat> conversations,
                               OnConversationClickListener listener,
                               OnConversationLongClickListener longClickListener) {
        this.context = context;
        this.conversations = conversations;
        this.listener = listener;
        this.longClickListener = longClickListener;
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    @NonNull
    @Override
    public ConversationViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_conversation, parent, false);
        return new ConversationViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ConversationViewHolder holder, int position) {
        GroupChat chat = conversations.get(position);
        holder.groupName.setText(chat.getName());
        holder.groupInfo.setText(String.format(java.util.Locale.getDefault(), "ID: %d", chat.getId()));

        Bitmap partnerPic = ProfilePictureCache.getBitmap(chat.getId());
        if (partnerPic != null) {
            holder.avatar.setImageBitmap(partnerPic);
            holder.avatar.setPadding(0, 0, 0, 0);
            holder.avatar.setImageTintList(null);
        } else {
            // Reset la placeholder dacă nu e poză (recycle view reuse)
            holder.avatar.setImageResource(android.R.drawable.sym_def_app_icon);
            int pad = (int) (12 * context.getResources().getDisplayMetrics().density);
            holder.avatar.setPadding(pad, pad, pad, pad);
            holder.avatar.setImageTintList(
                    android.content.res.ColorStateList.valueOf(0xFF94A3B8));
        }

        holder.itemView.setOnClickListener(v -> {
            if (enabled) {
                listener.onConversationClick(chat);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (enabled && longClickListener != null) {
                longClickListener.onConversationLongClick(chat);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return conversations.size();
    }

    public void setGroupChats(List<GroupChat> conversations) {
        this.conversations = conversations;
    }

    public static class ConversationViewHolder extends RecyclerView.ViewHolder {
        TextView groupName, groupInfo;
        ImageView avatar;

        public ConversationViewHolder(@NonNull View itemView) {
            super(itemView);
            groupName = itemView.findViewById(R.id.textViewGroupName);
            groupInfo = itemView.findViewById(R.id.textViewGroupInfo);
            avatar = itemView.findViewById(R.id.imgConversationAvatar);
        }
    }
}
