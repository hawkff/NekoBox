package xyz.nekobyte.nekobox.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.preference.EditTextPreferenceModifiers
import xyz.nekobyte.nekobox.fmt.naive.NaiveBean

class NaiveSettingsActivity : ProfileSettingsActivity<NaiveBean>() {

    override fun createEntity() = NaiveBean()

    override fun NaiveBean.init() {
        DataStore.profileName = name!!
        DataStore.serverAddress = serverAddress!!
        DataStore.serverPort = serverPort!!
        DataStore.serverUsername = username!!
        DataStore.serverPassword = password!!
        DataStore.serverProtocol = proto!!
        DataStore.serverSNI = sni!!
        DataStore.serverCertificates = certificates!!
        DataStore.serverHeaders = extraHeaders!!
        DataStore.serverInsecureConcurrency = insecureConcurrency!!
        DataStore.profileCacheStore.putBoolean("sUoT", sUoT)
    }

    override fun NaiveBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        username = DataStore.serverUsername
        password = DataStore.serverPassword
        proto = DataStore.serverProtocol
        sni = DataStore.serverSNI
        certificates = DataStore.serverCertificates
        extraHeaders = DataStore.serverHeaders.replace("\r\n", "\n")
        insecureConcurrency = DataStore.serverInsecureConcurrency
        sUoT = DataStore.profileCacheStore.getBoolean("sUoT")
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.naive_preferences)
        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        findPreference<EditTextPreference>(Key.SERVER_INSECURE_CONCURRENCY)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
    }
}
