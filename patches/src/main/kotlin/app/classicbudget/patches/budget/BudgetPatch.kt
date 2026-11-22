package app.classicbudget.patches.budget

import app.classicbudget.patches.shared.Constants.COMPATIBILITY_YNAB_CLASSIC
import app.classicbudget.patches.shared.PlusResources.addActivity
import app.classicbudget.patches.shared.PlusResources.addPermission
import app.classicbudget.patches.shared.PlusResources.addService
import app.classicbudget.patches.shared.PlusResources.addSettingsEntry
import app.classicbudget.patches.shared.PlusResources.addString
import app.classicbudget.patches.plus.plusSettingsPatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val P = "org.classicbudget.patch."
private const val PL = "Lorg/classicbudget/patch/"
private const val B = "Lcom/youneedabudget/ynab/core/backend"
private const val ITEM = "$B/h\$e;"

private fun MutableClass.method(name: String, signature: String): MutableMethod =
    methods.singleOrNull { it.name == name && it.parameterTypes.joinToString("", "(", ")") + it.returnType == signature }
        ?: throw IllegalStateException("Missing $type->$name$signature")

private fun BytecodePatchContext.cls(type: String) = mutableClassDefBy(type)

internal val budgetResources = resourcePatch {
    execute {
        addActivity(P + "BudgetActivity", addString("cb_budget", "Budget amounts (experimental)", "Kwoty budżetu (eksperymentalne)"))
        // Reading the budget index continues with the screen off or another app in front.
        addService(P + "BudgetIndexService")
        addPermission("android.permission.WAKE_LOCK")

    }
}

/**
 * Budgeting from the phone. The original Android app only reads budgeted amounts; this adds the
 * desktop's own monthlyCategoryBudget items to the original diff export (see BudgetExport) and keeps
 * an index of budget-cell notes from the files the original importer reads (see BudgetNotes), because
 * desktop clears a note that an incoming item does not carry.
 */
@Suppress("unused")
val budgetPatch = bytecodePatch(
    name = "Budget amounts from the phone",
    description = "Experimental: set budgeted amounts, zero a month or quick-budget (last month / average) from the phone. " +
        "Writes the same monthlyCategoryBudget items as YNAB 4 desktop, keeps budget notes, and refuses edits it cannot do " +
        "safely. Limited to TEST and COPY budgets.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)
    dependsOn(budgetResources, plusSettingsPatch)
    extendWith("extensions/extension.mpe")

    execute {
        // 1. Exporter item list: append unsent outbox rows (same array type).
        cls("$B/e;").method("e", "(Lcom/youneedabudget/ynab/core/c/f;)[$ITEM").let { m ->
            val insns = m.implementation!!.instructions.toList()
            val ret = insns.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
            if (ret.size != 1) throw IllegalStateException("Exporter item list return: ${ret.size}")
            val r = (ret.single().value as OneRegisterInstruction).registerA
            m.addInstructions(
                ret.single().index,
                """
                    invoke-static {p1, v$r}, ${PL}BudgetExport;->items(Ljava/lang/Object;[Ljava/lang/Object;)[Ljava/lang/Object;
                    move-result-object v$r
                    check-cast v$r, [$ITEM
                """,
            )
        }
        // 2. Marker after publish: our items are marked by us and removed from what the original marks.
        cls("$B/e;").method("a", "(Lcom/youneedabudget/ynab/core/c/f;[$ITEM)V").addInstructions(
            0,
            """
                invoke-static {p1, p2}, ${PL}BudgetExport;->sent(Ljava/lang/Object;[Ljava/lang/Object;)[Ljava/lang/Object;
                move-result-object p2
                check-cast p2, [$ITEM
            """,
        )
        // 3. Item serializer: our items written field for field, everything else unchanged.
        cls("$B/e\$a;").method("a", "($ITEM" + "Ljava/lang/reflect/Type;Lcom/d/a/r;)Lcom/d/a/l;").addInstructions(
            0,
            """
                invoke-static {p1}, ${PL}BudgetExport;->serialize(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                if-eqz v0, :cb_budget_original_item
                check-cast v0, Lcom/d/a/l;
                return-object v0
                :cb_budget_original_item
                nop
            """,
        )
        // 4. Importer (full file and diffs): note/month index sees the same text, unchanged.
        for (name in listOf("a", "b")) {
            cls("$B/h;").method(name, "(Ljava/io/Reader;)V").addInstructions(
                0,
                """
                    invoke-static {p1}, ${PL}BudgetNotes;->tee(Ljava/io/Reader;)Ljava/io/Reader;
                    move-result-object p1
                """,
            )
        }
    }
}
