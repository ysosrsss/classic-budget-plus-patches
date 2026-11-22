package app.classicbudget.patches.sync

import app.classicbudget.patches.shared.Constants.COMPATIBILITY_YNAB_CLASSIC
import app.classicbudget.patches.plus.captureSyncConflicts
import app.classicbudget.patches.shared.PlusResources.addActivity
import app.classicbudget.patches.shared.PlusResources.addString
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.w3c.dom.Element

private const val P = "Lorg/classicbudget/patch"
private const val Y = "Lcom/youneedabudget/ynab"

private val syncResourcesPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { doc ->
            // OkHttp (WebDAV / Koofr) needs Android 5.0.
            val manifest = doc.getElementsByTagName("manifest").item(0) as Element
            val uses = doc.getElementsByTagName("uses-sdk")
            val sdk = if (uses.length > 0) uses.item(0) as Element
            else doc.createElement("uses-sdk").also { manifest.insertBefore(it, manifest.firstChild) }
            val min = sdk.getAttribute("android:minSdkVersion").toIntOrNull() ?: 1
            if (min < 21) sdk.setAttribute("android:minSdkVersion", "21")
        }
        addActivity("org.classicbudget.patch.BackendChooserActivity", addString("cb_sync", "Sync", "Synchronizacja"))
        addActivity("org.classicbudget.patch.WebDavSetupActivity", "WebDAV")
        addActivity("org.classicbudget.patch.FolderSetupActivity", addString("cb_folder_test", "Folder (Syncthing)", "Folder (Syncthing)"))
        addActivity("org.classicbudget.patch.SyncSwitchActivity", addString("cb_switch_sync", "Change sync method", "Zmień synchronizację"))
        addActivity(
            "org.classicbudget.patch.SyncResetActivity",
            addString("cb_switch_sync_run", "Changing sync method", "Zmiana synchronizacji"),
            mapOf("android:process" to ":cbreset", "android:excludeFromRecents" to "true"),
        )
        addActivity("org.classicbudget.patch.ConflictReviewActivity", addString("cb_conflicts", "Sync conflicts", "Konflikty synchronizacji"))

        val intro = addString(
            "cb_setup_intro",
            "Choose how this phone syncs the budget: Dropbox, Koofr / WebDAV or a folder synced by Syncthing. " +
                "Koofr / WebDAV and the folder are a fallback when Dropbox is unavailable (in the TEST app: only inside YNAB4_PLUS_TEST).",
            "Wybierz, jak telefon synchronizuje budżet: Dropbox, Koofr / WebDAV albo folder synchronizowany przez Syncthing. " +
                "Koofr / WebDAV i folder to zapas na wypadek awarii Dropboxa (w aplikacji TEST: tylko w YNAB4_PLUS_TEST).",
        )
        val choose = addString("cb_setup_choose", "Choose sync", "Wybierz synchronizację")
        document("res/layout/fragment_setup_link_dropbox.xml").use { doc ->
            val all = doc.getElementsByTagName("*")
            var hits = 0
            for (i in 0 until all.length) {
                val node = all.item(i) as Element
                val id = node.getAttribute("android:id")
                if (id.endsWith("/textView2")) { node.setAttribute("android:text", intro); hits++ }
                if (id.endsWith("/button_link_dropbox")) { node.setAttribute("android:text", choose); hits++ }
            }
            if (hits != 2) throw IllegalStateException("Setup layout: $hits of 2 views found")
        }
    }
}

private fun Instruction.string(): String? =
    if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) ((this as ReferenceInstruction).reference as StringReference).string else null

private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.calls(definingClass: String, name: String, signature: String? = null): Boolean {
    val m = method() ?: return false
    if (m.definingClass != definingClass || m.name != name) return false
    return signature == null || m.parameterTypes.joinToString("", "(", ")") + m.returnType == signature
}

private fun MutableMethod.insns(): List<Instruction> = implementation!!.instructions.toList()

private fun MutableMethod.single(what: String, predicate: (Instruction) -> Boolean): Int {
    val hits = insns().withIndex().filter { predicate(it.value) }.map { it.index }
    if (hits.size != 1) throw IllegalStateException("$what: ${hits.size} matches in $definingClass->$name")
    return hits[0]
}

private fun MutableClass.method(name: String, signature: String): MutableMethod =
    methods.singleOrNull { it.name == name && it.parameterTypes.joinToString("", "(", ")") + it.returnType == signature }
        ?: throw IllegalStateException("Missing $type->$name$signature")

private fun BytecodePatchContext.cls(type: String) = mutableClassDefBy(type)

/** Inserts [smali] in front of a jump target without stealing its label: the label keeps pointing at a nop. */
private fun MutableMethod.beforeLabelled(index: Int, smali: String, original: String) {
    replaceInstruction(index, "nop")
    addInstructions(index + 1, smali + "\n" + original)
}

private fun smaliString(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

@Suppress("unused")
val syncPatch = bytecodePatch(
    name = "Sync: Koofr, WebDAV and Syncthing folder",
    description = "Adds a sync chooser to the first screen and \"Change sync method\" to Plus settings: Dropbox " +
        "(unchanged), Koofr / any WebDAV server, or a folder synced by Syncthing. Writes are journaled and " +
        "published atomically; conflicting edits are kept for review instead of being silently dropped. " +
        "Meant as a fallback when Dropbox is unavailable: copy the budget folder there and choose it. With " +
        "\"Production edition\" any folder works; the TEST app only accepts folders inside YNAB4_PLUS_TEST. Also turns off the app's crash reporting (it would send sync errors to the " +
        "original vendor).",
    default = false,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)
    dependsOn(syncResourcesPatch, captureSyncConflicts)
    extendWith("extensions/extension.mpe")

    execute {
        // A delayed old-profile SYNC must never create an empty database after a reset.
        cls("$Y/core/cloud/SyncService;").method("onHandleIntent", "(Landroid/content/Intent;)V").addInstructions(
            0,
            """
                invoke-static {p0, p1}, $P/SyncLifecycle;->allowIntent(Landroid/content/Context;Landroid/content/Intent;)Z
                move-result v0
                if-nez v0, :cb_sync_allowed
                return-void
                :cb_sync_allowed
                nop
            """,
        )
        // 1. Cloud provider factory: Dropbox provider -> selector (Dropbox / WebDAV / folder).
        cls("$Y/core/app/d;").method("onCreate", "()V").let { m ->
            val i = m.single("provider new-instance") { it.opcode == Opcode.NEW_INSTANCE && (it as ReferenceInstruction).reference.toString() == "$Y/core/cloud/a/a;" }
            val r = (m.insns()[i] as OneRegisterInstruction).registerA
            if (!m.insns()[i + 1].calls("$Y/core/cloud/a/a;", "<init>")) throw IllegalStateException("provider <init>")
            m.replaceInstruction(i, "new-instance v$r, $P/BackendSelector;")
            m.replaceInstruction(i + 1, "invoke-direct {v$r}, $P/BackendSelector;-><init>()V")
        }

        // 2. Application start: "change sync" reset process + no vendor crash reporting.
        cls("$Y/app/NewUIApp;").method("onCreate", "()V").let { m ->
            val crash = m.single("crash reporting init") { it.calls("Lb/a/a/a/c;", "a", "(Landroid/content/Context;[Lb/a/a/a/i;)Lb/a/a/a/c;") }
            if (m.insns().getOrNull(crash + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT) throw IllegalStateException("crash init result used")
            m.replaceInstruction(crash, "nop")
            m.addInstructions(
                0,
                """
                    invoke-static {p0}, $P/SyncResetActivity;->resetProcess(Landroid/app/Application;)Z
                    move-result v0
                    if-eqz v0, :cb_main_process
                    return-void
                    :cb_main_process
                    nop
                """,
            )
        }

        // 3. Sync errors: private diagnostic instead of the vendor crash log.
        for (type in listOf("$Y/core/cloud/w;", "$Y/core/f/b;")) {
            val c = cls(type)
            var hits = 0
            c.methods.forEach { m ->
                m.implementation ?: return@forEach
                m.insns().withIndex().filter { it.value.calls("Lcom/b/a/a;", "a", "(Ljava/lang/Throwable;)V") }.forEach { (i, insn) ->
                    val r = (insn as FiveRegisterInstruction).registerC
                    m.replaceInstruction(i, "invoke-static {v$r}, $P/DavProvider;->diagnostic(Ljava/lang/Throwable;)V")
                    hits++
                }
            }
            if (hits != 1) throw IllegalStateException("$type crash log calls: $hits")
        }

        // 4. Exporter: journal the committed export, never log exported JSON.
        val exporter = cls("$Y/core/backend/e;")
        exporter.method("a", "(Landroid/content/Context;)V").let { m ->
            val log = m.single("export log") { it.calls("$Y/core/e/g;", "e", "(Ljava/lang/String;)V") }
            m.replaceInstruction(log, "nop")
            val commit = m.single("export commit") { it.calls("$Y/core/c/f;", "d", "()V") }
            m.addInstructions(commit + 1, "invoke-static {p1}, $P/WriteJournal;->onExportCommitted(Landroid/content/Context;)V")
        }

        // 5. Export keeps each transaction's knowledge and resolved-conflict flag.
        var sql = 0
        val select = Regex("^SELECT DISTINCT +(?:accountTable\\.|t\\.)")
        exporter.methods.forEach { m ->
            m.implementation ?: return@forEach
            m.insns().withIndex().forEach { (i, insn) ->
                val s = insn.string() ?: return@forEach
                if (!select.containsMatchIn(s)) return@forEach
                if (!s.contains("t.") || !s.contains(" FROM ")) throw IllegalStateException("Unexpected export SQL")
                val patched = s.replaceFirst(" FROM ", ", t.madeWithKnowledge AS exportKnowledge, t.resolvedConflict AS exportResolvedConflict FROM ")
                m.replaceInstruction(i, "const-string v${(insn as OneRegisterInstruction).registerA}, \"${smaliString(patched)}\"")
                sql++
            }
        }
        if (sql != 6) throw IllegalStateException("Export SQL: $sql of 6")
        fun mapping(cursor: String) = """
            const-string v3, "exportKnowledge"
            invoke-interface {$cursor, v3}, Landroid/database/Cursor;->getColumnIndexOrThrow(Ljava/lang/String;)I
            move-result v3
            invoke-direct {p0, $cursor, v3}, $Y/core/backend/e;->c(Landroid/database/Cursor;I)Ljava/lang/String;
            move-result-object v3
            iput-object v3, v2, $Y/core/backend/h${'$'}e;->madeWithKnowledge:Ljava/lang/String;
            const-string v3, "exportResolvedConflict"
            invoke-interface {$cursor, v3}, Landroid/database/Cursor;->getColumnIndexOrThrow(Ljava/lang/String;)I
            move-result v3
            invoke-direct {p0, $cursor, v3}, $Y/core/backend/e;->d(Landroid/database/Cursor;I)Ljava/lang/Boolean;
            move-result-object v3
            iput-object v3, v2, $Y/core/backend/h${'$'}e;->isResolvedConflict:Ljava/lang/Boolean;
        """
        exporter.methods.single { it.name == "a" && it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/database/Cursor;", "$Y/core/c/f;", "Ljava/lang/String;") }.let { m ->
            val ret = m.single("mapper return") { it.opcode == Opcode.RETURN_OBJECT && (it as OneRegisterInstruction).registerA == 2 }
            m.addInstructions(ret, mapping("p1"))
        }
        exporter.methods.single { it.name == "a" && it.parameterTypes.map { p -> p.toString() } == listOf("$Y/core/c/f;", "Ljava/lang/String;", "J") }.let { m ->
            val add = m.single("children add") { it.calls("Ljava/util/ArrayList;", "add", "(Ljava/lang/Object;)Z") && (it as FiveRegisterInstruction).let { f -> f.registerC == 0 && f.registerD == 2 } }
            m.addInstructions(add, mapping("v1"))
        }
        cls("$Y/core/backend/e\$a;").let { c ->
            var hits = 0
            c.methods.forEach { m ->
                m.implementation ?: return@forEach
                val list = m.insns()
                list.indices.reversed().forEach { i ->
                    val iget = list[i]
                    if (iget.opcode != Opcode.IGET_OBJECT || ((iget as ReferenceInstruction).reference as FieldReference).name != "madeWithKnowledge") return@forEach
                    val put = list.getOrNull(i + 1) ?: return@forEach
                    if (!put.calls("Lcom/d/a/o;", "a", "(Ljava/lang/String;Ljava/lang/String;)V")) return@forEach
                    val dto = (iget as TwoRegisterInstruction).registerB
                    val f = put as FiveRegisterInstruction
                    m.addInstructions(
                        i + 2,
                        """
                            const-string v${f.registerD}, "isResolvedConflict"
                            iget-object v${f.registerE}, v$dto, $Y/core/backend/h${'$'}e;->isResolvedConflict:Ljava/lang/Boolean;
                            invoke-virtual {v${f.registerC}, v${f.registerD}, v${f.registerE}}, Lcom/d/a/o;->a(Ljava/lang/String;Ljava/lang/Boolean;)V
                        """,
                    )
                    hits++
                }
            }
            if (hits != 2) throw IllegalStateException("Serializer knowledge writes: $hits of 2")
        }

        // 6. Scheduled transactions: entered on the phone only with Dropbox (as before); otherwise by the desktop.
        cls("$Y/core/backend/q;").method("a", "(Landroid/content/Context;)I").addInstructions(
            0,
            """
                invoke-static {}, Lapp/classicbudget/extension/SyncGate;->scheduledAllowed()Z
                move-result v0
                if-nez v0, :cb_scheduled_allowed
                const/4 v0, 0x0
                return v0
                :cb_scheduled_allowed
                nop
            """,
        )

        // 6b. After the original DiffImporter imported files from other devices on Koofr/WebDAV/folder,
        //     recompute balances of what the import changed with the original code (see SyncGate).
        cls("$Y/core/backend/f;").method("a", "(Landroid/content/Context;$Y/core/backend/j;$Y/core/cloud/b;)$Y/core/backend/f${'$'}c;").let { m ->
            val returns = m.insns().withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
            if (returns.size != 1) throw IllegalStateException("DiffImporter returns: ${returns.size}")
            val i = returns.single()
            val r = (m.insns()[i] as OneRegisterInstruction).registerA
            m.addInstructions(
                i,
                """
                    invoke-static {p2, v$r}, Lapp/classicbudget/extension/SyncGate;->afterDiffImport(Ljava/lang/Object;Ljava/lang/Object;)V
                """,
            )
            // p2 (the local budget) is never reassigned in this method (checked in smali, 13.11).
            m.addInstructions(
                0,
                """
                    invoke-static {p2}, Lapp/classicbudget/extension/SyncGate;->beforeDiffImport(Ljava/lang/Object;)V
                """,
            )
        }

        // 7. Local edits record the knowledge they were made with.
        cls("$Y/core/backend/r;").method("f", "($Y/core/c/al;$Y/core/c/al;)V").addInstructions(
            0,
            """
                iget-object v0, p0, $Y/core/backend/r;->a:$Y/core/c/f;
                invoke-virtual {v0}, $Y/core/c/f;->c()Landroid/database/sqlite/SQLiteDatabase;
                move-result-object v0
                invoke-static {v0}, $P/LocalEditKnowledge;->capture(Landroid/database/sqlite/SQLiteDatabase;)Ljava/lang/String;
                move-result-object v0
                invoke-virtual {p1, v0}, $Y/core/c/al;->g(Ljava/lang/String;)V
                invoke-virtual {p2, v0}, $Y/core/c/al;->g(Ljava/lang/String;)V
                sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                invoke-virtual {p1, v0}, $Y/core/c/al;->a(Ljava/lang/Boolean;)V
                invoke-virtual {p2, v0}, $Y/core/c/al;->a(Ljava/lang/Boolean;)V
            """,
        )

        // 8. Keep financial diffs out of logcat.
        cls("$Y/core/backend/f;").let { c ->
            val m = c.methods.single { m -> m.implementation?.instructions?.any { it.calls("$Y/core/e/g;", "a", "(Ljava/lang/String;)V") && (it as FiveRegisterInstruction).registerC == 7 } == true }
            val i = m.single("import error log") { it.calls("$Y/core/e/g;", "a", "(Ljava/lang/String;)V") && (it as FiveRegisterInstruction).registerC == 7 }
            m.addInstructions(i, "const-string v7, \"Import interrupted; private conflict evidence retained\"")
        }

        // 9. Overview: conflict prompt; review remains in sync settings.
        cls("$Y/app/OverviewActivity;").let { c ->
            c.method("onResume", "()V").let { m ->
                val ret = m.single("onResume return") { it.opcode == Opcode.RETURN_VOID }
                m.beforeLabelled(ret, "invoke-static {p0}, $P/ConflictStore;->resume(Landroid/app/Activity;)V", "return-void")
            }
            // No conflict menu item is added; keeping another prefix here can relocate
            // PlusMenu branch labels backwards and loop on original menu actions.
            if (c.methods.any { it.name == "onPause" && it.parameterTypes.isEmpty() }) throw IllegalStateException("onPause exists")
            val onPause = ImmutableMethod(c.type, "onPause", emptyList(), "V", AccessFlags.PROTECTED.value, null, null, MutableMethodImplementation(1)).toMutable()
            onPause.addInstructions(
                0,
                """
                    invoke-static {p0}, $P/ConflictStore;->pause(Landroid/app/Activity;)V
                    invoke-super {p0}, ${c.superclass}->onPause()V
                    return-void
                """,
            )
            c.methods.add(onPause)
        }
    }
}
