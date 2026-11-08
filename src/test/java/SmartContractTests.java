import com.stolengalaxy.User;
import com.stolengalaxy.smartcontract.Bank;
import com.stolengalaxy.smartcontract.ISmartContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SmartContractTests {

    private static Bank bank;

    @BeforeAll
    static void start() throws Exception {
        bank = new Bank();
    }

    @AfterAll
    static void stop() {
        bank.close();
    }

    private static final long ONE_WEEK = 7 * 24 * 60 * 60;

    @Test
    public void testDeposit() throws Exception {
        BigInteger pool = BigInteger.ONE;
        bank.ownerContract(pool).createPool(ONE_WEEK);
        User alice = joinAndStake(1, "alice", pool, 10.5f);
        User bob = joinAndStake(2, "bob", pool, 25f);
        User carol = joinAndStake(3, "carol", pool, 0.75f);
        User dave = joinAndStake(4, "dave", pool, 100f);

        assertBalance("89.5", alice);
        assertBalance("75", bob);
        assertBalance("99.25", carol);
        assertBalance("0", dave);
    }

    /** anvil only mines on a transaction, so after the chain sits idle the latest block is old. chainTime() must still be "now". */
    @Test
    public void testChainTimeIsCurrentAfterIdle() throws Exception {
        long before = bank.chainTime();
        Thread.sleep(4000); // idle: no transactions, so no new blocks
        long chainNow = bank.chainTime();
        assertTrue(chainNow - before >= 3, "chain time should have moved on while idle, but went " + before + " -> " + chainNow);

        BigInteger pool = BigInteger.valueOf(99);
        bank.ownerContract(pool).createPool(60);
        long closesAt = bank.poolEndTime(pool).longValueExact();
        assertTrue(closesAt - (chainNow + 60) <= 1, "pool should close 60s from now, not 60s plus the idle time");
    }

    @Test
    public void testSettlePool() throws Exception {
        BigInteger pool = BigInteger.TWO;
        ISmartContract owner = bank.ownerContract(pool);
        owner.createPool(ONE_WEEK);
        User alice = joinAndStake(11, "alice", pool, 20f);
        User bob = joinAndStake(12, "bob", pool, 20f);
        User carol = joinAndStake(13, "carol", pool, 20f);

        Map<String, Float> split = Map.of(alice.walletAddress(), 30f, bob.walletAddress(), 30f);
        assertThrows(IllegalStateException.class, () -> owner.payout(split), "can't pay out before the deadline");
        bank.skipTime(ONE_WEEK + 1);

        assertThrows(IllegalStateException.class, () -> owner.payout(Map.of(alice.walletAddress(), 10f)));

        owner.payout(split);
        assertThrows(IllegalStateException.class, () -> owner.payout(Map.of(alice.walletAddress(), 60f)),
                "a pool can only be paid out once");

        bank.contractFor(alice, pool).withdraw();
        bank.contractFor(bob, pool).withdraw();

        assertBalance("110", alice);
        assertBalance("110", bob);
        assertBalance("80", carol);
    }

    @Test
    public void testStartingFundsAreGivenOnce() throws Exception {
        String address = bank.openWallet(21);
        assertEquals(0, Bank.STARTING_FUNDS.compareTo(bank.balanceOf(address)), "a new wallet starts with 100 USDC");
        assertEquals(address, bank.openWallet(21), "the same user always gets the same wallet");
        assertEquals(0, Bank.STARTING_FUNDS.compareTo(bank.balanceOf(address)), "opening it again doesn't add more");
    }

    @Test
    public void testStartingFundsSurviveRestart(@TempDir Path stateDir) throws Exception {
        String address;
        try (Bank first = Bank.persistent(stateDir)) {
            address = first.openWallet(1);
        }
        try (Bank second = Bank.persistent(stateDir)) {
            assertEquals(address, second.openWallet(1));
            assertEquals(0, Bank.STARTING_FUNDS.compareTo(second.balanceOf(address)),
                    "after a restart the wallet keeps its funds and isn't funded twice");
        }
    }

    private static User joinAndStake(int id, String name, BigInteger pool, float stake) throws Exception {
        User user = bank.login(new User(id, name, null, name + "-gh"));
        bank.mintUSDC(user, 100f);
        bank.contractFor(user, pool).deposit(stake);
        return user;
    }

    private static void assertBalance(String expected, User user) throws Exception {
        assertEquals(0, new BigDecimal(expected).compareTo(bank.balanceOf(user)),
                user.username() + " should have " + expected + " USDC but has " + bank.balanceOf(user));
    }
}
