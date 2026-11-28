# Changelog

For the full feature list and the menu paths, see the [user guide](docs/USER_GUIDE.md). Build numbers below identify internal Android builds. Public Morphe bundles are published as GitHub releases.

## v1.0.1 — 28 November 2026

- **Readable menus and dialogs in dark mode on Android 16.** On recent Android 16 updates (reported on a Pixel 7a) the ⋮ menu (**Open budget**, **Settings**, **Help**) and the buttons of dialogs showed white text on a white background when the app was dark. In dark mode these pop-ups now have a solid dark background, and dialogs use Android's own dark style. Light mode looks exactly as before.
- No other changes. Budgets, settings and sync work as in v1.0.0, and the same patches are recommended.

## v1.0.0 preview refresh — 23 November 2026, build 150

- **Payee rules.** **Remember rule** is aligned with the transaction fields. **Rule applied** replaces the added source rows. Hold the status to see which fields were filled or hide it in Plus settings. History-based suggestions still work.
- **Nearby payees.** Plus settings can request precise location. **Near You** continues to sort by distance and **Edit** gives access to **Forget Location**.
- **Dropbox.** The app makes it clearer that a custom location is a typed Dropbox path. The optional Syncthing route opens Android storage.
- **Updates.** Plus settings shows the Morphe bundle version. The refreshed MPP installs the usual **YNAB Plus** package with a single recommended installer.

## First public preview — November 2026, build 142

- Install YNAB Plus beside the original app from Morphe. Android compatibility and Dropbox TLS/IPv4 fixes are included. Start budget loading without the old two-second splash delay.
- Use Dropbox's usual location or type a separate Dropbox folder. Koofr, WebDAV, a local folder for Syncthing and device-letter recovery are optional.
- Open the full transaction form from the 1×1 or 2×1 widget. Choose a compact amount layout and keep the Add Transaction button visible after a save. Search payees by any word without needing accents.
- Search the whole register, save filters, use the Uncategorized and Unreconciled shortcuts, review counts and totals, and change categories for eligible entries in bulk.
- Suggest accounts from your own entries and accounts. Create payee rules directly in settings or when saving a transaction. Review and move saved payee locations.
- Edit budgeted amounts, move money between categories, add monthly category notes, set two kinds of monthly goals, and use quick-budget actions with exclusions.
- Manage scheduled payments and simple scheduled transfers. Preview upcoming entries and enter the next occurrence from the phone.
- Browse spending, trends, income and expenses, net worth, device activity, upcoming entries, budget analysis and period comparisons. Filter, drill into transactions, and export supported views to CSV or PDF.
- Set the language, dark theme and transitions. Hide amounts, use the home-screen budget summary and enable the optional overspending alert. Review ongoing sync and export diagnostic logs.

## Before the first public release

| Milestone | What reached the app |
| --- | --- |
| Community repairs and early Android patch | Dropbox connectivity fixes, a Summary layout adjustment and control of the old transition jump. |
| Initial sync experiments | Separate test installs and isolated budget copies for Dropbox, WebDAV/Koofr and Android folder sync. |
| Early Plus tools | Saved account preferences, payee rules, spending reports, localized settings, dark theme, budget widget and upcoming payments. |
| First Morphe bundles | Direct install from the original APK, Android 14 compatibility, payee search, optional alternative sync, searchable report filters and hidden categories. |
| Budget and reports | Scheduled edits, landscape reporting and exports, budget notes and amounts, quick budget, status while syncing, interactive trends and period comparisons. |
| Daily use | A single Plus menu, production and isolated lab editions, rules saved from transactions, all-register search, device-letter management, transaction widgets, GPS locations, diagnostics and conflict review. |
| Public preview | Saved transaction filters and bulk categories, category goals and money moves, Dropbox folder selector in the app, and one recommended production installer. |
