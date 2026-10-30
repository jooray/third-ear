package app.thirdear.activities

import org.fossify.commons.activities.BaseSimpleActivity
import app.thirdear.R
import app.thirdear.helpers.REPOSITORY_NAME

open class SimpleActivity : BaseSimpleActivity() {
    companion object {
        private const val PICK_FOLDER = 7341
        private var folderCallback: ((android.net.Uri?) -> Unit)? = null

        /** The system folder picker, opening at [initial] when given; [callback] gets the tree Uri or null. */
        fun pickFolder(activity: BaseSimpleActivity, initial: android.net.Uri? = null, callback: (android.net.Uri?) -> Unit) {
            folderCallback = callback
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
                if (initial != null) intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initial)
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, PICK_FOLDER)
            } catch (ignored: Exception) {
                // no folder picker on this phone: the caller treats it as cancelled
                folderCallback = null
                callback(null)
            }
        }
    }

    /**
     * fossify-commons nags "You are using a fake version of the app" at random (and on the first
     * run) in BaseSimpleActivity.onCreate, and opens that nag instead of the color customization,
     * whenever the package name does not start with org.fossify. Third Ear is a GPL fork with its
     * own id; commons is shown a fossify-style name only while those two calls run.
     */
    private var commonsIdentity = false

    override fun getPackageName(): String = if (commonsIdentity) "org.fossify.thirdear" else super.getPackageName()

    protected fun <T> withCommonsIdentity(block: () -> T): T {
        commonsIdentity = true
        try {
            return block()
        } finally {
            commonsIdentity = false
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        withCommonsIdentity { super.onCreate(savedInstanceState) }
    }

    fun openColorCustomization() = withCommonsIdentity { startCustomizationActivity() }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: android.content.Intent?) {
        if (requestCode == PICK_FOLDER) {
            val cb = folderCallback
            folderCallback = null
            val tree = if (resultCode == RESULT_OK) resultData?.data else null
            // Android may end the process while the picker is open: the choice still counts
            if (cb != null) cb(tree) else if (tree != null) onFolderPickedAfterRestart(tree)
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, resultData)
    }

    /** A folder picked while the process was gone (the caller's callback with it). */
    protected open fun onFolderPickedAfterRestart(tree: android.net.Uri) = Unit

    override fun getAppIconIDs() = arrayListOf(
        R.mipmap.ic_launcher_red,
        R.mipmap.ic_launcher_pink,
        R.mipmap.ic_launcher_purple,
        R.mipmap.ic_launcher_deep_purple,
        R.mipmap.ic_launcher_indigo,
        R.mipmap.ic_launcher_blue,
        R.mipmap.ic_launcher_light_blue,
        R.mipmap.ic_launcher_cyan,
        R.mipmap.ic_launcher_teal,
        R.mipmap.ic_launcher,
        R.mipmap.ic_launcher_light_green,
        R.mipmap.ic_launcher_lime,
        R.mipmap.ic_launcher_yellow,
        R.mipmap.ic_launcher_amber,
        R.mipmap.ic_launcher_orange,
        R.mipmap.ic_launcher_deep_orange,
        R.mipmap.ic_launcher_brown,
        R.mipmap.ic_launcher_blue_grey,
        R.mipmap.ic_launcher_grey_black
    )

    override fun getAppLauncherName() = getString(R.string.app_launcher_name)

    override fun getRepositoryName() = REPOSITORY_NAME
}
