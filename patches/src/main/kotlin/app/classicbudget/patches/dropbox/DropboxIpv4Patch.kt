package app.classicbudget.patches.dropbox

import app.classicbudget.patches.shared.Constants.COMPATIBILITY_YNAB_CLASSIC
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.patch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/** Dropbox SDK StandardHttpRequestor: opens each HttpURLConnection with the configured proxy. */
internal object OpenConnectionFingerprint : Fingerprint(
    returnType = "Ljava/net/HttpURLConnection;",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/Iterable;"),
    filters = listOf(
        methodCall(returnType = "Ljava/net/Proxy;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        methodCall(definingClass = "Ljava/net/URL;", name = "openConnection"),
    ),
)

@Suppress("unused")
val dropboxIpv4Patch = patch(
    name = "Dropbox: prefer IPv4",
    description = "Dropbox requests connect over IPv4 first and fall back to IPv6. Fixes Dropbox sync failing " +
        "where IPv6 is broken (for example with some ad-blocking or VPN apps): the original app waits on the " +
        "IPv6 address and never tries IPv4. Uses a small local tunnel; encryption is unchanged.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)
    extendWith("extensions/extension.mpe")

    execute {
        OpenConnectionFingerprint.let { fp ->
            val move = fp.instructionMatches[1]
            val register = move.getInstruction<OneRegisterInstruction>().registerA
            fp.method.addInstructions(
                move.index + 1,
                """
                    invoke-static { v$register }, Lapp/classicbudget/extension/Ipv4First;->proxy(Ljava/net/Proxy;)Ljava/net/Proxy;
                    move-result-object v$register
                """,
            )
        }
    }
}
