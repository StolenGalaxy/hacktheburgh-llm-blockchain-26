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

public class Bank implements AutoCloseable {

    private static final long CHAIN_ID = 31337;
    private static final String OWNER_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final BigInteger ETH_FOR_GAS = Convert.toWei("10", Convert.Unit.ETHER).toBigInteger();
    private static final BigInteger MIN_GAS_BALANCE = Convert.toWei("1", Convert.Unit.ETHER).toBigInteger();
    private static final String CONTRACTS = "src/main/java/com/stolengalaxy/smartcontract/";
    public static final BigDecimal STARTING_FUNDS = new BigDecimal("100");

    private static final String STATE_FILE = "anvil-state.json";
    private static final String CONTRACTS_FILE = "contracts.properties";
    private static final String SEED_FILE = "wallet-seed";
    private static final String PID_FILE = "anvil.pid";
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
    private final Set<String> fundedWallets = new HashSet<>();

    public Bank() throws Exception {
        this(null);
    }

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

    public void mintUSDC(User user, float amount) {
        mintUSDC(user, new BigDecimal(Float.toString(amount)).setScale(6, RoundingMode.DOWN));
    }

    public synchronized void mintUSDC(User user, BigDecimal amount) {
        Function mint = new Function("mint",
                List.of(new Address(user.walletAddress()), new Uint256(SmartContract.toUnits(amount))), List.of());
        SmartContract.send(web3j, ownerTx, usdcContract, FunctionEncoder.encode(mint), BigInteger.ZERO, "mint");
    }

    public BigDecimal balanceOf(User user) throws IOException {
        return balanceOf(user.walletAddress());
    }

    public BigDecimal balanceOf(String walletAddress) throws IOException {
        return new BigDecimal(view(usdcContract, "balanceOf", new Address(walletAddress)), 6);
    }

    public ISmartContract contractFor(User user, BigInteger poolId) {
        if (user.walletAddress() == null) {
            throw new IllegalStateException("User " + user.userID() + " has no wallet; call login first");
        }
        return new SmartContract(web3j, walletFor(user.userID()), CHAIN_ID, poolContract, usdcContract, poolId);
    }

    public ISmartContract ownerContract(BigInteger poolId) {
        return new SmartContract(web3j, owner, CHAIN_ID, poolContract, usdcContract, poolId);
    }

    public BigInteger poolEndTime(BigInteger poolId) throws IOException {
        return view(poolContract, "endTime", new Uint256(poolId));
    }

    public BigDecimal totalDeposited(BigInteger poolId) throws IOException {
        return new BigDecimal(view(poolContract, "totalDeposited", new Uint256(poolId)), 6);
    }

    public BigDecimal depositOf(BigInteger poolId, String walletAddress) throws IOException {
        return new BigDecimal(view(poolContract, "deposits", new Uint256(poolId), new Address(walletAddress)), 6);
    }

    public boolean isSettled(BigInteger poolId) throws IOException {
        return view(poolContract, "isSettled", new Uint256(poolId)).signum() != 0; // a bool comes back as 0 or 1
    }

    public BigDecimal claimableOf(BigInteger poolId, String walletAddress) throws IOException {
        return new BigDecimal(view(poolContract, "claimable", new Uint256(poolId), new Address(walletAddress)), 6);
    }

    public long chainTime() throws IOException {
        return web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send().getBlock().getTimestamp().longValueExact();
    }

    public String poolContractAddress() {
        return poolContract;
    }

    public String usdcContractAddress() {
        return usdcContract;
    }

    public boolean isRunning() {
        return anvil.isAlive();
    }

    public void skipTime(long seconds) throws IOException {
        new Request<>("evm_increaseTime", List.of(seconds), rpc, VoidResponse.class).send();
        new Request<>("evm_mine", List.of(), rpc, VoidResponse.class).send();
    }

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

    private static String foundry(String tool) {
        String exe = WINDOWS ? tool + ".exe" : tool;
        for (String dir : List.of(".foundry/bin", ".config/.foundry/bin")) {
            Path path = Path.of(System.getProperty("user.home"), dir, exe);
            if (Files.isExecutable(path)) return path.toString();
        }
        return exe;
    }

    private static Process startFoundry(ProcessBuilder builder) throws IOException {
        try {
            return builder.start();
        } catch (IOException e) {
            throw new IOException("Couldn't run '" + builder.command().get(0) + "'. Foundry (anvil + forge) must be installed"
                    + " in ~/.foundry/bin or on PATH: https://getfoundry.sh", e);
        }
    }
}
