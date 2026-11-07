package com.stolengalaxy.smartcontract;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.WalletUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.exceptions.TransactionException;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.ContractGasProvider;
import org.web3j.tx.response.PollingTransactionReceiptProcessor;
import org.web3j.tx.response.TransactionReceiptProcessor;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java bridge to a single pool of the on-chain {@code ProjectDeposit} contract (projectdeposit.sol).
 *
 * <p>All transactions are signed with {@code credentials}. Because {@code settlePool} is {@code onlyOwner},
 * those credentials must belong to the account that deployed the contract (the oracle backend).
 */
public class SmartContract implements ISmartContract {

    /** USDC uses 6 decimals: 1.00 USDC == 1_000_000 base units. */
    private static final int USDC_DECIMALS = 6;

    private final TransactionManager txManager;
    private final TransactionReceiptProcessor receiptProcessor;
    private final ContractGasProvider gasProvider;
    private final String contractAddress;
    private final String usdcAddress;
    private final BigInteger poolId;

    private final Map<Integer, User> wallets = new ConcurrentHashMap<>();

    public SmartContract(Web3j web3j,
                         Credentials credentials,
                         long chainId,
                         ContractGasProvider gasProvider,
                         String contractAddress,
                         String usdcAddress,
                         BigInteger poolId) {
        requireValidAddress(contractAddress, "contract");
        requireValidAddress(usdcAddress, "USDC");
        this.txManager = new RawTransactionManager(web3j, credentials, chainId);
        this.receiptProcessor = new PollingTransactionReceiptProcessor(
                web3j, TransactionManager.DEFAULT_POLLING_FREQUENCY, TransactionManager.DEFAULT_POLLING_ATTEMPTS_PER_TX_HASH);
        this.gasProvider = gasProvider;
        this.contractAddress = contractAddress;
        this.usdcAddress = usdcAddress;
        this.poolId = poolId;
    }

    // ---------------------------------------------------------------- ISmartContract

    /**
     * Settles the pool with the whole of {@code money} going to {@code address}.
     * The contract only accepts a settlement whose allocations equal the pool's total deposits,
     * and a pool can only be settled once; use {@link #settle(Map)} to split between several recipients.
     * The recipient must then call {@code withdraw(poolId)} themselves to receive the USDC.
     */
    @Override
    public void payout(float money, String address) {
        settle(Map.of(address, new BigDecimal(Float.toString(money))));
    }

    /** Approves the contract to pull {@code money} USDC from the signing wallet, then deposits it into the pool. */
    @Override
    public void deposit(float money) {
        BigInteger amount = toBaseUnits(new BigDecimal(Float.toString(money)));
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Deposit must be positive");
        }
        send(usdcAddress, new Function("approve",
                List.of(new Address(contractAddress), new Uint256(amount)),
                Collections.emptyList()));
        send(contractAddress, new Function("deposit",
                List.of(new Uint256(poolId), new Uint256(amount)),
                Collections.emptyList()));
    }

    /** Registers the user's wallet so payouts can be addressed by user. */
    @Override
    public void assignWallet(User user) {
        requireValidAddress(user.walletAddress(), "wallet");
        wallets.put(user.userID(), user);
    }

    // ---------------------------------------------------------------- extras

    /** Settles the pool across several recipients (address -> USDC amount). Amounts must sum to the pool total. */
    public TransactionReceipt settle(Map<String, BigDecimal> allocations) {
        if (allocations.isEmpty()) {
            throw new IllegalArgumentException("At least one recipient is required");
        }
        List<Address> recipients = allocations.keySet().stream().peek(a -> requireValidAddress(a, "recipient"))
                .map(Address::new).toList();
        List<Uint256> amounts = allocations.values().stream().map(v -> new Uint256(toBaseUnits(v))).toList();

        BigInteger total = amounts.stream().map(Uint256::getValue).reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger deposited = totalDeposited();
        if (!total.equals(deposited)) {
            throw new IllegalArgumentException("Allocations (" + total + ") must equal pool deposits (" + deposited + ")");
        }

        return send(contractAddress, new Function("settlePool",
                List.of(new Uint256(poolId),
                        new DynamicArray<>(Address.class, recipients),
                        new DynamicArray<>(Uint256.class, amounts)),
                Collections.emptyList()));
    }

    /** Pays out to a registered user by ID. */
    public void payout(float money, int userID) {
        User user = wallets.get(userID);
        if (user == null) {
            throw new IllegalArgumentException("No wallet assigned for user " + userID);
        }
        payout(money, user.walletAddress());
    }

    public User getUser(int userID) {
        return wallets.get(userID);
    }

    public BigInteger totalDeposited() {
        return callUint("totalDeposited", List.of(new Uint256(poolId)));
    }

    public boolean isSettled() {
        Function fn = new Function("isSettled", List.of(new Uint256(poolId)),
                List.of(new TypeReference<org.web3j.abi.datatypes.Bool>() {}));
        return (Boolean) call(fn).get(0).getValue();
    }

    public BigInteger claimable(String address) {
        requireValidAddress(address, "recipient");
        return callUint("claimable", List.of(new Uint256(poolId), new Address(address)));
    }

    // ---------------------------------------------------------------- plumbing

    private TransactionReceipt send(String to, Function function) {
        String data = FunctionEncoder.encode(function);
        try {
            EthSendTransaction response = txManager.sendTransaction(
                    gasProvider.getGasPrice(), gasProvider.getGasLimit(), to, data, BigInteger.ZERO);
            if (response.hasError()) {
                throw new SmartContractException(function.getName() + " rejected: " + response.getError().getMessage());
            }
            TransactionReceipt receipt = receiptProcessor.waitForTransactionReceipt(response.getTransactionHash());
            if (!receipt.isStatusOK()) {
                throw new SmartContractException(function.getName() + " reverted: "
                        + (receipt.getRevertReason() != null ? receipt.getRevertReason() : receipt.getTransactionHash()));
            }
            return receipt;
        } catch (IOException | TransactionException e) {
            throw new SmartContractException(function.getName() + " failed", e);
        }
    }

    @SuppressWarnings("rawtypes")
    private List<Type> call(Function function) {
        try {
            String result = txManager.sendCall(contractAddress, FunctionEncoder.encode(function),
                    DefaultBlockParameterName.LATEST);
            return FunctionReturnDecoder.decode(result, function.getOutputParameters());
        } catch (IOException e) {
            throw new SmartContractException(function.getName() + " call failed", e);
        }
    }

    @SuppressWarnings("rawtypes")
    private BigInteger callUint(String name, List<Type> inputs) {
        Function fn = new Function(name, inputs, List.of(new TypeReference<Uint256>() {}));
        return (BigInteger) call(fn).get(0).getValue();
    }

    /** Converts a USDC amount to base units. Goes through the float's decimal string so 0.1f becomes exactly 100000. */
    private static BigInteger toBaseUnits(BigDecimal usdc) {
        return usdc.setScale(USDC_DECIMALS, RoundingMode.HALF_UP).unscaledValue();
    }

    private static void requireValidAddress(String address, String what) {
        if (address == null || !WalletUtils.isValidAddress(address)) {
            throw new IllegalArgumentException("Invalid " + what + " address: " + address);
        }
    }

    public static class SmartContractException extends RuntimeException {
        public SmartContractException(String message) {
            super(message);
        }

        public SmartContractException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
