package org.qortal.controller;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.security.Security;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.account.PublicKeyAccount;
import org.qortal.api.ApiService;
import org.qortal.block.Block;
import org.qortal.block.BlockChain;
import org.qortal.block.BlockMinter;
import org.qortal.block.BlockChain.BlockTimingByHeight;
import org.qortal.controller.Synchronizer.SynchronizationResult;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.MintingAccountData;
import org.qortal.data.account.RewardShareData;
import org.qortal.data.block.BlockData;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.network.OnlineAccountData;
import org.qortal.data.network.PeerChainTipData;
import org.qortal.data.network.PeerData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.DataType;
import org.qortal.globalization.Translator;
import org.qortal.gui.Gui;
import org.qortal.gui.SysTray;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.message.ArbitraryDataMessage;
import org.qortal.network.message.BlockMessage;
import org.qortal.network.message.BlockSummariesMessage;
import org.qortal.network.message.GetArbitraryDataMessage;
import org.qortal.network.message.GetBlockMessage;
import org.qortal.network.message.GetBlockSummariesMessage;
import org.qortal.network.message.GetOnlineAccountsMessage;
import org.qortal.network.message.GetPeersMessage;
import org.qortal.network.message.GetSignaturesMessage;
import org.qortal.network.message.GetSignaturesV2Message;
import org.qortal.network.message.GetTransactionMessage;
import org.qortal.network.message.GetUnconfirmedTransactionsMessage;
import org.qortal.network.message.HeightMessage;
import org.qortal.network.message.HeightV2Message;
import org.qortal.network.message.Message;
import org.qortal.network.message.OnlineAccountsMessage;
import org.qortal.network.message.SignaturesMessage;
import org.qortal.network.message.TransactionMessage;
import org.qortal.network.message.TransactionSignaturesMessage;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryFactory;
import org.qortal.repository.RepositoryManager;
import org.qortal.repository.hsqldb.HSQLDBRepositoryFactory;
import org.qortal.settings.Settings;
import org.qortal.transaction.ArbitraryTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.utils.Base58;
import org.qortal.utils.ByteArray;
import org.qortal.utils.NTP;
import org.qortal.utils.Triple;

import com.google.common.primitives.Longs;

public class Controller extends Thread {

	static {
		// This must go before any calls to LogManager/Logger
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
	}

	/** Controller start-up time (ms) taken using <tt>System.currentTimeMillis()</tt>. */
	public static final long startTime = System.currentTimeMillis();
	public static final String VERSION_PREFIX = "qortal-";

	private static final Logger LOGGER = LogManager.getLogger(Controller.class);
	private static final long MISBEHAVIOUR_COOLOFF = 10 * 60 * 1000L; // ms
	private static final int MAX_BLOCKCHAIN_TIP_AGE = 5; // blocks
	private static final Object shutdownLock = new Object();
	private static final String repositoryUrlTemplate = "jdbc:hsqldb:file:%s" + File.separator + "blockchain;create=true;hsqldb.full_log_replay=true";
	private static final long ARBITRARY_REQUEST_TIMEOUT = 5 * 1000L; // ms
	private static final long REPOSITORY_BACKUP_PERIOD = 123 * 60 * 1000L; // ms
	private static final long NTP_PRE_SYNC_CHECK_PERIOD = 5 * 1000L; // ms
	private static final long NTP_POST_SYNC_CHECK_PERIOD = 5 * 60 * 1000L; // ms
	private static final long DELETE_EXPIRED_INTERVAL = 5 * 60 * 1000L; // ms

	// To do with online accounts list
	private static final long ONLINE_ACCOUNTS_TASKS_INTERVAL = 10 * 1000L; // ms
	private static final long ONLINE_ACCOUNTS_BROADCAST_INTERVAL = 1 * 60 * 1000L; // ms
	private static final long ONLINE_TIMESTAMP_MODULUS = 5 * 60 * 1000L;
	private static final long LAST_SEEN_EXPIRY_PERIOD = (ONLINE_TIMESTAMP_MODULUS * 2) + (1 * 60 * 1000L);

	private static volatile boolean isStopping = false;
	private static BlockMinter blockMinter = null;
	private static volatile boolean requestSync = false;
	private static volatile boolean requestSysTrayUpdate = true;
	private static Controller instance;

	private final String buildVersion;
	private final long buildTimestamp; // seconds
	private final String[] savedArgs;

	private volatile BlockData chainTip = null;

	private long repositoryBackupTimestamp = startTime + REPOSITORY_BACKUP_PERIOD; // ms
	private long ntpCheckTimestamp = startTime; // ms
	private long deleteExpiredTimestamp = startTime + DELETE_EXPIRED_INTERVAL; // ms
	private long onlineAccountsTasksTimestamp = startTime + ONLINE_ACCOUNTS_TASKS_INTERVAL; // ms

	/** Whether we can mint new blocks, as reported by BlockMinter. */
	private volatile boolean isMintingPossible = false;

	/** Latest block signatures from other peers that we know are on inferior chains. */
	List<ByteArray> inferiorChainSignatures = new ArrayList<>();

	/**
	 * Map of recent requests for ARBITRARY transaction data payloads.
	 * <p>
	 * Key is original request's message ID<br>
	 * Value is Triple&lt;transaction signature in base58, first requesting peer, first request's timestamp&gt;
	 * <p>
	 * If peer is null then either:<br>
	 * <ul>
	 * <li>we are the original requesting peer</li>
	 * <li>we have already sent data payload to original requesting peer.</li>
	 * </ul>
	 * If signature is null then we have already received the data payload and either:<br>
	 * <ul>
	 * <li>we are the original requesting peer and have saved it locally</li>
	 * <li>we have forwarded the data payload (and maybe also saved it locally)</li>
	 * </ul>
	 */
	private Map<Integer, Triple<String, Peer, Long>> arbitraryDataRequests = Collections.synchronizedMap(new HashMap<>());

	/** Lock for only allowing one blockchain-modifying codepath at a time. e.g. synchronization or newly minted block. */
	private final ReentrantLock blockchainLock = new ReentrantLock();

	/** Cache of 'online accounts' */
	List<OnlineAccountData> onlineAccounts = new ArrayList<>();

	// Constructors

	private Controller(String[] args) {
		Properties properties = new Properties();
		try (InputStream in = this.getClass().getResourceAsStream("/build.properties")) {
			properties.load(in);
		} catch (IOException e) {
			throw new RuntimeException("Can't read build.properties resource", e);
		}

		String buildTimestampProperty = properties.getProperty("build.timestamp");
		if (buildTimestampProperty == null)
			throw new RuntimeException("Can't read build.timestamp from build.properties resource");

		this.buildTimestamp = LocalDateTime.parse(buildTimestampProperty, DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).toEpochSecond(ZoneOffset.UTC);
		LOGGER.info(String.format("Build timestamp: %s", buildTimestampProperty));

		String buildVersionProperty = properties.getProperty("build.version");
		if (buildVersionProperty == null)
			throw new RuntimeException("Can't read build.version from build.properties resource");

		this.buildVersion = VERSION_PREFIX + buildVersionProperty;
		LOGGER.info(String.format("Build version: %s", this.buildVersion));

		this.savedArgs = args;
	}

	private static synchronized Controller newInstance(String[] args) {
		instance = new Controller(args);
		return instance;
	}

	public static synchronized Controller getInstance() {
		if (instance == null)
			instance = new Controller(null);

		return instance;
	}

	// Getters / setters

	public static String getRepositoryUrl() {
		return String.format(repositoryUrlTemplate, Settings.getInstance().getRepositoryPath());
	}

	public long getBuildTimestamp() {
		return this.buildTimestamp;
	}

	public String getVersionString() {
		return this.buildVersion;
	}

	/** Returns current blockchain height, or 0 if it's not available. */
	public int getChainHeight() {
		BlockData blockData = this.chainTip;
		if (blockData == null)
			return 0;

		return blockData.getHeight();
	}

	/** Returns highest block, or null if it's not available. */
	public BlockData getChainTip() {
		return this.chainTip;
	}

	/** Cache new blockchain tip, and also wipe cache of online accounts. */
	public void setChainTip(BlockData blockData) {
		this.chainTip = blockData;
	}

	public ReentrantLock getBlockchainLock() {
		return this.blockchainLock;
	}

	/* package */ String[] getSavedArgs() {
		return this.savedArgs;
	}

	// Entry point

	public static void main(String[] args) {
		LOGGER.info("Starting up...");

		// Potential GUI startup with splash screen, etc.
		Gui.getInstance();

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);

		// Load/check settings, which potentially sets up blockchain config, etc.
		try {
			if (args.length > 0)
				Settings.fileInstance(args[0]);
			else
				Settings.getInstance();
		} catch (Throwable t) {
			Gui.getInstance().fatalError("Settings file", t.getMessage());
			return; // Not System.exit() so that GUI can display error
		}

		Controller.newInstance(args);

		LOGGER.info("Starting NTP");
		NTP.start();

		LOGGER.info("Starting repository");
		try {
			RepositoryFactory repositoryFactory = new HSQLDBRepositoryFactory(getRepositoryUrl());
			RepositoryManager.setRepositoryFactory(repositoryFactory);
		} catch (DataException e) {
			// If exception has no cause then repository is in use by some other process.
			if (e.getCause() == null) {
				LOGGER.info("Repository in use by another process?");
				Gui.getInstance().fatalError("Repository issue", "Repository in use by another process?");
			} else {
				LOGGER.error("Unable to start repository", e);
				Gui.getInstance().fatalError("Repository issue", e);
			}

			return; // Not System.exit() so that GUI can display error
		}

		LOGGER.info("Validating blockchain");
		try {
			BlockChain.validate();

			// Set initial chain height/tip
			try (final Repository repository = RepositoryManager.getRepository()) {
				BlockData blockData = repository.getBlockRepository().getLastBlock();

				Controller.getInstance().setChainTip(blockData);
				LOGGER.info(String.format("Our chain height at start-up: %d", blockData.getHeight()));
			}
		} catch (DataException e) {
			LOGGER.error("Couldn't validate blockchain", e);
			Gui.getInstance().fatalError("Blockchain validation issue", e);
			return; // Not System.exit() so that GUI can display error
		}

		LOGGER.info("Starting controller");
		Controller.getInstance().start();

		LOGGER.info(String.format("Starting networking on port %d", Settings.getInstance().getListenPort()));
		try {
			Network network = Network.getInstance();
			network.start();
		} catch (IOException e) {
			LOGGER.error("Unable to start networking", e);
			Controller.getInstance().shutdown();
			Gui.getInstance().fatalError("Networking failure", e);
			return; // Not System.exit() so that GUI can display error
		}

		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				Thread.currentThread().setName("Shutdown hook");

				Controller.getInstance().shutdown();
			}
		});

		LOGGER.info("Starting block minter");
		blockMinter = new BlockMinter();
		blockMinter.start();

		// Arbitrary transaction data manager
		// LOGGER.info("Starting arbitrary-transaction data manager");
		// ArbitraryDataManager.getInstance().start();

		// Auto-update service?
		if (Settings.getInstance().isAutoUpdateEnabled()) {
			LOGGER.info("Starting auto-update");
			AutoUpdate.getInstance().start();
		}

		LOGGER.info(String.format("Starting API on port %d", Settings.getInstance().getApiPort()));
		try {
			ApiService apiService = ApiService.getInstance();
			apiService.start();
		} catch (Exception e) {
			LOGGER.error("Unable to start API", e);
			Controller.getInstance().shutdown();
			Gui.getInstance().fatalError("API failure", e);
			return; // Not System.exit() so that GUI can display error
		}

		// If GUI is enabled, we're no longer starting up but actually running now
		Gui.getInstance().notifyRunning();
	}

	/** Called by AdvancedInstaller's launch EXE in single-instance mode, when an instance is already running. */
	public static void secondaryMain(String[] args) {
		// Return as we don't want to run more than one instance
	}


	// Main thread

	@Override
	public void run() {
		Thread.currentThread().setName("Controller");

		try {
			while (!isStopping) {
				// Maybe update SysTray
				if (requestSysTrayUpdate) {
					requestSysTrayUpdate = false;
					updateSysTray();
				}

				Thread.sleep(1000);

				final long now = System.currentTimeMillis();

				// Check NTP status
				if (now >= ntpCheckTimestamp) {
					Long ntpTime = NTP.getTime();

					if (ntpTime != null) {
						if (ntpTime != now)
							// Only log if non-zero offset
							LOGGER.info(String.format("Adjusting system time by NTP offset: %dms", ntpTime - now));

						ntpCheckTimestamp = now + NTP_POST_SYNC_CHECK_PERIOD;
						requestSysTrayUpdate = true;
					} else {
						LOGGER.info(String.format("No NTP offset yet"));
						ntpCheckTimestamp = now + NTP_PRE_SYNC_CHECK_PERIOD;
						// We can't do much without a valid NTP time
						continue;
					}
				}

				if (requestSync) {
					requestSync = false;
					potentiallySynchronize();
				}

				// Clean up arbitrary data request cache
				final long requestMinimumTimestamp = now - ARBITRARY_REQUEST_TIMEOUT;
				arbitraryDataRequests.entrySet().removeIf(entry -> entry.getValue().getC() < requestMinimumTimestamp);

				// Give repository a chance to backup
				if (now >= repositoryBackupTimestamp) {
					repositoryBackupTimestamp = now + REPOSITORY_BACKUP_PERIOD;
					RepositoryManager.backup(true);
				}

				// Prune stuck/slow/old peers
				try {
					Network.getInstance().prunePeers();
				} catch (DataException e) {
					LOGGER.warn(String.format("Repository issue when trying to prune peers: %s", e.getMessage()));
				}

				// Delete expired transactions
				if (now >= deleteExpiredTimestamp) {
					deleteExpiredTimestamp = now + DELETE_EXPIRED_INTERVAL;
					deleteExpiredTransactions();
				}

				// Perform tasks to do with managing online accounts list
				if (now >= onlineAccountsTasksTimestamp) {
					onlineAccountsTasksTimestamp = now + ONLINE_ACCOUNTS_TASKS_INTERVAL;
					performOnlineAccountsTasks();
				}
			}
		} catch (InterruptedException e) {
			// Fall-through to exit
		}
	}

	public static final Predicate<Peer> hasMisbehaved = peer -> {
		final Long lastMisbehaved = peer.getPeerData().getLastMisbehaved();
		return lastMisbehaved != null && lastMisbehaved > NTP.getTime() - MISBEHAVIOUR_COOLOFF;
	};

	public static final Predicate<Peer> hasNoRecentBlock = peer -> {
		final Long minLatestBlockTimestamp = getMinimumLatestBlockTimestamp();
		final PeerChainTipData peerChainTipData = peer.getChainTipData();
		return peerChainTipData == null || peerChainTipData.getLastBlockTimestamp() == null || peerChainTipData.getLastBlockTimestamp() < minLatestBlockTimestamp;
	};

	public static final Predicate<Peer> hasNoOrSameBlock = peer -> {
		final BlockData latestBlockData = getInstance().getChainTip();
		final PeerChainTipData peerChainTipData = peer.getChainTipData();
		return peerChainTipData == null || peerChainTipData.getLastBlockSignature() == null || Arrays.equals(latestBlockData.getSignature(), peerChainTipData.getLastBlockSignature());
	};

	public static final Predicate<Peer> hasOnlyGenesisBlock = peer -> {
		final PeerChainTipData peerChainTipData = peer.getChainTipData();
		return peerChainTipData == null || peerChainTipData.getLastHeight() == null || peerChainTipData.getLastHeight() == 1;
	};

	public static final Predicate<Peer> hasInferiorChainTip = peer -> {
		final PeerChainTipData peerChainTipData = peer.getChainTipData();
		final List<ByteArray> inferiorChainTips = getInstance().inferiorChainSignatures;
		return peerChainTipData == null || peerChainTipData.getLastBlockSignature() == null || inferiorChainTips.contains(new ByteArray(peerChainTipData.getLastBlockSignature()));
	};

	private void potentiallySynchronize() throws InterruptedException {
		List<Peer> peers = Network.getInstance().getUniqueHandshakedPeers();

		// Disregard peers that have "misbehaved" recently
		peers.removeIf(hasMisbehaved);

		// Disregard peers that only have genesis block
		peers.removeIf(hasOnlyGenesisBlock);

		// Disregard peers that don't have a recent block
		peers.removeIf(hasNoRecentBlock);

		// Check we have enough peers to potentially synchronize
		if (peers.size() < Settings.getInstance().getMinBlockchainPeers())
			return;

		// Disregard peers that have no block signature or the same block signature as us
		peers.removeIf(hasNoOrSameBlock);

		// Disregard peers that are on the same block as last sync attempt and we didn't like their chain
		peers.removeIf(hasInferiorChainTip);

		if (peers.isEmpty())
			return;

		// Pick random peer to sync with
		int index = new SecureRandom().nextInt(peers.size());
		Peer peer = peers.get(index);

		actuallySynchronize(peer, false);
	}

	public SynchronizationResult actuallySynchronize(Peer peer, boolean force) throws InterruptedException {
		BlockData latestBlockData = getChainTip();

		SynchronizationResult syncResult = Synchronizer.getInstance().synchronize(peer, force);
		switch (syncResult) {
			case GENESIS_ONLY:
			case NO_COMMON_BLOCK:
			case TOO_DIVERGENT:
			case INVALID_DATA: {
				// These are more serious results that warrant a cool-off
				LOGGER.info(String.format("Failed to synchronize with peer %s (%s) - cooling off", peer, syncResult.name()));

				// Don't use this peer again for a while
				PeerData peerData = peer.getPeerData();
				peerData.setLastMisbehaved(NTP.getTime());

				// Only save to repository if outbound peer
				if (peer.isOutbound())
					try (final Repository repository = RepositoryManager.getRepository()) {
						repository.getNetworkRepository().save(peerData);
						repository.saveChanges();
					} catch (DataException e) {
						LOGGER.warn("Repository issue while updating peer synchronization info", e);
					}
				break;
			}

			case INFERIOR_CHAIN: {
				// Update our list of inferior chain tips
				ByteArray inferiorChainSignature = new ByteArray(peer.getChainTipData().getLastBlockSignature());
				if (!inferiorChainSignatures.contains(inferiorChainSignature))
					inferiorChainSignatures.add(inferiorChainSignature);

				// These are minor failure results so fine to try again
				LOGGER.debug(() -> String.format("Refused to synchronize with peer %s (%s)", peer, syncResult.name()));

				// Notify peer of our superior chain
				if (!peer.sendMessage(Network.getInstance().buildHeightMessage(peer, latestBlockData)))
					peer.disconnect("failed to notify peer of our superior chain");
				break;
			}

			case NO_REPLY:
			case NO_BLOCKCHAIN_LOCK:
			case REPOSITORY_ISSUE:
				// These are minor failure results so fine to try again
				LOGGER.debug(() -> String.format("Failed to synchronize with peer %s (%s)", peer, syncResult.name()));
				break;

			case OK:
				requestSysTrayUpdate = true;
				// fall-through...
			case NOTHING_TO_DO: {
				// Update our list of inferior chain tips
				ByteArray inferiorChainSignature = new ByteArray(peer.getChainTipData().getLastBlockSignature());
				if (!inferiorChainSignatures.contains(inferiorChainSignature))
					inferiorChainSignatures.add(inferiorChainSignature);

				LOGGER.debug(() -> String.format("Synchronized with peer %s (%s)", peer, syncResult.name()));
				break;
			}
		}

		// Has our chain tip changed?
		BlockData newLatestBlockData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			newLatestBlockData = repository.getBlockRepository().getLastBlock();
			this.setChainTip(newLatestBlockData);
		} catch (DataException e) {
			LOGGER.warn(String.format("Repository issue when trying to fetch post-synchronization chain tip: %s", e.getMessage()));
			return syncResult;
		}

		if (!Arrays.equals(newLatestBlockData.getSignature(), latestBlockData.getSignature())) {
			// Broadcast our new chain tip
			Network.getInstance().broadcast(recipientPeer -> Network.getInstance().buildHeightMessage(recipientPeer, newLatestBlockData));

			// Reset our cache of inferior chains
			inferiorChainSignatures.clear();
		}

		return syncResult;
	}

	private void updateSysTray() {
		if (NTP.getTime() == null) {
			SysTray.getInstance().setToolTipText(Translator.INSTANCE.translate("SysTray", "SYNCHRONIZING_CLOCK"));
			return;
		}

		final int numberOfPeers = Network.getInstance().getUniqueHandshakedPeers().size();

		final int height = getChainHeight();

		String connectionsText = Translator.INSTANCE.translate("SysTray", numberOfPeers != 1 ? "CONNECTIONS" : "CONNECTION");
		String heightText = Translator.INSTANCE.translate("SysTray", "BLOCK_HEIGHT");
		String mintingText = Translator.INSTANCE.translate("SysTray", isMintingPossible ? "MINTING_ENABLED" : "MINTING_DISABLED");

		String tooltip = String.format("%s - %d %s - %s %d", mintingText, numberOfPeers, connectionsText, heightText, height);
		SysTray.getInstance().setToolTipText(tooltip);
	}

	public void deleteExpiredTransactions() {
		final Long now = NTP.getTime();
		if (now == null)
			return;

		// This isn't critical so don't block for repository instance.
		try (final Repository repository = RepositoryManager.tryRepository()) {
			if (repository == null)
				return;

			List<TransactionData> transactions = repository.getTransactionRepository().getUnconfirmedTransactions();

			for (TransactionData transactionData : transactions)
				if (now >= Transaction.getDeadline(transactionData)) {
					LOGGER.info(String.format("Deleting expired, unconfirmed transaction %s", Base58.encode(transactionData.getSignature())));
					repository.getTransactionRepository().delete(transactionData);
				}

			repository.saveChanges();
		} catch (DataException e) {
			LOGGER.error("Repository issue while deleting expired unconfirmed transactions", e);
		}
	}

	// Shutdown

	public void shutdown() {
		synchronized (shutdownLock) {
			if (!isStopping) {
				isStopping = true;

				LOGGER.info("Shutting down API");
				ApiService.getInstance().stop();

				if (Settings.getInstance().isAutoUpdateEnabled()) {
					LOGGER.info("Shutting down auto-update");
					AutoUpdate.getInstance().shutdown();
				}

				// Arbitrary transaction data manager
				// LOGGER.info("Shutting down arbitrary-transaction data manager");
				// ArbitraryDataManager.getInstance().shutdown();

				if (blockMinter != null) {
					LOGGER.info("Shutting down block minter");
					blockMinter.shutdown();
					try {
						blockMinter.join();
					} catch (InterruptedException e) {
						// We were interrupted while waiting for thread to join
					}
				}

				LOGGER.info("Shutting down networking");
				Network.getInstance().shutdown();

				LOGGER.info("Shutting down controller");
				this.interrupt();
				try {
					this.join();
				} catch (InterruptedException e) {
					// We were interrupted while waiting for thread to join
				}

				try {
					LOGGER.info("Shutting down repository");
					RepositoryManager.closeRepositoryFactory();
				} catch (DataException e) {
					LOGGER.error("Error occurred while shutting down repository", e);
				}

				LOGGER.info("Shutting down NTP");
				NTP.shutdownNow();

				LOGGER.info("Shutdown complete!");
			}
		}
	}

	public void shutdownAndExit() {
		this.shutdown();
		System.exit(0);
	}

	// Callbacks for/from network

	public void doNetworkBroadcast() {
		Network network = Network.getInstance();

		// Send (if outbound) / Request peer lists
		network.broadcast(peer -> peer.isOutbound() ? network.buildPeersMessage(peer) : new GetPeersMessage());

		// Send our current height
		BlockData latestBlockData = getChainTip();
		network.broadcast(peer -> network.buildHeightMessage(peer, latestBlockData));

		// Send (if outbound) / Request unconfirmed transaction signatures
		network.broadcast(network::buildGetUnconfirmedTransactionsMessage);
	}

	public void onMintingPossibleChange(boolean isMintingPossible) {
		this.isMintingPossible = isMintingPossible;
		requestSysTrayUpdate = true;
	}

	public void onBlockMinted() {
		// Broadcast our new height info
		BlockData latestBlockData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			latestBlockData = repository.getBlockRepository().getLastBlock();
			this.setChainTip(latestBlockData);
		} catch (DataException e) {
			LOGGER.warn(String.format("Repository issue when trying to fetch post-mint chain tip: %s", e.getMessage()));
			return;
		}

		Network network = Network.getInstance();
		network.broadcast(peer -> network.buildHeightMessage(peer, latestBlockData));

		requestSysTrayUpdate = true;
	}

	public void onNewTransaction(TransactionData transactionData) {
		// Send round to all peers
		Network network = Network.getInstance();
		network.broadcast(peer -> network.buildNewTransactionMessage(peer, transactionData));
	}

	public void onPeerHandshakeCompleted(Peer peer) {
		// Only send if outbound
		if (peer.isOutbound()) {
			if (peer.getVersion() < 2) {
				// Legacy mode

				// Send our unconfirmed transactions
				try (final Repository repository = RepositoryManager.getRepository()) {
					List<TransactionData> transactions = repository.getTransactionRepository().getUnconfirmedTransactions();

					for (TransactionData transactionData : transactions) {
						Message transactionMessage = new TransactionMessage(transactionData);
						if (!peer.sendMessage(transactionMessage)) {
							peer.disconnect("failed to send unconfirmed transaction");
							return;
						}
					}
				} catch (DataException e) {
					LOGGER.error("Repository issue while sending unconfirmed transactions", e);
				}
			} else {
				// V2 protocol

				// Request peer's unconfirmed transactions
				Message message = new GetUnconfirmedTransactionsMessage();
				if (!peer.sendMessage(message)) {
					peer.disconnect("failed to send request for unconfirmed transactions");
					return;
				}
			}
		}

		requestSysTrayUpdate = true;
	}

	public void onPeerDisconnect(Peer peer) {
		requestSysTrayUpdate = true;
	}

	public void onNetworkMessage(Peer peer, Message message) {
		LOGGER.trace(() -> String.format("Processing %s message from %s", message.getType().name(), peer));

		switch (message.getType()) {
			case BLOCK: {
				// From a v1 peer, with no message ID, this is a broadcast of peer's latest block

				// Not version 1?
				if (peer.getVersion() == null || peer.getVersion() > 1)
					break;

				// Message ID present?
				if (message.hasId())
					break;

				BlockMessage blockMessage = (BlockMessage) message;
				BlockData blockData = blockMessage.getBlockData();

				// Update all peers with same ID

				List<Peer> connectedPeers = Network.getInstance().getHandshakedPeers();
				for (Peer connectedPeer : connectedPeers) {
					// Skip connectedPeer if they have no ID or their ID doesn't match sender's ID
					if (connectedPeer.getPeerId() == null || !Arrays.equals(connectedPeer.getPeerId(), peer.getPeerId()))
						continue;

					// Update peer chain tip data
					PeerChainTipData newChainTipData = new PeerChainTipData(blockData.getHeight(), blockData.getSignature(), blockData.getTimestamp(), blockData.getMinterPublicKey());
					connectedPeer.setChainTipData(newChainTipData);
				}

				// Potentially synchronize
				requestSync = true;

				break;
			}

			case HEIGHT: {
				HeightMessage heightMessage = (HeightMessage) message;

				// Update all peers with same ID

				List<Peer> connectedPeers = Network.getInstance().getHandshakedPeers();
				for (Peer connectedPeer : connectedPeers) {
					if (connectedPeer.getPeerId() == null || !Arrays.equals(connectedPeer.getPeerId(), peer.getPeerId()))
						continue;

					// Update peer chain tip data
					PeerChainTipData newChainTipData = new PeerChainTipData(heightMessage.getHeight(), null, null, null);
					connectedPeer.setChainTipData(newChainTipData);
				}

				// Potentially synchronize
				requestSync = true;

				break;
			}

			case HEIGHT_V2: {
				HeightV2Message heightV2Message = (HeightV2Message) message;

				// If peer is inbound and we've not updated their height
				// then this is probably their initial HEIGHT_V2 message
				// so they need a corresponding HEIGHT_V2 message from us
				if (!peer.isOutbound() && (peer.getChainTipData() == null || peer.getChainTipData().getLastHeight() == null))
					peer.sendMessage(Network.getInstance().buildHeightMessage(peer, getChainTip()));

				// Update all peers with same ID

				List<Peer> connectedPeers = Network.getInstance().getHandshakedPeers();
				for (Peer connectedPeer : connectedPeers) {
					// Skip connectedPeer if they have no ID or their ID doesn't match sender's ID
					if (connectedPeer.getPeerId() == null || !Arrays.equals(connectedPeer.getPeerId(), peer.getPeerId()))
						continue;

					// Update peer chain tip data
					PeerChainTipData newChainTipData = new PeerChainTipData(heightV2Message.getHeight(), heightV2Message.getSignature(), heightV2Message.getTimestamp(), heightV2Message.getMinterPublicKey());
					connectedPeer.setChainTipData(newChainTipData);
				}

				// Potentially synchronize
				requestSync = true;

				break;
			}

			case GET_SIGNATURES: {
				GetSignaturesMessage getSignaturesMessage = (GetSignaturesMessage) message;
				byte[] parentSignature = getSignaturesMessage.getParentSignature();

				try (final Repository repository = RepositoryManager.getRepository()) {
					List<byte[]> signatures = new ArrayList<>();

					do {
						BlockData blockData = repository.getBlockRepository().fromReference(parentSignature);

						if (blockData == null)
							break;

						parentSignature = blockData.getSignature();
						signatures.add(parentSignature);
					} while (signatures.size() < Network.MAX_SIGNATURES_PER_REPLY);

					Message signaturesMessage = new SignaturesMessage(signatures);
					signaturesMessage.setId(message.getId());
					if (!peer.sendMessage(signaturesMessage))
						peer.disconnect("failed to send signatures");
				} catch (DataException e) {
					LOGGER.error(String.format("Repository issue while sending signatures after %s to peer %s", Base58.encode(parentSignature), peer), e);
				}

				break;
			}

			case GET_SIGNATURES_V2: {
				GetSignaturesV2Message getSignaturesMessage = (GetSignaturesV2Message) message;
				byte[] parentSignature = getSignaturesMessage.getParentSignature();

				try (final Repository repository = RepositoryManager.getRepository()) {
					List<byte[]> signatures = new ArrayList<>();

					do {
						BlockData blockData = repository.getBlockRepository().fromReference(parentSignature);

						if (blockData == null)
							break;

						parentSignature = blockData.getSignature();
						signatures.add(parentSignature);
					} while (signatures.size() < getSignaturesMessage.getNumberRequested());

					Message signaturesMessage = new SignaturesMessage(signatures);
					signaturesMessage.setId(message.getId());
					if (!peer.sendMessage(signaturesMessage))
						peer.disconnect("failed to send signatures (v2)");
				} catch (DataException e) {
					LOGGER.error(String.format("Repository issue while sending V2 signatures after %s to peer %s", Base58.encode(parentSignature), peer), e);
				}

				break;
			}

			case GET_BLOCK: {
				GetBlockMessage getBlockMessage = (GetBlockMessage) message;
				byte[] signature = getBlockMessage.getSignature();

				try (final Repository repository = RepositoryManager.getRepository()) {
					BlockData blockData = repository.getBlockRepository().fromSignature(signature);
					if (blockData == null) {
						LOGGER.debug(() -> String.format("Ignoring GET_BLOCK request from peer %s for unknown block %s", peer, Base58.encode(signature)));
						// Send no response at all???
						break;
					}

					Block block = new Block(repository, blockData);

					Message blockMessage = new BlockMessage(block);
					blockMessage.setId(message.getId());
					if (!peer.sendMessage(blockMessage))
						peer.disconnect("failed to send block");
				} catch (DataException e) {
					LOGGER.error(String.format("Repository issue while send block %s to peer %s", Base58.encode(signature), peer), e);
				}

				break;
			}

			case GET_TRANSACTION: {
				GetTransactionMessage getTransactionMessage = (GetTransactionMessage) message;
				byte[] signature = getTransactionMessage.getSignature();

				try (final Repository repository = RepositoryManager.getRepository()) {
					TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
					if (transactionData == null) {
						LOGGER.debug(() -> String.format("Ignoring GET_TRANSACTION request from peer %s for unknown transaction %s", peer, Base58.encode(signature)));
						// Send no response at all???
						break;
					}

					Message transactionMessage = new TransactionMessage(transactionData);
					transactionMessage.setId(message.getId());
					if (!peer.sendMessage(transactionMessage))
						peer.disconnect("failed to send transaction");
				} catch (DataException e) {
					LOGGER.error(String.format("Repository issue while send transaction %s to peer %s", Base58.encode(signature), peer), e);
				}

				break;
			}

			case TRANSACTION: {
				TransactionMessage transactionMessage = (TransactionMessage) message;
				TransactionData transactionData = transactionMessage.getTransactionData();

				try (final Repository repository = RepositoryManager.getRepository()) {
					Transaction transaction = Transaction.fromData(repository, transactionData);

					// Check signature
					if (!transaction.isSignatureValid()) {
						LOGGER.trace(() -> String.format("Ignoring %s transaction %s with invalid signature from peer %s", transactionData.getType().name(), Base58.encode(transactionData.getSignature()), peer));
						break;
					}

					ValidationResult validationResult = transaction.importAsUnconfirmed();

					if (validationResult == ValidationResult.TRANSACTION_ALREADY_EXISTS) {
						LOGGER.trace(() -> String.format("Ignoring existing transaction %s from peer %s", Base58.encode(transactionData.getSignature()), peer));
						break;
					}

					if (validationResult == ValidationResult.NO_BLOCKCHAIN_LOCK) {
						LOGGER.trace(() -> String.format("Couldn't lock blockchain to import unconfirmed transaction %s from peer %s", Base58.encode(transactionData.getSignature()), peer));
						break;
					}

					if (validationResult != ValidationResult.OK) {
						LOGGER.trace(() -> String.format("Ignoring invalid (%s) %s transaction %s from peer %s", validationResult.name(), transactionData.getType().name(), Base58.encode(transactionData.getSignature()), peer));
						break;
					}

					LOGGER.debug(() -> String.format("Imported %s transaction %s from peer %s", transactionData.getType().name(), Base58.encode(transactionData.getSignature()), peer));
				} catch (DataException e) {
					LOGGER.error(String.format("Repository issue while processing transaction %s from peer %s", Base58.encode(transactionData.getSignature()), peer), e);
				}

				break;
			}

			case GET_UNCONFIRMED_TRANSACTIONS: {
				try (final Repository repository = RepositoryManager.getRepository()) {
					List<byte[]> signatures = repository.getTransactionRepository().getUnconfirmedTransactionSignatures();

					Message transactionSignaturesMessage = new TransactionSignaturesMessage(signatures);
					if (!peer.sendMessage(transactionSignaturesMessage))
						peer.disconnect("failed to send unconfirmed transaction signatures");
				} catch (DataException e) {
					LOGGER.error(String.format("Repository issue while sending unconfirmed transaction signatures to peer %s", peer), e);
				}
				break;
			}

			case TRANSACTION_SIGNATURES: {
				TransactionSignaturesMessage transactionSignaturesMessage = (TransactionSignaturesMessage) message;
				List<byte[]> signatures = transactionSignaturesMessage.getSignatures();
				List<byte[]> newSignatures = new ArrayList<>();

				try (final Repository repository = RepositoryManager.getRepository()) {
					for (byte[] signature : signatures) {
						// Do we have it already? (Before requesting transaction data itself)
						if (repository.getTransactionRepository().exists(signature)) {
							LOGGER.trace(() -> String.format("Ignoring existing transaction %s from peer %s", Base58.encode(signature), peer));
							continue;
						}

						// Check isInterrupted() here and exit fast
						if (Thread.currentThread().isInterrupted())
							return;

						// Fetch actual transaction data from peer
						Message getTransactionMessage = new GetTransactionMessage(signature);
						Message responseMessage = peer.getResponse(getTransactionMessage);
						if (!(responseMessage instanceof TransactionMessage)) {
							// Maybe peer no longer has this transaction
							LOGGER.trace(() -> String.format("Peer %s didn't send transaction %s", peer, Base58.encode(signature)));
							continue;
						}

						// Check isInterrupted() here and exit fast
						if (Thread.currentThread().isInterrupted())
							return;

						TransactionMessage transactionMessage = (TransactionMessage) responseMessage;
						TransactionData transactionData = transactionMessage.getTransactionData();
						Transaction transaction = Transaction.fromData(repository, transactionData);

						// Check signature
						if (!transaction.isSignatureValid()) {
							LOGGER.trace(() -> String.format("Ignoring %s transaction %s with invalid signature from peer %s", transactionData.getType().name(), Base58.encode(transactionData.getSignature()), peer));
							continue;
						}

						ValidationResult validationResult = transaction.importAsUnconfirmed();

						if (validationResult == ValidationResult.TRANSACTION_ALREADY_EXISTS) {
							LOGGER.trace(() -> String.format("Ignoring existing transaction %s from peer %s", Base58.encode(transactionData.getSignature()), peer));
							continue;
						}

						if (validationResult == ValidationResult.NO_BLOCKCHAIN_LOCK) {
							LOGGER.trace(() -> String.format("Couldn't lock blockchain to import unconfirmed transaction %s from peer %s", Base58.encode(transactionData.getSignature()), peer));
							// Some other thread (e.g. Synchronizer) might have blockchain lock for a while so might as well give up for now
							break;
						}

						if (validationResult != ValidationResult.OK) {
							LOGGER.trace(() -> String.format("Ignoring invalid (%s) %s transaction %s from peer %s", validationResult.name(), transactionData.getType().name(), Base58.encode(transactionData.getSignature()), peer));
							continue;
						}

						LOGGER.debug(() -> String.format("Imported %s transaction %s from peer %s", transactionData.getType().name(), Base58.encode(transactionData.getSignature()), peer));

						// We could collate signatures that are new to us and broadcast them to our peers too
						newSignatures.add(signature);
					}
				} catch (DataException e) {
					LOGGER.error(String.format("Repository issue while processing unconfirmed transactions from peer %s", peer), e);
				} catch (InterruptedException e) {
					// Shutdown
					return;
				}

				if (newSignatures.isEmpty())
					break;

				// Broadcast signatures that are new to us
				Network.getInstance().broadcast(broadcastPeer -> broadcastPeer == peer ? null : new TransactionSignaturesMessage(newSignatures));

				break;
			}

			case GET_BLOCK_SUMMARIES: {
				GetBlockSummariesMessage getBlockSummariesMessage = (GetBlockSummariesMessage) message;
				byte[] parentSignature = getBlockSummariesMessage.getParentSignature();

				try (final Repository repository = RepositoryManager.getRepository()) {
					List<BlockSummaryData> blockSummaries = new ArrayList<>();

					int numberRequested = Math.min(Network.MAX_BLOCK_SUMMARIES_PER_REPLY, getBlockSummariesMessage.getNumberRequested());

					do {
						BlockData blockData = repository.getBlockRepository().fromReference(parentSignature);

						if (blockData == null)
							break;

						BlockSummaryData blockSummary = new BlockSummaryData(blockData);
						blockSummaries.add(blockSummary);
						parentSignature = blockData.getSignature();
					} while (blockSummaries.size() < numberRequested);

					Message blockSummariesMessage = new BlockSummariesMessage(blockSummaries);
					blockSummariesMessage.setId(message.getId());
					if (!peer.sendMessage(blockSummariesMessage))
						peer.disconnect("failed to send block summaries");
				} catch (DataException e) {
					LOGGER.error(String.format("Repository issue while sending block summaries after %s to peer %s", Base58.encode(parentSignature), peer), e);
				}

				break;
			}

			case GET_ARBITRARY_DATA: {
				GetArbitraryDataMessage getArbitraryDataMessage = (GetArbitraryDataMessage) message;

				byte[] signature = getArbitraryDataMessage.getSignature();
				String signature58 = Base58.encode(signature);
				Long timestamp = NTP.getTime();
				Triple<String, Peer, Long> newEntry = new Triple<>(signature58, peer, timestamp);

				// If we've seen this request recently, then ignore
				if (arbitraryDataRequests.putIfAbsent(message.getId(), newEntry) != null)
					break;

				// Do we even have this transaction?
				try (final Repository repository = RepositoryManager.getRepository()) {
					TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
					if (transactionData == null || transactionData.getType() != TransactionType.ARBITRARY)
						break;

					ArbitraryTransaction transaction = new ArbitraryTransaction(repository, transactionData);

					// If we have the data then send it
					if (transaction.isDataLocal()) {
						byte[] data = transaction.fetchData();
						if (data == null)
							break;

						// Update requests map to reflect that we've sent it
						newEntry = new Triple<>(signature58, null, timestamp);
						arbitraryDataRequests.put(message.getId(), newEntry);

						Message arbitraryDataMessage = new ArbitraryDataMessage(signature, data);
						arbitraryDataMessage.setId(message.getId());
						if (!peer.sendMessage(arbitraryDataMessage))
							peer.disconnect("failed to send arbitrary data");

						break;
					}

					// Ask our other peers if they have it
					Network.getInstance().broadcast(broadcastPeer -> broadcastPeer == peer ? null : message);
				} catch (DataException e) {
					LOGGER.error(String.format("Repository issue while finding arbitrary transaction data for peer %s", peer), e);
				}

				break;
			}

			case ARBITRARY_DATA: {
				ArbitraryDataMessage arbitraryDataMessage = (ArbitraryDataMessage) message;

				// Do we have a pending request for this data?
				Triple<String, Peer, Long> request = arbitraryDataRequests.get(message.getId());
				if (request == null || request.getA() == null)
					break;

				// Does this message's signature match what we're expecting?
				byte[] signature = arbitraryDataMessage.getSignature();
				String signature58 = Base58.encode(signature);
				if (!request.getA().equals(signature58))
					break;

				byte[] data = arbitraryDataMessage.getData();

				// Check transaction exists and payload hash is correct
				try (final Repository repository = RepositoryManager.getRepository()) {
					TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
					if (!(transactionData instanceof ArbitraryTransactionData))
						break;

					ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) transactionData;

					byte[] actualHash = Crypto.digest(data);

					// "data" from repository will always be hash of actual raw data
					if (!Arrays.equals(arbitraryTransactionData.getData(), actualHash))
						break;

					// Update requests map to reflect that we've received it
					Triple<String, Peer, Long> newEntry = new Triple<>(null, null, request.getC());
					arbitraryDataRequests.put(message.getId(), newEntry);

					// Save payload locally
					// TODO: storage policy
					arbitraryTransactionData.setDataType(DataType.RAW_DATA);
					arbitraryTransactionData.setData(data);
					repository.getArbitraryRepository().save(arbitraryTransactionData);
					repository.saveChanges();
				} catch (DataException e) {
					LOGGER.error(String.format("Repository issue while finding arbitrary transaction data for peer %s", peer), e);
				}

				Peer requestingPeer = request.getB();
				if (requestingPeer != null) {
					// Forward to requesting peer;
					if (!requestingPeer.sendMessage(arbitraryDataMessage))
						requestingPeer.disconnect("failed to forward arbitrary data");
				}

				break;
			}

			case GET_ONLINE_ACCOUNTS: {
				GetOnlineAccountsMessage getOnlineAccountsMessage = (GetOnlineAccountsMessage) message;

				List<OnlineAccountData> excludeAccounts = getOnlineAccountsMessage.getOnlineAccounts();

				// Send online accounts info, excluding entries with matching timestamp & public key from excludeAccounts
				List<OnlineAccountData> accountsToSend;
				synchronized (this.onlineAccounts) {
					accountsToSend = new ArrayList<>(this.onlineAccounts);
				}

				Iterator<OnlineAccountData> iterator = accountsToSend.iterator();

				SEND_ITERATOR:
				while (iterator.hasNext()) {
					OnlineAccountData onlineAccountData = iterator.next();

					for (int i = 0; i < excludeAccounts.size(); ++i) {
						OnlineAccountData excludeAccountData = excludeAccounts.get(i);

						if (onlineAccountData.getTimestamp() == excludeAccountData.getTimestamp() && Arrays.equals(onlineAccountData.getPublicKey(), excludeAccountData.getPublicKey())) {
							iterator.remove();
							continue SEND_ITERATOR;
						}
					}
				}

				Message onlineAccountsMessage = new OnlineAccountsMessage(accountsToSend);
				peer.sendMessage(onlineAccountsMessage);

				LOGGER.trace(() -> String.format("Sent %d of our %d online accounts to %s", accountsToSend.size(), this.onlineAccounts.size(), peer));

				break;
			}

			case ONLINE_ACCOUNTS: {
				OnlineAccountsMessage onlineAccountsMessage = (OnlineAccountsMessage) message;

				List<OnlineAccountData> peersOnlineAccounts = onlineAccountsMessage.getOnlineAccounts();
				LOGGER.trace(() -> String.format("Received %d online accounts from %s", peersOnlineAccounts.size(), peer));

				try (final Repository repository = RepositoryManager.getRepository()) {
					for (OnlineAccountData onlineAccountData : peersOnlineAccounts)
						this.verifyAndAddAccount(repository, onlineAccountData);
				} catch (DataException e) {
					LOGGER.error(String.format("Repository issue while verifying online accounts from peer %s", peer), e);
				}

				break;
			}

			default:
				LOGGER.debug(String.format("Unhandled %s message [ID %d] from peer %s", message.getType().name(), message.getId(), peer));
				break;
		}
	}

	// Utilities

	private void verifyAndAddAccount(Repository repository, OnlineAccountData onlineAccountData) throws DataException {
		final Long now = NTP.getTime();
		if (now == null)
			return;

		PublicKeyAccount otherAccount = new PublicKeyAccount(repository, onlineAccountData.getPublicKey());

		// Check timestamp is 'recent' here
		if (Math.abs(onlineAccountData.getTimestamp() - now) > ONLINE_TIMESTAMP_MODULUS * 2) {
			LOGGER.trace(() -> String.format("Rejecting online account %s with out of range timestamp %d", otherAccount.getAddress(), onlineAccountData.getTimestamp()));
			return;
		}

		// Verify
		byte[] data = Longs.toByteArray(onlineAccountData.getTimestamp());
		if (!otherAccount.verify(onlineAccountData.getSignature(), data)) {
			LOGGER.trace(() -> String.format("Rejecting invalid online account %s", otherAccount.getAddress()));
			return;
		}

		// Qortal: check online account is actually reward-share
		RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(onlineAccountData.getPublicKey());
		if (rewardShareData == null) {
			// Reward-share doesn't even exist - probably not a good sign
			LOGGER.trace(() -> String.format("Rejecting unknown online reward-share public key %s", Base58.encode(onlineAccountData.getPublicKey())));
			return;
		}

		PublicKeyAccount mintingAccount = new PublicKeyAccount(repository, rewardShareData.getMinterPublicKey());
		if (!mintingAccount.canMint()) {
			// Minting-account component of reward-share can no longer mint - disregard
			LOGGER.trace(() -> String.format("Rejecting online reward-share with non-minting account %s", mintingAccount.getAddress()));
			return;
		}

		synchronized (this.onlineAccounts) {
			OnlineAccountData existingAccountData = this.onlineAccounts.stream().filter(account -> Arrays.equals(account.getPublicKey(), onlineAccountData.getPublicKey())).findFirst().orElse(null);

			if (existingAccountData != null) {
				if (existingAccountData.getTimestamp() < onlineAccountData.getTimestamp()) {
					this.onlineAccounts.remove(existingAccountData);

					LOGGER.trace(() -> String.format("Updated online account %s with timestamp %d (was %d)", otherAccount.getAddress(), onlineAccountData.getTimestamp(), existingAccountData.getTimestamp()));
				} else {
					LOGGER.trace(() -> String.format("Not updating existing online account %s", otherAccount.getAddress()));

					return;
				}
			} else {
				LOGGER.trace(() -> String.format("Added online account %s with timestamp %d", otherAccount.getAddress(), onlineAccountData.getTimestamp()));
			}

			this.onlineAccounts.add(onlineAccountData);
		}
	}

	public void ensureTestingAccountOnline(PrivateKeyAccount mintingAccount) {
		if (!BlockChain.getInstance().isTestChain()) {
			LOGGER.warn("Ignoring attempt to ensure test account is online for non-test chain!");
			return;
		}

		final Long now = NTP.getTime();
		if (now == null)
			return;

		// Check mintingAccount is actually reward-share?

		// Add reward-share & timestamp to online accounts
		final long onlineAccountsTimestamp = Controller.toOnlineAccountTimestamp(now);
		byte[] timestampBytes = Longs.toByteArray(onlineAccountsTimestamp);

		byte[] signature = mintingAccount.sign(timestampBytes);
		byte[] publicKey = mintingAccount.getPublicKey();

		OnlineAccountData ourOnlineAccountData = new OnlineAccountData(onlineAccountsTimestamp, signature, publicKey);
		synchronized (this.onlineAccounts) {
			this.onlineAccounts.clear();
			this.onlineAccounts.add(ourOnlineAccountData);
		}
	}

	private void performOnlineAccountsTasks() {
		final Long now = NTP.getTime();
		if (now == null)
			return;

		// Expire old entries
		final long cutoffThreshold = now - LAST_SEEN_EXPIRY_PERIOD;
		synchronized (this.onlineAccounts) {
			Iterator<OnlineAccountData> iterator = this.onlineAccounts.iterator();
			while (iterator.hasNext()) {
				OnlineAccountData onlineAccountData = iterator.next();

				if (onlineAccountData.getTimestamp() < cutoffThreshold) {
					iterator.remove();

					LOGGER.trace(() -> {
						PublicKeyAccount otherAccount = new PublicKeyAccount(null, onlineAccountData.getPublicKey());
						return String.format("Removed expired online account %s with timestamp %d", otherAccount.getAddress(), onlineAccountData.getTimestamp());
					});
				}
			}
		}

		// Request data from other peers?
		if ((this.onlineAccountsTasksTimestamp % ONLINE_ACCOUNTS_BROADCAST_INTERVAL) < ONLINE_ACCOUNTS_TASKS_INTERVAL) {
			Message message;
			synchronized (this.onlineAccounts) {
				message = new GetOnlineAccountsMessage(this.onlineAccounts);
			}
			Network.getInstance().broadcast(peer -> message);
		}

		// Refresh our online accounts signatures?
		sendOurOnlineAccountsInfo();

		// Trim blockchain by removing 'old' online accounts signatures
		BlockChain.trimOldOnlineAccountsSignatures();
	}

	private void sendOurOnlineAccountsInfo() {
		final Long now = NTP.getTime();
		if (now == null)
			return;

		List<MintingAccountData> mintingAccounts;
		try (final Repository repository = RepositoryManager.getRepository()) {
			mintingAccounts = repository.getAccountRepository().getMintingAccounts();

			// We have no accounts, but don't reset timestamp
			if (mintingAccounts.isEmpty())
				return;

			// Only reward-share accounts allowed
			Iterator<MintingAccountData> iterator = mintingAccounts.iterator();
			while (iterator.hasNext()) {
				MintingAccountData mintingAccountData = iterator.next();

				RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(mintingAccountData.getPublicKey());
				if (rewardShareData == null) {
					// Reward-share doesn't even exist - probably not a good sign
					iterator.remove();
					continue;
				}

				PublicKeyAccount mintingAccount = new PublicKeyAccount(repository, rewardShareData.getMinterPublicKey());
				if (!mintingAccount.canMint()) {
					// Minting-account component of reward-share can no longer mint - disregard
					iterator.remove();
					continue;
				}
			}
		} catch (DataException e) {
			LOGGER.warn(String.format("Repository issue trying to fetch minting accounts: %s", e.getMessage()));
			return;
		}

		// 'current' timestamp
		final long onlineAccountsTimestamp = Controller.toOnlineAccountTimestamp(now);
		boolean hasInfoChanged = false;

		byte[] timestampBytes = Longs.toByteArray(onlineAccountsTimestamp);
		List<OnlineAccountData> ourOnlineAccounts = new ArrayList<>();

		MINTING_ACCOUNTS:
		for (MintingAccountData mintingAccountData : mintingAccounts) {
			PrivateKeyAccount mintingAccount = new PrivateKeyAccount(null, mintingAccountData.getPrivateKey());

			byte[] signature = mintingAccount.sign(timestampBytes);
			byte[] publicKey = mintingAccount.getPublicKey();

			// Our account is online
			OnlineAccountData ourOnlineAccountData = new OnlineAccountData(onlineAccountsTimestamp, signature, publicKey);
			synchronized (this.onlineAccounts) {
				Iterator<OnlineAccountData> iterator = this.onlineAccounts.iterator();
				while (iterator.hasNext()) {
					OnlineAccountData existingOnlineAccountData = iterator.next();

					if (Arrays.equals(existingOnlineAccountData.getPublicKey(), ourOnlineAccountData.getPublicKey())) {
						// If our online account is already present, with same timestamp, then move on to next mintingAccount
						if (existingOnlineAccountData.getTimestamp() == onlineAccountsTimestamp)
							continue MINTING_ACCOUNTS;

						// If our online account is already present, but with older timestamp, then remove it
						iterator.remove();
						break;
					}
				}

				this.onlineAccounts.add(ourOnlineAccountData);
			}

			LOGGER.trace(() -> String.format("Added our online account %s with timestamp %d", mintingAccount.getAddress(), onlineAccountsTimestamp));
			ourOnlineAccounts.add(ourOnlineAccountData);
			hasInfoChanged = true;
		}

		if (!hasInfoChanged)
			return;

		Message message = new OnlineAccountsMessage(ourOnlineAccounts);
		Network.getInstance().broadcast(peer -> message);

		LOGGER.trace(()-> String.format("Broadcasted %d online account%s with timestamp %d", ourOnlineAccounts.size(), (ourOnlineAccounts.size() != 1 ? "s" : ""), onlineAccountsTimestamp));
	}

	public static long toOnlineAccountTimestamp(long timestamp) {
		return (timestamp / ONLINE_TIMESTAMP_MODULUS) * ONLINE_TIMESTAMP_MODULUS;
	}

	/** Returns list of online accounts with timestamp recent enough to be considered currently online. */
	public List<OnlineAccountData> getOnlineAccounts() {
		final long onlineTimestamp = Controller.toOnlineAccountTimestamp(NTP.getTime());

		synchronized (this.onlineAccounts) {
			return this.onlineAccounts.stream().filter(account -> account.getTimestamp() == onlineTimestamp).collect(Collectors.toList());
		}
	}

	public byte[] fetchArbitraryData(byte[] signature) throws InterruptedException {
		// Build request
		Message getArbitraryDataMessage = new GetArbitraryDataMessage(signature);

		// Save our request into requests map
		String signature58 = Base58.encode(signature);
		Triple<String, Peer, Long> requestEntry = new Triple<>(signature58, null, NTP.getTime());

		// Assign random ID to this message
		int id;
		do {
			id = new Random().nextInt(Integer.MAX_VALUE - 1) + 1;

			// Put queue into map (keyed by message ID) so we can poll for a response
			// If putIfAbsent() doesn't return null, then this ID is already taken
		} while (arbitraryDataRequests.put(id, requestEntry) != null);
		getArbitraryDataMessage.setId(id);

		// Broadcast request
		Network.getInstance().broadcast(peer -> peer.getVersion() < 2 ? null : getArbitraryDataMessage);

		// Poll to see if data has arrived
		final long singleWait = 100;
		long totalWait = 0;
		while (totalWait < ARBITRARY_REQUEST_TIMEOUT) {
			Thread.sleep(singleWait);

			requestEntry = arbitraryDataRequests.get(id);
			if (requestEntry == null)
				return null;

			if (requestEntry.getA() == null)
				break;

			totalWait += singleWait;
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getArbitraryRepository().fetchData(signature);
		} catch (DataException e) {
			LOGGER.error(String.format("Repository issue while fetching arbitrary transaction data"), e);
			return null;
		}
	}

	/** Returns a list of peers that are not misbehaving, and have a recent block. */
	public List<Peer> getRecentBehavingPeers() {
		final Long minLatestBlockTimestamp = getMinimumLatestBlockTimestamp();
		if (minLatestBlockTimestamp == null)
			return null;

		List<Peer> peers = Network.getInstance().getUniqueHandshakedPeers();

		// Filter out unsuitable peers
		Iterator<Peer> iterator = peers.iterator();
		while (iterator.hasNext()) {
			final Peer peer = iterator.next();

			final PeerData peerData = peer.getPeerData();
			if (peerData == null) {
				iterator.remove();
				continue;
			}

			// Disregard peers that have "misbehaved" recently
			if (hasMisbehaved.test(peer)) {
				iterator.remove();
				continue;
			}

			final PeerChainTipData peerChainTipData = peer.getChainTipData();
			if (peerChainTipData == null) {
				iterator.remove();
				continue;
			}

			// Disregard peers that don't have a recent block
			if (peerChainTipData.getLastBlockTimestamp() == null || peerChainTipData.getLastBlockTimestamp() < minLatestBlockTimestamp) {
				iterator.remove();
				continue;
			}
		}

		return peers;
	}

	/** Returns whether we think our node has up-to-date blockchain based on our info about other peers. */
	public boolean isUpToDate() {
		// Do we even have a vaguely recent block?
		final Long minLatestBlockTimestamp = getMinimumLatestBlockTimestamp();
		if (minLatestBlockTimestamp == null)
			return false;

		final BlockData latestBlockData = getChainTip();
		if (latestBlockData == null || latestBlockData.getTimestamp() < minLatestBlockTimestamp)
			return false;

		List<Peer> peers = Network.getInstance().getUniqueHandshakedPeers();
		if (peers == null)
			return false;

		// Disregard peers that have "misbehaved" recently
		peers.removeIf(hasMisbehaved);

		// Disregard peers that don't have a recent block
		peers.removeIf(hasNoRecentBlock);

		// Check we have enough peers to potentially synchronize/mint
		if (peers.size() < Settings.getInstance().getMinBlockchainPeers())
			return false;

		// If we don't have any peers left then can't synchronize, therefore consider ourself not up to date
		return !peers.isEmpty();
	}

	/** Returns minimum block timestamp for block to be considered 'recent', or <tt>null</tt> if NTP not synced. */
	public static Long getMinimumLatestBlockTimestamp() {
		Long now = NTP.getTime();
		if (now == null)
			return null;

		int height = getInstance().getChainHeight();
		if (height == 0)
			return null;

		long offset = 0;
		for (int ai = 0; height >= 1 && ai < MAX_BLOCKCHAIN_TIP_AGE; ++ai, --height) {
			BlockTimingByHeight blockTiming = BlockChain.getInstance().getBlockTimingByHeight(height);
			offset += blockTiming.target + blockTiming.deviation;
		}

		return now - offset;
	}

}
