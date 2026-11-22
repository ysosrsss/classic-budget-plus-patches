package app.classicbudget.patches.plus

import app.classicbudget.patches.shared.Constants.COMPATIBILITY_YNAB_CLASSIC
import app.classicbudget.patches.shared.PlusResources.addActivity
import app.classicbudget.patches.shared.PlusResources.addString
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.w3c.dom.Element

private const val SYNC_SERVICE = "Lcom/youneedabudget/ynab/core/cloud/SyncService;"
private const val HEALTH = "Lorg/classicbudget/patch/SyncHealth;"
private const val CORE = "Lcom/youneedabudget/ynab/core"

internal val captureSyncConflicts = bytecodePatch {
    extendWith("extensions/extension.mpe")
    execute {
        val importer = mutableClassDefBy("$CORE/backend/h\$f;").methods.single {
            it.name == "a" && it.parameterTypes.toList() ==
                listOf("$CORE/backend/h\$c;", "$CORE/backend/h\$e;", "$CORE/c/k;") && it.returnType == "Z"
        }
        val index = importer.implementation!!.instructions.toList().withIndex().single {
            val message = (it.value as? ReferenceInstruction)?.reference as? StringReference
            message?.string == "Neither version dominates (CONFLICT) - ignoring"
        }.index
        importer.addInstructions(index + 1, """
            move-object/from16 v10, p2
            iget-object v11, p0, $CORE/backend/h${'$'}f;->b:$CORE/c/f;
            invoke-virtual {v11}, $CORE/c/f;->c()Landroid/database/sqlite/SQLiteDatabase;
            move-result-object v11
            invoke-virtual/range {p3 .. p3}, $CORE/c/k;->a()Ljava/lang/String;
            move-result-object v12
            invoke-static/range {v10 .. v12}, Lorg/classicbudget/patch/ConflictStore;->captureBudget(Ljava/lang/Object;Landroid/database/sqlite/SQLiteDatabase;Ljava/lang/String;)V
        """)
    }
}

private val healthResources = resourcePatch {
    execute {
        addActivity("org.classicbudget.patch.SyncStatusActivity", "@string/cb_plus_settings")
        addActivity("org.classicbudget.patch.ConflictReviewActivity",
            addString("cb_conflicts", "Sync conflicts", "Konflikty synchronizacji"))
        document("res/layout/activity_budget_overview.xml").use { doc ->
            val elements = doc.getElementsByTagName("*")
            val body = (0 until elements.length).map { elements.item(it) as Element }
                .single { it.getAttribute("android:id") == "@id/pull_to_refresh" }
            val notice = doc.createElement("org.classicbudget.patch.SyncNoticeView")
            notice.setAttribute("android:id", "@+id/cb_sync_health_notice")
            notice.setAttribute("android:layout_width", "match_parent")
            notice.setAttribute("android:layout_height", "wrap_content")
            notice.setAttribute("android:layout_below", "@id/container_summary")
            body.parentNode.insertBefore(notice, body)
            body.setAttribute("android:layout_below", "@id/cb_sync_health_notice")
        }
        document("res/layout/fragment_setup_link_dropbox.xml").use { doc ->
            val views = doc.getElementsByTagName("*")
            val intro = (0 until views.length).map { views.item(it) as Element }
                .single { it.getAttribute("android:id") == "@id/textView2" }
            val notice = doc.createElement("org.classicbudget.patch.SyncNoticeView")
            notice.setAttribute("android:id", "@+id/cb_sync_dropbox_reconnect")
            notice.setAttribute("android:tag", "dropbox-reconnect")
            notice.setAttribute("android:layout_width", "match_parent")
            notice.setAttribute("android:layout_height", "wrap_content")
            notice.setAttribute("android:layout_above", "@id/textView2")
            notice.setAttribute("android:layout_marginBottom", "8dp")
            intro.parentNode.insertBefore(notice, intro)
        }
    }
}

@Suppress("unused")
val syncHealthPatch = bytecodePatch(
    name = "Sync warnings",
    description = "Shows an on-screen warning only when synchronization needs attention. " +
        "Status in Plus settings shows unsent transactions, schedules and budgeting changes. " +
        "For Syncthing, Plus reports local folder writes and leaves device delivery to Syncthing.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)
    dependsOn(plusSettingsPatch, healthResources, captureSyncConflicts)
    execute {
        val worker = mutableClassDefBy(SYNC_SERVICE).methods.single {
            it.name == "a" && it.parameterTypes.toList() == listOf("Ljava/lang/String;") &&
                it.returnType == "Landroid/content/Intent;"
        }
        val code = worker.implementation!!.instructions.toList()
        val end = code.indices.single { code[it].opcode == Opcode.RETURN_OBJECT }
        val result = (code[end] as OneRegisterInstruction).registerA
        val export = code.indices.single { index ->
            val ref = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == SYNC_SERVICE && ref.name == "d" && ref.returnType == "Ljava/lang/Exception;"
        }
        check(code[export + 1].opcode == Opcode.MOVE_RESULT_OBJECT)
        val failure = (code[export + 1] as OneRegisterInstruction).registerA
        worker.addInstruction(end,
            "invoke-static {p0, p1, v$result}, $HEALTH->finished(Landroid/content/Context;Ljava/lang/String;Landroid/content/Intent;)V")
        worker.addInstruction(export + 2,
            "invoke-static {p0, p1, v$failure}, $HEALTH->afterExport(Landroid/content/Context;Ljava/lang/String;Ljava/lang/Throwable;)V")
        worker.addInstruction(export,
            "invoke-static {p0, p1}, $HEALTH->beforeExport(Landroid/content/Context;Ljava/lang/String;)V")

        val handler = mutableClassDefBy(SYNC_SERVICE).methods.single {
            it.name == "onHandleIntent" && it.parameterTypes.toList() == listOf("Landroid/content/Intent;")
        }
        val unlink = handler.implementation!!.instructions.withIndex().single { (_, instruction) ->
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == "$CORE/cloud/c;" && ref.name == "b" &&
                ref.parameterTypes.toList() == listOf("Landroid/content/Context;") && ref.returnType == "V"
        }.index
        handler.addInstruction(unlink, "invoke-static {p0}, $HEALTH->dropboxRejected(Landroid/content/Context;)V")
    }
}
