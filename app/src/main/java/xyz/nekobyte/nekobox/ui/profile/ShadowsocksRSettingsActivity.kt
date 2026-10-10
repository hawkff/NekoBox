package xyz.nekobyte.nekobox.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.preference.EditTextPreferenceModifiers
import xyz.nekobyte.nekobox.fmt.shadowsocksr.ShadowsocksRBean

class ShadowsocksRSettingsActivity : ProfileSettingsActivity<ShadowsocksRBean>() {

    override fun createEntity() = ShadowsocksRBean()

    override fun ShadowsocksRBean.init() {
        DataStore.profileName = name!!
        DataStore.serverAddress = serverAddress!!
        DataStore.serverPort = serverPort!!
        DataStore.serverMethod = method!!
        DataStore.serverPassword = password!!
        DataStore.serverProtocol = protocol!!
        DataStore.serverObfs = obfs!!
        DataStore.serverProtocolParam = protocolParam!!
        DataStore.serverObfsParam = obfsParam!!
    }

    override fun ShadowsocksRBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        method = DataStore.serverMethod
        password = DataStore.serverPassword
        protocol = DataStore.serverProtocol
        obfs = DataStore.serverObfs
        protocolParam = DataStore.serverProtocolParam
        obfsParam = DataStore.serverObfsParam
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.shadowsocksr_preferences)
        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
    }
}
