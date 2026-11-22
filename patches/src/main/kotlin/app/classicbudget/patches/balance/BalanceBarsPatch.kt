package app.classicbudget.patches.balance

import app.classicbudget.patches.shared.Constants.COMPATIBILITY_YNAB_CLASSIC
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val DISPLAY = "Lorg/classicbudget/patch/Display;"

/**
 * The method that shows the category balance bars after a transaction is saved
 * (CategoryBalanceToaster, called from the budget and category screens on resume).
 * Matched by the resource IDs it uses: add_transaction, generic_list_container and the
 * category_balance_toast layout (fixed in 3.4.1_classic).
 */
internal object CategoryBalanceBarsFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        literal(0x7f0e0080), // R.id.add_transaction
        literal(0x7f0e002b), // R.id.generic_list_container
        literal(0x7f04000e), // R.layout.category_balance_toast
    ),
)

@Suppress("unused")
val keepAddTransactionVisiblePatch = bytecodePatch(
    name = "Keep Add transaction visible",
    description = "After saving a transaction the app hides the Add transaction button for about 3 seconds " +
        "and shows the category balance in its place, so a quick second tap lands on the list behind it. " +
        "With this patch the button stays and the balance appears just above it. " +
        "Plus settings can switch back to the original or turn the balance off.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)
    extendWith("extensions/extension.mpe")

    execute {
        val method = CategoryBalanceBarsFingerprint.method
        val instructions = method.implementation!!.instructions.toList()

        // Register holding the Add transaction button: findViewById(R.id.add_transaction) -> move-result-object vX.
        val idIndex = CategoryBalanceBarsFingerprint.instructionMatches[0].index
        val buttonRegister = instructions.drop(idIndex).first { it.opcode == Opcode.MOVE_RESULT_OBJECT }
            .let { (it as OneRegisterInstruction).registerA }
        val hideIndex = instructions.indexOfFirst {
            it.opcode == Opcode.INVOKE_VIRTUAL &&
                ((it as ReferenceInstruction).reference as MethodReference).name == "setVisibility" &&
                (it as FiveRegisterInstruction).registerC == buttonRegister
        }
        val gravityIndex = instructions.indexOfFirst {
            it.opcode == Opcode.IPUT &&
                ((it as ReferenceInstruction).reference as FieldReference).let { f ->
                    f.name == "gravity" && f.definingClass == "Landroid/widget/FrameLayout\$LayoutParams;"
                }
        }
        if (hideIndex < 0 || gravityIndex < 0) throw IllegalStateException("Unexpected category balance code")
        val paramsRegister = (instructions[gravityIndex] as TwoRegisterInstruction).registerB
        val visibilityRegister = (instructions[hideIndex] as FiveRegisterInstruction).registerD

        // From the highest index down so lower ones stay valid.
        method.addInstruction(gravityIndex + 1, "invoke-static { v$paramsRegister }, $DISPLAY->balanceToastParams(Landroid/widget/FrameLayout\$LayoutParams;)V")
        method.replaceInstruction(hideIndex, "invoke-static { v$buttonRegister, v$visibilityRegister }, $DISPLAY->balanceButton(Landroid/view/View;I)V")
        // "Off" in Plus settings: show nothing (v4 is free at the start of this method).
        method.addInstructions(
            0,
            """
                invoke-static {}, $DISPLAY->balanceToastOff()Z
                move-result v4
                if-eqz v4, :cb_balance_on
                return-void
                :cb_balance_on
                nop
            """,
        )
    }
}
