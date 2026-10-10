package xyz.nekobyte.nekobox.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.databinding.LayoutNetworkBinding
import xyz.nekobyte.nekobox.ktx.app

class NetworkFragment : NamedFragment(R.layout.layout_network) {

    override fun name0() = app.getString(R.string.tools_network)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutNetworkBinding.bind(view)
        binding.stunTest.setOnClickListener {
            startActivity(Intent(requireContext(), StunActivity::class.java))
        }
    }
}
