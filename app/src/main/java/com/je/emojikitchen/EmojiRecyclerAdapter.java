package com.je.emojikitchen;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.regex.Pattern;

public class EmojiRecyclerAdapter extends RecyclerView.Adapter<EmojiRecyclerAdapter.EmojiViewHolder> {
    private final String[] emojis;
    private final Typeface emojiTypeface;
    private int selectedPosition = -1;
    private final OnEmojiSelectedListener listener;
    private final Pattern VARIATION_SELECTOR = Pattern.compile("-fe0f$");

    public interface OnEmojiSelectedListener {
        void onEmojiSelected(int position);
    }

    public EmojiRecyclerAdapter(Context context, String[] emojis, OnEmojiSelectedListener listener) {
        this.emojis = emojis;
        this.listener = listener;
        this.emojiTypeface = Typeface.createFromAsset(context.getAssets(), "NotoColorEmoji.ttf");
    }

    @NonNull
    @Override
    public EmojiViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.emoji_grid_item, parent, false);
        return new EmojiViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull EmojiViewHolder holder, int position) {
        String hexCode = emojis[position];
        String emoji = convertHexToEmoji(hexCode);
        holder.textView.setText(emoji);
        holder.textView.setTypeface(emojiTypeface);
        holder.itemView.setSelected(position == selectedPosition);
        holder.itemView.setOnClickListener(v -> {
            setSelectedPosition(position);
            listener.onEmojiSelected(position);
        });
    }

    @Override
    public int getItemCount() {
        return emojis.length;
    }

    public void setSelectedPosition(int position) {
        if (selectedPosition != position) {
            int oldPosition = selectedPosition;
            selectedPosition = position;
            
            // Only notify the changed items
            if (oldPosition != -1) {
                notifyItemChanged(oldPosition, "selection");
            }
            if (position != -1) {
                notifyItemChanged(position, "selection");
            }
        }
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    private String convertHexToEmoji(String hexCode) {
        hexCode = VARIATION_SELECTOR.matcher(hexCode).replaceAll("");
        String[] parts = hexCode.split("-");
        StringBuilder emoji = new StringBuilder();
        for (String part : parts) {
            emoji.append(Character.toChars(Integer.parseInt(part, 16)));
        }
        return emoji.toString();
    }

    static class EmojiViewHolder extends RecyclerView.ViewHolder {
        TextView textView;

        EmojiViewHolder(View itemView) {
            super(itemView);
            textView = (TextView) itemView;
        }
    }

    @Override
    public void onBindViewHolder(@NonNull EmojiViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            // Only update selection state if that's all that changed
            holder.itemView.setSelected(position == selectedPosition);
            return;
        }
        onBindViewHolder(holder, position);
    }
}
