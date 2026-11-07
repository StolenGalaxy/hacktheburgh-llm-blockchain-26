import com.stolengalaxy.smartcontract.SmartContract;
import com.stolengalaxy.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.tx.response.PollingTransactionReceiptProcessor;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests against a throwaway local anvil chain.
 * Requires Foundry ({@code anvil} and {@code forge}) on this machine; set ANVIL_BIN / FORGE_BIN if they aren't on PATH.
 */
public class SmartContractTests {

    private static final long CHAIN_ID = 31337;
    private static final BigInteger POOL_ID = BigInteger.ONE;
    private static final BigInteger USDC_UNIT = BigInteger.TEN.pow(6);

    /** anvil's default, deterministic dev accounts. #0 deploys (and therefore owns) the contracts. */
    private static final String DEPLOYER_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final String[] USER_KEYS = {
            "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d",
            "0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a",
            "0x7c852118294e51e653712a81e05800f419141751be58f605c371e15141b007a6",
            "0x47e179ec197488593b187f80a00eb0da91f1b9d0b13f8733639f19c30a34926a",
    };

    @TempDir
    static Path forgeOut;

    private static Process anvil;
    private static String rpcUrl;
    private static Web3j web3j;
    private static Credentials deployer;
    private static String usdcAddress;
    private static String poolAddress;

    @BeforeAll
    static void startChainAndDeploy() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        rpcUrl = "http://127.0.0.1:" + port;
        anvil = new ProcessBuilder(tool("ANVIL_BIN", "anvil"), "--port", String.valueOf(port), "--silent")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();

        web3j = Web3j.build(new HttpService(rpcUrl));
        waitForChain();

        deployer = Credentials.create(DEPLOYER_KEY);
        usdcAddress = forgeCreate("src/test/resources/MockUSDC.sol:MockUSDC");
        poolAddress = forgeCreate("src/main/java/com/stolengalaxy/smartcontract/projectdeposit.sol:ProjectDeposit",
                "--constructor-args", usdcAddress);
    }

    @AfterAll
    static void stopChain() {
        if (web3j != null) web3j.shutdown();
        if (anvil != null) anvil.destroyForcibly();
    }

    @Test
    public void testDeposit() throws Exception {
        float[] amounts = {10.5f, 25f, 0.75f, 100f};

        // The oracle backend owns the contract and keeps the user -> wallet registry.
        SmartContract oracle = contractFor(deployer);

        List<User> users = new ArrayList<>();
        List<SmartContract> userContracts = new ArrayList<>();
        for (int i = 0; i < USER_KEYS.length; i++) {
            Credentials creds = Credentials.create(USER_KEYS[i]);
            User user = new User(i + 1, "user" + (i + 1), creds.getAddress());
            oracle.assignWallet(user);
            users.add(user);
            userContracts.add(contractFor(creds));
            mintUsdc(user.walletAddress(), BigInteger.valueOf(1_000).multiply(USDC_UNIT));
        }

        BigInteger expectedTotal = BigInteger.ZERO;
        for (int i = 0; i < users.size(); i++) {
            String wallet = users.get(i).walletAddress();
            BigInteger before = usdcBalance(wallet);

            // Each user signs their own deposit, so msg.sender is their wallet.
            userContracts.get(i).deposit(amounts[i]);

            BigInteger expected = usdc(amounts[i]);
            expectedTotal = expectedTotal.add(expected);
            assertEquals(expected, oracle.depositOf(wallet), "recorded deposit for " + users.get(i).username());
            assertEquals(before.subtract(expected), usdcBalance(wallet), "wallet debited for " + users.get(i).username());
        }

        assertEquals(usdc(136.25f), expectedTotal);
        assertEquals(expectedTotal, oracle.totalDeposited());
        assertEquals(expectedTotal, usdcBalance(poolAddress), "contract holds every deposit");
        assertFalse(oracle.isSettled());
        assertEquals(users.get(2), oracle.getUser(3));

        // A second deposit from the same user accumulates rather than overwriting.
        userContracts.get(0).deposit(4.5f);
        assertEquals(usdc(15f), oracle.depositOf(users.get(0).walletAddress()));
        assertEquals(expectedTotal.add(usdc(4.5f)), oracle.totalDeposited());

        assertThrows(IllegalArgumentException.class, () -> userContracts.get(1).deposit(0f));
    }

    // ---------------------------------------------------------------- helpers

    private static SmartContract contractFor(Credentials credentials) {
        return new SmartContract(web3j, credentials, CHAIN_ID, new DefaultGasProvider(), poolAddress, usdcAddress, POOL_ID);
    }

    private static BigInteger usdc(float amount) {
        return new java.math.BigDecimal(Float.toString(amount)).movePointRight(6).toBigIntegerExact();
    }

    private static BigInteger usdcBalance(String account) throws IOException {
        Function fn = new Function("balanceOf", List.of(new Address(account)), List.of(new TypeReference<Uint256>() {}));
        String raw = web3j.ethCall(
                org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(null, usdcAddress, FunctionEncoder.encode(fn)),
                DefaultBlockParameterName.LATEST).send().getValue();
        return (BigInteger) FunctionReturnDecoder.decode(raw, fn.getOutputParameters()).get(0).getValue();
    }

    private static void mintUsdc(String to, BigInteger amount) throws Exception {
        Function fn = new Function("mint", List.of(new Address(to), new Uint256(amount)), List.of());
        DefaultGasProvider gas = new DefaultGasProvider();
        EthSendTransaction tx = new RawTransactionManager(web3j, deployer, CHAIN_ID)
                .sendTransaction(gas.getGasPrice(), gas.getGasLimit(), usdcAddress, FunctionEncoder.encode(fn), BigInteger.ZERO);
        assertFalse(tx.hasError(), () -> "mint failed: " + tx.getError().getMessage());
        assertTrue(new PollingTransactionReceiptProcessor(web3j, 200, 50)
                .waitForTransactionReceipt(tx.getTransactionHash()).isStatusOK(), "mint reverted");
    }

    private static void waitForChain() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            try {
                web3j.ethChainId().send();
                return;
            } catch (IOException notUpYet) {
                if (!anvil.isAlive()) fail("anvil exited during startup");
                Thread.sleep(100);
            }
        }
        fail("anvil did not start at " + rpcUrl);
    }

    /** Compiles and deploys with forge (into a temp dir, so the repo isn't touched) and returns the address. */
    private static String forgeCreate(String contract, String... extraArgs) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(tool("FORGE_BIN", "forge"), "create", contract,
                "--root", System.getProperty("user.dir"),
                "--out", forgeOut.resolve("out").toString(),
                "--cache-path", forgeOut.resolve("cache").toString(),
                "--rpc-url", rpcUrl,
                "--private-key", DEPLOYER_KEY,
                "--broadcast"));
        cmd.addAll(List.of(extraArgs));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0, () -> "forge create failed:\n" + output);
        Matcher m = Pattern.compile("Deployed to: (0x[0-9a-fA-F]{40})").matcher(output);
        assertTrue(m.find(), () -> "no deployment address in forge output:\n" + output);
        return m.group(1);
    }

    /** Finds a Foundry binary: env override, then PATH, then the usual foundryup install locations. */
    private static String tool(String envVar, String name) {
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
}
