import com.stolengalaxy.User;
import com.stolengalaxy.smartcontract.Bank;
import com.stolengalaxy.smartcontract.ISmartContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Runs against a fresh local anvil chain started by the Bank (needs Foundry installed). */
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

        // Everyone was minted 100 USDC, so their balance is 100 minus their stake.
        assertBalance("89.5", alice);
        assertBalance("75", bob);
        assertBalance("99.25", carol);
        assertBalance("0", dave);
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

        // A payout must split exactly what was deposited (60).
        assertThrows(IllegalStateException.class, () -> owner.payout(Map.of(alice.walletAddress(), 10f)));

        // Carol didn't do her task, so her stake goes to Alice and Bob.
        owner.payout(split);
        assertThrows(IllegalStateException.class, () -> owner.payout(Map.of(alice.walletAddress(), 60f)),
                "a pool can only be paid out once");

        bank.contractFor(alice, pool).withdraw();
        bank.contractFor(bob, pool).withdraw();

        assertBalance("110", alice);
        assertBalance("110", bob);
        assertBalance("80", carol);
    }

    /** Logs a new user in (creating their wallet), mints them 100 USDC and stakes {@code stake} into the pool. */
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
