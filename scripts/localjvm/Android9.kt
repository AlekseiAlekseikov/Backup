package android.content.pm
open class ApplicationInfo {
    var packageName: String = ""; var uid: Int = 0; var flags: Int = 0
    companion object { const val FLAG_SYSTEM = 1 }
}
open class PackageInfo
open class PackageManager {
    open fun getInstalledApplications(f: Int): MutableList<ApplicationInfo> = mutableListOf()
    open fun getApplicationLabel(i: ApplicationInfo): CharSequence = ""
    open fun getPackageInfo(p: String, f: Int): PackageInfo = PackageInfo()
    companion object { const val GET_META_DATA = 128; const val MATCH_SYSTEM_ONLY = 0 } }
