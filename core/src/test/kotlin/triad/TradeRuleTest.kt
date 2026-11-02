package triad

import triad.core.TradeRule
import triad.core.maxLoss
import kotlin.test.*

class TradeRuleTest {
    @Test fun `max loss per rule`() {
        assertEquals(0, TradeRule.none.maxLoss())
        assertEquals(1, TradeRule.one.maxLoss())
        assertEquals(5, TradeRule.direct.maxLoss())
        assertEquals(5, TradeRule.diff.maxLoss())
        assertEquals(5, TradeRule.all.maxLoss())
        assertEquals(3, TradeRule.all.maxLoss(brought = 3))
    }
}
