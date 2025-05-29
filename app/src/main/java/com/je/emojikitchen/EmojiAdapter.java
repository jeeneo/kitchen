package com.je.emojikitchen;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.regex.Pattern;

public class EmojiAdapter extends ArrayAdapter<String> {

    private final Pattern VARIATION_SELECTOR = Pattern.compile("-fe0f$");
    private final Typeface emojiTypeface;
    private int selectedPosition = -1;

    public EmojiAdapter(Context context, String[] emojis) {
        super(context, R.layout.emoji_grid_item, emojis);
        this.emojiTypeface = loadEmojiTypeface();
    }

    private Typeface loadEmojiTypeface() {
        return Typeface.createFromAsset(getContext().getAssets(), "Noto-COLRv1-emojicompat.ttf");
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView view = (TextView) super.getView(position, convertView, parent);
        view.setTypeface(emojiTypeface);
        String hexCode = getItem(position);
        String emoji = convertHexToEmoji(hexCode);
        view.setText(emoji);
        view.setSelected(position == selectedPosition);
        return view;
    }

    private String convertHexToEmoji(String hexCode) {
        try {
            StringBuilder emoji = new StringBuilder();
            String[] parts = hexCode.split("-");

            for (String part : parts) {
                part = VARIATION_SELECTOR.matcher(part).replaceFirst("");
                int codePoint = Integer.parseInt(part, 16);
                emoji.append(new String(Character.toChars(codePoint)));
            }
            return emoji.toString();
        } catch (NumberFormatException e) {
            return hexCode;
        }
    }

    public void setSelectedPosition(int position) {
        selectedPosition = position;
        notifyDataSetChanged();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }
}