package triad

import triad.net.sameIpv4Subnet
import kotlin.test.*

class NetUtilTest {
    @Test fun `subnet heuristic matches the first three octets and rejects the rest`() {
        assertTrue(sameIpv4Subnet("192.168.1.5", "192.168.1.99"))
        assertTrue(sameIpv4Subnet("10.0.0.1", "10.0.0.254"))
        assertFalse(sameIpv4Subnet("192.168.1.5", "192.168.2.5"))
        assertFalse(sameIpv4Subnet("192.168.1.5", "10.168.1.5"))
    }

    @Test fun `missing or malformed addresses never claim the same network`() {
        assertFalse(sameIpv4Subnet(null, "192.168.1.1"))
        assertFalse(sameIpv4Subnet("192.168.1.1", null))
        assertFalse(sameIpv4Subnet(null, null))
        assertFalse(sameIpv4Subnet("nope", "192.168.1.1"))
        assertFalse(sameIpv4Subnet("192.168.1", "192.168.1.1"))
        assertFalse(sameIpv4Subnet("999.168.1.1", "192.168.1.1"))
    }
}
