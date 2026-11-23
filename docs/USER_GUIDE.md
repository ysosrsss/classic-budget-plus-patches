# Using YNAB Plus

**YNAB Plus** is the app installed by the **Classic Budget Plus** Morphe bundle. Start with the [installation steps](../README.md#install-with-morphe). The blue **Plus** button on the budget screen opens the everyday tools, and **Plus settings** holds the options for this phone.

[Add a transaction](#add-a-transaction) · [Search and save filters](#search-and-save-filters) · [Payee rules and nearby payees](#payee-rules-and-nearby-payees) · [Budgeting](#budgeting) · [Scheduled transactions](#scheduled-transactions) · [Reports](#reports) · [Sync and budget copies](#sync-and-budget-copies) · [Appearance and widgets](#appearance-and-widgets)

## Add a transaction

- Tap **Add Transaction** in the app, or add one of the **New transaction** widgets to your Android home screen. The 1×1 **+** and the wider 2×1 widget both open the full transaction form.
- To keep adding transactions after a save, set the category-balance message to appear above the add button in **Plus settings → Display**. You can use its original placement or turn it off there as well.
- Choose **Compact amount** under **Plus settings → Transaction form** if you want to see the payee, category and account alongside the amount. The original layout remains the default.
- Search for a payee using part of any word, with or without accents.

## Search and save filters

1. Open **Plus → Search transactions**. Choose a field such as payee, account, category, memo, amount or check number. Combine it with a date range, amount, reconciliation or approval status, or flag.
2. Use **Saved filters → Needs a category** to find entries that need a category and **Unreconciled** to review entries that still need reconciliation. Use **Save current filter** to name a combination you use often. These filters are saved for this budget on this phone.
3. Tap an entry to review it or open it in the normal editor. Select entries to see their count and total. Choose **Change category** to preview and recategorize eligible entries together. Split entries, transfers, reconciled entries and other unsupported rows are skipped by the bulk action.

## Payee rules and nearby payees

- Open **Plus settings → Account suggestions → Payee rules** to add a rule based on an exact payee name or a fragment. Set the account, the category, or both. Rules are local to this phone.
- To create a rule as you go, choose the payee and the values you want and tick **Remember rule** in **Add Transaction**. After the transaction saves, Plus opens a rule for you to review. A rule saved this way goes to the top of the list.
- **Rule applied** appears when a saved rule filled a field. Press and hold the text to see which field or fields it filled. You can hide this status under **Plus settings → Account suggestions**.
- For each field, the first matching rule on the list that sets that field takes priority. For example, a rule that sets the account and another that sets the category can both apply to the same payee. If both set the category, the earlier rule takes priority.
- Without a rule for a field, Plus can use the usual transaction suggestions. In **Account suggestions**, choose separately how Plus suggests the default account and the account for a selected payee. **My accounts** and your device choices let you prefer your own accounts and entries in a shared budget.
- For nearby suggestions, turn on **Nearby payees (GPS)** in **Account suggestions** and grant precise location. In the payee picker, **Near You** orders saved locations by distance. Tap **Edit → Forget Location** to remove one. The original blue icon in the form lets you remove the current location from the new transaction before it is saved.
- Open **Plus settings → Budget and sync → Payee places (GPS)** to manage saved locations. To move one, open it in Google Maps and share a new pin back to Plus, or enter coordinates and confirm.

[Example of Remember rule and Rule applied](screenshots/remember-payee-rule.png)

## Budgeting

Open **Plus → Budgeting** and pick a month. The table shows budgeted, spent and available amounts, with group totals. Tap a category's amounts or open its menu to make changes.

| Task | What to use |
| --- | --- |
| Assign money | Edit the budgeted amount directly. In the category menu, you can also use the calculator or a suggested amount based on earlier months or scheduled transactions. |
| Reallocate money | Move an amount from one category to another. Review the resulting amounts before confirming. |
| Work on several categories | Use **Quick budget** to fill empty budgeted amounts. **Zero month** resets the budgeted amount. **Available → 0** adjusts the budgeted amount to bring the available balance to zero. Each bulk operation has its own exclusions. |
| Set a monthly target | In the category menu choose a goal. **Set aside each month** checks what you added this month. **Refill using the opening balance** also considers money carried into the month. The goal view shows what's still needed. |
| Review a category | View its transactions for the month, add a category note, or choose how overspending carries forward. |
| See if a change synced | Tap the sync indicator below the month name. **Budget notes** also shows the status of its background refresh. |

## Scheduled transactions

- Open **Plus → Scheduled transactions**. Search the list or tap an entry to edit it. Use **+ Schedule** for a regular transaction and **+ Transfer** for a simple scheduled transfer. Set the amount, account, first date, frequency and memo as needed.
- Use **Upcoming** to preview recurring payments and balances over 30, 60 or 90 days. You can also enter the next occurrence in the register manually. With WebDAV or a local folder, let YNAB 4 for Windows handle automatic posting.

## Reports

Open **Plus → Reports**. Use the filters at the top to choose dates and, where available, categories, accounts, payees or the device that last edited an entry. Filters are saved per report. **More** can reset them and change report visibility or orientation.

- **Categories and Payees** show spending. Use the chart or ranked list and tap an amount to inspect the transactions. Search category groups, including hidden categories, or select all items in a group.
- **Spending Trends** shows monthly spending by group. Drag across the chart to change the selected month, or tap a segment to focus it and open its categories or transactions.
- **Income / expense** has a monthly chart and a table. Turn the phone sideways for the wider table or set rotation in **More**. Sort income sources alphabetically or by total.
- **Net worth** shows assets and debt over time for the selected accounts.
- **Upcoming**, **By device**, **Budget analysis** and **Comparison** can be enabled in **More → Report visibility**. The comparison uses the same filters for both periods. The device report tracks which device last edited an entry.
- In supported reports, **More → Export** offers CSV or Print / PDF. Transaction lists have their own search and totals. **Hide amounts** is available in the reports and budgeting screens.

## Sync and budget copies

- For regular Dropbox use, choose **Default location** when linking your account. For an isolated copy or a separately stored budget, choose **A different Dropbox folder** and type the Dropbox path beginning with `/`, for example `/Budgets/Household`. It should contain `.ynabSettings.yroot`.
- To switch Dropbox folders after setup, use **Plus settings → Budget and sync → Dropbox folder**. Send any pending changes first. YNAB Plus reloads the selected location and keeps the Dropbox sign-in.
- For a fallback, enable **Sync: Koofr, WebDAV and Syncthing folder** in Morphe. In the app, **Koofr** and **Other WebDAV** use server connections. **Folder (Syncthing)** selects a directory on your phone and Syncthing handles copying it. Use **Change sync method** for the switch and make sure the current connection has sent its changes. Try the full round trip with Windows on a separate budget copy before using a new provider for daily work.
- **Sync status** shows pending changes and conflicts. Open **Diagnostics** to capture a short recording or export the app log. Review logs before sharing them.
- **Devices** shows letters in use and the remaining slots. The optional **Recover a device letter (advanced)** patch is for people who need to reuse an old slot during registration.

[Example of a typed Dropbox path](screenshots/dropbox-folder.png)

## Appearance and widgets

- **Plus settings → Language** follows the phone, English or Polish for Plus screens. **Display** controls dark theme, transitions, the transaction layout and the message shown after saving a transaction.
- Turn on the optional daily overspending notice if you want a heads-up when opening a budget.
- Add the **Budget (Plus)** widget for current-month spending, overspent categories and upcoming payments. The widget respects the app's amount-hiding and PIN settings. The **New transaction** widgets open the editor directly.
- To verify the refreshed public build, open **Plus settings** and check the bundle version in the header.
