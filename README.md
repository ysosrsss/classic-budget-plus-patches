# Classic Budget Plus

Classic Budget Plus brings more of the YNAB 4 desktop workflow to Android. Add transactions quickly, manage your budget and scheduled transactions, and open reports on your phone. Morphe Manager patches the original APK and installs **YNAB Plus** directly on Android, alongside the original app, without ADB.

[Add to Morphe](https://morphe.software/add-source?github=ysosrsss/classic-budget-plus-patches) · [Download the v1.0.0 MPP](https://github.com/ysosrsss/classic-budget-plus-patches/releases/download/v1.0.0/classic-budget-plus-v1.0.0-production.mpp) · [Release notes](https://github.com/ysosrsss/classic-budget-plus-patches/releases/tag/v1.0.0)

## What it adds

- **Faster startup.** Budget loading starts without the original splash screen's fixed two-second delay.
- **Faster transaction entry.** Keep **Add transaction** visible after saving. Open the full **Add Transaction** screen with the **+** home-screen widget, or choose a compact amount layout in **Plus settings → Transaction form**.
- **Reports.** See spending by category or payee, monthly trends, net worth, and income versus expenses. Open reports full screen, use the landscape income and expense table, and tap amounts to see the underlying transactions.
- **Budgeting.** Edit budgeted amounts, move money between categories, add notes and see what's left to fund a monthly goal.
- **Scheduled transactions.** Create and edit recurring payments and scheduled transfers from the phone.
- **Payee rules.** Remember the account and category for a payee. The transaction form can show whether a suggestion came from a rule, location or history.
- **Transaction search.** Find entries by account, date, status or category, save filters and recategorize eligible transactions in bulk.
- **Sync options.** Use the usual Dropbox location or a separate folder for a budget copy. If you ever need to move away from Dropbox, an optional patch adds Koofr, other WebDAV providers, and local folders synced with Syncthing.

## Screenshots

Captured on Android with a fictional budget. Tap an image to view it at full size.

<table>
  <tr>
    <th>Spending by category</th>
    <th>Spending trends</th>
    <th>Saved transaction filters</th>
  </tr>
  <tr>
    <td><a href="docs/screenshots/spending-by-category.png"><img src="docs/screenshots/spending-by-category.png" width="230" alt="Spending ring and category totals"></a></td>
    <td><a href="docs/screenshots/spending-trends.png"><img src="docs/screenshots/spending-trends.png" width="230" alt="Monthly spending chart grouped by category"></a></td>
    <td><a href="docs/screenshots/saved-filters.png"><img src="docs/screenshots/saved-filters.png" width="230" alt="Saved filters and transaction search results"></a></td>
  </tr>
</table>

[![Landscape income and expense report with monthly columns](docs/screenshots/income-vs-expense.png)](docs/screenshots/income-vs-expense.png)

## Supported APK

Use your own unmodified **YNAB Classic `3.4.1_classic`**, version code `27296`, package `com.youneedabudget.ynab.app.market`. The SHA-256 below identifies the exact original file used to build and test the patches.

If you're looking for the file, search for `YNAB Classic 3.4.1_classic APK` and check the SHA-256 before patching.

```text
d49148b7c9501526c40890599d4ec4b5aad2bf57c0bd949d4649255c17f87772
```

Check the APK with `Get-FileHash -Algorithm SHA256 .\YNAB_Classic.apk` on Windows or `shasum -a 256 YNAB_Classic.apk` on macOS. The release provides the Morphe `.mpp` bundle.

## Install with Morphe

Morphe Manager is the Android app that applies the `.mpp` patches to your original YNAB Classic APK on the phone. [Get Morphe Manager from its official GitHub releases](https://github.com/MorpheApp/morphe-manager/releases).

1. Tap **Add to Morphe** above, or download the `.mpp` and import it in Morphe under **Sources → + → Local**.
2. Open **YNAB Classic** in Morphe, select your original APK file and use the recommended patches. **Install YNAB Plus** and **Budget amounts from the phone** are enabled by default.
3. Patch and tap **Install** in Morphe. Android handles installation. For an update, use the same Morphe signing key as your existing **YNAB Plus**.
4. At Dropbox setup, choose **Default location** for the usual YNAB 4 folders or **A different folder** for a budget stored separately. Enter a path such as `/Budgets/Household`. That folder should contain `.ynabSettings.yroot`.

To change the folder later, open **Plus settings → Dropbox folder**. Once all changes have synced, YNAB Plus reloads the budgets from the selected location and keeps your Dropbox sign-in.

The **Sync: Koofr, WebDAV and Syncthing folder** and **Recover a device letter (advanced)** patches are optional and start disabled. Enable the sync patch in Morphe's Expert mode if you want the alternative providers in the app.

## Public preview

The released bundle passed 14 offline Android 14 emulator checks covering an in-place update and Dropbox folder changes on an isolated budget copy. The maintainer reports that the app looks fine on their phone. Full phone-to-YNAB-Windows sync testing for this bundle is still pending. Try sync on a separate budget copy first.

Built with help from Codex and Claude Code.

If you run into an issue, include your phone model, Android version and the steps to reproduce it. [Report an issue on GitHub](https://github.com/ysosrsss/classic-budget-plus-patches/issues).

## Community credits

Classic Budget Plus grew out of seeing the YNAB 4 community get the Android app syncing again after Dropbox changes. In April 2022, the app needed TLS 1.2. A separate wave of problems in 2026 brought another round of community fixes.

- **u/kronicd** shared the TLS 1.2 patch in 2022, then a certificate update and a patcher in 2026. [2022 patch](https://www.reddit.com/r/ynab4/comments/u4rlma/comment/i55m5ts/) · [2026 certificate update](https://www.reddit.com/r/ynab4/comments/1qiokvb/comment/o2j6sk2/) · [2026 patcher](https://www.reddit.com/r/ynab4/comments/1qiokvb/comment/odrpdqw/)
- **u/modrzew** compared the 2022 patched APK with the original and documented how to reproduce the fix. [Comparison and instructions](https://www.reddit.com/r/ynab/comments/u4bnmi/comment/i5m38l5/)
- **u/boco_23** pointed out the Dropbox certificate change in 2026. [Original comment](https://www.reddit.com/r/ynab4/comments/1qiokvb/comment/o0ualv6/)
- **u/Magoun** shared the 2026 compatibility fix that used Android's system trust store and cipher suites. [Dropbox compatibility fix](https://www.reddit.com/r/ynab4/comments/1qiokvb/comment/odpqthm/)

Thanks also to everyone who reported the breakages and tested the fixes.

## Build

Use Java 21, Android SDK Platform 36 and Build Tools 36. Configure the SDK through `ANDROID_HOME` or a local `local.properties`. Gradle reads GitHub Packages credentials from the user's Gradle configuration or the standard GitHub Actions environment. Run `gradlew.bat buildAndroid` on Windows or `./gradlew buildAndroid` on other platforms. This repository builds the production bundle by default. The output is under `patches/build/libs`.

## License

The patch code is distributed under GPL-3.0. See [LICENSE](LICENSE), [NOTICE](NOTICE) and [third-party notices](THIRD_PARTY_NOTICES.md). YNAB and Morphe are separate projects.
