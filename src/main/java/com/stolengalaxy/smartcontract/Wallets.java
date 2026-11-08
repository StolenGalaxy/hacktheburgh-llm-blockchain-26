package com.stolengalaxy.smartcontract;

import com.stolengalaxy.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

public final class Wallets {

    private static final Logger LOG = LoggerFactory.getLogger(Wallets.class);
    public static final BigDecimal MAX_TOP_UP = new BigDecimal("1000");

    private final Bank bank; // null when the chain couldn't start

    Wallets(Bank bank) {
        this.bank = bank;
    }

    public static final class WalletException extends RuntimeException {
        WalletException(String message) {
            super(message);
        }
    }

    public record Wallet(String address, BigDecimal balance) {
    }

    public boolean available() {
        return bank != null && bank.isRunning();
    }

    public Wallet open(int userId) {
        requireChain();
        try {
            String address = bank.openWallet(userId);
            return new Wallet(address, bank.balanceOf(address));
        } catch (Exception e) {
            LOG.warn("Couldn't open the wallet for user {}", userId, e);
            throw new WalletException("Couldn't reach your wallet on the blockchain.");
        }
    }

    public Wallet addFunds(int userId, BigDecimal amount) {
        if (amount.signum() <= 0 || amount.compareTo(MAX_TOP_UP) > 0 || amount.stripTrailingZeros().scale() > 6) {
            throw new WalletException("Add between $0.01 and $" + MAX_TOP_UP.toPlainString() + " at a time, with at most 6 decimal places.");
        }
        requireChain();
        try {
            String address = bank.openWallet(userId);
            bank.mintUSDC(new User(userId, "user " + userId, address, null), amount);
            LOG.info("Added {} test USDC to user {}'s wallet", amount.toPlainString(), userId);
            return new Wallet(address, bank.balanceOf(address));
        } catch (Exception e) {
            LOG.warn("Couldn't add funds for user {}", userId, e);
            throw new WalletException("Adding funds failed: couldn't reach your wallet on the blockchain.");
        }
    }

    public static String dollars(BigDecimal amount) {
        BigDecimal plain = amount.stripTrailingZeros();
        return "$" + (plain.scale() < 2 ? plain.setScale(2) : plain).toPlainString();
    }

    private void requireChain() {
        if (!available()) {
            throw new WalletException("The blockchain isn't running, so wallets are unavailable.");
        }
    }
}
