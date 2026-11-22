package org.classicbudget.patch;

/** Shares the existing editor and local rule storage without duplicating a settings page. */
public final class PayeeRulesActivity extends PlusSettingsActivity {
    protected boolean rulesOnly() { return true; }
}
