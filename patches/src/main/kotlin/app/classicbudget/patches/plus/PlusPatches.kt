package app.classicbudget.patches.plus

import app.classicbudget.patches.shared.Constants.COMPATIBILITY_YNAB_CLASSIC
import app.classicbudget.patches.shared.PlusResources.addActivity
import app.classicbudget.patches.shared.PlusResources.addSettingsEntry
import app.classicbudget.patches.shared.PlusResources.addString
import app.classicbudget.patches.shared.PlusResources.currentPackage
import app.classicbudget.patches.shared.PlusResources.hasComponent
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

private const val P = "org.classicbudget.patch."
private const val PL = "Lorg/classicbudget/patch/"

// ---------------------------------------------------------------- Plus settings

internal val plusSettingsResources = resourcePatch {
    execute {
        addActivity(P + "ScheduledActivity", addString("cb_schedules", "Scheduled transactions", "Zlecenia stałe"))
        addActivity(P + "PayeeRulesActivity", addString("cb_payee_rules", "Payee rules", "Reguły płatników"))
        addActivity(P + "PlusSettingsActivity", addString("cb_plus_settings", "Plus settings", "Ustawienia Plus"))
        addActivity(P + "DiagnosticsActivity", addString("cb_diagnostics", "Diagnostics", "Diagnostyka"))
        addActivity(P + "PayeePlacesActivity", addString("cb_payee_places", "Payee places", "Miejsca płatników"))
        addActivity(P + "PayeePlaceEditorActivity", addString("cb_move_payee_place", "Move place", "Przenieś miejsce"))
        document("AndroidManifest.xml").use { doc ->
            val shareName = P + "PayeePlaceShareActivity"
            if (!hasComponent(doc, "activity", shareName)) {
                val app = doc.getElementsByTagName("application").item(0) as Element
                // Inherit the application label so the share sheet distinguishes TEST and PROD.
                val share = doc.createElement("activity").also {
                    it.setAttribute("android:name", shareName)
                    it.setAttribute("android:exported", "true")
                    it.setAttribute("android:theme", "@android:style/Theme.NoDisplay")
                    // The temporary receiver must not become the app task's entry intent after a cold share.
                    it.setAttribute("android:taskAffinity", "")
                    it.setAttribute("android:excludeFromRecents", "true")
                }
                val filter = doc.createElement("intent-filter")
                filter.appendChild(doc.createElement("action").also {
                    it.setAttribute("android:name", "android.intent.action.SEND")
                })
                filter.appendChild(doc.createElement("category").also {
                    it.setAttribute("android:name", "android.intent.category.DEFAULT")
                })
                filter.appendChild(doc.createElement("data").also {
                    it.setAttribute("android:mimeType", "text/plain")
                })
                share.appendChild(filter)
                app.appendChild(share)
            }
        }
        addSettingsEntry(
            "cbPlusSettings", "@string/cb_plus_settings",
            addString("cb_plus_settings_summary", "Language, display, account suggestions, payee rules",
                "Język, wygląd, podpowiadanie kont, reguły płatników"),
            P + "PlusSettingsActivity",
        )
    }
}

@Suppress("unused")
val plusSettingsPatch = bytecodePatch(
    name = "Plus settings",
    description = "Adds \"Plus settings\" to the app's Settings: language (phone, English, Polish), display, " +
        "account suggestions, my accounts and devices, payee rules. Needed to change the options of the other Plus patches.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)
    dependsOn(plusSettingsResources)
    extendWith("extensions/extension.mpe")
    execute {
        // Navigation belongs to Plus itself, so it also works without the sync patch.
        val overview = mutableClassDefBy("Lcom/youneedabudget/ynab/app/OverviewActivity;")
        val menu = overview.methods.single { it.name == "onCreateOptionsMenu" }
        val instructions = menu.implementation!!.instructions.toList()
        val end = instructions.indices.single { instructions[it].opcode == Opcode.RETURN }
        val result = (instructions[end] as OneRegisterInstruction).registerA
        menu.replaceInstruction(end, "invoke-static {p0, p1}, ${PL}PlusMenu;->menu(Landroid/app/Activity;Landroid/view/Menu;)V")
        menu.addInstruction(end + 1, "return v$result")
        overview.methods.single { it.name == "onOptionsItemSelected" }.addInstructions(0,
            """
                invoke-static {p0, p1}, ${PL}PlusMenu;->selected(Landroid/app/Activity;Landroid/view/MenuItem;)Z
                move-result v0
                if-eqz v0, :cb_plus_original_menu
                const/4 v0, 0x1
                return v0
                :cb_plus_original_menu
                nop
            """
        )
        // Plus destinations in the navigation drawer (076): one compact row under the original "Budget"
        // heading. The row lives inside that heading's view, so the drawer's header count and the
        // original position arithmetic (accounts start at position 2) stay unchanged.
        val drawer = mutableClassDefBy("Lcom/youneedabudget/ynab/app/NavigationDrawerFragment;")
        val create = drawer.methods.single {
            it.name == "a" && it.parameterTypes.joinToString("") == "Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;"
        }
        val body = create.implementation!!.instructions.toList()
        val budgetHeading = body.indices.single {
            body[it].opcode == Opcode.CONST && (body[it] as WideLiteralInstruction).wideLiteral == 0x7f040027L
        }
        val inflated = (budgetHeading until body.size).first { body[it].opcode == Opcode.MOVE_RESULT_OBJECT }
        val heading = (body[inflated] as OneRegisterInstruction).registerA
        create.addInstructions(inflated + 1, """
            invoke-static {v$heading}, ${PL}PlusMenu;->drawer(Landroid/view/View;)Landroid/view/View;
            move-result-object v$heading
        """)
        // Cache peer labels during the original metadata read, across every backend.
        val device = mutableClassDefBy("Lcom/youneedabudget/ynab/core/cloud/r;")
        val ctor = device.methods.single { it.name == "<init>" && it.parameterTypes.joinToString("") ==
            "Lcom/youneedabudget/ynab/core/cloud/z;Ljava/io/File;Ljava/io/File;" }
        val returns = ctor.implementation!!.instructions.toList().withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
        if (returns.size != 1) throw IllegalStateException("Device metadata constructor return")
        ctor.addInstruction(returns.single().index,
            "invoke-static {p2, p0}, ${PL}DeviceCatalog;->remember(Ljava/io/File;Ljava/lang/Object;)V")
        // §160: both registration hooks get the accessor (p2), data folder (p3) and devices folder (p4):
        // the GUID hook finishes a chosen takeover before the scan, the letter hook offers one at A–Z.
        // §157: the next device letter (Z -> AA in the original) passes DeviceLetters.next, which stops
        // a registration beyond Z; YNAB 4 for Windows ignores devices with longer ids.
        // Hooked at the single call site in the registering constructor, after move-result: inside a(String)
        // the "AA" branch jumps straight to the return label and would bypass code inserted before it (093).
        val register = device.methods.single { it.name == "<init>" && it.parameterTypes.joinToString("") ==
            "Landroid/content/Context;Lcom/youneedabudget/ynab/core/cloud/z;Ljava/io/File;Ljava/io/File;" }
        val calls = register.implementation!!.instructions.toList().withIndex().filter {
            val ref = (it.value as? ReferenceInstruction)?.reference as? MethodReference
            it.value.opcode == Opcode.INVOKE_DIRECT && ref?.definingClass == "Lcom/youneedabudget/ynab/core/cloud/r;" &&
                ref.name == "a" && ref.parameterTypes.joinToString("") == "Ljava/lang/String;" && ref.returnType == "Ljava/lang/String;"
        }
        if (calls.size != 1) throw IllegalStateException("Next device id call")
        val moveResult = register.implementation!!.instructions.toList()[calls.single().index + 1]
        if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT) throw IllegalStateException("Next device id result")
        val idRegister = (moveResult as OneRegisterInstruction).registerA
        register.addInstructions(calls.single().index + 2, """
            invoke-static {p1, p2, p3, p4, v$idRegister}, ${PL}DeviceLetters;->next(Landroid/content/Context;Ljava/lang/Object;Ljava/io/File;Ljava/io/File;Ljava/lang/String;)Ljava/lang/String;
            move-result-object v$idRegister
        """)
        val guidInstructions = register.implementation!!.instructions.toList()
        val guidCall = guidInstructions.indices.single {
            val ref = (guidInstructions[it] as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == "Lcom/youneedabudget/ynab/core/cloud/aa;" && ref.name == "a"
        }
        val guidResult = guidInstructions[guidCall + 1]
        check(guidResult.opcode == Opcode.MOVE_RESULT_OBJECT)
        val guidRegister = (guidResult as OneRegisterInstruction).registerA
        register.addInstructions(guidCall + 2, """
            invoke-static {p1, p2, p3, p4, v$guidRegister}, ${PL}DeviceTakeover;->registrationGuid(Landroid/content/Context;Ljava/lang/Object;Ljava/io/File;Ljava/io/File;Ljava/lang/String;)Ljava/lang/String;
            move-result-object v$guidRegister
        """)

        val cloud = mutableClassDefBy("Lcom/youneedabudget/ynab/core/cloud/b;")
        val syncBudget = cloud.methods.single { it.name == "<init>" && it.parameterTypes.joinToString("") ==
            "Landroid/content/Context;Lcom/youneedabudget/ynab/core/cloud/a;Lcom/youneedabudget/ynab/core/cloud/g;" }
        val syncInstructions = syncBudget.implementation!!.instructions.toList()
        val readOwnRegistration = syncInstructions.indices.single {
            val ref = (syncInstructions[it] as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == "Lcom/youneedabudget/ynab/core/cloud/r;" && ref.name == "<init>"
        }
        val selfRegister = (syncInstructions[readOwnRegistration] as FiveRegisterInstruction).registerG
        val dataRegister = (syncInstructions[readOwnRegistration] as FiveRegisterInstruction).registerE
        val ownRegister = (syncInstructions[readOwnRegistration] as FiveRegisterInstruction).registerC
        syncBudget.addInstructions(readOwnRegistration + 1, """
            invoke-virtual {v$ownRegister}, Lcom/youneedabudget/ynab/core/cloud/r;->e()Ljava/lang/String;
            move-result-object v4
            invoke-static {p1, v$dataRegister, v4}, ${PL}DeviceTakeover;->verifyOwnRegistration(Landroid/content/Context;Ljava/io/File;Ljava/lang/String;)V
        """)
        // The constructor has already resolved the backend and verified the data folder.
        // Insert before new-instance so a peer registration can never be read as our own mid-transfer.
        val newDevice = (readOwnRegistration - 1 downTo 0).first { syncInstructions[it].opcode == Opcode.NEW_INSTANCE }
        val deviceRegister = (syncInstructions[newDevice] as OneRegisterInstruction).registerA
        // Keep the branch label on the hook when the data folder comparison succeeds.
        syncBudget.replaceInstruction(newDevice,
            "invoke-static {p1, p0, p3, v$selfRegister}, ${PL}DeviceTakeover;->beforeSync(Landroid/content/Context;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;")
        syncBudget.addInstructions(newDevice + 1, """
            move-result-object v$selfRegister
            new-instance v$deviceRegister, Lcom/youneedabudget/ynab/core/cloud/r;
        """)
    }
}

@Suppress("unused")
val recoverDeviceLetterPatch = bytecodePatch(
    name = "Recover a device letter (advanced)",
    description = "Each synced budget uses a letter per device. YNAB 4 for Windows reads only A–Z " +
        "(26 places per budget). A normal app update keeps the existing registration. If Plus cannot " +
        "find its registration and a letter is free, this patch can offer your previous letter " +
        "before writing a new one. The phone model is only a clue. Register with the new letter if " +
        "unsure. Recover a letter only if it belonged to your previous installation and that " +
        "installation will never sync this budget again. Plus checks synchronization before changing " +
        "the registration. Available with Dropbox in the production edition and with supported " +
        "TEST/COPY profiles in the TEST edition. The offer requires no other YNAB edition on this phone.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)
    dependsOn(plusSettingsPatch)
    execute {
        val takeover = mutableClassDefBy("${PL}DeviceTakeover;")
        val enabled = takeover.methods.single { it.name == "earlyRecoveryEnabled" }
        val instructions = enabled.implementation!!.instructions.toList()
        val index = instructions.indices.single { instructions[it].opcode == Opcode.CONST_4 }
        val register = (instructions[index] as OneRegisterInstruction).registerA
        enabled.replaceInstruction(index, "const/4 v$register, 0x1")
    }
}

// ---------------------------------------------------------------- Reports (+ alerts, widget)

private const val WIDGET_LAYOUT = """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:id="@+id/cb_widget_root" android:layout_width="match_parent" android:layout_height="match_parent"
    android:orientation="vertical" android:padding="12dp" android:background="#E6143D4F">
    <TextView android:id="@+id/cb_widget_title" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textColor="#FFFFFF" android:textSize="15sp" android:textStyle="bold" android:singleLine="true" />
    <TextView android:id="@+id/cb_widget_line1" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textColor="#FFFFFF" android:textSize="13sp" />
    <TextView android:id="@+id/cb_widget_line2" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textColor="#FFD7D0" android:textSize="13sp" />
    <TextView android:id="@+id/cb_widget_line3" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textColor="#D0E8FF" android:textSize="13sp" />
</LinearLayout>
"""
private const val WIDGET_INFO = """<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android" android:minWidth="250dp" android:minHeight="110dp"
    android:updatePeriodMillis="1800000" android:initialLayout="@layout/cb_widget" android:resizeMode="horizontal|vertical" android:widgetCategory="home_screen" />
"""

internal val reportsResources = resourcePatch {
    execute {
        addActivity(P + "ReportsActivity", addString("cb_reports", "Reports", "Raporty"))
        get("res/layout/cb_widget.xml", false).writeText(WIDGET_LAYOUT)
        get("res/xml/cb_widget_info.xml", false).also { it.parentFile.mkdirs() }.writeText(WIDGET_INFO)
        val widgetLabel = addString("cb_widget_label", "Budget (Plus)", "Budżet (Plus)")
        document("AndroidManifest.xml").use { doc ->
            if (hasComponent(doc, "receiver", P + "BudgetWidget")) return@use
            val app = doc.getElementsByTagName("application").item(0) as Element
            val r = doc.createElement("receiver")
            r.setAttribute("android:name", P + "BudgetWidget"); r.setAttribute("android:exported", "false"); r.setAttribute("android:label", widgetLabel)
            val filter = doc.createElement("intent-filter")
            filter.appendChild(doc.createElement("action").also { it.setAttribute("android:name", "android.appwidget.action.APPWIDGET_UPDATE") })
            r.appendChild(filter)
            r.appendChild(doc.createElement("meta-data").also {
                it.setAttribute("android:name", "android.appwidget.provider"); it.setAttribute("android:resource", "@xml/cb_widget_info")
            })
            app.appendChild(r)
        }

    }
}

@Suppress("unused")
val reportsPatch = bytecodePatch(
    name = "Reports",
    description = "Read-only reports from the main menu: spending by category and payee (with trends), income vs expense, " +
        "net worth, by person (device), upcoming scheduled payments. Filters like YNAB 4 desktop. " +
        "Also a home-screen widget and an optional overspending alert. Never writes to the budget.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)
    dependsOn(reportsResources, plusSettingsPatch)
    extendWith("extensions/extension.mpe")
    execute { }
}

// ---------------------------------------------------------------- Display (dark theme, transitions, balance bars)


/**
 * AppTheme gets android:forceDarkAllowed=true (Android's own dark rendering in night mode). Morphe's
 * resource encoder resolves android:* names against the APK's old framework (API 25), where this API 29
 * attribute does not exist, so it is written by resource ID in the encoder's own unknown-name syntax.
 */
internal val displayResources = resourcePatch {
    execute {
        document("res/values/styles.xml").use { doc ->
            val styles = doc.getElementsByTagName("style")
            val theme = (0 until styles.length).map { styles.item(it) as Element }.single { it.getAttribute("name") == "AppTheme" }
            val items = theme.getElementsByTagName("item")
            if ((0 until items.length).none { (items.item(it) as Element).getAttribute("name") == FORCE_DARK_ALLOWED }) {
                theme.appendChild(doc.createElement("item").also { it.setAttribute("name", FORCE_DARK_ALLOWED); it.textContent = "true" })
            }
        }
    }
}
private const val FORCE_DARK_ALLOWED = "r0x0101058c" // android:forceDarkAllowed

// Dark theme: AppTheme cannot carry android:forceDarkAllowed here (Morphe's resource encoder does not know that API 29
// attribute), so Display turns the window renderer's force-dark on at runtime instead (Display.applyForceDark).

/** Application.onCreate of the app (NewUIApp): the class that logs "NewUIApp starting up". */
internal object AppOnCreateFingerprint : Fingerprint(
    name = "onCreate",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(string("NewUIApp starting up")),
)

@Suppress("unused")
val displayPatch = bytecodePatch(
    name = "Dark theme and no screen-transition jump",
    description = "Dark theme drawn by Android's own dark rendering (follow the phone, always dark, or off; Android 10+), " +
        "switched on for each screen at runtime. Original screen transitions remain enabled by default; " +
        "they can be disabled in Plus settings to fix the jump on affected phones.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)
    dependsOn(displayResources, plusSettingsPatch)
    extendWith("extensions/extension.mpe")

    execute {
        // 1. Every Activity.overridePendingTransition(II)V in the app goes through Display.transition.
        var calls = 0
        classDefForEach { classDef ->
            if (!classDef.type.startsWith("Lcom/youneedabudget/")) return@classDefForEach
            val has = classDef.methods.any { m -> m.implementation?.instructions?.any { isTransition(it) } == true }
            if (!has) return@classDefForEach
            mutableClassDefBy(classDef).methods.forEach { method ->
                val list = method.implementation?.instructions?.toList() ?: return@forEach
                list.forEachIndexed { index, insn ->
                    if (!isTransition(insn)) return@forEachIndexed
                    val r = insn as FiveRegisterInstruction
                    method.replaceInstruction(index, "invoke-static { v${r.registerC}, v${r.registerD}, v${r.registerE} }, ${PL}Display;->transition(Landroid/app/Activity;II)V")
                    calls++
                }
            }
        }
        if (calls == 0) throw IllegalStateException("No screen transitions found")

        // 2. Display.install(app) at the end of Application.onCreate (before its final return).
        AppOnCreateFingerprint.method.let { method ->
            val last = method.implementation!!.instructions.toList().indexOfLast { it.opcode == Opcode.RETURN_VOID }
            method.addInstruction(last, "invoke-static { p0 }, ${PL}Display;->install(Landroid/app/Application;)V")
        }
    }
}

private fun isTransition(insn: com.android.tools.smali.dexlib2.iface.instruction.Instruction): Boolean =
    insn.opcode == Opcode.INVOKE_VIRTUAL &&
        ((insn as ReferenceInstruction).reference as MethodReference).let { it.name == "overridePendingTransition" && it.parameterTypes.size == 2 }

// ---------------------------------------------------------------- Account suggestions and payee rules

private const val DEFAULT_SQL = "SELECT     t.accountId FROM       txnTable t LEFT OUTER JOIN accountTable a ON t.accountId = a._id WHERE      a.hidden = 0 AND t.deleted = 0 ORDER BY   t._id DESC LIMIT 1"

internal object DefaultSqlFingerprint : Fingerprint(returnType = "V", filters = listOf(string(DEFAULT_SQL)))

@Suppress("unused")
val accountSuggestionsPatch = bytecodePatch(
    name = "Account suggestions and payee rules",
    description = "In the transaction editor, suggest accounts from your own entries and your own accounts " +
        "(a shared budget: not your partner's), and apply payee rules (payee is/contains X -> account and/or category). " +
        "An optional checkbox in Add Transaction opens a rule to confirm after saving.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)
    dependsOn(plusSettingsPatch)
    extendWith("extensions/extension.mpe")

    execute {
        val default = DefaultSqlFingerprint.method
        val instructions = default.implementation!!.instructions.toList()
        // The first occurrence belongs to the original diagnostic. Replace the one used by the query.
        val index = instructions.indexOfLast {
            it.opcode == Opcode.CONST_STRING &&
                ((it as ReferenceInstruction).reference as com.android.tools.smali.dexlib2.iface.reference.StringReference)
                    .string == DEFAULT_SQL
        }
        check(index >= 0)
        val register = (instructions[index] as OneRegisterInstruction).registerA
        default.replaceInstruction(index, "invoke-static {}, ${PL}AccountSuggest;->defaultSql()Ljava/lang/String;")
        default.addInstructions(index + 1, "move-result-object v$register")

        val sources = "${PL}SuggestionSource;"
        val editorType = "Lcom/youneedabudget/ynab/app/edit/TxnEntryActivity;"
        val editor = mutableClassDefBy(editorType)
        val autoFill = editor.methods.single { it.name == "d" && it.parameterTypes == listOf("Ljava/lang/String;") }
        val lookupTypes = "Lcom/youneedabudget/ynab/core/c/f;Ljava/lang/String;"
        listOf("a" to "lookupAccount", "b" to "lookupCategory").forEach { (original, replacement) ->
            val code = autoFill.implementation!!.instructions.toList()
            val lookup = code.indices.single { position ->
                val ref = (code[position] as? ReferenceInstruction)?.reference as? MethodReference
                ref?.definingClass == "Lcom/youneedabudget/ynab/core/app/a/a;" && ref.name == original &&
                    ref.parameterTypes.joinToString("") == lookupTypes && ref.returnType == "J"
            }
            val args = code[lookup] as FiveRegisterInstruction
            autoFill.replaceInstruction(
                lookup, "invoke-static {p0, v${args.registerC}, v${args.registerD}}, $sources->$replacement($editorType${lookupTypes})J",
            )
        }
        val gps = editor.methods.single { it.name == "f" && it.parameterTypes == listOf("Ljava/lang/String;") }
        val gpsFill = gps.implementation!!.instructions.toList().indices.single { position ->
            val ref = (gps.implementation!!.instructions.elementAt(position) as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == editorType && ref.name == "d" && ref.parameterTypes == listOf("Ljava/lang/String;")
        }
        // This instruction is reached only after the native GPS/payee guard passed.
        gps.addInstruction(gpsFill, "invoke-static {p0, p1}, $sources->locationPayee(Landroid/app/Activity;Ljava/lang/String;)V")
        // a(J) clears the native account bit 0x10. d(J) clears the category bit 0x02.
        listOf(Triple("e", "Ljava/lang/String;", "manualPayee"), Triple("a", "J", "manualAccount"),
            Triple("d", "J", "manualCategory")).forEach { (name, type, call) ->
            editor.methods.single { it.name == name && it.parameterTypes == listOf(type) }.addInstruction(
                0, "invoke-static {p0}, $sources->$call(Landroid/app/Activity;)V",
            )
        }
        val openBudget = mutableClassDefBy("Lcom/youneedabudget/ynab/app/edit/TxnEntryActivity\$1;").methods.single {
            it.name == "a" && it.parameterTypes == listOf("Lcom/youneedabudget/ynab/core/backend/j;")
        }
        val opening = openBudget.implementation!!.instructions.toList()
        val applyDefault = opening.indices.single { position ->
            val ref = (opening[position] as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == editorType && ref.name == "c" &&
                ref.parameterTypes.joinToString("") == "${editorType}J" && ref.returnType == "V"
        }
        val defaultArgs = opening[applyDefault] as FiveRegisterInstruction
        openBudget.addInstruction(
            applyDefault, "invoke-static {v${defaultArgs.registerC}, v${defaultArgs.registerD}, v${defaultArgs.registerE}}, $sources->defaultAccount(${editorType}J)V",
        )

        val form = mutableClassDefBy("Lcom/youneedabudget/ynab/app/edit/n;").methods.single {
            it.name == "a" && it.returnType == "Landroid/view/View;" &&
                it.parameterTypes.joinToString("") == "Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;"
        }
        val formInstructions = form.implementation!!.instructions.toList()
        val returned = formInstructions.indices.single { formInstructions[it].opcode == Opcode.RETURN_OBJECT }
        val formRegister = (formInstructions[returned] as OneRegisterInstruction).registerA
        val rules = "${PL}TransactionPayeeRule;"
        form.addInstructions(returned, """
            invoke-static/range {v$formRegister .. v$formRegister}, $rules->attach(Landroid/view/View;)V
            invoke-static/range {v$formRegister .. v$formRegister}, $sources->attach(Landroid/view/View;)V
        """.trimIndent())

        val operationType = "Lcom/youneedabudget/ynab/core/backend/r;"
        val write = mutableClassDefBy(operationType).methods.single {
            it.name == "b" && it.parameterTypes.isEmpty() && it.returnType == "V"
        }
        val writeInstructions = write.implementation!!.instructions.toList()
        val insertions = writeInstructions.indices.filter { index ->
            val instruction = writeInstructions[index]
            val target = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            instruction.opcode == Opcode.INVOKE_DIRECT && target?.definingClass == operationType &&
                target.name == "a" && target.returnType == "J" &&
                target.parameterTypes.joinToString("") == "Lcom/youneedabudget/ynab/core/app/a/f;J"
        }
        check(insertions.size == 2) { "Expected a root transaction write and a split child write" }
        val rootResult = insertions.first() + 1
        check(writeInstructions[rootResult].opcode == Opcode.MOVE_RESULT_WIDE)
        val row = (writeInstructions[rootResult] as OneRegisterInstruction).registerA
        write.addInstruction(rootResult + 1, "invoke-static {p0, v$row, v${row + 1}}, $rules->captureSavedRow(Ljava/lang/Object;J)V")

        val taskType = "Lcom/youneedabudget/ynab/core/app/a/e;"
        val resultType = "Lcom/youneedabudget/ynab/core/app/a/e\$a;"
        val task = mutableClassDefBy(taskType).methods.single {
            it.name == "a" && it.parameterTypes.joinToString("") == "[Ljava/lang/Void;" && it.returnType == resultType
        }
        val taskInstructions = task.implementation!!.instructions.toList()
        val returnIndex = taskInstructions.indices.single { taskInstructions[it].opcode == Opcode.RETURN_OBJECT }
        val result = (taskInstructions[returnIndex] as OneRegisterInstruction).registerA
        check(result != 0 && task.implementation!!.registerCount <= 16)
        // Branches to the original return must also bind the completed native result.
        task.replaceInstruction(returnIndex, "nop")
        task.addInstructions(returnIndex + 1, """
            iget-object v0, p0, $taskType->a:$operationType
            invoke-static {v0, v$result}, $rules->bindSaveResult(Ljava/lang/Object;Ljava/lang/Object;)V
            return-object v$result
        """.trimIndent())

        val saved = editor.methods.single {
            it.name == "a" && it.parameterTypes.joinToString("") == resultType
        }
        // The callback can report a failure; the extension checks the result before opening a draft.
        saved.addInstruction(0, "invoke-static {p0, p1}, $rules->afterSave(Landroid/app/Activity;Ljava/lang/Object;)V")
        editor.methods.single { it.name == "onSaveInstanceState" }.addInstructions(0, """
            invoke-static {p0, p1}, $rules->saveChoice(Landroid/app/Activity;Landroid/os/Bundle;)V
            invoke-static {p0, p1}, $sources->save(Landroid/app/Activity;Landroid/os/Bundle;)V
        """.trimIndent())
        editor.methods.single { it.name == "onCreate" }.addInstructions(0, """
            invoke-static {p0, p1}, $rules->restoreChoice(Landroid/app/Activity;Landroid/os/Bundle;)V
            invoke-static {p0, p1}, $sources->restore(Landroid/app/Activity;Landroid/os/Bundle;)V
        """.trimIndent())
    }
}

// Original chooser keeps its active/deleted filters, transfer exclusion and row identities.
@Suppress("unused")
val payeeSearchPatch = bytecodePatch(
    name = "Payee search: any word and no accents",
    description = "Find payees by any part of their name, ignoring case and accents (zabka finds Żabka). Stored names stay unchanged.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)
    extendWith("extensions/extension.mpe")
    execute {
        var calls = 0
        classDefForEach { definition ->
            if (definition.type != "Lcom/youneedabudget/ynab/app/edit/f\$4;") return@classDefForEach
            mutableClassDefBy(definition).methods.forEach { method ->
                if (method.name != "runQuery" || method.parameterTypes != listOf("Ljava/lang/CharSequence;")) return@forEach
                val implementation = method.implementation ?: return@forEach
                check(implementation.registerCount <= 16) { "Payee query register layout changed" }
                implementation.instructions.toList().forEachIndexed { index, instruction ->
                    val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    if (instruction.opcode != Opcode.INVOKE_VIRTUAL || ref?.definingClass != "Landroid/database/sqlite/SQLiteDatabase;" || ref.name != "rawQuery") return@forEachIndexed
                    val r = instruction as FiveRegisterInstruction
                    method.replaceInstruction(index, "invoke-static { v${r.registerC}, v${r.registerD}, v${r.registerE}, p1 }, ${PL}PayeeSearch;->query(Landroid/database/sqlite/SQLiteDatabase;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/CharSequence;)Landroid/database/Cursor;")
                    calls++
                }
            }
        }
        check(calls == 1) { "Expected exactly one original payee chooser query, got $calls" }
    }
}
