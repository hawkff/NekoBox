package xyz.nekobyte.nekobox.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
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
import xyz.nekobyte.nekobox.databinding.ItemConnectionCheckAddressBinding
import xyz.nekobyte.nekobox.databinding.ItemConnectionCheckRowBinding
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

    private enum class Tone { OK, WARNING, INFO }

    private enum class Path(val label: Int) {
        VPN(R.string.connection_check_path_vpn),
        PROXY(R.string.connection_check_path_proxy),
        DIRECT(R.string.connection_check_path_direct),
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
        shown = path
        binding.connectionCheckPath.setText(path.label)
        return held
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
        binding.connectionCheckVerdict.isVisible = false
        binding.connectionCheckResults.isVisible = false
        binding.connectionCheckRows.isVisible = false
        binding.connectionCheckLookup.isVisible = false
    }

    private fun start() {
        val path = path()
        shown = path
        binding.connectionCheckPath.setText(path.label)
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

    private fun failed(e: Throwable) {
        val message = if (e is DnsLeakTest.ServiceException) {
            getString(R.string.connection_check_service_error, e.message)
        } else {
            getString(R.string.connection_check_failed, e.readableMessage)
        }
        verdict(message, R.drawable.ic_baseline_warning_24, getColorAttr(androidx.appcompat.R.attr.colorError))
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
        val v4 = (ipv4 as? ConnectionProbes.Exit.Seen)?.address
        val v6 = (ipv6 as? ConnectionProbes.Exit.Seen)?.address
        when (ipv4) {
            is ConnectionProbes.Exit.Seen -> row(R.string.connection_check_ipv4, describe(ipv4.address), Tone.INFO)
            ConnectionProbes.Exit.Unreachable -> row(R.string.connection_check_ipv4, getString(R.string.connection_check_ipv4_none), Tone.INFO)
            ConnectionProbes.Exit.Unchecked -> row(R.string.connection_check_ipv4, getString(R.string.connection_check_unchecked), Tone.INFO)
        }
        val ipv6Detail = v6?.let(::describe)
        // A failed answer says nothing about whether IPv6 gets through.
        if (ipv6 == ConnectionProbes.Exit.Unchecked) {
            row(R.string.connection_check_ipv6, getString(R.string.connection_check_unchecked), Tone.INFO)
        } else {
            when (ConnectionProbes.ipv6(path == Path.DIRECT, v4, v6)) {
                ConnectionProbes.Ipv6.NONE -> row(R.string.connection_check_ipv6, getString(R.string.connection_check_ipv6_none), if (path == Path.DIRECT) Tone.INFO else Tone.OK)
                ConnectionProbes.Ipv6.CONNECTED -> row(R.string.connection_check_ipv6, getString(R.string.connection_check_ipv6_connected), Tone.INFO, ipv6Detail)
                ConnectionProbes.Ipv6.SAME_NETWORK -> row(R.string.connection_check_ipv6, getString(R.string.connection_check_ipv6_same), Tone.OK, ipv6Detail)
                ConnectionProbes.Ipv6.OTHER_NETWORK -> row(R.string.connection_check_ipv6, getString(R.string.connection_check_ipv6_other), Tone.WARNING, ipv6Detail)
                ConnectionProbes.Ipv6.UNCOMPARED -> row(R.string.connection_check_ipv6, getString(R.string.connection_check_ipv6_uncompared), Tone.INFO, ipv6Detail)
            }
        }
        val udpDetail = udp?.let { listOfNotNull(it.address.hostAddress, udpNetwork?.isp?.ifEmpty { null }).joinToString(" · ") }
        when (ConnectionProbes.udp(path != Path.PROXY, listOfNotNull(v4, v6), udp, udpNetwork)) {
            ConnectionProbes.Udp.NOT_CHECKED -> row(R.string.connection_check_udp, getString(R.string.connection_check_udp_proxy), Tone.INFO)
            ConnectionProbes.Udp.NO_ANSWER -> row(R.string.connection_check_udp, getString(R.string.connection_check_udp_none), Tone.INFO)
            ConnectionProbes.Udp.SAME_ADDRESS -> row(R.string.connection_check_udp, getString(R.string.connection_check_udp_same_address), Tone.OK, udpDetail)
            ConnectionProbes.Udp.SAME_NETWORK -> row(R.string.connection_check_udp, getString(R.string.connection_check_udp_same), Tone.INFO, udpDetail)
            ConnectionProbes.Udp.OTHER_NETWORK -> row(R.string.connection_check_udp, getString(R.string.connection_check_udp_other), Tone.WARNING, udpDetail)
            ConnectionProbes.Udp.OTHER_ADDRESS -> row(R.string.connection_check_udp, getString(R.string.connection_check_udp_other_address), Tone.INFO, udpDetail)
        }
        val kinds = lists.values.flatMap { it.kinds }.toSortedSet()
        when {
            lists.isEmpty() -> row(R.string.connection_check_lists, getString(R.string.connection_check_unchecked), Tone.INFO)

            kinds.isNotEmpty() -> row(R.string.connection_check_lists, getString(R.string.connection_check_lists_found, kinds.joinToString(", ") { getString(it.label()) }), Tone.INFO)

            lists.values.any { !it.complete } -> row(R.string.connection_check_lists, getString(R.string.connection_check_lists_incomplete), Tone.INFO)

            else -> row(
                R.string.connection_check_lists,
                getString(R.string.connection_check_lists_none),
                Tone.OK,
                getString(R.string.connection_check_lists_tor_ipv4).takeIf { lists.values.any { AddressReputation.Kind.TOR in it.uncovered } },
            )
        }
        when (dnssec) {
            ConnectionProbes.Dnssec.VALIDATED -> row(R.string.connection_check_dnssec, getString(R.string.connection_check_dnssec_validated), Tone.OK)
            ConnectionProbes.Dnssec.NOT_VALIDATED -> row(R.string.connection_check_dnssec, getString(R.string.connection_check_dnssec_not_validated), Tone.WARNING)
            ConnectionProbes.Dnssec.UNKNOWN -> row(R.string.connection_check_dnssec, getString(R.string.connection_check_unchecked), Tone.INFO)
        }
        if (trace == null) {
            row(R.string.connection_check_tls, getString(R.string.connection_check_tls_unknown), Tone.INFO)
        } else {
            val ech = when (trace.encryptedSni) {
                true -> getString(R.string.connection_check_tls_ech)
                false -> getString(R.string.connection_check_tls_no_ech)
                null -> null
            }
            val status = listOfNotNull(trace.tls?.replace("TLSv", "TLS "), ech).joinToString(" · ").ifEmpty { getString(R.string.connection_check_tls_unknown) }
            row(R.string.connection_check_tls, status, Tone.INFO, getString(R.string.connection_check_tls_detail))
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
                    row(R.string.connection_check_proxycheck_title, getString(R.string.connection_check_proxycheck_none), Tone.OK, detail, LOOKUP)
                } else {
                    row(R.string.connection_check_proxycheck_title, getString(R.string.connection_check_proxycheck_found, flags.joinToString(", ")), Tone.INFO, detail, LOOKUP)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                row(R.string.connection_check_proxycheck_title, getString(R.string.connection_check_proxycheck_failed, e.readableMessage), Tone.WARNING, null, LOOKUP)
            } finally {
                binding.connectionCheckProxycheck.isEnabled = true
            }
        }
    }

    private fun row(title: Int, status: CharSequence, tone: Tone, detail: CharSequence? = null, tag: String? = null) {
        val rows = binding.connectionCheckRows
        tag?.let { rows.findViewWithTag<View>(it)?.let(rows::removeView) }
        ItemConnectionCheckRowBinding.inflate(layoutInflater, rows, true).apply {
            root.tag = tag
            rowTitle.setText(title)
            rowStatus.text = status
            val (icon, color) = when (tone) {
                Tone.OK -> R.drawable.ic_action_done to getColorAttr(androidx.appcompat.R.attr.colorPrimary)
                Tone.WARNING -> R.drawable.ic_baseline_warning_24 to getColorAttr(androidx.appcompat.R.attr.colorError)
                Tone.INFO -> R.drawable.ic_baseline_info_24 to getColorAttr(android.R.attr.textColorSecondary)
            }
            val drawable = ContextCompat.getDrawable(this@ConnectionCheckActivity, icon)?.mutate()?.apply { setTint(color) }
            rowStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
            rowDetail.text = detail
            rowDetail.isVisible = !detail.isNullOrEmpty()
        }
    }

    private fun show(result: DnsLeakTest.Result) {
        when (result.verdict) {
            DnsLeakTest.Verdict.CLEAN -> verdict(getString(R.string.dns_leak_none), R.drawable.ic_action_done, getColorAttr(androidx.appcompat.R.attr.colorPrimary))

            DnsLeakTest.Verdict.LEAK -> verdict(getString(R.string.dns_leak_found), R.drawable.ic_baseline_warning_24, getColorAttr(androidx.appcompat.R.attr.colorError))

            // Text bash.ws did not phrase as a known verdict is shown as it came.
            DnsLeakTest.Verdict.UNKNOWN -> verdict(
                result.conclusion ?: getString(R.string.connection_check_no_verdict),
                R.drawable.ic_baseline_info_24,
                getColorAttr(android.R.attr.textColorPrimary),
            )
        }
        fill(binding.connectionCheckAddresses, result.addresses)
        fill(binding.connectionCheckServers, result.servers)
        binding.connectionCheckResults.isVisible = true
    }

    private fun verdict(text: CharSequence, @DrawableRes icon: Int, @ColorInt color: Int) = with(binding.connectionCheckVerdict) {
        this.text = text
        setTextColor(color)
        val drawable = ContextCompat.getDrawable(context, icon)?.mutate()?.apply { setTint(color) }
        setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
        isVisible = true
    }

    private companion object {
        const val LOOKUP = "proxycheck"
    }

    private fun fill(list: LinearLayout, addresses: List<DnsLeakTest.Address>) {
        list.removeAllViews()
        if (addresses.isEmpty()) {
            ItemConnectionCheckAddressBinding.inflate(layoutInflater, list, true).apply {
                addressIp.setText(R.string.connection_check_none)
                addressDetail.isVisible = false
            }
        }
        for (address in addresses) {
            ItemConnectionCheckAddressBinding.inflate(layoutInflater, list, true).apply {
                addressIp.text = address.ip
                addressDetail.text = listOf(address.place, address.asn).filter { it.isNotEmpty() }.joinToString(" · ")
                addressDetail.isVisible = addressDetail.text.isNotEmpty()
            }
        }
    }
}
