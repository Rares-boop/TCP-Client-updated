package com.vladurares.tcpclient.ui.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.vladurares.tcpclient.R;

import java.util.List;

public class UserListAdapter extends RecyclerView.Adapter<UserListAdapter.UserViewHolder> {
    private final Context context;
    private final List<String> userNames;
    private final OnUserClickListener listener;

    public interface OnUserClickListener {
        void onUserClick(int position);
    }

    public UserListAdapter(Context context, List<String> userNames, OnUserClickListener listener) {
        this.context = context;
        this.userNames = userNames;
        this.listener = listener;
    }

    @NonNull
    @Override
    public UserViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_user, parent, false);
        return new UserViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull UserViewHolder holder, int position) {
        String name = userNames.get(position);
        holder.txtUsername.setText(name);
        holder.txtInitial.setText(name.isEmpty() ? "?" : String.valueOf(name.charAt(0)).toUpperCase());
        holder.itemView.setOnClickListener(v -> listener.onUserClick(position));
    }

    @Override
    public int getItemCount() {
        return userNames.size();
    }

    public static class UserViewHolder extends RecyclerView.ViewHolder {
        TextView txtUsername, txtInitial;

        public UserViewHolder(@NonNull View itemView) {
            super(itemView);
            txtUsername = itemView.findViewById(R.id.txtUserName);
            txtInitial = itemView.findViewById(R.id.txtUserInitial);
        }
    }
}
