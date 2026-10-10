package xyz.nekobyte.nekobox.ui.profile

import android.os.Bundle
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.preference.EditTextPreferenceModifiers
import xyz.nekobyte.nekobox.fmt.amneziawg.AmneziaWGBean
import xyz.nekobyte.nekobox.fmt.wireguard.MAX_WIREGUARD_PEERS
import xyz.nekobyte.nekobox.ktx.applyDefaultValues
import xyz.nekobyte.nekobox.ktx.onMainDispatcher
import xyz.nekobyte.nekobox.proxy.PreferenceBinding
import xyz.nekobyte.nekobox.proxy.PreferenceBindingManager
import xyz.nekobyte.nekobox.proxy.Type

class AmneziaWGSettingsActivity : ProfileSettingsActivity<AmneziaWGBean>() {

    override fun createEntity() = AmneziaWGBean()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val localAddress = pbm.add(PreferenceBinding(Type.Text, "localAddress"))
    private val privateKey = pbm.add(PreferenceBinding(Type.Text, "privateKey"))
    private val peerPublicKey = pbm.add(PreferenceBinding(Type.Text, "peerPublicKey"))
    private val peerPreSharedKey = pbm.add(PreferenceBinding(Type.Text, "peerPreSharedKey"))
    private val mtu = pbm.add(PreferenceBinding(Type.TextToInt, "mtu"))
    private val reserved = pbm.add(PreferenceBinding(Type.Text, "reserved"))
    private val allowedIPs = pbm.add(PreferenceBinding(Type.Text, "allowedIPs"))
    private val persistentKeepalive = pbm.add(PreferenceBinding(Type.TextToInt, "persistentKeepalive"))
    private val extraPeers = pbm.add(PreferenceBinding(Type.Text, "extraPeers"))
    private val serverPeerPosition = pbm.add(PreferenceBinding(Type.TextToInt, "serverPeerPosition"))
    private val dnsMode = pbm.add(PreferenceBinding(Type.TextToInt, "dnsMode"))
    private val importedDnsServers = pbm.add(PreferenceBinding(Type.Text, "importedDnsServers"))
    private val importedDnsDomains = pbm.add(PreferenceBinding(Type.Text, "importedDnsDomains"))
    private val customDnsServers = pbm.add(PreferenceBinding(Type.Text, "customDnsServers"))
    private val customDnsDomains = pbm.add(PreferenceBinding(Type.Text, "customDnsDomains"))
    private val status = WireGuardStatusLoader(this)

    private val jc = pbm.add(PreferenceBinding(Type.TextToInt, "jc"))
    private val jmin = pbm.add(PreferenceBinding(Type.TextToInt, "jmin"))
    private val jmax = pbm.add(PreferenceBinding(Type.TextToInt, "jmax"))
    private val s1 = pbm.add(PreferenceBinding(Type.TextToInt, "s1"))
    private val s2 = pbm.add(PreferenceBinding(Type.TextToInt, "s2"))
    private val s3 = pbm.add(PreferenceBinding(Type.TextToInt, "s3"))
    private val s4 = pbm.add(PreferenceBinding(Type.TextToInt, "s4"))
    private val h1 = pbm.add(PreferenceBinding(Type.Text, "h1"))
    private val h2 = pbm.add(PreferenceBinding(Type.Text, "h2"))
    private val h3 = pbm.add(PreferenceBinding(Type.Text, "h3"))
    private val h4 = pbm.add(PreferenceBinding(Type.Text, "h4"))
    private val i1 = pbm.add(PreferenceBinding(Type.Text, "i1"))
    private val i2 = pbm.add(PreferenceBinding(Type.Text, "i2"))
    private val i3 = pbm.add(PreferenceBinding(Type.Text, "i3"))
    private val i4 = pbm.add(PreferenceBinding(Type.Text, "i4"))
    private val i5 = pbm.add(PreferenceBinding(Type.Text, "i5"))

    override fun AmneziaWGBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun AmneziaWGBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override suspend fun saveAndExit() {
        val draft = ((proxyEntity?.requireBean() as? AmneziaWGBean)?.clone() ?: createEntity().applyDefaultValues()).apply { serialize() }
        val problem = wireGuardDraftProblem(draft)
        if (problem != null) {
            onMainDispatcher { Toast.makeText(this@AmneziaWGSettingsActivity, problem, Toast.LENGTH_LONG).show() }
            return
        }
        super.saveAndExit()
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.amneziawg_preferences)
        pbm.setPreferenceFragment(this)

        (serverPort.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        (privateKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (mtu.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (persistentKeepalive.preference as EditTextPreference).acceptNumbers(0..65535)
        (serverPeerPosition.preference as EditTextPreference).acceptNumbers(0..MAX_WIREGUARD_PEERS - 1)
        (extraPeers.preference as EditTextPreference).apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Monospace)
            summaryProvider = ExtraPeersSummaryProvider
        }
        status.bind(findPreference("wireguardStatus")!!)
        for (intPref in listOf(jc, jmin, jmax, s1, s2, s3, s4)) {
            (intPref.preference as EditTextPreference)
                .setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
    }
}
