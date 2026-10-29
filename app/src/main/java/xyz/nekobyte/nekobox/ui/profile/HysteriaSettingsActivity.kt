package xyz.nekobyte.nekobox.ui.profile

import android.os.Bundle
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.preference.EditTextPreferenceModifiers
import xyz.nekobyte.nekobox.fmt.hysteria.HysteriaBean
import xyz.nekobyte.nekobox.fmt.hysteria.canonicalHysteria2ECHConfig
import xyz.nekobyte.nekobox.ktx.applyDefaultValues
import xyz.nekobyte.nekobox.ktx.onMainDispatcher
import xyz.nekobyte.nekobox.ui.ChoicePreference

class HysteriaSettingsActivity : ProfileSettingsActivity<HysteriaBean>() {

    override fun createEntity() = HysteriaBean().applyDefaultValues()

    override fun HysteriaBean.init() {
        DataStore.profileName = name!!
        DataStore.serverAddress = serverAddress!!
        DataStore.serverPorts = serverPorts!!
        DataStore.serverObfs = obfuscation!!
        DataStore.serverHy2ObfsType = hysteria2ObfsType!!
        DataStore.serverHy2GeckoMinPacket = geckoMinPacketSize!!
        DataStore.serverHy2GeckoMaxPacket = geckoMaxPacketSize!!
        DataStore.serverHy2EchEnabled = enableECH!!
        DataStore.serverHy2EchConfig = echConfig!!
        DataStore.serverPassword = authPayload!!
        DataStore.serverSNI = sni!!
        DataStore.serverCertificates = caText!!
        DataStore.serverAllowInsecure = allowInsecure!!
        DataStore.serverUploadSpeed = uploadMbps!!
        DataStore.serverDownloadSpeed = downloadMbps!!
        DataStore.serverHopInterval = hopInterval!!
    }

    override suspend fun saveAndExit() {
        if (DataStore.serverHy2EchEnabled) {
            val failure = runCatching {
                canonicalHysteria2ECHConfig(DataStore.serverHy2EchConfig)
            }.exceptionOrNull()
            if (failure != null) {
                onMainDispatcher {
                    Toast.makeText(
                        this@HysteriaSettingsActivity,
                        failure.message ?: getString(R.string.hysteria2_ech_config_invalid),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return
            }
        }
        super.saveAndExit()
    }

    override fun HysteriaBean.serialize() {
        check(protocolVersion == 2) { getString(R.string.hysteria1_retired) }
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPorts = DataStore.serverPorts
        obfuscation = DataStore.serverObfs
        hysteria2ObfsType = DataStore.serverHy2ObfsType
        geckoMinPacketSize = DataStore.serverHy2GeckoMinPacket
        geckoMaxPacketSize = DataStore.serverHy2GeckoMaxPacket
        enableECH = DataStore.serverHy2EchEnabled
        echConfig = DataStore.serverHy2EchConfig
        authPayload = DataStore.serverPassword
        sni = DataStore.serverSNI
        caText = DataStore.serverCertificates
        allowInsecure = DataStore.serverAllowInsecure
        uploadMbps = DataStore.serverUploadSpeed
        downloadMbps = DataStore.serverDownloadSpeed
        hopInterval = DataStore.serverHopInterval
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.hysteria_preferences)

        val enableECH = findPreference<SwitchPreference>(Key.SERVER_HY2_ECH_ENABLED)!!
        val echConfig = findPreference<EditTextPreference>(Key.SERVER_HY2_ECH_CONFIG)!!
        echConfig.isVisible = DataStore.serverHy2EchEnabled
        enableECH.setOnPreferenceChangeListener { _, newValue ->
            echConfig.isVisible = newValue as Boolean
            true
        }

        val obfsType = findPreference<ChoicePreference>(Key.SERVER_HY2_OBFS_TYPE)!!
        val obfsPassword = findPreference<EditTextPreference>(Key.SERVER_OBFS)!!
        val geckoMin = findPreference<EditTextPreference>(Key.SERVER_HY2_GECKO_MIN_PACKET)!!
        val geckoMax = findPreference<EditTextPreference>(Key.SERVER_HY2_GECKO_MAX_PACKET)!!
        geckoMin.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        geckoMax.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)

        fun updateObfs(type: Int) {
            obfsPassword.isVisible = type != HysteriaBean.OBFS_NONE
            geckoMin.isVisible = type == HysteriaBean.OBFS_GECKO
            geckoMax.isVisible = type == HysteriaBean.OBFS_GECKO
        }
        obfsType.setOnPreferenceChangeListener { _, newValue ->
            updateObfs(newValue.toString().toIntOrNull() ?: HysteriaBean.OBFS_NONE)
            true
        }
        updateObfs(DataStore.serverHy2ObfsType)

        for (key in listOf(Key.SERVER_UPLOAD_SPEED, Key.SERVER_DOWNLOAD_SPEED, Key.SERVER_HOP_INTERVAL)) {
            findPreference<EditTextPreference>(key)!!.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.summaryProvider = PasswordSummaryProvider
        obfsPassword.summaryProvider = PasswordSummaryProvider
    }
}
