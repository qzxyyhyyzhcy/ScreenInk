package local.screenink;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

public final class PenShortcuts extends ContentProvider {
    static final Uri URI = Uri.parse("content://local.screenink.pen");
    static final String STATE = "state";
    static final String SESSION = "session";
    static final String NEXT_BRUSH = "next_brush";
    static final String TEMPORARY_ERASER = "temporary_eraser";
    static final String NEXT_COLOR = "next_color";

    static void publish(Context context, long session) {
        InkService.shortcutSession = session;
        context.getContentResolver().notifyChange(URI, null, 0);
    }

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Pen shortcuts are accepted only from the system.");
        }
        long session = InkService.shortcutSession;
        if (STATE.equals(method)) {
            Bundle state = new Bundle();
            state.putLong(SESSION, session);
            return state;
        }
        InkService service = InkService.connected();
        if (service != null && extras != null && session != 0 && session == extras.getLong(SESSION)) {
            service.penShortcut(session, method);
        }
        return null;
    }

    @Override public Cursor query(Uri uri, String[] columns, String where, String[] args, String sort) {
        return null;
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String where, String[] args) {
        throw new UnsupportedOperationException();
    }
}
