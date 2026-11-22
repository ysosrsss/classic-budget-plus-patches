package app.classicbudget.patches.shared

import app.morphe.patcher.patch.ResourcePatchContext
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Shared resource edits for the Plus modules (English, in res/values). */
internal object PlusResources {
    const val ORIGINAL_PACKAGE = "com.youneedabudget.ynab.app.market"

    /** Adds (or keeps) an English string resource; returns "@string/name". */
    fun ResourcePatchContext.addString(name: String, en: String, pl: String): String {
        require(name.startsWith("cb_"))
        // English only: resources follow the phone language, but the original app (first screen, Settings)
        // is English-only and Plus screens follow the language chosen in Plus settings. [pl] documents the
        // translation used at runtime by those screens.
        for ((folder, value) in listOf("values" to en)) {
            val path = "res/$folder/strings.xml"
            val file = get(path, false)
            if (!file.exists()) {
                file.parentFile.mkdirs()
                file.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n")
            }
            document(path).use { doc ->
                val root = doc.documentElement
                val strings = doc.getElementsByTagName("string")
                val exists = (0 until strings.length).any { (strings.item(it) as Element).getAttribute("name") == name }
                if (!exists) {
                    val s = doc.createElement("string")
                    s.setAttribute("name", name)
                    s.textContent = value.replace("'", "\\'").replace("\"", "\\\"")
                    root.appendChild(s)
                }
            }
        }
        return "@string/$name"
    }

    /** Current application ID (the original, or the "alongside" one when that patch already ran). */
    fun ResourcePatchContext.currentPackage(): String =
        document("AndroidManifest.xml").use { (it.getElementsByTagName("manifest").item(0) as Element).getAttribute("package") }

    /** Adds an <activity> (not exported) to the manifest once. */
    fun ResourcePatchContext.addActivity(name: String, label: String, extra: Map<String, String> = emptyMap()) {
        document("AndroidManifest.xml").use { doc ->
            val app = doc.getElementsByTagName("application").item(0) as Element
            if (hasComponent(doc, "activity", name)) return@use
            val a = doc.createElement("activity")
            a.setAttribute("android:name", name)
            a.setAttribute("android:exported", "false")
            a.setAttribute("android:label", label)
            extra.forEach { (k, v) -> a.setAttribute(k, v) }
            app.appendChild(a)
        }
    }

    /** Not exported, started only by the app itself. */
    fun ResourcePatchContext.addService(name: String) {
        document("AndroidManifest.xml").use { doc ->
            val app = doc.getElementsByTagName("application").item(0) as Element
            if (hasComponent(doc, "service", name)) return@use
            val s = doc.createElement("service")
            s.setAttribute("android:name", name)
            s.setAttribute("android:exported", "false")
            app.appendChild(s)
        }
    }

    fun ResourcePatchContext.addPermission(name: String) {
        document("AndroidManifest.xml").use { doc ->
            if (hasComponent(doc, "uses-permission", name)) return@use
            val manifest = doc.getElementsByTagName("manifest").item(0) as Element
            val app = doc.getElementsByTagName("application").item(0)
            val permission = doc.createElement("uses-permission")
            permission.setAttribute("android:name", name)
            manifest.insertBefore(permission, app)
        }
    }

    fun hasComponent(doc: Document, tag: String, name: String): Boolean {
        val list = doc.getElementsByTagName(tag)
        return (0 until list.length).any { (list.item(it) as Element).getAttribute("android:name") == name }
    }

    /** Adds an entry to the original Settings screen, in a "Plus" category placed before "Support". */
    fun ResourcePatchContext.addSettingsEntry(key: String, title: String, summary: String, targetClass: String) {
        val pkg = currentPackage()
        document("res/xml/pref_general.xml").use { doc ->
            val root = doc.documentElement
            val categories = doc.getElementsByTagName("PreferenceCategory")
            var plus: Element? = null
            var support: Element? = null
            for (i in 0 until categories.length) {
                val c = categories.item(i) as Element
                when (c.getAttribute("android:title")) { "Plus" -> plus = c; "Support" -> support = c }
            }
            if (plus == null) {
                plus = doc.createElement("PreferenceCategory").also { it.setAttribute("android:title", "Plus") }
                root.insertBefore(plus, support)
            }
            val prefs = plus!!.getElementsByTagName("Preference")
            if ((0 until prefs.length).any { (prefs.item(it) as Element).getAttribute("android:key") == key }) return@use
            val p = doc.createElement("Preference")
            p.setAttribute("android:layout", "@layout/preference")
            p.setAttribute("android:title", title)
            p.setAttribute("android:summary", summary)
            p.setAttribute("android:key", key)
            val intent = doc.createElement("intent")
            intent.setAttribute("android:targetPackage", pkg)
            intent.setAttribute("android:targetClass", targetClass)
            p.appendChild(intent)
            plus.appendChild(p)
        }
    }

    /** The "alongside" patch renames the package: entries added earlier must follow. */
    fun ResourcePatchContext.retargetSettings(newPackage: String) {
        document("res/xml/pref_general.xml").use { doc ->
            val intents = doc.getElementsByTagName("intent")
            for (i in 0 until intents.length) {
                val intent = intents.item(i) as Element
                if (intent.getAttribute("android:targetPackage") == ORIGINAL_PACKAGE &&
                    intent.getAttribute("android:targetClass").startsWith("org.classicbudget.")) {
                    intent.setAttribute("android:targetPackage", newPackage)
                }
            }
        }
    }
}
