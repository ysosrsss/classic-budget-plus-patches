package app.classicbudget.patches.dropbox

import app.classicbudget.patches.shared.Constants.COMPATIBILITY_YNAB_CLASSIC
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val ACCESSOR = "Lcom/youneedabudget/ynab/core/cloud/z;"
private const val EXTENSION = "Lapp/classicbudget/extension/DropboxFolder;"

/** The Dropbox provider's accessor getter: the class holding the "dropboxSettings" preferences. */
internal object DropboxProviderFingerprint : Fingerprint(
    filters = listOf(string("dropboxSettings")),
)

@Suppress("unused")
val dropboxFolderPatch = bytecodePatch(
    name = "Dropbox budget folder",
    description = "Use a Dropbox folder of your choice as the place where the app looks for budgets " +
        "(the folder that holds .ynabSettings.yroot). The original only looks at the top of Dropbox and in /YNAB. " +
        "Nothing outside the chosen folder is read or written. TEST app: ON with a TEST folder (e.g. YNAB4_PLUS_TEST). " +
        "Production app (\"Production edition\" ON): OFF.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)
    extendWith("extensions/extension.mpe")

    val folder by stringOption(
        key = "folder",
        default = "",
        title = "Folder",
        description = "Dropbox folder with .ynabSettings.yroot, written WITHOUT a leading slash, for example Budgets " +
            "or YNAB4_PLUS_TEST. Empty = original behaviour (top of Dropbox and /YNAB).",
        required = false,
    ) { value ->
        val v = value ?: ""
        !v.startsWith("/") && !v.startsWith("\\") && !v.contains("..") && !v.contains("\"") && !v.contains(":") &&
            v.none { it < ' ' } && v.length <= 300
    }

    execute {
        // 1. The chosen folder becomes the extension's constant.
        mutableClassDefBy(EXTENSION).methods.first { it.name == "folder" }.let { method ->
            val instructions = method.implementation!!.instructions.toList()
            val index = instructions.indexOfFirst { it.opcode == Opcode.CONST_STRING }
            val register = (instructions[index] as OneRegisterInstruction).registerA
            method.replaceInstruction(index, "const-string v$register, \"${(folder ?: "").replace("\\", "/")}\"")
        }

        // 2. Every accessor the provider hands out is wrapped (all return points of its getter).
        val providerType = DropboxProviderFingerprint.originalClassDef.type
        mutableClassDefBy(providerType).methods.filter { it.returnType == ACCESSOR && it.parameterTypes.isEmpty() }
            .also { if (it.isEmpty()) throw IllegalStateException("Dropbox accessor getter not found") }
            .forEach { method ->
                val instructions = method.implementation!!.instructions.toList()
                instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.reversed().forEach { (index, insn) ->
                    val register = (insn as OneRegisterInstruction).registerA
                    method.addInstructions(
                        index,
                        """
                            invoke-static { v$register }, $EXTENSION->wrap(Ljava/lang/Object;)Ljava/lang/Object;
                            move-result-object v$register
                            check-cast v$register, $ACCESSOR
                        """,
                    )
                }
            }
    }
}
