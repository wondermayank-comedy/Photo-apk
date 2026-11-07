package in.wondermayank.imagestudio;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small native helper: fast cached thumbnails and (consent-based) deleting of photos.
 * If this file fails to compile, the GitHub workflow builds the app without it.
 */
@CapacitorPlugin(name = "MediaTools", requestCodes = { 9021 })
public class MediaTools extends Plugin {

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private int pendingCount = 0;

    @PluginMethod
    public void getThumbs(final PluginCall call) {
        final JSArray paths = call.getArray("paths");
        Integer sz = call.getInt("size", 256);
        final int size = sz == null ? 256 : sz;
        Boolean sq = call.getBoolean("square", true);
        final boolean square = sq == null ? true : sq;
        if (paths == null) {
            call.reject("paths required");
            return;
        }
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = new File(getContext().getCacheDir(), "thumbs");
                    if (!dir.exists()) dir.mkdirs();
                    JSArray out = new JSArray();
                    for (int i = 0; i < paths.length(); i++) {
                        String res = "";
                        try {
                            res = makeThumb(paths.getString(i), size, square, dir);
                        } catch (Throwable t) {
                            res = "";
                        }
                        out.put(res);
                    }
                    JSObject ret = new JSObject();
                    ret.put("thumbs", out);
                    call.resolve(ret);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                }
            }
        });
    }

    private String makeThumb(String path, int size, boolean square, File dir) throws Exception {
        File src = new File(path);
        if (!src.exists()) return "";
        String key = Integer.toHexString((path + src.lastModified() + size + square).hashCode());
        File out = new File(dir, key + ".jpg");
        if (out.exists() && out.length() > 0) return out.getAbsolutePath();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return "";
        int sample = 1;
        while ((bounds.outWidth / (sample * 2)) >= size && (bounds.outHeight / (sample * 2)) >= size) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(path, opts);
        if (b == null) return "";

        int rot = 0;
        try {
            ExifInterface ex = new ExifInterface(path);
            int ori = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
        } catch (Exception ignored) {
        }

        Matrix m = new Matrix();
        Bitmap cut;
        if (square) {
            int side = Math.min(b.getWidth(), b.getHeight());
            int x = (b.getWidth() - side) / 2;
            int y = (b.getHeight() - side) / 2;
            float sc = (float) size / side;
            m.postScale(sc, sc);
            if (rot != 0) m.postRotate(rot);
            cut = Bitmap.createBitmap(b, x, y, side, side, m, true);
        } else {
            float sc = (float) size / Math.max(b.getWidth(), b.getHeight());
            if (sc > 1f) sc = 1f;
            m.postScale(sc, sc);
            if (rot != 0) m.postRotate(rot);
            cut = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        }
        FileOutputStream fo = new FileOutputStream(out);
        cut.compress(Bitmap.CompressFormat.JPEG, 80, fo);
        fo.close();
        if (cut != b) cut.recycle();
        b.recycle();
        return out.getAbsolutePath();
    }

    @PluginMethod
    public void deleteFiles(final PluginCall call) {
        JSArray paths = call.getArray("paths");
        if (paths == null || paths.length() == 0) {
            call.reject("paths required");
            return;
        }
        try {
            ContentResolver cr = getContext().getContentResolver();
            List<Uri> uris = new ArrayList<Uri>();
            for (int i = 0; i < paths.length(); i++) {
                String p = paths.getString(i);
                Cursor c = cr.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[] { MediaStore.Images.Media._ID },
                    MediaStore.Images.Media.DATA + "=?",
                    new String[] { p },
                    null
                );
                if (c != null) {
                    try {
                        if (c.moveToFirst()) {
                            uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
                        }
                    } finally {
                        c.close();
                    }
                }
            }
            if (uris.isEmpty()) {
                call.reject("No matching photos");
                return;
            }
            if (Build.VERSION.SDK_INT >= 30) {
                PendingIntent pi = MediaStore.createDeleteRequest(cr, uris);
                pendingCount = uris.size();
                saveCall(call);
                getActivity().startIntentSenderForResult(pi.getIntentSender(), 9021, null, 0, 0, 0);
            } else {
                int n = 0;
                for (Uri u : uris) {
                    try {
                        n += cr.delete(u, null, null);
                    } catch (Exception ignored) {
                    }
                }
                JSObject r = new JSObject();
                r.put("deleted", n);
                call.resolve(r);
            }
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);
        if (requestCode != 9021) return;
        PluginCall call = getSavedCall();
        if (call == null) return;
        JSObject r = new JSObject();
        boolean ok = resultCode == Activity.RESULT_OK;
        r.put("ok", ok);
        r.put("deleted", ok ? pendingCount : 0);
        call.resolve(r);
        freeSavedCall();
    }
}
