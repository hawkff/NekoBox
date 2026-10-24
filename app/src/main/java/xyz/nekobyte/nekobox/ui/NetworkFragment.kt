package xyz.nekobyte.nekobox.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.databinding.ItemToolBinding
import xyz.nekobyte.nekobox.databinding.LayoutNetworkBinding
import xyz.nekobyte.nekobox.ktx.app

class NetworkFragment : NamedFragment(R.layout.layout_network) {

    private class Tool(@DrawableRes val icon: Int, @StringRes val title: Int, @StringRes val summary: Int, val activity: Class<out Activity>)

    override fun name0() = app.getString(R.string.tools_network)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val list = LayoutNetworkBinding.bind(view).networkTools
        for (tool in TOOLS) {
            ItemToolBinding.inflate(layoutInflater, list, true).apply {
                toolIcon.setImageResource(tool.icon)
                toolTitle.setText(tool.title)
                toolSummary.setText(tool.summary)
                root.setOnClickListener { startActivity(Intent(requireContext(), tool.activity)) }
            }
        }
    }

    private companion object {
        val TOOLS = listOf(
            Tool(R.drawable.ic_baseline_security_24, R.string.connection_check, R.string.connection_check_summary, ConnectionCheckActivity::class.java),
            Tool(R.drawable.ic_baseline_nat_24, R.string.stun_test, R.string.stun_test_summary, StunActivity::class.java),
        )
    }
}
