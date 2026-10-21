package xyz.nekobyte.nekobox.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendLogTest {
    @Test
    fun reportsKeepCodeNamesAndLoseServersKeysAndDestinations() {
        val log = listOf(
            "2026/10/21 12:34:56 [Info] outbound/vless[my-server]: connect to www.example.com:443 via 203.0.113.7:8443",
            "dns: exchange cloudflare-dns.com from 2001:db8::1 failed",
            "user 6ba7b810-9dad-11d1-80b4-00c04fd430c8 key YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo= sub https://panel.example.net/sub/abc",
            "contact admin@example.org",
            "at xyz.nekobyte.nekobox.bg.BaseService\$Interface.onStartConnect(BaseService.kt:803)",
            "github.com/sagernet/sing-box/transport/amneziawg.(*Endpoint).Start(0xc000123456)",
            "\t/root/go/pkg/mod/github.com/amnezia-vpn/amneziawg-go/v3@v3.1/device/send.go:345 +0x1a5",
            "neko.log trimmed",
            "update of USA done",
            "OS_VERSION: 6.6.30-android15-8-gdd9c02ccfe27-ab12000000 TYPE: userdebug",
            "hex 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        ).joinToString("\n")
        val report = SendLog.redact(log, listOf("my-server", "Home Wi-Fi Box", "user", "USA"))

        for (secret in listOf("my-server", "www.example.com", "203.0.113.7", "cloudflare-dns.com", "2001:db8::1", "6ba7b810", "YWJjZGVm", "panel.example.net", "admin@example.org")) {
            assertFalse(secret, secret in report)
        }
        assertTrue("<host>:443" in report)
        // Times, code names, Go module paths and file names stay readable.
        assertTrue("12:34:56" in report)
        assertTrue("xyz.nekobyte.nekobox.bg.BaseService\$Interface.onStartConnect(BaseService.kt:803)" in report)
        assertTrue("github.com/sagernet/sing-box/transport/amneziawg" in report)
        assertTrue("send.go:345" in report)
        assertTrue("neko.log trimmed" in report)
        // A stored username does not cut into words, and a kernel version is no key.
        assertTrue("6.6.30-android15-8-gdd9c02ccfe27-ab12000000 TYPE: userdebug" in report)
        assertTrue("hex <key>" in report)
        // Short names are replaced too.
        assertTrue("update of <redacted> done" in report)
        assertEquals(log.lines().size, report.lines().size)
    }
}
