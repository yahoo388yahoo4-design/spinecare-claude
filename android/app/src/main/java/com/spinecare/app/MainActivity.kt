package com.spinecare.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.spinecare.app.I18n.t
import com.spinecare.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        labelTabs()
        b.bottomNav.setOnItemSelectedListener { item -> show(fragmentFor(item.itemId)); true }
        if (savedInstanceState == null) { show(HomeFragment()); offerOldAppRemoval() }
    }

    /** The app id changed (com.spinecare.app -> com.spinecare.claude): once, offer to
     *  remove the old install through the system dialog. Settings keeps the button. */
    private fun offerOldAppRemoval() {
        val prefs = getSharedPreferences("spinecare", MODE_PRIVATE)
        if (prefs.getBoolean("old_app_offered", false) || !AppPackages.oldInstalled(this)) return
        prefs.edit().putBoolean("old_app_offered", true).apply()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(t("old_app_title"))
            .setMessage(t("uninstall_old_body"))
            .setPositiveButton(t("uninstall_old")) { _, _ -> AppPackages.uninstall(this, AppPackages.OLD_PACKAGE) }
            .setNegativeButton(t("skip"), null)
            .show()
    }

    private fun fragmentFor(id: Int): Fragment = when (id) {
        R.id.tab_exercises -> ExercisesFragment()
        R.id.tab_sessions -> SessionsFragment()
        R.id.tab_sensors -> SensorsFragment()
        R.id.tab_settings -> SettingsFragment()
        else -> HomeFragment()
    }

    private fun labelTabs() {
        val m = b.bottomNav.menu
        m.findItem(R.id.tab_home).title = t("tab_home")
        m.findItem(R.id.tab_exercises).title = t("tab_plan")
        m.findItem(R.id.tab_sessions).title = t("tab_progress")
        m.findItem(R.id.tab_sensors).title = t("tab_sensors")
        m.findItem(R.id.tab_settings).title = t("tab_settings")
    }

    /** In-app English/Romanian switch (independent of the system language). */
    fun toggleLanguage() = setLanguage(if (I18n.lang == "en") "ro" else "en")

    fun setLanguage(l: String) {
        if (l == I18n.lang) return
        I18n.set(this, l)
        labelTabs()
        show(fragmentFor(b.bottomNav.selectedItemId))
    }

    private fun show(f: Fragment) {
        supportFragmentManager.beginTransaction().replace(R.id.fragment, f).commit()
    }
}
