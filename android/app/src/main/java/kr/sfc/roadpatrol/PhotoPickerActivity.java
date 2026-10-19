package kr.sfc.roadpatrol;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 최근 사진 목록에서 고르는 화면.
 *
 * 시스템 사진 선택 화면(Photo Picker)은 앱에 사진을 넘길 때 GPS 좌표를 지워버리므로,
 * MediaStore를 직접 읽어 앱 안에서 고르게 한다. 고른 사진은 MainActivity가 원본으로 연다.
 */
public class PhotoPickerActivity extends Activity {

    static final String EXTRA_MULTIPLE = "multiple";
    static final String EXTRA_MAX = "max";
    static final String EXTRA_URIS = "uris";

    private static final int MAX_ITEMS = 1000;
    private static final int BLUE = 0xFF1A73E8;

    private final ArrayList<Uri> items = new ArrayList<>();
    private final ArrayList<Uri> selected = new ArrayList<>();
    private final ExecutorService loader = Executors.newFixedThreadPool(3);
    private LruCache<Uri, Bitmap> thumbCache;

    private boolean multiple;
    private int max;
    private int cellSize;
    private float density;

    private TextView title;
    private Button done;
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BLUE);

        multiple = getIntent().getBooleanExtra(EXTRA_MULTIPLE, true);
        max = getIntent().getIntExtra(EXTRA_MAX, 5);
        density = getResources().getDisplayMetrics().density;
        cellSize = (getResources().getDisplayMetrics().widthPixels - px(4)) / 3;

        int cacheKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        thumbCache = new LruCache<Uri, Bitmap>(cacheKb) {
            @Override
            protected int sizeOf(Uri key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // 상단 바: 제목 + 완료
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BLUE);
        bar.setPadding(px(16), px(8), px(8), px(8));

        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        done = new Button(this);
        done.setText("완료");
        done.setOnClickListener(v -> finishWithSelection());
        if (!multiple) done.setVisibility(View.GONE);
        bar.addView(done);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Android 14+에서 "일부 사진만 허용"을 고른 경우 안내
        if (Build.VERSION.SDK_INT >= 34
                && checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
            TextView hint = new TextView(this);
            hint.setText("일부 사진만 허용된 상태입니다. 찾는 사진이 없으면 여기를 눌러 권한 › 사진 및 동영상에서 '항상 모두 허용'을 선택해주세요.");
            hint.setTextSize(13);
            hint.setTextColor(0xFF5F6368);
            hint.setBackgroundColor(0xFFFEF7E0);
            hint.setPadding(px(16), px(10), px(16), px(10));
            hint.setOnClickListener(v -> startActivity(new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null))));
            root.addView(hint);
        }

        loadItems();

        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("사진이 없습니다.");
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(0xFF5F6368);
            root.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            GridView grid = new GridView(this);
            grid.setNumColumns(3);
            grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            grid.setHorizontalSpacing(px(2));
            grid.setVerticalSpacing(px(2));
            adapter = new PhotoAdapter();
            grid.setAdapter(adapter);
            grid.setOnItemClickListener((parent, view, position, id) -> toggle(position));
            root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        setContentView(root);
        updateTitle();
    }

    @Override
    protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }

    private void loadItems() {
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Images.Media._ID};
        try (Cursor c = getContentResolver().query(collection, projection, null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c == null) return;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            while (c.moveToNext() && items.size() < MAX_ITEMS) {
                items.add(ContentUris.withAppendedId(collection, c.getLong(idCol)));
            }
        } catch (RuntimeException e) {
            Toast.makeText(this, "사진 목록을 불러오지 못했습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggle(int position) {
        Uri uri = items.get(position);
        if (!multiple) {
            selected.clear();
            selected.add(uri);
            finishWithSelection();
            return;
        }
        if (selected.contains(uri)) {
            selected.remove(uri);
        } else if (selected.size() >= max) {
            Toast.makeText(this, "최대 " + max + "장까지 선택할 수 있습니다.", Toast.LENGTH_SHORT).show();
            return;
        } else {
            selected.add(uri);
        }
        adapter.notifyDataSetChanged();
        updateTitle();
    }

    private void updateTitle() {
        title.setText(multiple ? "사진 선택 (" + selected.size() + "/" + max + ")" : "사진 선택");
        done.setEnabled(!selected.isEmpty());
    }

    private void finishWithSelection() {
        if (selected.isEmpty()) {
            setResult(RESULT_CANCELED);
        } else {
            Intent data = new Intent();
            data.putParcelableArrayListExtra(EXTRA_URIS, new ArrayList<>(selected));
            setResult(RESULT_OK, data);
        }
        finish();
    }

    private int px(int dp) {
        return Math.round(dp * density);
    }

    private static class Holder {
        final ImageView image;
        final TextView badge;
        volatile Uri uri;

        Holder(ImageView image, TextView badge) {
            this.image = image;
            this.badge = badge;
        }
    }

    private class PhotoAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FrameLayout cell;
            Holder h;
            if (convertView == null) {
                cell = new FrameLayout(PhotoPickerActivity.this);
                cell.setLayoutParams(new AbsListView.LayoutParams(cellSize, cellSize));

                ImageView image = new ImageView(PhotoPickerActivity.this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setBackgroundColor(0xFFE0E0E0);
                cell.addView(image, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                TextView badge = new TextView(PhotoPickerActivity.this);
                badge.setTextColor(Color.WHITE);
                badge.setTypeface(Typeface.DEFAULT_BOLD);
                badge.setGravity(Gravity.CENTER);
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(BLUE);
                circle.setStroke(px(2), Color.WHITE);
                badge.setBackground(circle);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(28), px(28), Gravity.TOP | Gravity.END);
                lp.setMargins(0, px(6), px(6), 0);
                cell.addView(badge, lp);

                h = new Holder(image, badge);
                cell.setTag(h);
            } else {
                cell = (FrameLayout) convertView;
                h = (Holder) cell.getTag();
            }

            Uri uri = items.get(position);
            int order = selected.indexOf(uri);
            h.badge.setVisibility(order >= 0 ? View.VISIBLE : View.GONE);
            h.badge.setText(String.valueOf(order + 1));
            cell.setForeground(order >= 0 ? new ColorDrawable(0x551A73E8) : null);

            h.uri = uri;
            Bitmap cached = thumbCache.get(uri);
            if (cached != null) {
                h.image.setImageBitmap(cached);
            } else {
                h.image.setImageDrawable(null);
                final Holder holder = h;
                loader.execute(() -> loadThumb(holder, uri));
            }
            return cell;
        }
    }

    private void loadThumb(Holder h, Uri uri) {
        if (!uri.equals(h.uri)) return; // 이미 다른 칸으로 스크롤됨
        try {
            Bitmap bm = getContentResolver().loadThumbnail(uri, new Size(cellSize, cellSize), null);
            thumbCache.put(uri, bm);
            runOnUiThread(() -> {
                if (uri.equals(h.uri)) h.image.setImageBitmap(bm);
            });
        } catch (IOException | RuntimeException ignored) {
        }
    }
}
