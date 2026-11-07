package com.stolengalaxy.smartcontract;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.exceptions.TransactionException;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.exceptions.ContractCallException;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.tx.response.PollingTransactionReceiptProcessor;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Talks to one pool of the ProjectDeposit contract (projectdeposit.sol), signing every transaction as one wallet. */
public class SmartContract implements ISmartContract {

    private static final DefaultGasProvider GAS = new DefaultGasProvider();

    private final Web3j web3j;
    private final TransactionManager tx;
    private final String poolContract;
    private final String usdcContract;
    private final BigInteger poolId;

    public SmartContract(Web3j web3j, Credentials signer, long chainId,
                         String poolContract, String usdcContract, BigInteger poolId) {
        this.web3j = web3j;
        this.tx = new RawTransactionManager(web3j, signer, chainId);
        this.poolContract = poolContract;
        this.usdcContract = usdcContract;
        this.poolId = poolId;
    }

    @Override
    public void createPool(long durationSeconds) {
        call(poolContract, "createPool", new Uint256(poolId), new Uint256(durationSeconds));
    }

    @Override
    public void deposit(float money) {
        Uint256 amount = new Uint256(toUnits(money));
        call(usdcContract, "approve", new Address(poolContract), amount); // let the pool take the USDC
        call(poolContract, "deposit", new Uint256(poolId), amount);
    }

    @Override
    public void payout(Map<String, Float> payouts) {
        List<Address> recipients = new ArrayList<>();
        List<Uint256> amounts = new ArrayList<>();
        payouts.forEach((wallet, money) -> {
            recipients.add(new Address(wallet));
            amounts.add(new Uint256(toUnits(money)));
        });
        call(poolContract, "settlePool", new Uint256(poolId),
                new DynamicArray<>(Address.class, recipients), new DynamicArray<>(Uint256.class, amounts));
    }

    @Override
    public void withdraw() {
        call(poolContract, "withdraw", new Uint256(poolId));
    }

    @SuppressWarnings("rawtypes")
    private void call(String contract, String function, Type... args) {
        send(web3j, tx, contract, FunctionEncoder.encode(new Function(function, List.of(args), List.of())),
                BigInteger.ZERO, function);
    }

    /** Sends a transaction and waits for it to be mined. Throws with the contract's error message if it fails. */
    static void send(Web3j web3j, TransactionManager tx, String to, String data, BigInteger value, String what) {
        try {
            EthSendTransaction sent = tx.sendTransaction(GAS.getGasPrice(), GAS.getGasLimit(), to, data, value);
            if (sent.hasError()) {
                throw new IllegalStateException(what + " failed: " + sent.getError().getMessage());
            }
            TransactionReceipt receipt = new PollingTransactionReceiptProcessor(web3j, 200, 3_000)
                    .waitForTransactionReceipt(sent.getTransactionHash());
            if (!receipt.isStatusOK()) {
                // anvil doesn't put the error in the receipt, so replay the call on the previous block to get it.
                try {
                    tx.sendCall(to, data, DefaultBlockParameter.valueOf(receipt.getBlockNumber().subtract(BigInteger.ONE)));
                } catch (ContractCallException e) {
                    throw new IllegalStateException(what + " failed: " + e.getMessage());
                }
                throw new IllegalStateException(what + " failed");
            }
        } catch (IOException | TransactionException e) {
            throw new IllegalStateException(what + " failed", e);
        }
    }

    /** USDC has 6 decimals, so 12.5 USDC is 12,500,000 units. Goes via the decimal string so 0.1f is exactly 100,000. */
    static BigInteger toUnits(float usdc) {
        return new BigDecimal(Float.toString(usdc)).movePointRight(6).toBigInteger();
    }
}
