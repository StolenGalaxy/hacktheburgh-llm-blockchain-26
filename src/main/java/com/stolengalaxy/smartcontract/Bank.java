package com.stolengalaxy.smartcontract;

import com.stolengalaxy.User;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.VoidResponse;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.TransactionManager;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a local anvil chain with the contracts deployed, gives each user a wallet, and mints mock USDC into wallets.
 * The bank holds the wallets' private keys; a User only stores the address.
 *
 * <ul>
 *   <li>{@code new Bank()}: a throwaway chain (for tests). Everything is gone after {@link #close()}.</li>
 *   <li>{@link #persistent(Path)}: the chain state, contract addresses and the seed the wallets are derived from
 *       are kept in a folder and reloaded on the next start, so pools and balances survive restarts.</li>
 * </ul>
 */
public class Bank implements AutoCloseable {

    private static final long CHAIN_ID = 31337;
    /** anvil's first dev account (10,000 ETH). It deploys the contracts, so it's the owner that pays out pools. */
    private static final String OWNER_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final BigInteger ETH_FOR_GAS = Convert.toWei("10", Convert.Unit.ETHER).toBigInteger();
    /** Top a wallet up with {@link #ETH_FOR_GAS} when it drops below this. */
    private static final BigInteger MIN_GAS_BALANCE = Convert.toWei("1", Convert.Unit.ETHER).toBigInteger();
    private static final String CONTRACTS = "src/main/java/com/stolengalaxy/smartcontract/";
    /** Mock USDC every new wallet starts with (see {@link #openWallet}). */
    public static final BigDecimal STARTING_FUNDS = new BigDecimal("100");

    private static final String STATE_FILE = "anvil-state.json";
    private static final String CONTRACTS_FILE = "contracts.properties";
    private static final String SEED_FILE = "wallet-seed";
    private static final String PID_FILE = "anvil.pid";
    /** Addresses that have been given {@link #STARTING_FUNDS}, one per line. */
    private static final String FUNDED_FILE = "funded-wallets.txt";

    private final Path stateDir; // null for a throwaway chain
    private final Process anvil;
    private final HttpService rpc;
    private final Web3j web3j;
    private final Credentials owner = Credentials.create(OWNER_KEY);
    private final TransactionManager ownerTx;
    private final byte[] walletSeed;
    private final String usdcContract;
    private final String poolContract;
    private final Map<Integer, Credentials> wallets = new ConcurrentHashMap<>();
    /** Lower-case addresses that have had their {@link #STARTING_FUNDS}. */
    private final Set<String> fundedWallets = new HashSet<>();

    /** Starts a throwaway anvil chain and deploys MockUSDC + ProjectDeposit. Needs Foundry installed; run from the project root. */
    public Bank() throws Exception {
        this(null);
    }

    /**
     * Starts an anvil chain whose state is saved in {@code stateDir} (every second, and on {@link #close()}).
     * The contracts are deployed on the first start and reused after that. Needs Foundry installed; run from the project root.
     */
    public static Bank persistent(Path stateDir) throws Exception {
        return new Bank(stateDir);
    }

    private Bank(Path stateDir) throws Exception {
        this.stateDir = stateDir;
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        String rpcUrl = "http://127.0.0.1:" + port;

        List<String> cmd = new ArrayList<>(List.of(foundry("anvil"), "--port", String.valueOf(port), "--silent"));
        if (stateDir != null) {
            Files.createDirectories(stateDir);
            stopLeftoverAnvil(stateDir);
            cmd.addAll(List.of("--state", stateDir.resolve(STATE_FILE).toString(), "--state-interval", "1"));
        }
        anvil = startFoundry(new ProcessBuilder(cmd)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD));
        rpc = new HttpService(rpcUrl);
        web3j = Web3j.build(rpc);
        ownerTx = new RawTransactionManager(web3j, owner, CHAIN_ID);
        try {
            if (stateDir != null) {
                Files.writeString(stateDir.resolve(PID_FILE), String.valueOf(anvil.pid()));
            }
            waitForChain();
            walletSeed = stateDir == null ? randomSeed() : loadOrCreateSeed(stateDir.resolve(SEED_FILE));

            Properties saved = stateDir == null ? null : loadContracts(stateDir.resolve(CONTRACTS_FILE));
            if (saved != null && hasCode(saved.getProperty("usdc")) && hasCode(saved.getProperty("pool"))) {
                usdcContract = saved.getProperty("usdc");
                poolContract = saved.getProperty("pool");
                fundedWallets.addAll(loadFundedWallets(stateDir.resolve(FUNDED_FILE)));
            } else {
                usdcContract = deploy(rpcUrl, "MockUSDC.sol:MockUSDC");
                poolContract = deploy(rpcUrl, "projectdeposit.sol:ProjectDeposit", "--constructor-args", usdcContract);
                if (stateDir != null) {
                    saveContracts(stateDir.resolve(CONTRACTS_FILE));
                    Files.deleteIfExists(stateDir.resolve(FUNDED_FILE)); // a fresh token: nobody has been funded on it yet
                }
            }
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    /**
     * Makes sure the user has a wallet with ETH for gas, setting its address on the user and returning that same user.
     * The wallet is derived from the bank's seed and the user's ID, so it's the same wallet every time.
     */
    public synchronized User login(User user) throws Exception {
        if (user.walletAddress() != null && !user.walletAddress().isBlank()) {
            return user;
        }
        Credentials wallet = walletFor(user.userID());
        BigInteger gas = web3j.ethGetBalance(wallet.getAddress(), DefaultBlockParameterName.LATEST).send().getBalance();
        if (gas.compareTo(MIN_GAS_BALANCE) < 0) {
            SmartContract.send(web3j, ownerTx, wallet.getAddress(), "", ETH_FOR_GAS, "fund gas");
        }
        user.setWalletAddress(wallet.getAddress());
        return user;
    }

    /**
     * Makes sure the user's wallet exists and has gas, and gives it {@link #STARTING_FUNDS} the first time.
     * Safe to call on every request. Returns the wallet's address.
     */
    public synchronized String openWallet(int userID) throws Exception {
        // A detached copy with no address, so login always (re)derives the wallet and checks its gas
        User wallet = login(new User(userID, "user " + userID, null, null));
        String key = wallet.walletAddress().toLowerCase(Locale.ROOT);
        if (!fundedWallets.contains(key)) {
            mintUSDC(wallet, STARTING_FUNDS);
            fundedWallets.add(key);
            if (stateDir != null) {
                Files.writeString(stateDir.resolve(FUNDED_FILE), key + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        }
        return wallet.walletAddress();
    }

    /** Creates {@code amount} new mock USDC in the user's wallet. */
    public void mintUSDC(User user, float amount) {
        mintUSDC(user, new BigDecimal(Float.toString(amount)).setScale(6, RoundingMode.DOWN));
    }

    /** Creates {@code amount} new mock USDC in the user's wallet (at most 6 decimal places). */
    public synchronized void mintUSDC(User user, BigDecimal amount) {
        Function mint = new Function("mint",
                List.of(new Address(user.walletAddress()), new Uint256(SmartContract.toUnits(amount))), List.of());
        SmartContract.send(web3j, ownerTx, usdcContract, FunctionEncoder.encode(mint), BigInteger.ZERO, "mint");
    }

    /** The user's USDC balance. */
    public BigDecimal balanceOf(User user) throws IOException {
        return balanceOf(user.walletAddress());
    }

    /** A wallet's USDC balance. */
    public BigDecimal balanceOf(String walletAddress) throws IOException {
        return new BigDecimal(view(usdcContract, "balanceOf", new Address(walletAddress)), 6);
    }

    /** The project's pool, used as this user (deposit, withdraw). */
    public ISmartContract contractFor(User user, BigInteger poolId) {
        if (user.walletAddress() == null) {
            throw new IllegalStateException("User " + user.userID() + " has no wallet; call login first");
        }
        return new SmartContract(web3j, walletFor(user.userID()), CHAIN_ID, poolContract, usdcContract, poolId);
    }

    /** The project's pool, used as the owner (createPool, payout). */
    public ISmartContract ownerContract(BigInteger poolId) {
        return new SmartContract(web3j, owner, CHAIN_ID, poolContract, usdcContract, poolId);
    }

    /** When the pool's deposit window closes (unix seconds), or 0 if the pool doesn't exist on this chain. */
    public BigInteger poolEndTime(BigInteger poolId) throws IOException {
        return view(poolContract, "endTime", new Uint256(poolId));
    }

    /** Total USDC deposited into the pool. */
    public BigDecimal totalDeposited(BigInteger poolId) throws IOException {
        return new BigDecimal(view(poolContract, "totalDeposited", new Uint256(poolId)), 6);
    }

    /** USDC deposited into the pool by one wallet. */
    public BigDecimal depositOf(BigInteger poolId, String walletAddress) throws IOException {
        return new BigDecimal(view(poolContract, "deposits", new Uint256(poolId), new Address(walletAddress)), 6);
    }

    /** The latest block's timestamp (unix seconds): "now" as the contracts see it. */
    public long chainTime() throws IOException {
        return web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send().getBlock().getTimestamp().longValueExact();
    }

    /** Address of the deployed ProjectDeposit contract that holds every pool. */
    public String poolContractAddress() {
        return poolContract;
    }

    /** Address of the deployed mock USDC token. */
    public String usdcContractAddress() {
        return usdcContract;
    }

    /** Whether the anvil process is still running (it can't be restarted without a new Bank). */
    public boolean isRunning() {
        return anvil.isAlive();
    }

    /** Moves the local chain's clock forward, e.g. past a pool's deadline. */
    public void skipTime(long seconds) throws IOException {
        new Request<>("evm_increaseTime", List.of(seconds), rpc, VoidResponse.class).send();
        new Request<>("evm_mine", List.of(), rpc, VoidResponse.class).send();
    }

    /** Stops the chain. A persistent chain is asked to stop cleanly first, so it saves its latest state. */
    @Override
    public void close() {
        web3j.shutdown();
        if (stateDir != null && anvil.isAlive()) {
            anvil.destroy(); // SIGTERM: anvil writes its state file on the way out
            try {
                anvil.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        anvil.destroyForcibly();
        if (stateDir != null) {
            try {
                Files.deleteIfExists(stateDir.resolve(PID_FILE));
            } catch (IOException ignored) {
                // a stale pid file is checked before it's used
            }
        }
    }

    // ===== Wallets =====

    /** The user's wallet: its private key is keccak256(seed ‖ userID), so the same seed always gives the same wallet. */
    private Credentials walletFor(int userID) {
        return wallets.computeIfAbsent(userID, id -> {
            byte[] input = ByteBuffer.allocate(walletSeed.length + Integer.BYTES).put(walletSeed).putInt(id).array();
            return Credentials.create(ECKeyPair.create(new BigInteger(1, Hash.sha3(input))));
        });
    }

    private static byte[] randomSeed() {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        return seed;
    }

    /** Reads the wallet seed, creating it (readable only by this user) on the first start. */
    private static byte[] loadOrCreateSeed(Path file) throws IOException {
        if (Files.exists(file)) {
            return Numeric.hexStringToByteArray(Files.readString(file).trim());
        }
        byte[] seed = randomSeed();
        Files.writeString(file, Numeric.toHexString(seed));
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system (Windows)
        }
        return seed;
    }

    // ===== Chain helpers =====

    /** Calls a view function that returns a single uint256. */
    @SuppressWarnings("rawtypes")
    private BigInteger view(String contract, String function, Type... args) throws IOException {
        Function fn = new Function(function, List.of(args), List.of(new TypeReference<Uint256>() {}));
        String result = ownerTx.sendCall(contract, FunctionEncoder.encode(fn), DefaultBlockParameterName.LATEST);
        return (BigInteger) FunctionReturnDecoder.decode(result, fn.getOutputParameters()).getFirst().getValue();
    }

    private boolean hasCode(String address) throws IOException {
        if (address == null) {
            return false;
        }
        String code = web3j.ethGetCode(address, DefaultBlockParameterName.LATEST).send().getCode();
        return code != null && !code.equals("0x");
    }

    private void waitForChain() throws Exception {
        for (int i = 0; i < 100; i++) {
            try {
                web3j.ethChainId().send();
                return;
            } catch (IOException notUpYet) {
                if (!anvil.isAlive()) {
                    throw new IOException("anvil exited with code " + anvil.exitValue());
                }
                Thread.sleep(100);
            }
        }
        throw new IOException("anvil did not start");
    }

    /**
     * Stops an anvil left running on this state folder by a previous run that didn't shut down cleanly
     * (it would otherwise keep overwriting the state file with stale state).
     */
    private static void stopLeftoverAnvil(Path stateDir) throws IOException {
        Path pidFile = stateDir.resolve(PID_FILE);
        if (!Files.exists(pidFile)) {
            return;
        }
        try {
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            ProcessHandle.of(pid)
                    .filter(handle -> handle.info().command().map(c -> c.endsWith("anvil") || c.endsWith("anvil.exe")).orElse(false))
                    .ifPresent(handle -> {
                        handle.destroy();
                        try {
                            handle.onExit().get(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            handle.destroyForcibly();
                        }
                    });
        } catch (NumberFormatException ignored) {
            // corrupt pid file: nothing to stop
        }
        Files.deleteIfExists(pidFile);
    }

    private static List<String> loadFundedWallets(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        return Files.readAllLines(file).stream().map(String::trim).filter(line -> !line.isEmpty()).toList();
    }

    private static Properties loadContracts(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }

    private void saveContracts(Path file) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("usdc", usdcContract);
        properties.setProperty("pool", poolContract);
        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(out, "Contracts deployed on the chain in " + STATE_FILE);
        }
    }

    /** Compiles and deploys a contract with forge (build files go in build/forge) and returns its address. */
    private static String deploy(String rpcUrl, String contract, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(foundry("forge"), "create", CONTRACTS + contract,
                "--out", "build/forge/out", "--cache-path", "build/forge/cache",
                "--rpc-url", rpcUrl, "--private-key", OWNER_KEY, "--broadcast"));
        cmd.addAll(List.of(args));
        Process forge = startFoundry(new ProcessBuilder(cmd).redirectErrorStream(true));
        String output = new String(forge.getInputStream().readAllBytes());
        Matcher m = Pattern.compile("Deployed to: (0x[0-9a-fA-F]{40})").matcher(output);
        if (!m.find()) {
            throw new IOException("forge create " + contract + " failed:\n" + output);
        }
        return m.group(1);
    }

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().startsWith("windows");

    /** Foundry's install folder, or just the name if it's on PATH. */
    private static String foundry(String tool) {
        String exe = WINDOWS ? tool + ".exe" : tool;
        for (String dir : List.of(".foundry/bin", ".config/.foundry/bin")) {
            Path path = Path.of(System.getProperty("user.home"), dir, exe);
            if (Files.isExecutable(path)) return path.toString();
        }
        return exe;
    }

    /** Starts a Foundry tool, with a clear message if Foundry isn't installed. */
    private static Process startFoundry(ProcessBuilder builder) throws IOException {
        try {
            return builder.start();
        } catch (IOException e) {
            throw new IOException("Couldn't run '" + builder.command().get(0) + "'. Foundry (anvil + forge) must be installed"
                    + " in ~/.foundry/bin or on PATH: https://getfoundry.sh", e);
        }
    }
}
