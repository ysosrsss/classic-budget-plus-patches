package app.classicbudget.patches.dropbox

import app.classicbudget.patches.shared.Constants.COMPATIBILITY_YNAB_CLASSIC
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Dropbox client setup that writes the OAuth access token to the Android log. */
internal object DropboxClientInitFingerprint : Fingerprint(
    filters = listOf(string("Initialising Dropbox client with access token: ")),
)

@Suppress("unused")
val dropboxTokenLogPatch = patch(
    name = "Do not log the Dropbox token",
    description = "The app writes your Dropbox access token to the Android system log each time it connects. " +
        "This patch removes that log line.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)

    execute {
        DropboxClientInitFingerprint.let { fp ->
            val method = fp.method
            val start = fp.instructionMatches[0].index
            val instructions = method.implementation!!.instructions.toList()
            // The first static call taking the built message (String) and returning void is the log call.
            val logIndex = (start until instructions.size).first { i ->
                val insn = instructions[i]
                insn.opcode == Opcode.INVOKE_STATIC &&
                    ((insn as ReferenceInstruction).reference as MethodReference).let { m ->
                        m.returnType == "V" && m.parameterTypes.map { it.toString() } == listOf("Ljava/lang/String;")
                    }
            }
            method.replaceInstruction(logIndex, "nop")
        }
    }
}
