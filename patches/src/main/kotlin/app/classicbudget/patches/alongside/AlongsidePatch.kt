package app.classicbudget.patches.alongside

import app.classicbudget.patches.shared.Constants.COMPATIBILITY_YNAB_CLASSIC
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.w3c.dom.Element
import app.classicbudget.patches.shared.PlusResources.retargetSettings

/** Application ID of the copy installed next to the original. */
internal const val ALONGSIDE_PACKAGE = "com.youneedabudget.ynab.app.market.plus.test"
internal const val ALONGSIDE_LABEL = "YNAB Plus TEST"

/** Broadcast actions used by the app. Namespaced so the two installed apps never wake each other. */
private val ACTION_PREFIXES = listOf(
    "com.youneedabudget.action.",
    "com.radioactiveyak.places.SINGLE_LOCATION_UPDATE_ACTION",
    "com.youneedabudget.ynab.intent.action.",
    "com.youneedabudget.ynab.RESULT_",
    "com.youneedabudget.ynab.SYNC",
    "com.youneedabudget.ynab.GET_BUDGETS",
    "com.youneedabudget.ynab.IMPORT_FULL",
)
private fun isolated(action: String) = "$ALONGSIDE_PACKAGE.isolated.$action"

private val alongsideManifestPatch = resourcePatch {
    execute {
        retargetSettings(ALONGSIDE_PACKAGE)   // Plus settings entries added before this patch
        document("AndroidManifest.xml").use { doc ->
            val manifest = doc.getElementsByTagName("manifest").item(0) as Element
            if (manifest.getAttribute("package") != "com.youneedabudget.ynab.app.market") {
                throw IllegalStateException("Unexpected package ${manifest.getAttribute("package")}")
            }
            if (doc.getElementsByTagName("provider").length != 0) throw IllegalStateException("Unexpected content provider")
            manifest.setAttribute("package", ALONGSIDE_PACKAGE)
            val application = doc.getElementsByTagName("application").item(0) as Element
            application.setAttribute("android:label", ALONGSIDE_LABEL)
            val actions = doc.getElementsByTagName("action")
            for (i in 0 until actions.length) {
                val action = actions.item(i) as Element
                val name = action.getAttribute("android:name")
                if (name.startsWith("com.youneedabudget.action.")) action.setAttribute("android:name", isolated(name))
            }
            // Launcher entries carry their own label: show which copy is which.
            val activities = doc.getElementsByTagName("activity")
            for (i in 0 until activities.length) {
                val activity = activities.item(i) as Element
                if (activity.hasAttribute("android:label")) activity.setAttribute("android:label", ALONGSIDE_LABEL)
            }
        }
    }
}

/** Dropbox SDK check that refuses to log in when another app handles the same db-<key>:// scheme. */
internal object DropboxAuthHandlersFingerprint : Fingerprint(
    definingClass = "Lcom/dropbox/core/android/AuthActivity;",
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Z"),
    filters = listOf(
        methodCall(definingClass = "Landroid/content/pm/PackageManager;", name = "queryIntentActivities"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
    ),
)

@Suppress("unused")
val installAlongsidePatch = bytecodePatch(
    name = "Install alongside the original",
    description = "REQUIRED for both apps. Installs the patched app as its own app next to the original YNAB Classic " +
        "(the original stays untouched). Alone it makes the TEST app \"$ALONGSIDE_LABEL\" ($ALONGSIDE_PACKAGE, orange bar): " +
        "then also turn on \"Dropbox budget folder\" with a TEST folder such as YNAB4_PLUS_TEST. Together with " +
        "\"Production edition\" it makes \"$PRODUCTION_LABEL\" for the real budget instead. Each app keeps its own local data " +
        "and registers as its own device in the budget.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)
    dependsOn(alongsideManifestPatch)
    extendWith("extensions/extension.mpe")

    execute {
        // 1. Same actions in code as in the manifest.
        var renamed = 0
        classDefForEach { classDef ->
            // The app's own code and the Plus / sync modules (they broadcast the same actions).
            if (!classDef.type.startsWith("Lcom/youneedabudget/") && !classDef.type.startsWith("Lorg/classicbudget/")) return@classDefForEach
            val hit = classDef.methods.any { m ->
                m.implementation?.instructions?.any { insn ->
                    insn.opcode == Opcode.CONST_STRING &&
                        ((insn as ReferenceInstruction).reference as StringReference).string.let { s -> ACTION_PREFIXES.any { s.startsWith(it) } }
                } == true
            }
            if (!hit) return@classDefForEach
            mutableClassDefBy(classDef).methods.forEach { method ->
                val instructions = method.implementation?.instructions?.toList() ?: return@forEach
                instructions.forEachIndexed { index, insn ->
                    if (insn.opcode != Opcode.CONST_STRING) return@forEachIndexed
                    val s = ((insn as ReferenceInstruction).reference as StringReference).string
                    if (ACTION_PREFIXES.none { s.startsWith(it) }) return@forEachIndexed
                    val register = (insn as OneRegisterInstruction).registerA
                    method.replaceInstruction(index, "const-string v$register, \"${isolated(s)}\"")
                    renamed++
                }
            }
        }
        if (renamed == 0) throw IllegalStateException("No broadcast actions found")

        // 2. Dropbox login next to the original: ignore only the original's own AuthActivity.
        DropboxAuthHandlersFingerprint.let { fp ->
            val move = fp.instructionMatches[1]
            val list = move.getInstruction<OneRegisterInstruction>().registerA
            fp.method.addInstructions(
                move.index + 1,
                """
                    invoke-static { p0, v$list }, Lapp/classicbudget/extension/DropboxCoexist;->filter(Landroid/content/Context;Ljava/util/List;)Ljava/util/List;
                    move-result-object v$list
                """,
            )
        }
    }

    // After every selected patch has run: refuse a mix of options that would put the wrong budget in the wrong app.
    // Morphe Manager remembers the options of the previous patching, so one app's settings easily carry into the other.
    finalize {
        val production = !constEdition()
        val folder = dropboxFolder()
        if (production && folder.uppercase().contains("TEST")) throw IllegalStateException(
            "Production edition with Dropbox budget folder \"$folder\": the production app would open the TEST budget. " +
                "Turn \"Dropbox budget folder\" OFF (or leave its folder empty) for \"$PRODUCTION_LABEL\".")
        if (!production && folder.isEmpty()) throw IllegalStateException(
            "TEST app without a Dropbox budget folder: \"$ALONGSIDE_LABEL\" would open the real budget in /YNAB. " +
                "Turn \"Dropbox budget folder\" ON with a TEST folder (e.g. YNAB4_PLUS_TEST), or turn \"Production edition\" ON for the real budget.")
    }
}

/** PlusEdition.edition() as patched so far: true = TEST, false = production. */
internal fun app.morphe.patcher.patch.BytecodePatchContext.constEdition(): Boolean {
    val method = mutableClassDefBy("Lorg/classicbudget/patch/PlusEdition;").methods.single { it.name == "edition" }
    val insn = method.implementation!!.instructions.single { it.opcode == Opcode.CONST_4 }
    return (insn as com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction).narrowLiteral != 0
}

/** The folder "Dropbox budget folder" wrote into its extension ("" when that patch is off), without slashes. */
private fun app.morphe.patcher.patch.BytecodePatchContext.dropboxFolder(): String {
    val method = mutableClassDefBy("Lapp/classicbudget/extension/DropboxFolder;").methods.single { it.name == "folder" }
    val insn = method.implementation!!.instructions.first { it.opcode == Opcode.CONST_STRING }
    return ((insn as ReferenceInstruction).reference as StringReference).string.trim().trim('/', '\\')
}
