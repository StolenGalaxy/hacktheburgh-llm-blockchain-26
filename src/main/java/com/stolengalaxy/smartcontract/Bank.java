package com.stolengalaxy.smartcontract;

import com.stolengalaxy.User;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.VoidResponse;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.TransactionManager;
import org.web3j.utils.Convert;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a fresh local anvil chain with the contracts deployed, creates a wallet for each user on login,
 * and mints mock USDC into wallets. The bank keeps the wallets' private keys; a User only stores the address.
 * Everything (chain, wallets, keys) is gone after {@link #close()}.
 */
public class Bank implements AutoCloseable {

    private static final long CHAIN_ID = 31337;
    /** anvil's first dev account (10,000 ETH). It deploys the contracts, so it's the owner that pays out pools. */
    private static final String OWNER_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final BigInteger ETH_FOR_GAS = Convert.toWei("10", Convert.Unit.ETHER).toBigInteger();
    private static final String CONTRACTS = "src/main/java/com/stolengalaxy/smartcontract/";

    private final Process anvil;
    private final HttpService rpc;
    private final Web3j web3j;
    private final Credentials owner = Credentials.create(OWNER_KEY);
    private final TransactionManager ownerTx;
    private final String usdcContract;
    private final String poolContract;
    private final Map<Integer, Credentials> wallets = new ConcurrentHashMap<>();

    /** Starts anvil and deploys MockUSDC + ProjectDeposit. Needs Foundry installed; run from the project root. */
    public Bank() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        String rpcUrl = "http://127.0.0.1:" + port;
        anvil = new ProcessBuilder(foundry("anvil"), "--port", String.valueOf(port), "--silent")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        rpc = new HttpService(rpcUrl);
        web3j = Web3j.build(rpc);
        ownerTx = new RawTransactionManager(web3j, owner, CHAIN_ID);
        try {
            waitForChain();
            usdcContract = deploy(rpcUrl, "MockUSDC.sol:MockUSDC");
            poolContract = deploy(rpcUrl, "projectdeposit.sol:ProjectDeposit", "--constructor-args", usdcContract);
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    /** Gives a user without a wallet a new one (with ETH for gas). Store the returned User in place of the old one. */
    public synchronized User login(User user) throws Exception {
        if (user.walletAddress() != null && !user.walletAddress().isBlank()) {
            return user;
        }
        Credentials wallet = Credentials.create(Keys.createEcKeyPair());
        SmartContract.send(web3j, ownerTx, wallet.getAddress(), "", ETH_FOR_GAS, "fund gas");
        wallets.put(user.userID(), wallet);
        return new User(user.userID(), user.username(), wallet.getAddress(), user.githubUsername());
    }

    /** Creates {@code amount} new mock USDC in the user's wallet. */
    public synchronized void mintUSDC(User user, float amount) {
        Function mint = new Function("mint",
                List.of(new Address(user.walletAddress()), new Uint256(SmartContract.toUnits(amount))), List.of());
        SmartContract.send(web3j, ownerTx, usdcContract, FunctionEncoder.encode(mint), BigInteger.ZERO, "mint");
    }

    /** The user's USDC balance. */
    public BigDecimal balanceOf(User user) throws IOException {
        Function fn = new Function("balanceOf", List.of(new Address(user.walletAddress())),
                List.of(new TypeReference<Uint256>() {}));
        String result = ownerTx.sendCall(usdcContract, FunctionEncoder.encode(fn), DefaultBlockParameterName.LATEST);
        BigInteger units = (BigInteger) FunctionReturnDecoder.decode(result, fn.getOutputParameters()).getFirst().getValue();
        return new BigDecimal(units, 6);
    }

    /** The project's pool, used as this user (deposit, withdraw). */
    public ISmartContract contractFor(User user, BigInteger poolId) {
        Credentials wallet = wallets.get(user.userID());
        if (wallet == null) {
            throw new IllegalStateException("User " + user.userID() + " has no wallet; call login first");
        }
        return new SmartContract(web3j, wallet, CHAIN_ID, poolContract, usdcContract, poolId);
    }

    /** The project's pool, used as the owner (createPool, payout). */
    public ISmartContract ownerContract(BigInteger poolId) {
        return new SmartContract(web3j, owner, CHAIN_ID, poolContract, usdcContract, poolId);
    }

    /** Moves the local chain's clock forward, e.g. past a pool's deadline. */
    public void skipTime(long seconds) throws IOException {
        new Request<>("evm_increaseTime", List.of(seconds), rpc, VoidResponse.class).send();
        new Request<>("evm_mine", List.of(), rpc, VoidResponse.class).send();
    }

    @Override
    public void close() {
        web3j.shutdown();
        anvil.destroyForcibly();
    }

    private void waitForChain() throws Exception {
        for (int i = 0; i < 100; i++) {
            try {
                web3j.ethChainId().send();
                return;
            } catch (IOException notUpYet) {
                Thread.sleep(100);
            }
        }
        throw new IOException("anvil did not start");
    }

    /** Compiles and deploys a contract with forge (build files go in build/forge) and returns its address. */
    private static String deploy(String rpcUrl, String contract, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(foundry("forge"), "create", CONTRACTS + contract,
                "--out", "build/forge/out", "--cache-path", "build/forge/cache",
                "--rpc-url", rpcUrl, "--private-key", OWNER_KEY, "--broadcast"));
        cmd.addAll(List.of(args));
        Process forge = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(forge.getInputStream().readAllBytes());
        Matcher m = Pattern.compile("Deployed to: (0x[0-9a-fA-F]{40})").matcher(output);
        if (!m.find()) {
            throw new IOException("forge create " + contract + " failed:\n" + output);
        }
        return m.group(1);
    }

    /** Foundry's install folder, or just the name if it's on PATH. */
    private static String foundry(String tool) {
        for (String dir : List.of(".foundry/bin", ".config/.foundry/bin")) {
            Path path = Path.of(System.getProperty("user.home"), dir, tool);
            if (Files.isExecutable(path)) return path.toString();
        }
        return tool;
    }
}
