package com.stolengalaxy.smartcontract;

import java.math.BigDecimal;
import java.util.Map;

/** One project's pool, used as one wallet. Amounts are in USDC, e.g. 12.5f. */
public interface ISmartContract {
    /** Owner only: opens the pool for deposits until the deadline, {@code durationSeconds} from now. */
    void createPool(long durationSeconds);

    /** Before the deadline: stakes {@code money} from this wallet into the pool. */
    void deposit(float money);

    /** Before the deadline: stakes {@code money} (at most 6 decimal places) from this wallet into the pool, exactly. */
    void deposit(BigDecimal money);

    /** Owner only, once per pool, after the deadline: wallet address -> amount. The amounts must add up to exactly what was deposited. */
    void payout(Map<String, Float> payouts);

    /** After payout, moves this wallet's share out of the pool and back into the wallet. */
    void withdraw();
}
