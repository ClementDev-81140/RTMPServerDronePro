package com.rtmp.drone.ui;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.rtmp.drone.R;
import com.rtmp.drone.model.StreamChannel;
import java.util.*;

public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ViewHolder> {
    private List<StreamChannel> channels;
    private final ChannelListener listener;

    public interface ChannelListener {
        void onEdit(StreamChannel channel);
        void onDelete(StreamChannel channel);
        void onDuplicate(StreamChannel channel);
        void onToggleActive(StreamChannel channel, boolean active);
    }

    public ChannelAdapter(List<StreamChannel> channels, ChannelListener listener) {
        this.channels = new ArrayList<>(channels);
        this.listener = listener;
    }

    public void updateChannels(List<StreamChannel> newChannels) {
        this.channels = new ArrayList<>(newChannels);
        notifyDataSetChanged();
    }

    public void updateChannel(StreamChannel channel) {
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).id == channel.id) {
                channels.set(i, channel);
                notifyItemChanged(i);
                break;
            }
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_channel, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        StreamChannel channel = channels.get(position);

        holder.textName.setText(channel.name);
        holder.textPrimaryStar.setVisibility(channel.isPrimary ? View.VISIBLE : View.GONE);

        if (channel.status == StreamChannel.Status.LIVE || (channel.isActive && channel.currentBitrate > 0)) {
            holder.textStats.setVisibility(View.VISIBLE);
            holder.textStats.setText("● LIVE • " + channel.currentBitrate + " kbps");
            holder.textStats.setTextColor(Color.parseColor("#00F593"));
        } else {
            holder.textStats.setVisibility(View.GONE);
        }

        if (channel.isActive) {
            holder.card.setCardBackgroundColor(Color.parseColor("#102A20"));
            holder.card.setStrokeColor(Color.parseColor("#00F593"));
        } else {
            holder.card.setCardBackgroundColor(Color.parseColor("#18181B"));
            holder.card.setStrokeColor(Color.parseColor("#2D2D35"));
        }

        styleSwitch(holder.switchActive, Color.parseColor("#00F593"), Color.parseColor("#FFFFFF"));

        holder.switchActive.setOnCheckedChangeListener(null);
        holder.switchActive.setChecked(channel.isActive);
        holder.switchActive.setOnCheckedChangeListener((btn, isChecked) -> {
            if (listener != null) listener.onToggleActive(channel, isChecked);
        });

        holder.btnEdit.setOnClickListener(v -> {
            if (listener != null) listener.onEdit(channel);
        });

        holder.btnDuplicate.setOnClickListener(v -> {
            if (listener != null) listener.onDuplicate(channel);
        });

        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(channel);
        });
    }

    private void styleSwitch(SwitchMaterial sw, int colorOn, int colorOff) {
        int[][] states = new int[][] {
            new int[] { android.R.attr.state_checked },
            new int[] { -android.R.attr.state_checked }
        };
        int[] thumbColors = new int[] { colorOn, colorOff };
        int[] trackColors = new int[] { adjustAlpha(colorOn, 0.3f), Color.parseColor("#3D3D48") };

        sw.setThumbTintList(new ColorStateList(states, thumbColors));
        sw.setTrackTintList(new ColorStateList(states, trackColors));
    }

    private int adjustAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        return Color.argb(alpha, red, green, blue);
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView card;
        TextView textName, textStats, textPrimaryStar;
        ImageButton btnDuplicate, btnEdit, btnDelete;
        SwitchMaterial switchActive;

        ViewHolder(View view) {
            super(view);
            card = view.findViewById(R.id.cardChannel);
            textPrimaryStar = view.findViewById(R.id.textPrimaryStar);
            textName = view.findViewById(R.id.textViewChannelName);
            textStats = view.findViewById(R.id.textViewChannelStats);
            btnDuplicate = view.findViewById(R.id.buttonDuplicate);
            btnEdit = view.findViewById(R.id.buttonEdit);
            btnDelete = view.findViewById(R.id.buttonDelete);
            switchActive = view.findViewById(R.id.switchChannelActive);
        }
    }
}
