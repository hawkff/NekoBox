package xyz.nekobyte.nekobox.ui

import android.os.Build
import android.os.Bundle
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
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.OkHttpClient
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.databinding.ItemConnectionCheckAddressBinding
import xyz.nekobyte.nekobox.databinding.LayoutConnectionCheckBinding
import xyz.nekobyte.nekobox.ktx.getColorAttr
import xyz.nekobyte.nekobox.ktx.readableMessage
import xyz.nekobyte.nekobox.net.DnsLeakTest
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** The public address and DNS servers this connection presents, from the bash.ws DNS leak test. */
class ConnectionCheckActivity : ThemedActivity() {
    private lateinit var binding: LayoutConnectionCheckBinding
    private var check: Job? = null

    // The path the label and any results on screen belong to.
    private var shown: Path? = null

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
    }

    override fun onResume() {
        super.onResume()
        if (check?.isActive == true) return
        val path = path()
        // Results from another path no longer describe this connection.
        if (path != shown) {
            binding.connectionCheckVerdict.isVisible = false
            binding.connectionCheckResults.isVisible = false
        }
        shown = path
        binding.connectionCheckPath.setText(path.label)
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

    private fun start() {
        val path = path()
        shown = path
        binding.connectionCheckPath.setText(path.label)
        binding.connectionCheckStart.isEnabled = false
        binding.connectionCheckProgress.isVisible = true
        binding.connectionCheckVerdict.isVisible = false
        binding.connectionCheckResults.isVisible = false
        check?.cancel()
        check = lifecycleScope.launch {
            try {
                show(DnsLeakTest.run(client(path)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = if (e is DnsLeakTest.ServiceException) {
                    getString(R.string.connection_check_service_error, e.message)
                } else {
                    getString(R.string.connection_check_failed, e.readableMessage)
                }
                verdict(message, R.drawable.ic_baseline_warning_24, getColorAttr(androidx.appcompat.R.attr.colorError))
            } finally {
                binding.connectionCheckProgress.isVisible = false
                binding.connectionCheckStart.isEnabled = true
            }
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
