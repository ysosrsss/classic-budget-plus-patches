package app.classicbudget.patches.plus

import app.classicbudget.patches.shared.Constants.COMPATIBILITY_YNAB_CLASSIC
import app.classicbudget.patches.shared.PlusResources.addActivity
import app.classicbudget.patches.shared.PlusResources.addString
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

private const val CLASS = "org.classicbudget.patch.NewTransactionWidget"
private const val TYPE = "Lorg/classicbudget/patch/NewTransactionWidget;"
private const val COMPACT_CLASS = "$CLASS\$Compact"

private val widgetResources = resourcePatch {
    execute {
        val label = addString("cb_new_transaction_label", "New transaction", "Nowa transakcja")
        val compactLabel = addString("cb_new_transaction_compact_label", "New transaction (+)", "Nowa transakcja (+)")
        addActivity(
            "$CLASS\$OpenActivity",
            label,
            mapOf("android:theme" to "@android:style/Theme.NoDisplay"),
        )
        addActivity(
            "$CLASS\$LaunchActivity",
            label,
            mapOf(
                "android:theme" to "@android:style/Theme.NoDisplay",
                "android:relinquishTaskIdentity" to "true",
            ),
        )
        get("res/layout/cb_new_transaction_widget.xml", false).apply {
            parentFile.mkdirs()
            writeText("""
                <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent" android:layout_height="match_parent">
                    <TextView android:id="@+id/cb_new_transaction_button"
                        android:layout_width="match_parent" android:layout_height="48dp" android:layout_gravity="center"
                        android:gravity="center" android:text="$label"
                        android:background="@drawable/cb_new_transaction_background"
                        android:paddingLeft="10dp" android:paddingRight="10dp"
                        android:textColor="#FFFFFF" android:textStyle="bold" android:textSize="15sp" />
                </FrameLayout>
            """.trimIndent())
        }
        get("res/layout/cb_new_transaction_compact.xml", false).apply {
            parentFile.mkdirs()
            writeText("""
                <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent" android:layout_height="match_parent">
                    <TextView android:id="@+id/cb_new_transaction_button"
                        android:layout_width="48dp" android:layout_height="48dp" android:layout_gravity="center"
                        android:gravity="center" android:text="+" android:contentDescription="$label"
                        android:background="@drawable/cb_new_transaction_background"
                        android:textColor="#FFFFFF" android:textStyle="bold" android:textSize="27sp" />
                </FrameLayout>
            """.trimIndent())
        }
        get("res/drawable/cb_new_transaction_background.xml", false).apply {
            parentFile.mkdirs()
            writeText("""
                <shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
                    <solid android:color="#096A80" />
                    <corners android:radius="16dp" />
                </shape>
            """.trimIndent())
        }
        get("res/xml/cb_new_transaction_widget.xml", false).apply {
            parentFile.mkdirs()
            writeText("""
                <appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
                    android:minWidth="110dp" android:minHeight="48dp"
                    android:initialLayout="@layout/cb_new_transaction_widget"
                    android:resizeMode="horizontal" android:widgetCategory="home_screen" />
            """.trimIndent())
        }
        get("res/xml/cb_new_transaction_compact.xml", false).apply {
            parentFile.mkdirs()
            writeText("""
                <appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
                    android:minWidth="48dp" android:minHeight="48dp"
                    android:initialLayout="@layout/cb_new_transaction_compact"
                    android:widgetCategory="home_screen" />
            """.trimIndent())
        }
        document("AndroidManifest.xml").use { doc ->
            val app = doc.getElementsByTagName("application").item(0) as Element
            for ((provider, title, info) in listOf(
                Triple(CLASS, label, "cb_new_transaction_widget"),
                Triple(COMPACT_CLASS, compactLabel, "cb_new_transaction_compact"),
            )) {
                val receiver = doc.createElement("receiver")
                receiver.setAttribute("android:name", provider)
                receiver.setAttribute("android:exported", "false")
                receiver.setAttribute("android:label", title)
                val filter = doc.createElement("intent-filter")
                filter.appendChild(doc.createElement("action").also {
                    it.setAttribute("android:name", "android.appwidget.action.APPWIDGET_UPDATE")
                })
                filter.appendChild(doc.createElement("action").also {
                    it.setAttribute("android:name", "android.intent.action.MY_PACKAGE_REPLACED")
                })
                receiver.appendChild(filter)
                receiver.appendChild(doc.createElement("meta-data").also {
                    it.setAttribute("android:name", "android.appwidget.provider")
                    it.setAttribute("android:resource", "@xml/$info")
                })
                app.appendChild(receiver)
            }
        }
    }
}

@Suppress("unused")
val newTransactionWidgetPatch = bytecodePatch(
    name = "New transaction widget",
    description = "Adds two home-screen widgets for the current budget: a 2×1 New transaction button and a " +
        "1×1 plus button. Both open the original Add Transaction form through the normal app and passcode flow.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YNAB_CLASSIC)
    dependsOn(widgetResources)
    extendWith("extensions/extension.mpe")
    execute {
        val navigation = mutableClassDefBy("Lcom/youneedabudget/ynab/app/a/a;").methods.single {
            it.name == "a" && it.parameterTypes.toList() == listOf("Landroid/os/Bundle;", "I")
        }
        val launch = navigation.implementation!!.instructions.toList().withIndex().single {
            val ref = (it.value as? ReferenceInstruction)?.reference as? MethodReference
            it.value.opcode == Opcode.INVOKE_VIRTUAL && ref?.name == "startActivity" &&
                ref.definingClass == "Landroid/app/Activity;"
        }
        val launchRegisters = launch.value as FiveRegisterInstruction
        navigation.addInstruction(launch.index,
            "invoke-static {v${launchRegisters.registerC}, v${launchRegisters.registerD}}, $TYPE->forward(Landroid/app/Activity;Landroid/content/Intent;)V")

        val category = mutableClassDefBy("Lcom/youneedabudget/ynab/app/j;").methods.single {
            it.name == "b" && it.parameterTypes.toList() == listOf("Lcom/youneedabudget/ynab/core/backend/j;")
        }
        val body = category.implementation!!.instructions.toList()
        val activityCall = body.indices.single {
            val ref = (body[it] as? ReferenceInstruction)?.reference as? MethodReference
            ref?.name == "k" && ref.returnType == "Landroid/support/v4/a/l;"
        }
        check(body[activityCall + 1].opcode == Opcode.MOVE_RESULT_OBJECT)
        val activity = (body[activityCall + 1] as OneRegisterInstruction).registerA
        val listener = body.indices.single {
            val ref = (body[it] as? ReferenceInstruction)?.reference as? MethodReference
            ref?.name == "setOnClickListener" && ref.definingClass == "Landroid/view/View;"
        }
        val button = (body[listener] as FiveRegisterInstruction).registerC
        category.addInstruction(listener + 1,
            "invoke-static {v$activity, v$button}, $TYPE->ready(Landroid/app/Activity;Landroid/view/View;)V")
    }
}
