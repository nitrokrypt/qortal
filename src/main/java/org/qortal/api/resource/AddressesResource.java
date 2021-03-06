package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiException;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.model.ApiOnlineAccount;
import org.qortal.api.model.RewardShareKeyRequest;
import org.qortal.asset.Asset;
import org.qortal.controller.Controller;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.RewardShareData;
import org.qortal.data.network.OnlineAccountData;
import org.qortal.data.transaction.RewardShareTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.transform.transaction.RewardShareTransactionTransformer;
import org.qortal.utils.Base58;

@Path("/addresses")
@Tag(name = "Addresses")
public class AddressesResource {

	@Context
	HttpServletRequest request;
	
	@GET
	@Path("/{address}")
	@Operation(
		summary = "Return general account information for the given address",
		responses = {
			@ApiResponse(
				description = "general account information",
				content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = AccountData.class))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public AccountData getAccountInfo(@PathParam("address") String address) {
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			AccountData accountData = repository.getAccountRepository().getAccount(address);

			// Not found?
			if (accountData == null)
				accountData = new AccountData(address);
			else {
				// Unconfirmed transactions could update lastReference
				Account account = new Account(repository, address);

				// Use last reference based on unconfirmed transactions if possible
				byte[] unconfirmedLastReference = account.getUnconfirmedLastReference();

				if (unconfirmedLastReference != null)
					// There are unconfirmed transactions so modify returned data
					accountData.setReference(unconfirmedLastReference);
			}

			return accountData;
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/lastreference/{address}")
	@Operation(
		summary = "Fetch reference for next transaction to be created by address, considering unconfirmed transactions",
		description = "Returns the base58-encoded signature of the last confirmed/unconfirmed transaction created by address, failing that: the first incoming transaction. Returns \"false\" if there is no transactions.",
		responses = {
			@ApiResponse(
				description = "the base58-encoded transaction signature",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public String getLastReferenceUnconfirmed(@PathParam("address") String address) {
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		byte[] lastReference = null;

		try (final Repository repository = RepositoryManager.getRepository()) {
			Account account = new Account(repository, address);

			// Use last reference based on unconfirmed transactions if possible
			lastReference = account.getUnconfirmedLastReference();

			if (lastReference == null)
				// No unconfirmed transactions so fallback to using one save in account data
				lastReference = account.getLastReference();
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}

		if(lastReference == null || lastReference.length == 0) {
			return "false";
		} else {
			return Base58.encode(lastReference);
		}
	}

	@GET
	@Path("/validate/{address}")
	@Operation(
		summary = "Validates the given address",
		description = "Returns true/false.",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "boolean"))
			)
		}
	)
	public boolean validate(@PathParam("address") String address) {
		return Crypto.isValidAddress(address);
	}

	@GET
	@Path("/online")
	@Operation(
		summary = "Return currently 'online' accounts",
		responses = {
			@ApiResponse(
				description = "online accounts",
				content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = ApiOnlineAccount.class)))
			)
		}
	)
	@ApiErrors({ApiError.PUBLIC_KEY_NOT_FOUND, ApiError.REPOSITORY_ISSUE})
	public List<ApiOnlineAccount> getOnlineAccounts() {
		List<OnlineAccountData> onlineAccounts = Controller.getInstance().getOnlineAccounts();

		// Map OnlineAccountData entries to OnlineAccount via reward-share data
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<ApiOnlineAccount> apiOnlineAccounts = new ArrayList<>();

			for (OnlineAccountData onlineAccountData : onlineAccounts) {
				RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(onlineAccountData.getPublicKey());
				if (rewardShareData == null)
					// This shouldn't happen?
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.PUBLIC_KEY_NOT_FOUND);

				apiOnlineAccounts.add(new ApiOnlineAccount(onlineAccountData.getTimestamp(), onlineAccountData.getSignature(), onlineAccountData.getPublicKey(),
						rewardShareData.getMintingAccount(), rewardShareData.getRecipient()));
			}

			return apiOnlineAccounts;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/balance/{address}")
	@Operation(
		summary = "Returns account balance",
		description = "Returns account's balance, optionally of given asset and at given height",
		responses = {
			@ApiResponse(
				description = "the balance",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", format = "number"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.INVALID_ASSET_ID, ApiError.INVALID_HEIGHT,  ApiError.REPOSITORY_ISSUE})
	public BigDecimal getBalance(@PathParam("address") String address,
			@QueryParam("assetId") Long assetId,
			@QueryParam("height") Integer height) {
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Account account = new Account(repository, address);

			if (assetId == null)
				assetId = Asset.QORT;
			else if (!repository.getAssetRepository().assetExists(assetId))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ASSET_ID);

			if (height == null)
				height = repository.getBlockRepository().getBlockchainHeight();
			else if (height <= 0)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_HEIGHT);

			return account.getBalance(assetId, height);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/publickey/{address}")
	@Operation(
		summary = "Get public key of address",
		description = "Returns the base58-encoded account public key of the given address, or \"false\" if address not known or has no public key.",
		responses = {
			@ApiResponse(
				description = "the public key",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public String getPublicKey(@PathParam("address") String address) {
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			AccountData accountData = repository.getAccountRepository().getAccount(address);

			if (accountData == null)
				return "false";

			byte[] publicKey = accountData.getPublicKey();
			if (publicKey == null)
				return "false";

			return Base58.encode(publicKey);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/convert/{publickey}")
	@Operation(
		summary = "Convert public key into address",
		description = "Returns account address based on supplied public key. Expects base58-encoded, 32-byte public key.",
		responses = {
			@ApiResponse(
				description = "the address",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.NON_PRODUCTION, ApiError.REPOSITORY_ISSUE})
	public String fromPublicKey(@PathParam("publickey") String publicKey58) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		// Decode public key
		byte[] publicKey;
		try {
			publicKey = Base58.decode(publicKey58);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY, e);
		}

		// Correct size for public key?
		if (publicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		try (final Repository repository = RepositoryManager.getRepository()) {
			return Crypto.toAddress(publicKey);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/rewardshares")
	@Operation(
		summary = "List reward-share relationships",
		description = "Returns list of accounts, with reward-share percentage and reward-share public key.",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = RewardShareData.class)))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public List<RewardShareData> getRewardShares(@QueryParam("minters") List<String> mintingAccounts,
			@QueryParam("recipients") List<String> recipientAccounts,
			@QueryParam("involving") List<String> addresses,
			@Parameter(
			ref = "limit"
			) @QueryParam("limit") Integer limit, @Parameter(
				ref = "offset"
			) @QueryParam("offset") Integer offset, @Parameter(
				ref = "reverse"
			) @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getAccountRepository().findRewardShares(mintingAccounts, recipientAccounts, addresses, limit, offset, reverse);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/rewardsharekey")
	@Operation(
		summary = "Calculate reward-share private key",
		description = "Calculates reward-share private key using passed minting account's private key and recipient account's public key",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = RewardShareKeyRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_PUBLIC_KEY, ApiError.REPOSITORY_ISSUE})
	public String calculateRewardShareKey(RewardShareKeyRequest rewardShareKeyRequest) {
		byte[] mintingPrivateKey = rewardShareKeyRequest.mintingAccountPrivateKey;
		byte[] recipientPublicKey = rewardShareKeyRequest.recipientAccountPublicKey;

		if (mintingPrivateKey == null || mintingPrivateKey.length != Transformer.PRIVATE_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		if (recipientPublicKey == null || recipientPublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		PrivateKeyAccount mintingAccount = new PrivateKeyAccount(null, mintingPrivateKey);

		byte[] rewardSharePrivateKey = mintingAccount.getRewardSharePrivateKey(recipientPublicKey);

		return Base58.encode(rewardSharePrivateKey);
	}

	@POST
	@Path("/rewardshare")
	@Operation(
		summary = "Build raw, unsigned, REWARD_SHARE transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = RewardShareTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, REWARD_SHARE transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String rewardShare(RewardShareTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = RewardShareTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

}
