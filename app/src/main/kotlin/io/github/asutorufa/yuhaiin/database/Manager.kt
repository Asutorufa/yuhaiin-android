package io.github.asutorufa.yuhaiin.database

import androidx.preference.Preference
import io.github.asutorufa.yuhaiin.MainApplication

class Manager {
    companion object {
        private val db = MainApplication.db.ProfileDao()
        var profile =
            db.getProfileByName(db.getLastProfile() ?: "Default") ?: Profile(name = "Default")

        fun switchProfile(name: String) {
            db.getProfileByName(name)?.let {
                profile = it
                db.setLastProfile(LastProfile(name = name))
            }
        }

        fun addProfile(name: String) {
            if (db.isProfileExists(name)) {
                throw Exception("Profile $name already exists")
            }
            profile = Profile(name = name)
            db.addProfile(profile)
            db.setLastProfile(LastProfile(name = name))
        }

        fun deleteProfile() {
            db.deleteProfile(profile)
            profile = db.getProfileByName("Default") ?: Profile(name = "Default")
        }

        fun getProfileNames(): List<String> {
            return db.getProfileNames()
        }


        fun setOnPreferenceChangeListener(
            it: Preference,
            run: (p: Preference, newValue: Any) -> Unit
        ) {
            it.setOnPreferenceChangeListener { preference, newValue ->
                run(preference, newValue)
                db.updateProfile(profile)
                true
            }
        }
    }
}