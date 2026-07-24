package com.vladurares.tcpclient.ui.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.vladurares.tcpclient.R;
import com.vladurares.tcpclient.ui.activities.PendingInvitesActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class InviteListAdapter extends RecyclerView.Adapter<InviteListAdapter.InviteViewHolder> {
    private final Context context;
    private final List<PendingInvitesActivity.InviteItem> invites;
    private final OnActionListener acceptListener;
    private final OnActionListener denyListener;

    public interface OnActionListener {
        void onAction(int position);
    }

    public InviteListAdapter(Context context, List<PendingInvitesActivity.InviteItem> invites,
                             OnActionListener acceptListener, OnActionListener denyListener) {
        this.context = context;
        this.invites = invites;
        this.acceptListener = acceptListener;
        this.denyListener = denyListener;
    }

    @NonNull
    @Override
    public InviteViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_invite, parent, false);
        return new InviteViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull InviteViewHolder holder, int position) {
        PendingInvitesActivity.InviteItem invite = invites.get(position);

        holder.txtSenderName.setText(invite.senderName);
        holder.txtInitial.setText(invite.senderName.isEmpty() ? "?"
                : String.valueOf(invite.senderName.charAt(0)).toUpperCase());

        // Format timestamp
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault());
        holder.txtTime.setText(sdf.format(new Date(invite.createdAt)));

        holder.btnAccept.setOnClickListener(v -> acceptListener.onAction(position));
        holder.btnDeny.setOnClickListener(v -> denyListener.onAction(position));
    }

    @Override
    public int getItemCount() {
        return invites.size();
    }

    public static class InviteViewHolder extends RecyclerView.ViewHolder {
        TextView txtSenderName, txtInitial, txtTime;
        Button btnAccept, btnDeny;

        public InviteViewHolder(@NonNull View itemView) {
            super(itemView);
            txtSenderName = itemView.findViewById(R.id.txtInviteSender);
            txtInitial = itemView.findViewById(R.id.txtInviteInitial);
            txtTime = itemView.findViewById(R.id.txtInviteTime);
            btnAccept = itemView.findViewById(R.id.btnAcceptInvite);
            btnDeny = itemView.findViewById(R.id.btnDenyInvite);
        }
    }
}

