import com.stolengalaxy.User;
import com.stolengalaxy.smartcontract.Bank;
import com.stolengalaxy.smartcontract.SmartContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Runs against a fresh local anvil chain started by the Bank (needs Foundry installed). */
public class SmartContractTests {

    private static final BigInteger POOL_ID = BigInteger.ONE;

    private static Bank bank;

    @BeforeAll
    static void start() throws Exception {
        bank = Bank.startLocal();
    }

    @AfterAll
    static void stop() {
        bank.close();
    }

    @Test
    public void testDeposit() {
        String[] names = {"alice", "bob", "carol", "dave"};
        float[] amounts = {10.5f, 25f, 0.75f, 100f};
        SmartContract owner = bank.ownerContract(POOL_ID);

        for (int i = 0; i < names.length; i++) {
            User user = bank.login(new User(i + 1, names[i], null, names[i] + "-gh"));
            bank.mintUSDC(user, new BigDecimal("1000"));

            bank.contractFor(user, POOL_ID).deposit(amounts[i]);

            BigDecimal expectedBalance = new BigDecimal("1000").subtract(new BigDecimal(Float.toString(amounts[i])));
            assertEquals(0, expectedBalance.compareTo(bank.balanceOf(user)), names[i] + "'s wallet was debited");
            assertEquals(usdc(amounts[i]), owner.depositOf(user.walletAddress()), names[i] + "'s deposit was recorded");
        }

        assertEquals(usdc(136.25f), owner.totalDeposited());
        assertFalse(owner.isSettled());
    }

    /** Whole USDC -> the contract's 6-decimal base units. */
    private static BigInteger usdc(float amount) {
        return new BigDecimal(Float.toString(amount)).movePointRight(6).toBigIntegerExact();
    }
}
