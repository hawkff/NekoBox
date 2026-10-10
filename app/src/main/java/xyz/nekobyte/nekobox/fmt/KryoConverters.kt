package xyz.nekobyte.nekobox.fmt

import androidx.room.TypeConverter
import xyz.nekobyte.nekobox.database.SubscriptionBean
import xyz.nekobyte.nekobox.fmt.amneziawg.AmneziaWGBean
import xyz.nekobyte.nekobox.fmt.http.HttpBean
import xyz.nekobyte.nekobox.fmt.hysteria.HysteriaBean
import xyz.nekobyte.nekobox.fmt.internal.ChainBean
import xyz.nekobyte.nekobox.fmt.juicity.JuicityBean
import xyz.nekobyte.nekobox.fmt.mieru.MieruBean
import xyz.nekobyte.nekobox.fmt.naive.NaiveBean
import xyz.nekobyte.nekobox.fmt.shadowsocks.ShadowsocksBean
import xyz.nekobyte.nekobox.fmt.shadowsocksr.ShadowsocksRBean
import xyz.nekobyte.nekobox.fmt.snell.SnellBean
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.fmt.ssh.SSHBean
import xyz.nekobyte.nekobox.fmt.tailscale.TailscaleBean
import xyz.nekobyte.nekobox.fmt.trojan.TrojanBean
import xyz.nekobyte.nekobox.fmt.tuic.TuicBean
import xyz.nekobyte.nekobox.fmt.v2ray.VMessBean
import xyz.nekobyte.nekobox.fmt.wireguard.WireGuardBean
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.byteBuffer
import xyz.nekobyte.nekobox.proxy.anytls.AnyTLSBean
import xyz.nekobyte.nekobox.proxy.config.ConfigBean
import xyz.nekobyte.nekobox.proxy.shadowtls.ShadowTLSBean
import java.io.ByteArrayOutputStream

object KryoConverters {
    private fun ByteArray?.isNullOrEmpty() = this == null || isEmpty()

    @TypeConverter
    @JvmStatic
    fun serialize(bean: Serializable?): ByteArray {
        if (bean == null) return byteArrayOf()
        val output = ByteArrayOutputStream()
        output.byteBuffer().use { buffer ->
            bean.serializeToBuffer(buffer)
            buffer.flush()
        }
        return output.toByteArray()
    }

    private fun <T : Serializable> freshDefault(bean: T): T = try {
        bean.javaClass.getDeclaredConstructor().newInstance()
    } catch (e: ReflectiveOperationException) {
        Logs.w(e)
        bean
    }

    @JvmStatic
    fun <T : Serializable> deserialize(bean: T, bytes: ByteArray?): T {
        if (bytes == null) return bean
        var decoded = bean
        bytes.inputStream().byteBuffer().use { buffer ->
            try {
                decoded.deserializeFromBuffer(buffer)
            } catch (e: Exception) {
                // Discard partially decoded fields before applying defaults.
                Logs.w(e)
                decoded = freshDefault(bean)
            }
        }
        decoded.initializeDefaultValues()
        return decoded
    }

    @TypeConverter
    @JvmStatic
    fun socksDeserialize(bytes: ByteArray?): SOCKSBean? = if (bytes.isNullOrEmpty()) null else deserialize(SOCKSBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun httpDeserialize(bytes: ByteArray?): HttpBean? = if (bytes.isNullOrEmpty()) null else deserialize(HttpBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun shadowsocksDeserialize(bytes: ByteArray?): ShadowsocksBean? = if (bytes.isNullOrEmpty()) null else deserialize(ShadowsocksBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun shadowsocksrDeserialize(bytes: ByteArray?): ShadowsocksRBean? = if (bytes.isNullOrEmpty()) null else deserialize(ShadowsocksRBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun configDeserialize(bytes: ByteArray?): ConfigBean? = if (bytes.isNullOrEmpty()) null else deserialize(ConfigBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun vmessDeserialize(bytes: ByteArray?): VMessBean? = if (bytes.isNullOrEmpty()) null else deserialize(VMessBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun trojanDeserialize(bytes: ByteArray?): TrojanBean? = if (bytes.isNullOrEmpty()) null else deserialize(TrojanBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun mieruDeserialize(bytes: ByteArray?): MieruBean? = if (bytes.isNullOrEmpty()) null else deserialize(MieruBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun naiveDeserialize(bytes: ByteArray?): NaiveBean? = if (bytes.isNullOrEmpty()) null else deserialize(NaiveBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun hysteriaDeserialize(bytes: ByteArray?): HysteriaBean? = if (bytes.isNullOrEmpty()) null else deserialize(HysteriaBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun sshDeserialize(bytes: ByteArray?): SSHBean? = if (bytes.isNullOrEmpty()) null else deserialize(SSHBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun wireguardDeserialize(bytes: ByteArray?): WireGuardBean? = if (bytes.isNullOrEmpty()) null else deserialize(WireGuardBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun amneziaWGDeserialize(bytes: ByteArray?): AmneziaWGBean? = if (bytes.isNullOrEmpty()) null else deserialize(AmneziaWGBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun tailscaleDeserialize(bytes: ByteArray?): TailscaleBean? = if (bytes.isNullOrEmpty()) null else deserialize(TailscaleBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun tuicDeserialize(bytes: ByteArray?): TuicBean? = if (bytes.isNullOrEmpty()) null else deserialize(TuicBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun juicityDeserialize(bytes: ByteArray?): JuicityBean? = if (bytes.isNullOrEmpty()) null else deserialize(JuicityBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun shadowTLSDeserialize(bytes: ByteArray?): ShadowTLSBean? = if (bytes.isNullOrEmpty()) null else deserialize(ShadowTLSBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun anyTLSDeserialize(bytes: ByteArray?): AnyTLSBean? = if (bytes.isNullOrEmpty()) null else deserialize(AnyTLSBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun snellDeserialize(bytes: ByteArray?): SnellBean? = if (bytes.isNullOrEmpty()) null else deserialize(SnellBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun chainDeserialize(bytes: ByteArray?): ChainBean? = if (bytes.isNullOrEmpty()) null else deserialize(ChainBean(), bytes)

    @TypeConverter
    @JvmStatic
    fun subscriptionDeserialize(bytes: ByteArray?): SubscriptionBean? = if (bytes.isNullOrEmpty()) null else deserialize(SubscriptionBean(), bytes)
}
