package app.classicbudget.patches.alongside

import app.classicbudget.patches.shared.Constants.COMPATIBILITY_YNAB_CLASSIC
import app.classicbudget.patches.dropbox.dropboxPathAllowed
import app.classicbudget.patches.dropbox.setDropboxPreset
import app.classicbudget.patches.sync.syncProfilePatch
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.w3c.dom.Element

/**
 * Production copy next to the TEST copy (077). "Install alongside the original" makes the TEST copy
 * (com.youneedabudget.ynab.app.market.plus.test, "YNAB Plus TEST"); this patch, applied on top of it,
 * moves the result to its own application ID and label, so both copies and the original can be
 * installed at once. Everything the alongside patch namespaced with the TEST package (broadcast
 * actions in the manifest and in code, settings intents) follows, so the copies never wake each other.
 * In code, the Plus editors drop their TEST/COPY-only limit and the orange TEST bar.
 */
internal const val PRODUCTION_PACKAGE = "com.youneedabudget.ynab.app.market.plus"
internal const val PRODUCTION_LABEL = "YNAB Plus"
private const val TEST_PREFIX = "$ALONGSIDE_PACKAGE.isolated."
private const val PRODUCTION_PREFIX = "$PRODUCTION_PACKAGE.isolated."

private val productionManifestPatch = resourcePatch {
    dependsOn(installAlongsidePatch)
    execute {
        document("AndroidManifest.xml").use { doc ->
            val manifest = doc.getElementsByTagName("manifest").item(0) as Element
            if (manifest.getAttribute("package") != ALONGSIDE_PACKAGE) {
                throw IllegalStateException("Expected the alongside copy, found ${manifest.getAttribute("package")}")
            }
            manifest.setAttribute("package", PRODUCTION_PACKAGE)
            val application = doc.getElementsByTagName("application").item(0) as Element
            application.setAttribute("android:label", PRODUCTION_LABEL)
            val activities = doc.getElementsByTagName("activity")
            for (i in 0 until activities.length) {
                val activity = activities.item(i) as Element
                if (activity.getAttribute("android:label") == ALONGSIDE_LABEL) activity.setAttribute("android:label", PRODUCTION_LABEL)
            }
            val actions = doc.getElementsByTagName("action")
            for (i in 0 until actions.length) {
                val action = actions.item(i) as Element
                val name = action.getAttribute("android:name")
                if (name.startsWith(TEST_PREFIX)) action.setAttribute("android:name", PRODUCTION_PREFIX + name.removePrefix(TEST_PREFIX))
            }
        }
        // Settings entries that open Plus screens point at the TEST package after the alongside patch.
        document("res/xml/pref_general.xml").use { doc ->
            val intents = doc.getElementsByTagName("intent")
            for (i in 0 until intents.length) {
                val intent = intents.item(i) as Element
                if (intent.getAttribute("android:targetPackage") == ALONGSIDE_PACKAGE) intent.setAttribute("android:targetPackage", PRODUCTION_PACKAGE)
            }
        }
    }
}

@Suppress("unused")
val productionEditionPatch = bytecodePatch(
    name = "Production edition",
    description = "ON = the production app \"$PRODUCTION_LABEL\" ($PRODUCTION_PACKAGE) for your real budget; OFF = the TEST app. " +
        "Includes the Dropbox folder selector before sign-in and in Plus settings. " +
        "Budgeting and the schedule editor work on every budget.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)
    dependsOn(installAlongsidePatch, productionManifestPatch, syncProfilePatch)
    extendWith("extensions/extension.mpe")

    val initialFolder by stringOption(
        key = "initialDropboxFolder",
        default = "",
        title = "Initial Dropbox folder",
        description = "Optional path to the folder containing .ynabSettings.yroot. Empty uses Dropbox's original root and /YNAB. " +
            "Keep the same path when upgrading a previously patched custom-folder installation.",
        required = false,
    ) { value -> dropboxPathAllowed(value) }

    execute {
        setDropboxPreset(initialFolder.orEmpty())
        // 1. Broadcast actions in code: the TEST namespace the alongside patch wrote becomes the production one.
        var renamed = 0
        classDefForEach { classDef ->
            if (!classDef.type.startsWith("Lcom/youneedabudget/") && !classDef.type.startsWith("Lorg/classicbudget/") &&
                !classDef.type.startsWith("Lapp/classicbudget/")) return@classDefForEach
            val hit = classDef.methods.any { m ->
                m.implementation?.instructions?.any { insn ->
                    insn.opcode == Opcode.CONST_STRING &&
                        ((insn as ReferenceInstruction).reference as StringReference).string.startsWith(TEST_PREFIX)
                } == true
            }
            if (!hit) return@classDefForEach
            mutableClassDefBy(classDef).methods.forEach { method ->
                val instructions = method.implementation?.instructions?.toList() ?: return@forEach
                instructions.forEachIndexed { index, insn ->
                    if (insn.opcode != Opcode.CONST_STRING) return@forEachIndexed
                    val s = ((insn as ReferenceInstruction).reference as StringReference).string
                    if (!s.startsWith(TEST_PREFIX)) return@forEachIndexed
                    val register = (insn as OneRegisterInstruction).registerA
                    method.replaceInstruction(index, "const-string v$register, \"${PRODUCTION_PREFIX + s.removePrefix(TEST_PREFIX)}\"")
                    renamed++
                }
            }
        }
        if (renamed == 0) throw IllegalStateException("No TEST-namespaced actions found")

        // 2. PlusEdition.edition(): const true -> false.
        val edition = mutableClassDefBy("Lorg/classicbudget/patch/PlusEdition;").methods.single { it.name == "edition" }
        val instructions = edition.implementation!!.instructions.toList()
        val index = instructions.indices.single { instructions[it].opcode == Opcode.CONST_4 }
        val register = (instructions[index] as OneRegisterInstruction).registerA
        edition.replaceInstruction(index, "const/4 v$register, 0x0")
    }
}
