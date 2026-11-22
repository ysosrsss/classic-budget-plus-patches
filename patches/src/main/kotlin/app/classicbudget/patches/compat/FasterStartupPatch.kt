package app.classicbudget.patches.compat

import app.classicbudget.patches.shared.Constants.COMPATIBILITY_YNAB_CLASSIC
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val fasterStartupPatch = bytecodePatch(
    name = "Faster startup",
    description = "Starts loading the budget without the Classic splash screen's fixed two-second delay.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)
    execute {
        val start = mutableClassDefBy("Lcom/youneedabudget/ynab/app/StartActivity;").methods.single {
            it.name == "onCreate" && it.parameterTypes.map { type -> type.toString() } == listOf("Landroid/os/Bundle;")
        }
        val instructions = start.implementation!!.instructions.toList()
        val call = instructions.indices.single { index ->
            val reference = (instructions[index] as? ReferenceInstruction)?.reference as? MethodReference
            reference?.definingClass == "Landroid/os/Handler;" && reference.name == "postDelayed" &&
                reference.parameterTypes.map { it.toString() } == listOf("Ljava/lang/Runnable;", "J")
        }
        val delay = instructions[call - 1]
        val register = (instructions[call] as FiveRegisterInstruction).registerE
        check(delay.opcode == Opcode.CONST_WIDE_16 && (delay as WideLiteralInstruction).wideLiteral == 2000L &&
            (delay as OneRegisterInstruction).registerA == register) { "Unexpected delay in Classic StartActivity" }
        // Keep the original asynchronous callback and all backend/Activity initialization.
        start.replaceInstruction(call - 1, "const-wide/16 v$register, 0")
    }
}
