package app.classicbudget.patches.dropbox

import app.classicbudget.patches.shared.Constants.COMPATIBILITY_YNAB_CLASSIC
import app.classicbudget.patches.shared.PRODUCTION_ONLY
import app.classicbudget.patches.alongside.constEdition
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val ACCESSOR = "Lcom/youneedabudget/ynab/core/cloud/z;"
private const val EXTENSION = "Lapp/classicbudget/extension/DropboxFolder;"
private const val PROFILE = "Lorg/classicbudget/patch/DropboxProfile;"

internal fun dropboxPathAllowed(value: String?): Boolean {
    val path = value.orEmpty().trim().trimEnd('/')
    return path.length <= 300 && path.none { it < ' ' || it in "\\\"%:" } &&
        path.trimStart('/').split('/').none { it == "." || it == ".." } && !path.contains("//")
}

internal fun BytecodePatchContext.setDropboxPreset(value: String) {
    val method = mutableClassDefBy(PROFILE).methods.single { it.name == "initialFolder" }
    val instructions = method.implementation!!.instructions.toList()
    val index = instructions.indexOfFirst { it.opcode == Opcode.CONST_STRING }
    val register = (instructions[index] as OneRegisterInstruction).registerA
    method.replaceInstruction(index, "const-string v$register, \"${value.trim().trim('/')}\"")
}

private fun BytecodePatchContext.dropboxPreset(): String {
    val method = mutableClassDefBy(PROFILE).methods.single { it.name == "initialFolder" }
    val instruction = method.implementation!!.instructions.first { it.opcode == Opcode.CONST_STRING } as ReferenceInstruction
    return (instruction.reference as StringReference).string
}

/** The Dropbox provider's accessor getter: the class holding the "dropboxSettings" preferences. */
internal object DropboxProviderFingerprint : Fingerprint(
    filters = listOf(string("dropboxSettings")),
)

@Suppress("unused")
val dropboxFolderPatch = bytecodePatch(
    name = if (PRODUCTION_ONLY) null else "Dropbox budget folder",
    description = "Legacy option for the isolated TEST app and older Dropbox configurations. " +
        "YNAB Plus includes its folder selector in Production edition.",
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
    ) { value -> dropboxPathAllowed(value) }

    execute {
        // 1. The chosen folder becomes the extension's constant.
        mutableClassDefBy(EXTENSION).methods.first { it.name == "folder" }.let { method ->
            val instructions = method.implementation!!.instructions.toList()
            val index = instructions.indexOfFirst { it.opcode == Opcode.CONST_STRING }
            val register = (instructions[index] as OneRegisterInstruction).registerA
            method.replaceInstruction(index, "const-string v$register, \"${(folder ?: "").replace("\\", "/")}\"")
        }

    }

    finalize {
        if (!constEdition()) {
            // Existing saved Morphe options seed the production profile without wrapping Dropbox twice.
            val legacy = folder.orEmpty().trim().trim('/')
            val preset = dropboxPreset()
            if (preset.isNotEmpty() && legacy.isNotEmpty() && !preset.equals(legacy, ignoreCase = true)) {
                throw IllegalStateException("Choose the Dropbox folder in Production edition or the legacy folder patch, using one path.")
            }
            if (preset.isEmpty() && legacy.isNotEmpty()) setDropboxPreset(legacy)
            return@finalize
        }

        // TEST keeps the original accessor wrapper and confinement of its explicit test folder.
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
