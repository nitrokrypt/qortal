package repository.hsqldb;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import data.block.BlockData;
import data.transaction.TransactionData;
import database.DB;
import repository.BlockRepository;
import repository.DataException;
import repository.RepositoryManager;
import repository.TransactionRepository;

public class HSQLDBBlockRepository implements BlockRepository {
	protected static final int TRANSACTIONS_SIGNATURE_LENGTH = 64;
	protected static final int GENERATOR_SIGNATURE_LENGTH = 64;
	protected static final int REFERENCE_LENGTH = GENERATOR_SIGNATURE_LENGTH + TRANSACTIONS_SIGNATURE_LENGTH;

	private static final String BLOCK_DB_COLUMNS = "version, reference, transaction_count, total_fees, "
			+ "transactions_signature, height, generation, generating_balance, generator, generator_signature, AT_data, AT_fees";

	public BlockData fromSignature(byte[] signature) throws DataException {
		try {
			ResultSet rs = DB.checkedExecute("SELECT " + BLOCK_DB_COLUMNS + " FROM Blocks WHERE signature = ?", signature);
			return getBlockFromResultSet(rs);
		} catch (SQLException e) {
			throw new DataException("Error loading data from DB", e);
		}
	}

	public BlockData fromReference(byte[] reference) throws DataException {
		try {
			ResultSet rs = DB.checkedExecute("SELECT " + BLOCK_DB_COLUMNS + " FROM Blocks WHERE reference = ?", reference);
			return getBlockFromResultSet(rs);
		} catch (SQLException e) {
			throw new DataException("Error loading data from DB", e);
		}
	}

	public BlockData fromHeight(int height) throws DataException {
		try {
			ResultSet rs = DB.checkedExecute("SELECT " + BLOCK_DB_COLUMNS + " FROM Blocks WHERE height = ?", height);
			return getBlockFromResultSet(rs);
		} catch (SQLException e) {
			throw new DataException("Error loading data from DB", e);
		}
	}

	private BlockData getBlockFromResultSet(ResultSet rs) throws DataException {
		if (rs == null)
			return null;

		try {
			int version = rs.getInt(1);
			byte[] reference = DB.getResultSetBytes(rs.getBinaryStream(2));
			int transactionCount = rs.getInt(3);
			BigDecimal totalFees = rs.getBigDecimal(4);
			byte[] transactionsSignature = DB.getResultSetBytes(rs.getBinaryStream(5));
			int height = rs.getInt(6);
			long timestamp = rs.getTimestamp(7).getTime();
			BigDecimal generatingBalance = rs.getBigDecimal(8);
			byte[] generatorPublicKey = DB.getResultSetBytes(rs.getBinaryStream(9));
			byte[] generatorSignature = DB.getResultSetBytes(rs.getBinaryStream(10));
			byte[] atBytes = DB.getResultSetBytes(rs.getBinaryStream(11));
			BigDecimal atFees = rs.getBigDecimal(12);

			return new BlockData(version, reference, transactionCount, totalFees, transactionsSignature, height, timestamp, generatingBalance, generatorPublicKey,
					generatorSignature, atBytes, atFees);
		} catch (SQLException e) {
			throw new DataException(e);
		}
	}

	public List<TransactionData> getTransactionsFromSignature(byte[] signature) throws DataException {
		List<TransactionData> transactions = new ArrayList<TransactionData>();

		try {
			ResultSet rs = DB.checkedExecute("SELECT transaction_signature FROM BlockTransactions WHERE block_signature = ?", signature);
			if (rs == null)
				return transactions; // No transactions in this block

			TransactionRepository transactionRepo = RepositoryManager.getTransactionRepository();

			// NB: do-while loop because DB.checkedExecute() implicitly calls ResultSet.next() for us
			do {
				byte[] transactionSignature = DB.getResultSetBytes(rs.getBinaryStream(1));
				transactions.add(transactionRepo.fromSignature(transactionSignature));
			} while (rs.next());
		} catch (SQLException e) {
			throw new DataException(e);
		}

		return transactions;
	}

	public void save(BlockData blockData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Blocks");

		saveHelper.bind("signature", blockData.getSignature()).bind("version", blockData.getVersion()).bind("reference", blockData.getReference())
				.bind("transaction_count", blockData.getTransactionCount()).bind("total_fees", blockData.getTotalFees()).bind("transactions_signature", blockData.getTransactionsSignature())
				.bind("height", blockData.getHeight()).bind("generation", new Timestamp(blockData.getTimestamp())).bind("generating_balance", blockData.getGeneratingBalance())
				.bind("generator", blockData.getGeneratorPublicKey()).bind("generator_signature", blockData.getGeneratorSignature()).bind("AT_data", blockData.getAtBytes())
				.bind("AT_fees", blockData.getAtFees());

		try {
			saveHelper.execute();
		} catch (SQLException e) {
			throw new DataException("Unable to save Block into repository", e);
		}
	}

}