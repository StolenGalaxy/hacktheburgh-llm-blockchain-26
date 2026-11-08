package com.stolengalaxy.smartcontract;

import java.math.BigDecimal;
import java.util.Map;

public interface ISmartContract {
    void createPool(long durationSeconds);

    void deposit(float money);

    void deposit(BigDecimal money);

    void payout(Map<String, Float> payouts);

    void payoutExact(Map<String, BigDecimal> payouts);

    void withdraw();
}
