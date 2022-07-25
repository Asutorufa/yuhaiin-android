package io.github.asutorufa.yuhaiin

import android.Manifest
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import androidx.preference.*
import com.github.logviewer.LogcatActivity
import io.github.asutorufa.yuhaiin.database.Manager
import io.github.asutorufa.yuhaiin.database.Manager.profile
import io.github.asutorufa.yuhaiin.database.Manager.setOnPreferenceChangeListener

class ProfileFragment : PreferenceFragmentCompat() {
    private val refreshPreferences = ArrayList<() -> Unit>()
    private val mainActivity by lazy { requireActivity() as MainActivity }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) =
        setPreferencesFromResource(R.xml.settings, rootKey)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        @Suppress("CAST_NEVER_SUCCEEDS")
        (mainActivity as MenuHost).addMenuProvider(
            menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED
        )
        preferenceManager.preferenceDataStore = mainActivity.dataStore
        initPreferences()
        reload()
    }

    private val menuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) =
            menuInflater.inflate(R.menu.main, menu)

        fun showAlertDialog(title: Int, view: View?, message: String?, PositiveFun: () -> Unit) =
            AlertDialog.Builder(requireContext()).apply {
                setTitle(title)
                view?.let { setView(it) }
                message?.let { setMessage(it) }
                setPositiveButton(android.R.string.ok) { _, _ -> PositiveFun() }
                setNegativeButton(android.R.string.cancel) { _, _ -> }
                show()
            }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean =
            when (menuItem.itemId) {
                R.id.prof_add -> {
                    val e = AppCompatEditText(requireContext()).apply { isSingleLine = true }
                    showAlertDialog(R.string.prof_add, e, null) {
                        val name = e.text.toString()
                        if (name.isEmpty()) return@showAlertDialog
                        try {
                            Manager.addProfile(name)
                        } catch (e: Exception) {
                            e.message?.let { mainActivity.showSnackBar(it) }
                        }
                        reload()
                    }
                    true
                }
                R.id.prof_del -> {
                    showAlertDialog(
                        R.string.prof_del,
                        null,
                        String.format(getString(R.string.prof_del_confirm), profile.name)
                    ) {
                        try {
                            Manager.deleteProfile()
                            reload()
                        } catch (e: Exception) {
                            e.message?.let { mainActivity.showSnackBar(it) }
                        }
                    }
                    true
                }
                else -> false
            }
    }

    private fun initPreferences() {
        findPreference<ListPreference>(resources.getString(R.string.profile_key))!!.also {
            refreshPreferences.add {
                it.value = profile.name
                val profiles = Manager.getProfileNames()
                it.entries = profiles.toTypedArray()
                it.entryValues = profiles.toTypedArray()
            }

            setOnPreferenceChangeListener(it) { _, newValue ->
                Manager.switchProfile(newValue as String)
                reload()
            }
        }

        findPreference<EditTextPreference>(resources.getString(R.string.yuhaiin_port_key))!!.also {
            it.setOnBindEditTextListener { editText: EditText ->
                editText.inputType =
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }

            setOnPreferenceChangeListener(it) { _, newValue ->
                profile.yuhaiinPort = newValue.toString().toInt()
            }

            refreshPreferences.add { it.text = profile.yuhaiinPort.toString() }
        }

        findPreference<EditTextPreference>(resources.getString(R.string.http_server_port_key))!!.also {
            it.setOnBindEditTextListener { editText: EditText ->
                editText.inputType =
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }

            setOnPreferenceChangeListener(it) { _, newValue ->
                profile.httpServerPort = newValue.toString().toInt()
            }

            refreshPreferences.add { it.text = profile.httpServerPort.toString() }
        }

        findPreference<SwitchPreferenceCompat>(resources.getString(R.string.append_http_proxy_to_vpn))!!.also {
            setOnPreferenceChangeListener(it) { _, newValue ->
                profile.appendHttpProxyToSystem = newValue as Boolean
            }
            refreshPreferences.add { it.isChecked = profile.appendHttpProxyToSystem }
        }

        findPreference<EditTextPreference>(resources.getString(R.string.socks5_server_port_key))!!.also {
            it.setOnBindEditTextListener { editText: EditText ->
                editText.inputType =
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }

            setOnPreferenceChangeListener(it) { _, newValue ->
                profile.socks5ServerPort = newValue.toString().toInt()
            }

            refreshPreferences.add { it.text = profile.socks5ServerPort.toString() }
        }

        findPreference<SwitchPreferenceCompat>(resources.getString(R.string.auth_userpw_key))!!.also {
            setOnPreferenceChangeListener(it) { _, newValue ->
                profile.isUserPw = newValue as Boolean
                reload()
            }
            refreshPreferences.add { it.isChecked = profile.isUserPw }
        }

        findPreference<EditTextPreference>(resources.getString(R.string.auth_username_key))!!.also {
            setOnPreferenceChangeListener(it) { _, newValue ->
                profile.username = newValue.toString()
            }
            refreshPreferences.add {
                it.text = profile.username
                it.isVisible = profile.isUserPw
            }
        }

        findPreference<EditTextPreference>(resources.getString(R.string.auth_password_key))!!.also {
            it.setOnBindEditTextListener { editText: EditText ->
                editText.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

            setOnPreferenceChangeListener(it) { _, newValue ->
                profile.password = newValue.toString()
            }
            refreshPreferences.add {
                it.text = profile.password
                it.isVisible = profile.isUserPw
            }
        }

        findPreference<SwitchPreferenceCompat>(resources.getString(R.string.adv_per_app_key))!!.also {
            setOnPreferenceChangeListener(it) { _, newValue ->
                profile.isPerApp = newValue as Boolean
            }
            refreshPreferences.add { it.isChecked = profile.isPerApp }
        }

        findPreference<SwitchPreferenceCompat>(resources.getString(R.string.adv_app_bypass_key))!!.also {
            setOnPreferenceChangeListener(it) { _, newValue ->
                profile.isBypassApp = newValue as Boolean
            }
            refreshPreferences.add { it.isChecked = profile.isBypassApp }
        }
        findPreference<MultiSelectListPreference>(resources.getString(R.string.adv_app_list_key))!!.also {
            it.setOnPreferenceClickListener { _ ->
                updateAppList(it)
                false
            }

            @Suppress("Unchecked_Cast")
            setOnPreferenceChangeListener(it) { _, newValue ->
                profile.appList = newValue as Set<String>
            }

            refreshPreferences.add { it.values = profile.appList }
        }

        findPreference<SwitchPreferenceCompat>(resources.getString(R.string.ipv6_proxy_key))!!.also {
            setOnPreferenceChangeListener(it) { _, newValue ->
                profile.hasIPv6 = newValue as Boolean
            }
            refreshPreferences.add { it.isChecked = profile.hasIPv6 }
        }

        findPreference<SwitchPreferenceCompat>(resources.getString(R.string.adv_auto_connect_key))!!.also {
            setOnPreferenceChangeListener(it) { _, newValue ->
                profile.autoConnect = newValue as Boolean
            }
            refreshPreferences.add { it.isChecked = profile.autoConnect }
        }

        findPreference<SwitchPreferenceCompat>(resources.getString(R.string.allow_lan_key))!!.also {
            setOnPreferenceChangeListener(it) { _, newValue ->
                profile.allowLan = newValue as Boolean
            }
            refreshPreferences.add { it.isChecked = profile.allowLan }
        }
        findPreference<Preference>(resources.getString(R.string.rule))!!.also {
            it.setOnPreferenceClickListener {
                findNavController().navigate(ProfileFragmentDirections.actionProfileFragmentToRuleFragment())
                true
            }
        }

        findPreference<SwitchPreferenceCompat>(resources.getString(R.string.save_logcat))!!.also {
            setOnPreferenceChangeListener(it) { _, newValue ->
                profile.saveLogcat = newValue as Boolean
            }
            refreshPreferences.add { it.isChecked = profile.saveLogcat }
        }

        findPreference<Preference>(resources.getString(R.string.logcat))?.apply {
            setOnPreferenceClickListener {
                val logcatExcludeRules = arrayListOf(
                    ".*]: processMotionEvent MotionEvent \\{ action=ACTION_.*",
                    ".*]: dispatchPointerEvent handled=true, event=MotionEvent \\{ action=ACTION_.*",
                    ".*Davey! duration=.*"
                )
                LogcatActivity.start(context, logcatExcludeRules)
                true
            }
        }

        findPreference<Preference>(resources.getString(R.string.open_yuhaiin_page))?.apply {
            setOnPreferenceClickListener {
                if (mainActivity.mBinder?.isRunning == false) {
                    mainActivity.showSnackBar("yuhaiin is not running")
                    return@setOnPreferenceClickListener true
                }

                CustomTabsIntent.Builder()
                    .apply {
                        setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
                    }.build()
                    .launchUrl(
                        requireActivity(),
                        Uri.parse("http://localhost:${profile.yuhaiinPort}")
                    )
//                findNavController().navigate(ProfileFragmentDirections.actionProfileFragmentToPageFragment())
                true
            }
        }

        findPreference<Preference>(resources.getString(R.string.adv_dns_Key))?.let {
            it.setOnPreferenceClickListener {
                findNavController().navigate(ProfileFragmentDirections.actionProfileFragmentToDnsFragment())
                true
            }
        }
    }

    private fun reload() = refreshPreferences.forEach { it() }

    private fun updateAppList(mPrefAppList: MultiSelectListPreference) {
        val titles = mutableListOf<String>()
        val packageNames = mutableListOf<String>()
        var index = 0

        packages.toList().sortedBy { it.second }.forEach {
            if (profile.appList.contains(it.first)) {
                packageNames.add(index, it.first)
                titles.add(index, it.second)
                index++
            } else {
                packageNames.add(it.first)
                titles.add(it.second)
            }
        }

        mPrefAppList.values = profile.appList
        mPrefAppList.entries = titles.toTypedArray()
        mPrefAppList.entryValues = packageNames.toTypedArray()
    }

    private val PackageInfo.hasInternetPermission: Boolean
        get() {
            val permissions = requestedPermissions
            return permissions?.any { it == Manifest.permission.INTERNET } ?: false
        }

    private val packages: Map<String, String>
        get() {
            val packageManager = requireContext().packageManager
            val packages =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    packageManager.getInstalledPackages(
                        PackageManager.PackageInfoFlags.of(
                            PackageManager.GET_PERMISSIONS.toLong()
                        )
                    )
                else
                    packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)

            val apps = HashMap<String, String>()

            packages.forEach {
                if (!it.hasInternetPermission && it.packageName != "android") return@forEach

                val applicationInfo = it.applicationInfo

                val appName = applicationInfo.loadLabel(packageManager).toString()
                // val appIcon = applicationInfo.loadIcon(packageManager)
                // val isSystemApp = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) > 0

                apps[it.packageName] = "$appName\n${it.packageName}"
            }

            return apps
        }
}
