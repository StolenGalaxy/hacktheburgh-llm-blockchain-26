package com.stolengalaxy.smartcontract;

import com.stolengalaxy.User;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.gas.DefaultGasProvider;

import java.math.BigInteger;
import java.util.List;

public class Bank {
    private static final String DEPLOYER_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final long CHAIN_ID = 31337;

    private static Process anvil;
    private static String rpcUrl;
    private static Web3j web3j;
    private static Credentials deployer;
    private static String usdcAddress;
    private static String poolAddress;

    public Bank() {
        deployer = Credentials.create(DEPLOYER_KEY);
    }

    public void mintUSDC(User user, BigInteger amount) throws Exception {
        Function fn = new Function("mint", List.of(new Address(user.walletAddress()), new Uint256(amount)), List.of());
        DefaultGasProvider gas = new DefaultGasProvider();
        EthSendTransaction tx = new RawTransactionManager(web3j, deployer, CHAIN_ID)
                .sendTransaction(gas.getGasPrice(), gas.getGasLimit(), usdcAddress, FunctionEncoder.encode(fn), BigInteger.ZERO);
    }
}
