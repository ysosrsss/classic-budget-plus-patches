package app.classicbudget.patches.compat

import app.classicbudget.patches.shared.Constants.COMPATIBILITY_YNAB_CLASSIC
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import org.w3c.dom.Element

private const val EXTENSION = "Lapp/classicbudget/extension/Compat;"
private const val TARGET_SDK = "24"

private val targetSdkManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { doc ->
            val manifest = doc.getElementsByTagName("manifest").item(0) as Element
            val uses = doc.getElementsByTagName("uses-sdk")
            val sdk = if (uses.length > 0) uses.item(0) as Element
            else doc.createElement("uses-sdk").also { manifest.insertBefore(it, manifest.firstChild) }
            if (!sdk.hasAttribute("android:minSdkVersion")) sdk.setAttribute("android:minSdkVersion", "15")
            sdk.setAttribute("android:targetSdkVersion", TARGET_SDK)
        }
    }
}

/** Device registration reads the phone ID (needs a runtime permission from target SDK 23 on). */
internal object DeviceIdFingerprint : Fingerprint(
    filters = listOf(methodCall(definingClass = "Landroid/telephony/TelephonyManager;", name = "getDeviceId")),
)

/** Payee location helper. */
internal object LocationHelperFingerprint : Fingerprint(
    filters = listOf(string("should not be attempting to get the best location when the LocationHelper is not started!")),
)

@Suppress("unused")
val modernAndroidPatch = bytecodePatch(
    name = "Install on Android 14 and newer",
    description = "Android 14 and newer refuse to install apps built for very old Android versions " +
        "(INSTALL_FAILED_DEPRECATED_SDK_VERSION). This raises the app's target to Android 7 (SDK 24) and " +
        "makes the two places that would then need runtime permissions safe: the phone ID is optional and " +
        "payee locations are skipped unless location access is granted.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)
    dependsOn(targetSdkManifestPatch)
    extendWith("extensions/extension.mpe")

    execute {
        // 1. Phone ID: SecurityException -> null.
        DeviceIdFingerprint.let { fp ->
            val call = fp.instructionMatches[0]
            val register = call.getInstruction<FiveRegisterInstruction>().registerC
            fp.method.replaceInstruction(
                call.index,
                "invoke-static { v$register }, $EXTENSION->deviceId(Landroid/telephony/TelephonyManager;)Ljava/lang/String;",
            )
        }

        // 2. Location: the helper's start methods return early (and stay "not started") without permission.
        val helper = mutableClassDefBy(LocationHelperFingerprint.originalClassDef.type)
        val contextField = helper.fields.single { it.type == "Landroid/content/Context;" && !it.accessFlags.let { f -> f and 0x8 != 0 } }
        val startedField = helper.fields.single { it.type == "Z" }
        val starts = helper.methods.filter { it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/app/Activity;") && it.returnType == "V" }
        if (starts.size != 2) throw IllegalStateException("Location helper start methods: ${starts.size}")
        starts.forEach { method ->
            method.addInstructions(
                0,
                """
                    iget-object v0, p0, ${helper.type}->${contextField.name}:Landroid/content/Context;
                    invoke-static { v0 }, $EXTENSION->locationAllowed(Landroid/content/Context;)Z
                    move-result v0
                    if-nez v0, :cb_location_allowed
                    const/4 v0, 0x0
                    iput-boolean v0, p0, ${helper.type}->${startedField.name}:Z
                    return-void
                    :cb_location_allowed
                    nop
                """,
            )
        }
    }
}
