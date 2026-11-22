package app.classicbudget.patches.dropbox

import app.classicbudget.patches.shared.Constants.COMPATIBILITY_YNAB_CLASSIC
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** Dropbox SDK SSL config: trust manager built from the bundled, outdated "/trusted-certs.raw". */
internal object BundledTrustManagerFingerprint : Fingerprint(
    returnType = "Ljavax/net/ssl/X509TrustManager;",
    parameters = listOf(),
    filters = listOf(string("/trusted-certs.raw")),
)

/** Dropbox SDK SSL config: per-socket protocol and cipher-suite setup. */
internal object SocketSetupFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljavax/net/ssl/SSLSocket;"),
    filters = listOf(
        methodCall(definingClass = "Ljavax/net/ssl/SSLSocket;", name = "setEnabledCipherSuites"),
        string("Socket doesn't support protocols \"TLSv1.2\", \"TLSv1.0\" or \"TLSv1\"."),
    ),
)

@Suppress("unused")
val dropboxTlsPatch = bytecodePatch(
    name = "Dropbox: modern TLS",
    description = "Lets the app connect to Dropbox on current Android: TLS 1.2 only, the phone's own " +
        "certificate store instead of the outdated bundled one, and the phone's default cipher suites. " +
        "Without it the Dropbox login and sync fail.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)

    execute {
        // 1. System trust store: the SDK's own a(KeyStore) helper with a null KeyStore = Android's default store.
        BundledTrustManagerFingerprint.method.apply {
            val helper = "$definingClass->a(Ljava/security/KeyStore;)Ljavax/net/ssl/X509TrustManager;"
            addInstructions(
                0,
                """
                    const/4 v0, 0x0
                    invoke-static { v0 }, $helper
                    move-result-object v0
                    return-object v0
                """,
            )
        }

        // 2. Default cipher suites: drop the SDK's forced (outdated) list.
        SocketSetupFingerprint.let { it.method.removeInstruction(it.instructionMatches[0].index) }

        // 3. TLS 1.2 only. The SDK takes the FIRST supported protocol it recognizes, and current Android
        //    lists "TLSv1" first, so the socket would be limited to TLS 1.0 (refused by Dropbox).
        //    Every protocol name the class uses becomes "TLSv1.2" (its static arrays and the comparisons).
        val sslClass = SocketSetupFingerprint.originalClassDef.type
        var replaced = 0
        mutableClassDefBy(sslClass).methods.forEach { method ->
            val impl = method.implementation ?: return@forEach
            impl.instructions.toList().forEachIndexed { index, insn ->
                if (insn.opcode != Opcode.CONST_STRING) return@forEachIndexed
                val value = ((insn as ReferenceInstruction).reference as StringReference).string
                if (value == "TLSv1.0" || value == "TLSv1") {
                    val register = (insn as OneRegisterInstruction).registerA
                    method.replaceInstruction(index, "const-string v$register, \"TLSv1.2\"")
                    replaced++
                }
            }
        }
        if (replaced < 4) throw IllegalStateException("Dropbox TLS: expected protocol strings not found ($replaced)")
    }
}
