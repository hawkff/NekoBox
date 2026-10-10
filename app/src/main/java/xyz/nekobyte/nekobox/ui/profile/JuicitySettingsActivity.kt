package xyz.nekobyte.nekobox.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.fmt.juicity.JuicityBean
import xyz.nekobyte.nekobox.ktx.applyDefaultValues

class JuicitySettingsActivity : ProfileSettingsActivity<JuicityBean>() {

    override fun createEntity() = JuicityBean().applyDefaultValues()

    override fun JuicityBean.init() {
        DataStore.profileName = name!!
        DataStore.serverAddress = serverAddress!!
        DataStore.serverPort = serverPort!!
        DataStore.serverUserId = uuid!!
        DataStore.serverPassword = password!!
        DataStore.serverSNI = sni!!
        DataStore.serverPinnedCertChainSha256 = pinnedCertchainSha256!!
        DataStore.serverAllowInsecure = allowInsecure!!
    }

    override fun JuicityBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        uuid = DataStore.serverUserId
        password = DataStore.serverPassword
        sni = DataStore.serverSNI
        pinnedCertchainSha256 = DataStore.serverPinnedCertChainSha256
        allowInsecure = DataStore.serverAllowInsecure
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.juicity_preferences)

        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
    }
}
