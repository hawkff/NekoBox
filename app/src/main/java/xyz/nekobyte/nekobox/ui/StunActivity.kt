package xyz.nekobyte.nekobox.ui

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import libcore.StunResult
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.databinding.ItemCheckResultBinding
import xyz.nekobyte.nekobox.databinding.LayoutStunBinding
import xyz.nekobyte.nekobox.ktx.getColorAttr
import xyz.nekobyte.nekobox.ktx.readableMessage
import xyz.nekobyte.nekobox.net.NatBehavior

/** How the NAT on this connection maps and filters UDP, tested against a STUN server (RFC 5780). */
class StunActivity : ThemedActivity() {

    private lateinit var binding: LayoutStunBinding
    private var test: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutStunBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.stun_test)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }
        // The page runs edge to edge; its end stays clear of the navigation bar.
        val bottom = binding.natScroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.natScroll) { view, insets ->
            view.updatePadding(bottom = bottom + insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }
        binding.natStunServer.setSimpleItems(SERVERS)
        if (savedInstanceState == null) binding.natStunServer.setText(SERVERS.first(), false)
        binding.natStunServer.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_GO).also { if (it) start() }
        }
        binding.natStart.setOnClickListener { start() }
        binding.natWebrtc.icon(R.drawable.ic_baseline_warning_24, getColorAttr(androidx.appcompat.R.attr.colorError))
    }

    override fun onResume() {
        super.onResume()
        showPath()
    }

    // This app stays inside the VPN, so its UDP takes the path other apps take; the local proxy carries none of it.
    private fun showPath() {
        val vpn = DataStore.serviceState.started && DataStore.serviceMode != Key.MODE_PROXY
        val label = when {
            vpn -> R.string.connection_check_path_vpn
            DataStore.serviceState.started -> R.string.nat_path_proxy
            else -> R.string.connection_check_path_direct
        }
        binding.natPath.setText(label)
        binding.natPath.icon(if (vpn) R.drawable.ic_baseline_vpn_key_24 else R.drawable.baseline_public_24, getColorAttr(android.R.attr.textColorSecondary))
        binding.natWebrtc.isVisible = vpn && DataStore.webrtcLeakProtection
    }

    private fun TextView.icon(@DrawableRes icon: Int, @ColorInt color: Int) {
        val drawable = ContextCompat.getDrawable(context, icon)?.mutate()?.apply { setTint(color) }
        setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
    }

    private fun start() {
        // The core's test cannot be stopped, so a second one would only race the first.
        if (test?.isActive == true) return
        val server = binding.natStunServer.text.toString().trim()
        if (server.isEmpty()) {
            binding.natServerLayout.error = getString(R.string.nat_server_missing)
            return
        }
        binding.natServerLayout.error = null
        binding.natStart.isEnabled = false
        binding.natProgress.isVisible = true
        binding.natVerdict.isVisible = false
        binding.natVerdictDetail.isVisible = false
        binding.natRows.isVisible = false
        test = lifecycleScope.launch {
            try {
                show(withContext(Dispatchers.IO) { Libcore.stunTest(server) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                verdict(R.string.nat_failed, CheckTone.WARNING, e.readableMessage)
            } finally {
                binding.natProgress.isVisible = false
                binding.natStart.isEnabled = true
            }
        }
    }

    private fun show(result: StunResult) {
        val type = NatBehavior.type(result.noNat, result.mapping, result.filtering)
        // A symmetric NAT limits peer-to-peer connections without being a fault, so it reads as neutral.
        when {
            result.problem == NatBehavior.UNREACHABLE -> verdict(R.string.nat_unreachable, CheckTone.WARNING, getString(R.string.nat_unreachable_detail))
            result.problem == NatBehavior.UNSUPPORTED -> verdict(R.string.nat_unsupported, CheckTone.INFO, getString(R.string.nat_unsupported_detail))
            type == NatBehavior.Type.NONE -> verdict(R.string.nat_type_none, CheckTone.INFO, getString(R.string.nat_detail_none))
            type == NatBehavior.Type.FULL_CONE -> verdict(R.string.nat_type_full_cone, CheckTone.OK, getString(R.string.nat_detail_cone))
            type == NatBehavior.Type.RESTRICTED_CONE -> verdict(R.string.nat_type_restricted_cone, CheckTone.OK, getString(R.string.nat_detail_cone))
            type == NatBehavior.Type.PORT_RESTRICTED_CONE -> verdict(R.string.nat_type_port_restricted_cone, CheckTone.OK, getString(R.string.nat_detail_cone))
            type == NatBehavior.Type.CONE -> verdict(R.string.nat_type_cone, CheckTone.OK, getString(R.string.nat_detail_cone))
            type == NatBehavior.Type.SYMMETRIC -> verdict(R.string.nat_type_symmetric, CheckTone.INFO, getString(R.string.nat_detail_symmetric))
            else -> verdict(R.string.nat_failed, CheckTone.WARNING, result.error)
        }
        val rows = binding.natRows
        rows.removeAllViews()
        if (result.mapping.isNotEmpty()) {
            val independent = result.mapping == NatBehavior.ENDPOINT_INDEPENDENT
            row(R.string.nat_mapping, behavior(result.mapping), if (independent) CheckTone.OK else CheckTone.INFO, mappingDetail(result.mapping))
            // A filtering test that stopped early says why.
            val filtering = result.filtering
            row(R.string.nat_filtering, behavior(filtering), CheckTone.INFO, if (filtering.isEmpty()) result.error else filteringDetail(filtering))
        }
        if (result.publicAddress.isNotEmpty()) row(R.string.connection_check_address, result.publicAddress, CheckTone.INFO)
        rows.isVisible = rows.isNotEmpty()
    }

    private fun verdict(@StringRes title: Int, tone: CheckTone, detail: String?) {
        binding.natVerdict.showVerdict(getString(title), tone)
        binding.natVerdictDetail.text = detail
        binding.natVerdictDetail.isVisible = !detail.isNullOrEmpty()
    }

    private fun row(@StringRes title: Int, status: CharSequence, tone: CheckTone, detail: CharSequence? = null) {
        ItemCheckResultBinding.inflate(layoutInflater, binding.natRows, true).showResult(title, status, tone, detail)
    }

    private fun behavior(name: String) = getString(
        when (name) {
            NatBehavior.ENDPOINT_INDEPENDENT -> R.string.nat_behavior_endpoint
            NatBehavior.ADDRESS_DEPENDENT -> R.string.nat_behavior_address
            NatBehavior.ADDRESS_AND_PORT_DEPENDENT -> R.string.nat_behavior_address_port
            else -> R.string.nat_behavior_unknown
        },
    )

    private fun mappingDetail(name: String) = getString(
        when (name) {
            NatBehavior.ENDPOINT_INDEPENDENT -> R.string.nat_mapping_endpoint
            NatBehavior.ADDRESS_DEPENDENT -> R.string.nat_mapping_address
            else -> R.string.nat_mapping_address_port
        },
    )

    private fun filteringDetail(name: String) = getString(
        when (name) {
            NatBehavior.ENDPOINT_INDEPENDENT -> R.string.nat_filtering_endpoint
            NatBehavior.ADDRESS_DEPENDENT -> R.string.nat_filtering_address
            else -> R.string.nat_filtering_address_port
        },
    )

    private companion object {
        // STUN servers that answer from a second address, which the behavior test needs (RFC 5780).
        val SERVERS = arrayOf("stun.voipgate.com:3478", "stun.nextcloud.com:3478", "stun.sonetel.com:3478")
    }
}
