package org.qortal.transaction;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import org.qortal.account.Account;
import org.qortal.account.PublicKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.crypto.Crypto;
import org.qortal.data.group.GroupData;
import org.qortal.data.transaction.CancelGroupBanTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

public class CancelGroupBanTransaction extends Transaction {

	// Properties
	private CancelGroupBanTransactionData groupUnbanTransactionData;

	// Constructors

	public CancelGroupBanTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.groupUnbanTransactionData = (CancelGroupBanTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<Account> getRecipientAccounts() throws DataException {
		return Collections.emptyList();
	}

	@Override
	public boolean isInvolved(Account account) throws DataException {
		String address = account.getAddress();

		if (address.equals(this.getAdmin().getAddress()))
			return true;

		if (address.equals(this.getMember().getAddress()))
			return true;

		return false;
	}

	@Override
	public BigDecimal getAmount(Account account) throws DataException {
		String address = account.getAddress();
		BigDecimal amount = BigDecimal.ZERO.setScale(8);

		if (address.equals(this.getAdmin().getAddress()))
			amount = amount.subtract(this.transactionData.getFee());

		return amount;
	}

	// Navigation

	public Account getAdmin() throws DataException {
		return new PublicKeyAccount(this.repository, this.groupUnbanTransactionData.getAdminPublicKey());
	}

	public Account getMember() throws DataException {
		return new Account(this.repository, this.groupUnbanTransactionData.getMember());
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		// Check member address is valid
		if (!Crypto.isValidAddress(groupUnbanTransactionData.getMember()))
			return ValidationResult.INVALID_ADDRESS;

		GroupData groupData = this.repository.getGroupRepository().fromGroupId(groupUnbanTransactionData.getGroupId());

		// Check group exists
		if (groupData == null)
			return ValidationResult.GROUP_DOES_NOT_EXIST;

		Account admin = getAdmin();

		// Can't unban if not an admin
		if (!this.repository.getGroupRepository().adminExists(groupUnbanTransactionData.getGroupId(), admin.getAddress()))
			return ValidationResult.NOT_GROUP_ADMIN;

		Account member = getMember();

		// Check ban actually exists
		if (!this.repository.getGroupRepository().banExists(groupUnbanTransactionData.getGroupId(), member.getAddress()))
			return ValidationResult.BAN_UNKNOWN;

		// Check fee is positive
		if (groupUnbanTransactionData.getFee().compareTo(BigDecimal.ZERO) <= 0)
			return ValidationResult.NEGATIVE_FEE;

		// Check creator has enough funds
		if (admin.getConfirmedBalance(Asset.QORT).compareTo(groupUnbanTransactionData.getFee()) < 0)
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		// Update Group Membership
		Group group = new Group(this.repository, groupUnbanTransactionData.getGroupId());
		group.cancelBan(groupUnbanTransactionData);

		// Save this transaction with updated member/admin references to transactions that can help restore state
		this.repository.getTransactionRepository().save(groupUnbanTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert group membership
		Group group = new Group(this.repository, groupUnbanTransactionData.getGroupId());
		group.uncancelBan(groupUnbanTransactionData);

		// Save this transaction with removed member/admin references
		this.repository.getTransactionRepository().save(groupUnbanTransactionData);
	}

}
