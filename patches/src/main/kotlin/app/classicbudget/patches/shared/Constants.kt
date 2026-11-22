package app.classicbudget.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /**
     * The last YNAB Classic (YNAB 4 companion) Android release, signed by YNAB:
     * versionName 3.4.1_classic, versionCode 27296,
     * SHA-256 d49148b7c9501526c40890599d4ec4b5aad2bf57c0bd949d4649255c17f87772.
     */
    val COMPATIBILITY_YNAB_CLASSIC = Compatibility(
        name = "YNAB Classic",
        packageName = "com.youneedabudget.ynab.app.market",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x3FB5C8,
        targets = listOf(AppTarget(version = "3.4.1_classic")),
    )
}
