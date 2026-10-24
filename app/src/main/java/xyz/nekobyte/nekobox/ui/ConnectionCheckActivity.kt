package xyz.nekobyte.nekobox.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.OkHttpClient
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.databinding.ItemCheckMoreBinding
import xyz.nekobyte.nekobox.databinding.ItemCheckResultBinding
import xyz.nekobyte.nekobox.databinding.ItemCheckSectionBinding
import xyz.nekobyte.nekobox.databinding.ItemConnectionCheckAddressBinding
import xyz.nekobyte.nekobox.databinding.LayoutConnectionCheckBinding
import xyz.nekobyte.nekobox.ktx.getColorAttr
import xyz.nekobyte.nekobox.ktx.readableMessage
import xyz.nekobyte.nekobox.net.AddressReputation
import xyz.nekobyte.nekobox.net.ConnectionProbes
import xyz.nekobyte.nekobox.net.DnsLeakTest
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * What this connection shows to the internet: the bash.ws DNS leak test, the IPv4 and IPv6 exits, the UDP
 * exit, public VPN, datacenter and Tor lists, DNSSEC validation on the way and the app's own TLS.
 */
class ConnectionCheckActivity : ThemedActivity() {
    private lateinit var binding: LayoutConnectionCheckBinding
    private var check: Job? = null
    private var lookup: Job? = null

    // The client and address of the last check, for the proxycheck.io lookup on request.
    private var lookupClient: OkHttpClient? = null
    private var lookupAddress: String? = null

    // The path the label and any results on screen belong to.
    private var shown: Path? = null

    private enum class Path(val label: Int, val icon: Int) {
        VPN(R.string.connection_check_path_vpn, R.drawable.ic_baseline_vpn_key_24),
        PROXY(R.string.connection_check_path_proxy, R.drawable.ic_hardware_router),
        DIRECT(R.string.connection_check_path_direct, R.drawable.baseline_public_24),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutConnectionCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.connection_check)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }
        // The page runs edge to edge; its end stays clear of the navigation bar.
        val bottom = binding.connectionCheckScroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.connectionCheckScroll) { view, insets ->
            view.updatePadding(bottom = bottom + insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }
        binding.connectionCheckStart.setOnClickListener { start() }
        binding.connectionCheckProxycheck.setOnClickListener { lookUp() }
    }

    override fun onResume() {
        super.onResume()
        // A check that started on another path no longer describes this connection.
        if (check?.isActive == true && path() != shown) check?.cancel()
        if (check?.isActive != true) followPath()
    }

    // Results from another path no longer describe this connection. Returns whether the path held.
    private fun followPath(): Boolean {
        val path = path()
        val held = path == shown
        if (!held) hideResults()
        showPath(path)
        return held
    }

    private fun showPath(path: Path) {
        shown = path
        binding.connectionCheckPath.setText(path.label)
        val icon = ContextCompat.getDrawable(this, path.icon)?.mutate()?.apply { setTint(getColorAttr(android.R.attr.textColorSecondary)) }
        binding.connectionCheckPath.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
    }

    private fun path() = when {
        !DataStore.serviceState.started -> Path.DIRECT
        DataStore.serviceMode == Key.MODE_PROXY -> Path.PROXY
        else -> Path.VPN
    }

    // This app stays inside the VPN, so its requests and lookups take the path other apps take. In
    // proxy mode only apps set to use the local proxy go through NekoBox, so the check does too.
    private fun client(path: Path) = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .apply {
            if (path != Path.PROXY) return@apply
            proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", DataStore.mixedPort)))
            // Only the service process knows whether the running inbound asks for credentials, so
            // they are sent when it does: on its challenge, once.
            proxyAuthenticator { _, response ->
                response.request.takeIf { it.header("Proxy-Authorization") == null }?.newBuilder()
                    ?.header("Proxy-Authorization", Credentials.basic(Key.MIXED_USERNAME, DataStore.mixedSecret))?.build()
            }
        }
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return@apply
            // Android 6 reads no network security config, so the root bash.ws chains to is added here,
            // next to the system and user certificates Android 6 trusts by default.
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
            val system = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            for (alias in system.aliases()) store.setCertificateEntry(alias, system.getCertificate(alias))
            resources.openRawResource(R.raw.isrg_root_x1).use { store.setCertificateEntry("isrg_root_x1", CertificateFactory.getInstance("X.509").generateCertificate(it)) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
                .trustManagers.filterIsInstance<X509TrustManager>().single()
            sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory, trust)
        }
        .build()

    private fun hideResults() {
        binding.connectionCheckFailure.isVisible = false
        binding.connectionCheckResults.isVisible = false
        binding.connectionCheckRows.isVisible = false
        binding.connectionCheckLookup.isVisible = false
    }

    private fun start() {
        val path = path()
        showPath(path)
        binding.connectionCheckStart.isEnabled = false
        binding.connectionCheckProgress.isVisible = true
        hideResults()
        check?.cancel()
        lookup?.cancel()
        check = lifecycleScope.launch {
            try {
                val client = client(path)

                // Results are shown only while the path they were measured on holds.
                fun held() = path() == path
                coroutineScope {
                    // The DNS result shows as soon as it is in; the other rows wait for one another.
                    launch {
                        try {
                            DnsLeakTest.run(client).let { if (held()) show(it) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (held()) failed(e)
                        }
                    }
                    val ipv4 = async { ConnectionProbes.exit(client, ConnectionProbes.IPV4) }
                    val ipv6 = async { ConnectionProbes.exit(client, ConnectionProbes.IPV6) }
                    // An HTTP proxy carries no UDP, so in proxy mode there is nothing to compare.
                    val udp = async { if (path == Path.PROXY) null else ConnectionProbes.udpAddress() }
                    val trace = async { ConnectionProbes.trace(client) }
                    val dnssec = async { ConnectionProbes.dnssec(client) }
                    val public = listOf(ipv4.await(), ipv6.await()).mapNotNull { (it as? ConnectionProbes.Exit.Seen)?.address }
                    val lists = async { AddressReputation.match(client, cacheDir, public.map { it.ip }) }
                    // Another UDP address is looked up, so that only another network counts as another exit.
                    val udpNetwork = udp.await()?.address?.takeIf { address -> public.none { AddressReputation.numeric(it.ip)?.contentEquals(address.address) == true } }
                        ?.let { ConnectionProbes.publicAddress(client, ConnectionProbes.lookupUrl(it.hostAddress.orEmpty())) }
                    val matches = lists.await()
                    val validation = dnssec.await()
                    val tls = trace.await()
                    if (!held()) {
                        followPath()
                        return@coroutineScope
                    }
                    lookupClient = client
                    lookupAddress = public.firstOrNull()?.ip
                    rows(path, ipv4.await(), ipv6.await(), udp.await(), udpNetwork, matches, validation, tls)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (path() == path) failed(e)
            } finally {
                binding.connectionCheckProgress.isVisible = false
                binding.connectionCheckStart.isEnabled = true
            }
        }
    }

    private fun failed(e: Throwable) = with(binding.connectionCheckFailure) {
        text = if (e is DnsLeakTest.ServiceException) {
            getString(R.string.connection_check_service_error, e.message)
        } else {
            getString(R.string.connection_check_failed, e.readableMessage)
        }
        val color = getColorAttr(androidx.appcompat.R.attr.colorError)
        setTextColor(color)
        val icon = ContextCompat.getDrawable(context, R.drawable.ic_baseline_warning_24)?.mutate()?.apply { setTint(color) }
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
        isVisible = true
    }

    private fun rows(
        path: Path,
        ipv4: ConnectionProbes.Exit,
        ipv6: ConnectionProbes.Exit,
        udp: InetSocketAddress?,
        udpNetwork: ConnectionProbes.PublicAddress?,
        lists: Map<String, AddressReputation.Match>,
        dnssec: ConnectionProbes.Dnssec,
        trace: ConnectionProbes.Trace?,
    ) {
        binding.connectionCheckRows.removeAllViews()
        section(R.string.connection_check_section_exits)
        val v4 = (ipv4 as? ConnectionProbes.Exit.Seen)?.address
        val v6 = (ipv6 as? ConnectionProbes.Exit.Seen)?.address
        when (ipv4) {
            is ConnectionProbes.Exit.Seen -> row(R.string.connection_check_ipv4, describe(ipv4.address), CheckTone.INFO)
            ConnectionProbes.Exit.Unreachable -> row(R.string.connection_check_ipv4, getString(R.string.connection_check_ipv4_none), CheckTone.INFO)
            ConnectionProbes.Exit.Unchecked -> row(R.string.connection_check_ipv4, getString(R.string.connection_check_unchecked), CheckTone.INFO)
        }
        val ipv6Detail = v6?.let(::describe)
        // A failed answer says nothing about whether IPv6 gets through.
        if (ipv6 == ConnectionProbes.Exit.Unchecked) {
            row(R.string.connection_check_ipv6, getString(R.string.connection_check_unchecked), CheckTone.INFO)
        } else {
            when (ConnectionProbes.ipv6(path == Path.DIRECT, v4, v6)) {
                ConnectionProbes.Ipv6.NONE -> row(R.string.connection_check_ipv6, getString(R.string.connection_check_ipv6_none), if (path == Path.DIRECT) CheckTone.INFO else CheckTone.OK)
                ConnectionProbes.Ipv6.CONNECTED -> row(R.string.connection_check_ipv6, getString(R.string.connection_check_ipv6_connected), CheckTone.INFO, ipv6Detail)
                ConnectionProbes.Ipv6.SAME_NETWORK -> row(R.string.connection_check_ipv6, getString(R.string.connection_check_ipv6_same), CheckTone.OK, ipv6Detail)
                ConnectionProbes.Ipv6.OTHER_NETWORK -> row(R.string.connection_check_ipv6, getString(R.string.connection_check_ipv6_other), CheckTone.WARNING, ipv6Detail)
                ConnectionProbes.Ipv6.UNCOMPARED -> row(R.string.connection_check_ipv6, getString(R.string.connection_check_ipv6_uncompared), CheckTone.INFO, ipv6Detail)
            }
        }
        val udpDetail = udp?.let { listOfNotNull(it.address.hostAddress, udpNetwork?.isp?.ifEmpty { null }).joinToString(" · ") }
        // WebRTC protection drops STUN in the VPN, unless the profile brings a config of its own.
        val webrtc = getString(R.string.webrtc_may_drop_stun).takeIf { path == Path.VPN && DataStore.webrtcLeakProtection }
        when (ConnectionProbes.udp(path != Path.PROXY, listOfNotNull(v4, v6), udp, udpNetwork)) {
            ConnectionProbes.Udp.NOT_CHECKED -> row(R.string.connection_check_udp, getString(R.string.connection_check_udp_proxy), CheckTone.INFO)
            ConnectionProbes.Udp.NO_ANSWER -> row(R.string.connection_check_udp, getString(R.string.connection_check_udp_none), CheckTone.INFO, webrtc)
            ConnectionProbes.Udp.SAME_ADDRESS -> row(R.string.connection_check_udp, getString(R.string.connection_check_udp_same_address), CheckTone.OK, udpDetail)
            ConnectionProbes.Udp.SAME_NETWORK -> row(R.string.connection_check_udp, getString(R.string.connection_check_udp_same), CheckTone.INFO, udpDetail)
            ConnectionProbes.Udp.OTHER_NETWORK -> row(R.string.connection_check_udp, getString(R.string.connection_check_udp_other), CheckTone.WARNING, udpDetail)
            ConnectionProbes.Udp.OTHER_ADDRESS -> row(R.string.connection_check_udp, getString(R.string.connection_check_udp_other_address), CheckTone.INFO, udpDetail)
        }
        section(R.string.connection_check_section_dns_tls)
        when (dnssec) {
            ConnectionProbes.Dnssec.VALIDATED -> row(R.string.connection_check_dnssec, getString(R.string.connection_check_dnssec_validated), CheckTone.OK)
            ConnectionProbes.Dnssec.NOT_VALIDATED -> row(R.string.connection_check_dnssec, getString(R.string.connection_check_dnssec_not_validated), CheckTone.WARNING)
            ConnectionProbes.Dnssec.UNKNOWN -> row(R.string.connection_check_dnssec, getString(R.string.connection_check_unchecked), CheckTone.INFO)
        }
        if (trace == null) {
            row(R.string.connection_check_tls, getString(R.string.connection_check_tls_unknown), CheckTone.INFO)
        } else {
            val ech = when (trace.encryptedSni) {
                true -> getString(R.string.connection_check_tls_ech)
                false -> getString(R.string.connection_check_tls_no_ech)
                null -> null
            }
            val status = listOfNotNull(trace.tls?.replace("TLSv", "TLS "), ech).joinToString(" · ").ifEmpty { getString(R.string.connection_check_tls_unknown) }
            row(R.string.connection_check_tls, status, CheckTone.INFO, getString(R.string.connection_check_tls_detail))
        }
        // Last, so a proxycheck.io answer lands under it, next to the button that asks.
        section(R.string.connection_check_section_lists)
        val kinds = lists.values.flatMap { it.kinds }.toSortedSet()
        when {
            lists.isEmpty() -> row(R.string.connection_check_lists, getString(R.string.connection_check_unchecked), CheckTone.INFO)

            kinds.isNotEmpty() -> row(R.string.connection_check_lists, getString(R.string.connection_check_lists_found, kinds.joinToString(", ") { getString(it.label()) }), CheckTone.INFO)

            lists.values.any { !it.complete } -> row(R.string.connection_check_lists, getString(R.string.connection_check_lists_incomplete), CheckTone.INFO)

            else -> row(
                R.string.connection_check_lists,
                getString(R.string.connection_check_lists_none),
                CheckTone.OK,
                getString(R.string.connection_check_lists_tor_ipv4).takeIf { lists.values.any { AddressReputation.Kind.TOR in it.uncovered } },
            )
        }
        binding.connectionCheckRows.isVisible = true
        binding.connectionCheckLookup.isVisible = lookupAddress != null
    }

    private fun AddressReputation.Kind.label() = when (this) {
        AddressReputation.Kind.VPN -> R.string.connection_check_kind_vpn
        AddressReputation.Kind.DATACENTER -> R.string.connection_check_kind_datacenter
        AddressReputation.Kind.TOR -> R.string.connection_check_kind_tor
    }

    private fun describe(address: ConnectionProbes.PublicAddress) = listOf(address.ip, address.isp, address.country).filter { it.isNotEmpty() }.joinToString(" · ")

    // Sends the address to proxycheck.io through the same path as the check.
    private fun lookUp() {
        if (!followPath()) return
        val client = lookupClient ?: return
        val address = lookupAddress ?: return
        binding.connectionCheckProxycheck.isEnabled = false
        lookup?.cancel()
        lookup = lifecycleScope.launch {
            try {
                val result = AddressReputation.proxycheck(client, address)
                val flags = listOfNotNull(
                    R.string.connection_check_flag_vpn.takeIf { result.vpn },
                    R.string.connection_check_flag_proxy.takeIf { result.proxy },
                    R.string.connection_check_flag_tor.takeIf { result.tor },
                    R.string.connection_check_flag_hosting.takeIf { result.hosting },
                ).map { getString(it) }
                val detail = listOfNotNull(result.operator?.let { getString(R.string.connection_check_proxycheck_operator, it) }, result.network).joinToString("\n")
                if (flags.isEmpty()) {
                    row(R.string.connection_check_proxycheck_title, getString(R.string.connection_check_proxycheck_none), CheckTone.OK, detail, LOOKUP)
                } else {
                    row(R.string.connection_check_proxycheck_title, getString(R.string.connection_check_proxycheck_found, flags.joinToString(", ")), CheckTone.INFO, detail, LOOKUP)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                row(R.string.connection_check_proxycheck_title, getString(R.string.connection_check_proxycheck_failed, e.readableMessage), CheckTone.WARNING, null, LOOKUP)
            } finally {
                binding.connectionCheckProxycheck.isEnabled = true
            }
        }
    }

    private fun section(@StringRes title: Int) {
        ItemCheckSectionBinding.inflate(layoutInflater, binding.connectionCheckRows, true).root.setText(title)
    }

    private fun row(@StringRes title: Int, status: CharSequence, tone: CheckTone, detail: CharSequence? = null, tag: String? = null) {
        val rows = binding.connectionCheckRows
        tag?.let { rows.findViewWithTag<View>(it)?.let(rows::removeView) }
        ItemCheckResultBinding.inflate(layoutInflater, rows, true).apply {
            root.tag = tag
            showResult(title, status, tone, detail)
        }
    }

    private fun show(result: DnsLeakTest.Result) {
        when (result.verdict) {
            DnsLeakTest.Verdict.CLEAN -> verdict(getString(R.string.dns_leak_none), CheckTone.OK)

            DnsLeakTest.Verdict.LEAK -> verdict(getString(R.string.dns_leak_found), CheckTone.WARNING)

            // Text bash.ws did not phrase as a known verdict is shown as it came.
            DnsLeakTest.Verdict.UNKNOWN -> verdict(result.conclusion ?: getString(R.string.connection_check_no_verdict), CheckTone.INFO)
        }
        fill(binding.connectionCheckAddresses, result.addresses)
        fill(binding.connectionCheckServers, result.servers)
        binding.connectionCheckResults.isVisible = true
    }

    private fun verdict(text: CharSequence, tone: CheckTone) = binding.connectionCheckVerdict.showVerdict(text, tone)

    private companion object {
        const val LOOKUP = "proxycheck"

        // Public resolvers answer from a dozen addresses or more; this many show until asked for the rest.
        const val FOLDED = 3
    }

    private fun fill(list: LinearLayout, addresses: List<DnsLeakTest.Address>) {
        list.removeAllViews()
        if (addresses.isEmpty()) {
            ItemConnectionCheckAddressBinding.inflate(layoutInflater, list, true).apply {
                addressIp.setText(R.string.connection_check_none)
                addressDetail.isVisible = false
            }
        }
        // Folding away a single address would save nothing.
        val shown = if (addresses.size > FOLDED + 1) addresses.take(FOLDED) else addresses
        shown.forEach { add(list, it) }
        if (shown.size == addresses.size) return
        ItemCheckMoreBinding.inflate(layoutInflater, list, true).root.apply {
            text = getString(R.string.connection_check_show_more, addresses.size - shown.size)
            setOnClickListener {
                list.removeView(this)
                addresses.drop(shown.size).forEach { add(list, it) }
            }
        }
    }

    private fun add(list: LinearLayout, address: DnsLeakTest.Address) {
        ItemConnectionCheckAddressBinding.inflate(layoutInflater, list, true).apply {
            addressIp.text = address.ip
            addressDetail.text = listOf(address.place, address.asn).filter { it.isNotEmpty() }.joinToString(" · ")
            addressDetail.isVisible = addressDetail.text.isNotEmpty()
        }
    }
}
