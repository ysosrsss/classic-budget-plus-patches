package org.classicbudget.patch;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.Arrays;

/** Only the original payee chooser query: preserve IDs, exclusions, active/deleted filters and order. */
public final class PayeeSearch {
    public static Cursor query(SQLiteDatabase db, String sql, String[] args, CharSequence query) {
        if (query == null || query.toString().trim().isEmpty()) return db.rawQuery(sql, args);
        final String prefix = " AND name LIKE ?";
        int at = sql.indexOf(prefix);
        if (at < 0 || sql.indexOf(prefix, at + prefix.length()) >= 0 || args == null || args.length == 0)
            throw new IllegalArgumentException("Unsupported payee chooser query");
        String all = sql.substring(0, at) + sql.substring(at + prefix.length());
        String[] remaining = args.length == 1 ? null : Arrays.copyOfRange(args, 1, args.length);
        try (Cursor source = db.rawQuery(all, remaining)) {
            MatrixCursor result = new MatrixCursor(source.getColumnNames());
            int name = source.getColumnIndexOrThrow("name"), width = source.getColumnCount();
            while (source.moveToNext()) {
                if (!SearchText.matches(source.getString(name), query)) continue;
                Object[] row = new Object[width];
                for (int i = 0; i < width; i++) switch (source.getType(i)) {
                    case Cursor.FIELD_TYPE_NULL: row[i] = null; break;
                    case Cursor.FIELD_TYPE_INTEGER: row[i] = source.getLong(i); break;
                    case Cursor.FIELD_TYPE_FLOAT: row[i] = source.getDouble(i); break;
                    case Cursor.FIELD_TYPE_BLOB: row[i] = source.getBlob(i); break;
                    default: row[i] = source.getString(i);
                }
                result.addRow(row);
            }
            return result;
        }
    }
}
