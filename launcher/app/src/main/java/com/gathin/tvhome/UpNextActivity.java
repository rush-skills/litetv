package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Up Next: Continue watching and the apps' latest rows (UpNext), with the focused title, its app and the time
 * left at the top. Up and Down move exactly one row and land on the card last focused in that row; the ends of a
 * row do not fall through. The rows are read again each time the screen comes back, so a show watched in
 * between moves to the front.
 */
public class UpNextActivity extends Activity {
    private static final float FOCUS_SCALE = 1.06f;

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, Integer> rowFocus = new HashMap<>();
    private List<UpNext.Row> rows = Collections.emptyList();
    private List<String> dockApps;
    private RecyclerView rowsView;
    private LinearLayoutManager rowsLayout;
    private TextView heroTitle, heroMeta, heroDescription, message;
    private ArtLoader art;
    private int focusedRow, scrolledRow = -1;
    private boolean loaded;
    private int landscapeW, landscapeH, portraitW, portraitH;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_up_next);
        float density = getResources().getDisplayMetrics().density;
        landscapeW = Math.round(176 * density);
        landscapeH = Math.round(99 * density);
        portraitW = Math.round(96 * density);
        portraitH = Math.round(144 * density);
        heroTitle = findViewById(R.id.heroTitle);
        heroMeta = findViewById(R.id.heroMeta);
        heroDescription = findViewById(R.id.heroDescription);
        message = findViewById(R.id.message);
        rowsView = findViewById(R.id.rows);
        rowsLayout = new LinearLayoutManager(this);
        rowsView.setLayoutManager(rowsLayout);
        rowsView.setItemAnimator(null);
        rowsView.setAdapter(new RowAdapter());
        dockApps = Arrays.asList(getResources().getStringArray(R.array.dock_apps));
        art = new ArtLoader(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(UpNext.READ_TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            showMessage(R.string.up_next_permission);
            return;
        }
        if (!loaded) showMessage(R.string.up_next_loading);
        loader.execute(() -> {
            List<UpNext.Row> fresh;
            try {
                fresh = UpNext.load(this, dockApps);
            } catch (RuntimeException e) {
                fresh = null;
            }
            final List<UpNext.Row> result = fresh;
            main.post(() -> show(result));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        loader.shutdownNow();
        main.removeCallbacksAndMessages(null);
        art.shutdown();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) art.trim();
    }

    private void show(List<UpNext.Row> fresh) {
        if (isDestroyed()) return;
        if (fresh == null) {
            showMessage(R.string.up_next_error);
            return;
        }
        if (fresh.isEmpty()) {
            showMessage(R.string.up_next_empty);
            return;
        }
        boolean first = !loaded;
        loaded = true;
        message.setVisibility(View.GONE);
        rows = fresh;
        scrolledRow = -1;
        rowsView.getAdapter().notifyDataSetChanged();
        int row = first ? 0 : Math.min(focusedRow, rows.size() - 1);
        focus(row, first ? 0 : rememberedIndex(row));
    }

    private void showMessage(int text) {
        rows = Collections.emptyList();
        rowsView.getAdapter().notifyDataSetChanged();
        heroTitle.setText(R.string.up_next);
        heroMeta.setText(null);
        heroDescription.setText(null);
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        loaded = false;
    }

    private int rememberedIndex(int row) {
        Integer i = rowFocus.get(row);
        return i != null ? i : 0;
    }

    /** Focuses the card at index in the row, clamped to the row, once both lists have laid it out. */
    private void focus(int row, int index) {
        if (row < 0 || row >= rows.size()) return;
        int i = Math.max(0, Math.min(index, rows.get(row).items.size() - 1));
        rowsView.scrollToPosition(row);
        rowsView.post(() -> {
            RecyclerView.ViewHolder holder = rowsView.findViewHolderForAdapterPosition(row);
            if (!(holder instanceof RowHolder)) return;
            RecyclerView cards = ((RowHolder) holder).cards;
            cards.scrollToPosition(i);
            cards.post(() -> {
                RecyclerView.ViewHolder card = cards.findViewHolderForAdapterPosition(i);
                if (card != null) card.itemView.requestFocus();
            });
        });
    }

    private void onCardFocused(int row, int index, UpNext.Item item) {
        focusedRow = row;
        rowFocus.put(row, index);
        heroTitle.setText(item.title);
        heroMeta.setText(UpNext.join(item.subtitle, timeLeft(item)));
        heroDescription.setText(item.description);
        if (row != scrolledRow) {
            scrolledRow = row;
            // The focused row slides up to the top of the list, as on Apple TV.
            LinearSmoothScroller scroller = new LinearSmoothScroller(this) {
                @Override
                protected int getVerticalSnapPreference() {
                    return SNAP_TO_START;
                }
            };
            scroller.setTargetPosition(row);
            rowsLayout.startSmoothScroll(scroller);
        }
    }

    private String timeLeft(UpNext.Item item) {
        if (item.duration <= 0 || item.position <= 0 || item.position >= item.duration) return null;
        long minutes = Math.max(1, (item.duration - item.position) / 60_000);
        return minutes < 60 ? getString(R.string.minutes_left, minutes)
                : getString(R.string.hours_left, minutes / 60, minutes % 60);
    }

    /** Opens the program in its app, or the app itself when the program's own link does not open. */
    private void open(UpNext.Item item) {
        Intent program = programIntent(item);
        if (program != null && start(program)) return;
        if (item.pkg != null) {
            PackageManager pm = getPackageManager();
            Intent app = pm.getLeanbackLaunchIntentForPackage(item.pkg);
            if (app == null) app = pm.getLaunchIntentForPackage(item.pkg);
            if (app != null && start(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return;
        }
        Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
    }

    /**
     * The row's intent_uri comes from another app, so it is opened like a link: no URI permission grants, no
     * selector, never into this launcher, and kept in the publishing app when the link names no app itself.
     */
    private Intent programIntent(UpNext.Item item) {
        if (item.intentUri == null) return null;
        Intent i;
        try {
            if (item.intentUri.startsWith("intent:")) {
                i = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
            } else if (item.intentUri.startsWith("android-app:")) {
                i = Intent.parseUri(item.intentUri, Intent.URI_ANDROID_APP_SCHEME);
            } else {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(item.intentUri));
            }
        } catch (URISyntaxException e) {
            return null;
        }
        i.setFlags(i.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
        i.setClipData(null);
        i.setSelector(null);
        ComponentName target = i.getComponent();
        if (target != null && getPackageName().equals(target.getPackageName())) return null;
        if (getPackageName().equals(i.getPackage())) return null;
        if (target == null && i.getPackage() == null && item.pkg != null) i.setPackage(item.pkg);
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private boolean start(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    // ---- rows --------------------------------------------------------------------------------------

    private final class RowAdapter extends RecyclerView.Adapter<RowHolder> {
        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RowHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_up_next_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RowHolder h, int position) {
            UpNext.Row row = rows.get(position);
            h.title.setText(row.title);
            h.adapter.bind(position, row);
            h.cards.scrollToPosition(Math.min(rememberedIndex(position), row.items.size() - 1));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private final class RowHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final RecyclerView cards;
        final CardAdapter adapter = new CardAdapter();

        RowHolder(View v) {
            super(v);
            title = v.findViewById(R.id.rowTitle);
            cards = v.findViewById(R.id.cards);
            cards.setLayoutManager(new LinearLayoutManager(v.getContext(), LinearLayoutManager.HORIZONTAL, false));
            cards.setItemAnimator(null);
            cards.setAdapter(adapter);
        }
    }

    // ---- cards -------------------------------------------------------------------------------------

    private final class CardAdapter extends RecyclerView.Adapter<CardHolder> {
        private int row;
        private UpNext.Row data;

        void bind(int row, UpNext.Row data) {
            this.row = row;
            this.data = data;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CardHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_up_next_card, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder h, int position) {
            UpNext.Item item = data.items.get(position);
            final int rowIndex = row, last = data.items.size() - 1;
            int w = data.portrait ? portraitW : landscapeW, height = data.portrait ? portraitH : landscapeH;
            ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
            if (lp.width != w || lp.height != height) {
                lp.width = w;
                lp.height = height;
                h.itemView.setLayoutParams(lp);
            }
            h.fallback.setText(item.title);
            art.load(item.image, h.art, w, height);
            if (item.duration > 0 && item.position > 0 && item.position < item.duration) {
                h.progress.setProgress((int) (1000 * item.position / item.duration));
                h.progress.setVisibility(View.VISIBLE);
            } else {
                h.progress.setVisibility(View.GONE);
            }
            boolean focused = h.itemView.isFocused();
            h.itemView.setScaleX(focused ? FOCUS_SCALE : 1f);
            h.itemView.setScaleY(focused ? FOCUS_SCALE : 1f);
            h.itemView.setOnClickListener(v -> open(item));
            h.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                v.animate().scaleX(hasFocus ? FOCUS_SCALE : 1f).scaleY(hasFocus ? FOCUS_SCALE : 1f)
                        .setDuration(150).start();
                if (hasFocus) onCardFocused(rowIndex, h.getBindingAdapterPosition(), item);
            });
            h.itemView.setOnKeyListener((v, code, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int index = h.getBindingAdapterPosition();
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (rowIndex > 0) focus(rowIndex - 1, rememberedIndex(rowIndex - 1));
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (rowIndex < rows.size() - 1) focus(rowIndex + 1, rememberedIndex(rowIndex + 1));
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return index <= 0;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return index >= last;
                    default:
                        return false;
                }
            });
        }

        @Override
        public int getItemCount() {
            return data == null ? 0 : data.items.size();
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
