package org.qortal.test.minting;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.block.BlockChain;
import org.qortal.block.BlockMinter;
import org.qortal.block.BlockChain.RewardByHeight;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AccountUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;

public class RewardTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testSimpleReward() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Map<String, Map<Long, BigDecimal>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT);

			BigDecimal blockReward = BlockUtils.getNextBlockReward(repository);

			BlockUtils.mintBlock(repository);

			BigDecimal expectedBalance = initialBalances.get("alice").get(Asset.QORT).add(blockReward);
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, expectedBalance);
		}
	}

	@Test
	public void testRewards() throws DataException {
		List<RewardByHeight> rewardsByHeight = BlockChain.getInstance().getBlockRewardsByHeight();

		try (final Repository repository = RepositoryManager.getRepository()) {
			Map<String, Map<Long, BigDecimal>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT);

			int rewardIndex = rewardsByHeight.size() - 1;

			RewardByHeight rewardInfo = rewardsByHeight.get(rewardIndex);
			BigDecimal expectedBalance = initialBalances.get("alice").get(Asset.QORT);

			for (int height = rewardInfo.height; height > 1; --height) {
				if (height < rewardInfo.height) {
					--rewardIndex;
					rewardInfo = rewardsByHeight.get(rewardIndex);
				}

				BlockUtils.mintBlock(repository);

				expectedBalance = expectedBalance.add(rewardInfo.reward);
			}

			AccountUtils.assertBalance(repository, "alice", Asset.QORT, expectedBalance);
		}
	}

	@Test
	public void testRewardSharing() throws DataException {
		final BigDecimal share = new BigDecimal("12.8");

		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] rewardSharePrivateKey = AccountUtils.rewardShare(repository, "alice", "bob", share);
			PrivateKeyAccount rewardShareAccount = new PrivateKeyAccount(repository, rewardSharePrivateKey);

			Map<String, Map<Long, BigDecimal>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT);
			BigDecimal blockReward = BlockUtils.getNextBlockReward(repository);

			BlockMinter.mintTestingBlock(repository, rewardShareAccount);

			// We're expecting reward * 12.8% to Bob, the rest to Alice

			BigDecimal bobShare = blockReward.multiply(share.movePointLeft(2)).setScale(8, RoundingMode.DOWN);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, initialBalances.get("bob").get(Asset.QORT).add(bobShare));

			BigDecimal aliceShare = blockReward.subtract(bobShare);
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, initialBalances.get("alice").get(Asset.QORT).add(aliceShare));
		}
	}


	@Test
	public void testLegacyQoraReward() throws DataException {
		Common.useSettings("test-settings-v2-qora-holder.json");

		BigDecimal qoraHoldersShare = BlockChain.getInstance().getQoraHoldersShare();
		BigDecimal qoraPerQort = BlockChain.getInstance().getQoraPerQortReward();

		try (final Repository repository = RepositoryManager.getRepository()) {
			Map<String, Map<Long, BigDecimal>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);

			BigDecimal blockReward = BlockUtils.getNextBlockReward(repository);

			// Fetch all legacy QORA holder balances
			List<AccountBalanceData> qoraHolders = repository.getAccountRepository().getAssetBalances(Asset.LEGACY_QORA, true);
			BigDecimal totalQoraHeld = BigDecimal.ZERO.setScale(8);
			for (AccountBalanceData accountBalanceData : qoraHolders)
				totalQoraHeld = totalQoraHeld.add(accountBalanceData.getBalance());

			BlockUtils.mintBlock(repository);

			/*
			 * Example:
			 * 
			 * Block reward is 100 QORT, QORA-holders' share is 0.20 (20%) = 20 QORT
			 * 
			 * We hold 100 QORA
			 * Someone else holds 28 QORA
			 * Total QORA held: 128 QORA
			 * 
			 * Our portion of that is 100 QORA / 128 QORA * 20 QORT = 15.625 QORT
			 * 
			 * QORA holders earn at most 1 QORT per 250 QORA held.
			 * 
			 * So we can earn at most 100 QORA / 250 QORAperQORT = 0.4 QORT
			 * 
			 * Thus our block earning should be capped to 0.4 QORT.
			 */

			// Expected reward
			BigDecimal qoraHoldersReward = blockReward.multiply(qoraHoldersShare);
			assertTrue("QORA-holders share of block reward should be less than total block reward", qoraHoldersReward.compareTo(blockReward) < 0);

			BigDecimal ourQoraHeld = initialBalances.get("chloe").get(Asset.LEGACY_QORA);
			BigDecimal ourQoraReward = qoraHoldersReward.multiply(ourQoraHeld).divide(totalQoraHeld, RoundingMode.DOWN).setScale(8, RoundingMode.DOWN);
			assertTrue("Our QORA-related reward should be less than total QORA-holders share of block reward", ourQoraReward.compareTo(qoraHoldersReward) < 0);

			BigDecimal ourQortFromQoraCap = ourQoraHeld.divide(qoraPerQort, RoundingMode.DOWN);

			BigDecimal expectedReward = ourQoraReward.min(ourQortFromQoraCap);
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, initialBalances.get("chloe").get(Asset.QORT).add(expectedReward));

			AccountUtils.assertBalance(repository, "chloe", Asset.QORT_FROM_QORA, initialBalances.get("chloe").get(Asset.QORT_FROM_QORA).add(expectedReward));
		}
	}

	@Test
	public void testMaxLegacyQoraReward() throws DataException {
		Common.useSettings("test-settings-v2-qora-holder.json");

		BigDecimal qoraPerQort = BlockChain.getInstance().getQoraPerQortReward();

		try (final Repository repository = RepositoryManager.getRepository()) {
			Map<String, Map<Long, BigDecimal>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);

			// Mint lots of blocks
			for (int i = 0; i < 100; ++i)
				BlockUtils.mintBlock(repository);

			// Expected balances to be limited by Chloe's legacy QORA amount
			BigDecimal expectedBalance = initialBalances.get("chloe").get(Asset.LEGACY_QORA).divide(qoraPerQort);
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, initialBalances.get("chloe").get(Asset.QORT).add(expectedBalance));
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT_FROM_QORA, initialBalances.get("chloe").get(Asset.QORT_FROM_QORA).add(expectedBalance));
		}
	}

	/** Use Alice-Chloe reward-share to bump Chloe from level 0 to level 1, then check orphaning works as expected. */
	@Test
	public void testLevel1() throws DataException {
		List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			assertEquals(0, (int) chloe.getLevel());

			// Alice needs to mint block containing REWARD_SHARE BEFORE Alice loses minting privs
			byte[] aliceChloeRewardSharePrivateKey = AccountUtils.rewardShare(repository, "alice", "chloe", BigDecimal.ZERO); // Block minted by Alice
			PrivateKeyAccount aliceChloeRewardShareAccount = new PrivateKeyAccount(repository, aliceChloeRewardSharePrivateKey);

			final int minterBlocksNeeded = cumulativeBlocksByLevel.get(1);
			// Mint enough blocks to bump testAccount level
			for (int bc = 0; bc < minterBlocksNeeded; ++bc)
				BlockMinter.mintTestingBlock(repository, aliceChloeRewardShareAccount);

			assertEquals(1, (int) chloe.getLevel());

			// Orphan back to genesis block
			BlockUtils.orphanToBlock(repository, 1);

			assertEquals(0, (int) chloe.getLevel());
		}
	}

}