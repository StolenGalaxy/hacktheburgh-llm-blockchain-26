package com.stolengalaxy.smartcontract;

import com.stolengalaxy.User;
import com.stolengalaxy.smartcontract.SmartContract.SmartContractException;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.crypto.WalletUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.exceptions.TransactionException;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.ContractGasProvider;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.tx.response.PollingTransactionReceiptProcessor;
import org.web3j.tx.response.TransactionReceiptProcessor;
import org.web3j.utils.Convert;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Custodial bank on a local anvil chain: creates a wallet for each user on login, mints mock USDC into it,
 * and hands out {@link SmartContract} clients that sign as that user.
 *
 * <p>The bank keeps each wallet's private key in memory; only the address is put into the {@link User} record,
 * so a User can be serialised (e.g. sent to the frontend) without leaking a key. Keys, like the anvil chain itself,
 * are lost when the bank is closed.
 */
public class Bank implements AutoCloseable {

    public static final long ANVIL_CHAIN_ID = 31337;
    /** anvil dev account #0, funded with 10,000 ETH. It deploys, and so owns, both contracts. */
    private static final String ANVIL_DEPLOYER_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    /** ETH given to each new wallet so it can pay gas for approve/deposit/withdraw. */
    private static final BigInteger GAS_ALLOWANCE = Convert.toWei("10", Convert.Unit.ETHER).toBigIntegerExact();
    private static final int USDC_DECIMALS = 6;

    private final Web3j web3j;
    private final Credentials operator;
    private final String usdcAddress;
    private final String poolAddress;
    private final ContractGasProvider gas = new DefaultGasProvider();
    private final TransactionManager operatorTx;
    private final TransactionReceiptProcessor receipts;
    private final Map<Integer, Credentials> wallets = new ConcurrentHashMap<>();

    private final Process anvil;
    private final Path buildDir;

    private Bank(Web3j web3j, String usdcAddress, String poolAddress, Process anvil, Path buildDir) {
        this.web3j = web3j;
        this.operator = Credentials.create(ANVIL_DEPLOYER_KEY);
        this.usdcAddress = usdcAddress;
        this.poolAddress = poolAddress;
        this.operatorTx = new RawTransactionManager(web3j, operator, ANVIL_CHAIN_ID);
        this.receipts = new PollingTransactionReceiptProcessor(web3j, 200, 3_000);
        this.anvil = anvil;
        this.buildDir = buildDir;
    }

    /** Starts a fresh anvil chain and deploys MockUSDC + ProjectDeposit. Run from the project root. */
    public static Bank startLocal() throws IOException {
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        String rpcUrl = "http://127.0.0.1:" + port;
        Process anvil = new ProcessBuilder(findFoundry("ANVIL_BIN", "anvil"), "--port", String.valueOf(port), "--silent")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        Web3j web3j = Web3j.build(new HttpService(rpcUrl));
        Path buildDir = Files.createTempDirectory("bank-forge");
        try {
            waitForChain(web3j, anvil, rpcUrl);
            String contracts = "src/main/java/com/stolengalaxy/smartcontract/";
            String usdc = forgeCreate(projectRoot, buildDir, rpcUrl, contracts + "MockUSDC.sol:MockUSDC");
            String pool = forgeCreate(projectRoot, buildDir, rpcUrl, contracts + "projectdeposit.sol:ProjectDeposit",
                    "--constructor-args", usdc);
            return new Bank(web3j, usdc, pool, anvil, buildDir);
        } catch (IOException | RuntimeException e) {
            web3j.shutdown();
            anvil.destroyForcibly();
            deleteRecursively(buildDir);
            throw e;
        }
    }

    // ---------------------------------------------------------------- wallets

    /**
     * Call when a user logs in. A user without a wallet gets a newly generated one, funded with ETH for gas,
     * and the returned User carries its address (store that record in place of the old one).
     * A user who already has a wallet address is returned unchanged.
     */
    public synchronized User login(User user) {
        String existing = user.walletAddress();
        if (existing != null && !existing.isBlank()) {
            requireValidAddress(existing, "wallet");
            return user;
        }
        Credentials wallet;
        try {
            wallet = Credentials.create(Keys.createEcKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not generate a wallet key", e);
        }
        sendFromOperator(wallet.getAddress(), "", GAS_ALLOWANCE, "fund gas");
        wallets.put(user.userID(), wallet);
        return new User(user.userID(), user.username(), wallet.getAddress(), user.githubUsername());
    }

    /** True if the bank holds the private key for this user's wallet (i.e. it can sign deposits for them). */
    public boolean holdsKeyFor(User user) {
        Credentials c = wallets.get(user.userID());
        return c != null && c.getAddress().equalsIgnoreCase(user.walletAddress());
    }

    /** A pool client that signs as {@code user}, e.g. {@code bank.contractFor(user, pool).deposit(10f)}. */
    public SmartContract contractFor(User user, BigInteger poolId) {
        if (!holdsKeyFor(user)) {
            throw new IllegalStateException("Bank holds no key for user " + user.userID() + "; call login first");
        }
        return new SmartContract(web3j, wallets.get(user.userID()), ANVIL_CHAIN_ID, gas, poolAddress, usdcAddress, poolId);
    }

    /** A pool client that signs as the contract owner, needed for payout/settle. */
    public SmartContract ownerContract(BigInteger poolId) {
        return new SmartContract(web3j, operator, ANVIL_CHAIN_ID, gas, poolAddress, usdcAddress, poolId);
    }

    // ---------------------------------------------------------------- money

    /** Mints {@code amount} mock USDC (e.g. 12.5) into the user's wallet. */
    public void mintUSDC(User user, BigDecimal amount) {
        requireValidAddress(user.walletAddress(), "wallet (call login first)");
        BigInteger units = toBaseUnits(amount);
        if (units.signum() <= 0) {
            throw new IllegalArgumentException("Mint amount must be positive");
        }
        Function fn = new Function("mint", List.of(new Address(user.walletAddress()), new Uint256(units)), List.of());
        sendFromOperator(usdcAddress, FunctionEncoder.encode(fn), BigInteger.ZERO, "mint");
    }

    /** The user's USDC balance, in whole USDC. */
    public BigDecimal balanceOf(User user) {
        requireValidAddress(user.walletAddress(), "wallet");
        Function fn = new Function("balanceOf", List.of(new Address(user.walletAddress())),
                List.of(new TypeReference<Uint256>() {}));
        try {
            String raw = operatorTx.sendCall(usdcAddress, FunctionEncoder.encode(fn), DefaultBlockParameterName.LATEST);
            BigInteger units = (BigInteger) FunctionReturnDecoder.decode(raw, fn.getOutputParameters()).get(0).getValue();
            return new BigDecimal(units, USDC_DECIMALS);
        } catch (IOException e) {
            throw new SmartContractException("balanceOf call failed", e);
        }
    }

    @Override
    public void close() {
        web3j.shutdown();
        anvil.destroyForcibly();
        deleteRecursively(buildDir);
    }

    // ---------------------------------------------------------------- plumbing

    /** Synchronised so concurrent calls don't pick the same nonce for the operator account. */
    private synchronized TransactionReceipt sendFromOperator(String to, String data, BigInteger value, String what) {
        try {
            EthSendTransaction tx = operatorTx.sendTransaction(gas.getGasPrice(), gas.getGasLimit(), to, data, value);
            if (tx.hasError()) {
                throw new SmartContractException(what + " rejected: " + tx.getError().getMessage());
            }
            TransactionReceipt receipt = receipts.waitForTransactionReceipt(tx.getTransactionHash());
            if (!receipt.isStatusOK()) {
                throw new SmartContractException(what + " reverted: tx " + receipt.getTransactionHash());
            }
            return receipt;
        } catch (IOException | TransactionException e) {
            throw new SmartContractException(what + " failed", e);
        }
    }

    private static BigInteger toBaseUnits(BigDecimal usdc) {
        return usdc.setScale(USDC_DECIMALS, RoundingMode.HALF_UP).unscaledValue();
    }

    private static void requireValidAddress(String address, String what) {
        if (address == null || !WalletUtils.isValidAddress(address)) {
            throw new IllegalArgumentException("Invalid " + what + " address: " + address);
        }
    }

    private static void waitForChain(Web3j web3j, Process anvil, String rpcUrl) throws IOException {
        for (int i = 0; i < 100; i++) {
            try {
                web3j.ethChainId().send();
                return;
            } catch (IOException notUpYet) {
                if (!anvil.isAlive()) throw new IOException("anvil exited during startup");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted waiting for anvil", e);
                }
            }
        }
        throw new IOException("anvil did not start at " + rpcUrl);
    }

    /** Compiles and deploys with forge (build output in {@code buildDir}, not the repo) and returns the address. */
    private static String forgeCreate(Path root, Path buildDir, String rpcUrl, String contract, String... extraArgs)
            throws IOException {
        List<String> cmd = new ArrayList<>(List.of(findFoundry("FORGE_BIN", "forge"), "create", contract,
                "--root", root.toString(),
                "--out", buildDir.resolve("out").toString(),
                "--cache-path", buildDir.resolve("cache").toString(),
                "--rpc-url", rpcUrl,
                "--private-key", ANVIL_DEPLOYER_KEY,
                "--broadcast"));
        cmd.addAll(List.of(extraArgs));
        Process p = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) {
                p.destroyForcibly();
                throw new IOException("forge create " + contract + " failed:\n" + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted running forge", e);
        }
        Matcher m = Pattern.compile("Deployed to: (0x[0-9a-fA-F]{40})").matcher(output);
        if (!m.find()) throw new IOException("No deployment address in forge output:\n" + output);
        return m.group(1);
    }

    /** Finds a Foundry binary: env override, then PATH, then the usual foundryup install locations. */
    private static String findFoundry(String envVar, String name) {
        String override = System.getenv(envVar);
        if (override != null && !override.isBlank()) return override;
        String home = System.getProperty("user.home");
        List<String> candidates = new ArrayList<>();
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            candidates.add(dir + File.separator + name);
        }
        candidates.add(home + "/.foundry/bin/" + name);
        candidates.add(home + "/.config/.foundry/bin/" + name);
        for (String c : candidates) {
            if (Files.isExecutable(Path.of(c))) return c;
        }
        throw new IllegalStateException(name + " not found; install Foundry or set " + envVar);
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best-effort cleanup of a temp dir
        }
    }
}
