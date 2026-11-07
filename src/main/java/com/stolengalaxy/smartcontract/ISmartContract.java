package com.stolengalaxy.smartcontract;

import com.stolengalaxy.User;

public interface ISmartContract {
    void payout(float money, String address);
    void deposit(float money);
    void assignWallet(User user);
}
