package org.classicbudget.patch;

import java.io.*;
import java.util.*;
import org.json.*;

/**
 * Finds the budgets of a storage root (its .ynabSettings.yroot) and narrows the
 * write scope to the budget a registration or diff path belongs to, reading the
 * data folder name from that budget's Budget.ymeta. Used by writers whose root
 * is configured (generic WebDAV, SAF folder); the lab default needs none of it.
 */
final class BudgetScope {
    interface Source { byte[] read(String path) throws IOException; }

    final Source source; boolean loaded;
    BudgetScope(Source source) { this.source = source; }

    synchronized void load() throws IOException {
        if (loaded) return;
        byte[] yroot;
        try { yroot = source.read("/.ynabSettings.yroot"); } catch (FileNotFoundException missing) { loaded = true; return; }
        try {
            JSONArray list = new JSONObject(new String(yroot, WritePolicy.UTF8)).getJSONArray("relativeKnownBudgets");
            List<String> budgets = new ArrayList<String>();
            for (int i = 0; i < list.length() && i < 50; i++) { String b = list.getString(i); budgets.add(b.startsWith("/") ? b : "/" + b); }
            WritePolicy.known(budgets); loaded = true;
            if (budgets.size() == 1) scopeTo(budgets.get(0));
        } catch (JSONException error) { throw new IOException("INVALID_YROOT", error); }
    }

    /** Before a registration/diff write or own-metadata read: write scope = the path's budget. */
    synchronized String path(String path) throws IOException {
        load();
        String budget = WritePolicy.budgetOf(path);
        if (budget != null && !budget.equals(WritePolicy.BUDGET) && (path.endsWith(".ydevice") || path.endsWith(".ydiff"))) scopeTo(budget);
        WritePolicy.readable(path);
        return path;
    }

    void scopeTo(String budget) throws IOException {
        if (budget.equals(WritePolicy.BUDGET)) return;
        try {
            String data = new JSONObject(new String(source.read(budget + "/Budget.ymeta"), WritePolicy.UTF8)).getString("relativeDataFolderName");
            WritePolicy.scope(budget, data);
        } catch (JSONException error) { throw new IOException("INVALID_BUDGET_META", error); }
    }
}
