package com.gathin.tvhome;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Continue watching row above the dock (Launcher settings, Continue watching): what the apps put in Watch Next
 * (UpNext.loadContinue), as small cards in the space the dock's big title uses, so a show is one press from Home.
 * The big title gives way while the row has cards. Down goes back to the dock and Up to the top bar. Home places
 * the row (place) whenever it shows the top shelf, and hides it while focus is in the grid below the dock, where
 * the rows scroll up into its space.
 */
final class ResumeRow {
    interface Host {
        /** A card took focus: the glass highlight leaves the tiles. x, y: the card's centre as a share of the screen. */
        void onResumeCardFocused(float x, float y);

        /** Down from the row. */
        void onResumeRowDown();

        /** The row got its first cards, or lost its last ones: Home shows or hides the big title instead. */
        void onResumeRowChanged(boolean hasCards);
    }

    private static final float FOCUS_SCALE = 1.06f;
    private static final int MAX_CARDS = 12;

    private final Activity activity;
    private final Host host;
    private final View container;
    private final TextView label;
    private final RecyclerView cards;
    private final ArtLoader art;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private List<UpNext.Item> items = Collections.emptyList();
    private int cardW, cardH;
    private int remembered;
    private boolean shown;
    private boolean released;

    ResumeRow(Activity activity, Host host, View container) {
        this.activity = activity;
        this.host = host;
        this.container = container;
        density = activity.getResources().getDisplayMetrics().density;
        label = container.findViewById(R.id.resumeLabel);
        cards = container.findViewById(R.id.resumeCards);
        cards.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false));
        cards.setItemAnimator(null);
        cards.setAdapter(new CardAdapter());
        art = new ArtLoader(activity);
        cardH = Math.round(80 * density);
        cardW = cardH * 16 / 9;
    }

    boolean hasCards() {
        return !items.isEmpty();
    }

    /** Reads Watch Next again (off the main thread); a show watched in between moves to the front. */
    void refresh() {
        if (activity.checkSelfPermission(UpNext.READ_TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) return;
        loader.execute(() -> {
            UpNext.Row row;
            try {
                row = UpNext.loadContinue(activity);
            } catch (RuntimeException e) {
                row = null;
            }
            List<UpNext.Item> fresh = row == null ? Collections.emptyList()
                    : row.items.subList(0, Math.min(MAX_CARDS, row.items.size()));
            main.post(() -> show(fresh));
        });
    }

    private void show(List<UpNext.Item> fresh) {
        if (released) return;
        boolean had = hasCards();
        items = fresh;
        remembered = Math.min(remembered, Math.max(0, items.size() - 1));
        cards.getAdapter().notifyDataSetChanged();
        if (!hasCards()) hide(false);
        if (had != hasCards()) host.onResumeRowChanged(hasCards());
    }

    /**
     * Shows the row with its bottom above the dock band: between top (the header's bottom) and bottom, both in
     * root coordinates, with the cards starting at left like the first dock tile. False when the cards do not fit.
     */
    boolean place(float top, float bottom, int left, boolean animate) {
        if (!hasCards()) return false;
        int labelH = label.getHeight() > 0 ? label.getHeight() : Math.round(18 * density);
        float room = bottom - top - labelH - 14 * density;
        int h = Math.round(Math.min(84 * density, room));
        if (h < 52 * density) {
            hide(animate);
            return false;
        }
        if (h != cardH) {
            cardH = h;
            cardW = h * 16 / 9;
            cards.getAdapter().notifyDataSetChanged();
        }
        label.setPadding(left + Math.round(2 * density), 0, 0, 0);
        cards.setPadding(left - Math.round(6 * density), cards.getPaddingTop(), cards.getPaddingRight(),
                cards.getPaddingBottom());
        ViewGroup.LayoutParams lp = cards.getLayoutParams();
        int listH = cardH + Math.round(10 * density);
        if (lp.height != listH) {
            lp.height = listH;
            cards.setLayoutParams(lp);
        }
        float y = bottom - labelH - Math.round(6 * density) - listH;
        container.setY(y);
        container.setVisibility(View.VISIBLE);
        if (!shown) {
            shown = true;
            container.animate().cancel();
            if (animate) container.animate().alpha(1f).setDuration(220).start();
            else container.setAlpha(1f);
        }
        return true;
    }

    void hide(boolean animate) {
        if (!shown) return;
        shown = false;
        container.animate().cancel();
        if (animate) {
            container.animate().alpha(0f).setDuration(160)
                    .withEndAction(() -> { if (!shown) container.setVisibility(View.GONE); }).start();
        } else {
            container.setAlpha(0f);
            container.setVisibility(View.GONE);
        }
    }

    boolean isShown() {
        return shown && hasCards();
    }

    /** Focuses the card focused last; false when the row is not on screen. */
    boolean focus() {
        if (!isShown()) return false;
        int i = Math.min(remembered, items.size() - 1);
        RecyclerView.ViewHolder holder = cards.findViewHolderForAdapterPosition(i);
        if (holder != null) return holder.itemView.requestFocus();
        cards.scrollToPosition(i);
        cards.post(() -> {
            RecyclerView.ViewHolder h = cards.findViewHolderForAdapterPosition(i);
            if (h != null) h.itemView.requestFocus();
        });
        return true;
    }

    boolean hasFocus() {
        return cards.hasFocus();
    }

    void release() {
        released = true;
        loader.shutdownNow();
        main.removeCallbacksAndMessages(null);
        art.shutdown();
    }

    void trim() {
        art.trim();
    }

    private void onCardFocused(int index, UpNext.Item item, View card) {
        remembered = index;
        label.setText(UpNext.join(activity.getString(R.string.continue_watching), item.title,
                UpNext.timeLeft(activity, item)));
        int[] at = new int[2];
        card.getLocationOnScreen(at);
        View root = container.getRootView();
        host.onResumeCardFocused((at[0] + card.getWidth() / 2f) / Math.max(1, root.getWidth()),
                (at[1] + card.getHeight() / 2f) / Math.max(1, root.getHeight()));
    }

    private final class CardAdapter extends RecyclerView.Adapter<CardHolder> {
        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CardHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_up_next_card, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder h, int position) {
            UpNext.Item item = items.get(position);
            ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
            if (lp.width != cardW || lp.height != cardH) {
                lp.width = cardW;
                lp.height = cardH;
                h.itemView.setLayoutParams(lp);
            }
            h.fallback.setText(item.title);
            art.load(item.image, h.art, cardW, cardH);
            if (item.duration > 0 && item.position > 0 && item.position < item.duration) {
                h.progress.setProgress((int) (1000 * item.position / item.duration));
                h.progress.setVisibility(View.VISIBLE);
            } else {
                h.progress.setVisibility(View.GONE);
            }
            boolean focused = h.itemView.isFocused();
            h.itemView.setScaleX(focused ? FOCUS_SCALE : 1f);
            h.itemView.setScaleY(focused ? FOCUS_SCALE : 1f);
            h.itemView.setOnClickListener(v -> UpNext.open(activity, item));
            h.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                v.animate().scaleX(hasFocus ? FOCUS_SCALE : 1f).scaleY(hasFocus ? FOCUS_SCALE : 1f)
                        .setDuration(150).start();
                if (hasFocus) onCardFocused(h.getBindingAdapterPosition(), item, v);
                else label.setText(R.string.continue_watching);
            });
            h.itemView.setOnKeyListener((v, code, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int index = h.getBindingAdapterPosition();
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        host.onResumeRowDown();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return index <= 0;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return index >= items.size() - 1;
                    default:
                        return false;
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static final class CardHolder extends RecyclerView.ViewHolder {
        final ImageView art;
        final TextView fallback;
        final ProgressBar progress;

        CardHolder(View v) {
            super(v);
            art = v.findViewById(R.id.art);
            fallback = v.findViewById(R.id.fallback);
            progress = v.findViewById(R.id.progress);
            v.setClipToOutline(true);
        }
    }
}
