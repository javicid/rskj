/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.peg;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.crypto.TransactionSignature;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptBuilder;
import co.rsk.bitcoinj.script.ScriptChunk;
import co.rsk.bitcoinj.store.BlockStoreException;
import co.rsk.bitcoinj.wallet.SendRequest;
import co.rsk.bitcoinj.wallet.Wallet;
import co.rsk.config.BridgeConstants;
import co.rsk.core.RskAddress;
import co.rsk.crypto.Keccak256;
import co.rsk.panic.PanicProcessor;
import co.rsk.peg.bitcoin.MerkleBranch;
import co.rsk.peg.utils.BridgeEventLogger;
import co.rsk.peg.utils.BtcTransactionFormatUtils;
import co.rsk.peg.utils.PartialMerkleTreeFormatUtils;
import co.rsk.peg.whitelist.LockWhitelist;
import co.rsk.peg.whitelist.LockWhitelistEntry;
import co.rsk.peg.whitelist.OneOffWhiteListEntry;
import co.rsk.peg.whitelist.UnlimitedWhiteListEntry;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.commons.lang3.tuple.Pair;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.core.Block;
import org.ethereum.core.Repository;
import org.ethereum.core.Transaction;
import org.ethereum.crypto.ECKey;
import org.ethereum.vm.PrecompiledContracts;
import org.ethereum.vm.program.Program;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class to move funds from btc to rsk and rsk to btc
 *
 * @author Oscar Guindzberg
 */
public class BridgeSupport {
    public static final RskAddress BURN_ADDRESS =
            new RskAddress("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");

    public static final int MAX_RELEASE_ITERATIONS = 30;

    public static final Integer FEDERATION_CHANGE_GENERIC_ERROR_CODE = -10;
    public static final Integer LOCK_WHITELIST_GENERIC_ERROR_CODE = -10;
    public static final Integer LOCK_WHITELIST_INVALID_ADDRESS_FORMAT_ERROR_CODE = -2;
    public static final Integer LOCK_WHITELIST_ALREADY_EXISTS_ERROR_CODE = -1;
    public static final Integer LOCK_WHITELIST_UNKNOWN_ERROR_CODE = 0;
    public static final Integer LOCK_WHITELIST_SUCCESS_CODE = 1;
    public static final Integer FEE_PER_KB_GENERIC_ERROR_CODE = -10;

    public static final Integer BTC_TRANSACTION_CONFIRMATION_INEXISTENT_BLOCK_HASH_ERROR_CODE = -1;
    public static final Integer BTC_TRANSACTION_CONFIRMATION_BLOCK_NOT_IN_BEST_CHAIN_ERROR_CODE =
            -2;
    public static final Integer BTC_TRANSACTION_CONFIRMATION_INCONSISTENT_BLOCK_ERROR_CODE = -3;
    public static final Integer BTC_TRANSACTION_CONFIRMATION_BLOCK_TOO_OLD_ERROR_CODE = -4;
    public static final Integer BTC_TRANSACTION_CONFIRMATION_INVALID_MERKLE_BRANCH_ERROR_CODE = -5;

    // Enough depth to be able to search backwards one month worth of blocks
    // (6 blocks/hour, 24 hours/day, 30 days/month)
    public static final Integer BTC_TRANSACTION_CONFIRMATION_MAX_DEPTH = 4320;

    private static final Logger logger = LoggerFactory.getLogger("BridgeSupport");
    private static final PanicProcessor panicProcessor = new PanicProcessor();

    private static final String INVALID_ADDRESS_FORMAT_MESSAGE = "invalid address format";

    private final List<String> FEDERATION_CHANGE_FUNCTIONS =
            Collections.unmodifiableList(
                    Arrays.asList("create", "add", "add-multi", "commit", "rollback"));

    private final BridgeConstants bridgeConstants;
    private final BridgeStorageProvider provider;
    private final Repository rskRepository;
    private final BridgeEventLogger eventLogger;

    private final FederationSupport federationSupport;

    private final Context btcContext;
    private BtcBlockStoreWithCache.Factory btcBlockStoreFactory;
    private BtcBlockStoreWithCache btcBlockStore;
    private BtcBlockChain btcBlockChain;
    private final org.ethereum.core.Block rskExecutionBlock;

    // Used by bridge
    public BridgeSupport(
            BridgeConstants bridgeConstants,
            BridgeStorageConfiguration bridgeStorageConfiguration,
            BridgeEventLogger eventLogger,
            Repository repository,
            Block rskExecutionBlock,
            RskAddress contractAddress,
            BtcBlockStoreWithCache.Factory btcBlockStoreFactory) {
        this(
                bridgeConstants,
                new BridgeStorageProvider(
                        repository, contractAddress, bridgeConstants, bridgeStorageConfiguration),
                eventLogger,
                repository,
                rskExecutionBlock,
                btcBlockStoreFactory,
                null);
    }

    // Used by unit tests
    public BridgeSupport(
            BridgeConstants bridgeConstants,
            BridgeStorageProvider provider,
            BridgeEventLogger eventLogger,
            Repository repository,
            Block executionBlock,
            BtcBlockStoreWithCache.Factory btcBlockStoreFactory,
            BtcBlockChain btcBlockChain) {
        this(
                bridgeConstants,
                provider,
                eventLogger,
                repository,
                executionBlock,
                new Context(bridgeConstants.getBtcParams()),
                new FederationSupport(bridgeConstants, provider, executionBlock),
                btcBlockStoreFactory,
                btcBlockChain);
    }

    public BridgeSupport(
            BridgeConstants bridgeConstants,
            BridgeStorageProvider provider,
            BridgeEventLogger eventLogger,
            Repository repository,
            Block executionBlock,
            Context btcContext,
            FederationSupport federationSupport,
            BtcBlockStoreWithCache.Factory btcBlockStoreFactory,
            BtcBlockChain btcBlockChain) {
        this.rskRepository = repository;
        this.provider = provider;
        this.rskExecutionBlock = executionBlock;
        this.bridgeConstants = bridgeConstants;
        this.eventLogger = eventLogger;
        this.btcContext = btcContext;
        this.federationSupport = federationSupport;
        this.btcBlockStoreFactory = btcBlockStoreFactory;
        this.btcBlockChain = btcBlockChain;
    }

    @VisibleForTesting
    InputStream getCheckPoints() {
        InputStream checkpoints =
                BridgeSupport.class.getResourceAsStream(
                        "/rskbitcoincheckpoints/"
                                + bridgeConstants.getBtcParams().getId()
                                + ".checkpoints");
        if (checkpoints == null) {
            // If we don't have a custom checkpoints file, try to use bitcoinj's default checkpoints
            // for that network
            checkpoints =
                    BridgeSupport.class.getResourceAsStream(
                            "/" + bridgeConstants.getBtcParams().getId() + ".checkpoints");
        }
        return checkpoints;
    }

    public void save() throws IOException {
        provider.save();
    }

    /**
     * Receives an array of serialized Bitcoin block headers and adds them to the internal
     * BlockChain structure.
     *
     * @param headers The bitcoin headers
     */
    public void receiveHeaders(BtcBlock[] headers) throws IOException, BlockStoreException {
        if (headers.length > 0) {
            logger.debug(
                    "Received {} headers. First {}, last {}.",
                    headers.length,
                    headers[0].getHash(),
                    headers[headers.length - 1].getHash());
        } else {
            logger.warn("Received 0 headers");
        }

        Context.propagate(btcContext);
        this.ensureBtcBlockChain();
        for (int i = 0; i < headers.length; i++) {
            try {
                btcBlockChain.add(headers[i]);
            } catch (Exception e) {
                // If we tray to add an orphan header bitcoinj throws an exception
                // This catches that case and any other exception that may be thrown
                logger.warn("Exception adding btc header {}", headers[i].getHash(), e);
            }
        }
    }

    /**
     * Get the wallet for the currently active federation
     *
     * @return A BTC wallet for the currently active federation
     * @throws IOException
     */
    public Wallet getActiveFederationWallet() throws IOException {
        Federation federation = getActiveFederation();
        List<UTXO> utxos = getActiveFederationBtcUTXOs();

        return BridgeUtils.getFederationSpendWallet(btcContext, federation, utxos);
    }

    /**
     * Get the wallet for the currently retiring federation or null if there's currently no retiring
     * federation
     *
     * @return A BTC wallet for the currently active federation
     * @throws IOException
     */
    public Wallet getRetiringFederationWallet() throws IOException {
        Federation federation = getRetiringFederation();
        if (federation == null) {
            return null;
        }

        List<UTXO> utxos = getRetiringFederationBtcUTXOs();

        return BridgeUtils.getFederationSpendWallet(btcContext, federation, utxos);
    }

    /**
     * Get the wallet for the currently live federations but limited to a specific list of UTXOs
     *
     * @return A BTC wallet for the currently live federation(s) limited to the given list of UTXOs
     * @throws IOException
     */
    public Wallet getUTXOBasedWalletForLiveFederations(List<UTXO> utxos) throws IOException {
        return BridgeUtils.getFederationsSpendWallet(btcContext, getLiveFederations(), utxos);
    }

    /**
     * Get a no spend wallet for the currently live federations
     *
     * @return A no spend BTC wallet for the currently live federation(s)
     * @throws IOException
     */
    public Wallet getNoSpendWalletForLiveFederations() throws IOException {
        return BridgeUtils.getFederationsNoSpendWallet(btcContext, getLiveFederations());
    }

    /**
     * In case of a lock tx: Transfers some SBTCs to the sender of the btc tx and keeps track of the
     * new UTXOs available for spending. In case of a release tx: Keeps track of the change UTXOs,
     * now available for spending.
     *
     * @param rskTx The RSK transaction
     * @param btcTxSerialized The raw BTC tx
     * @param height The height of the BTC block that contains the tx
     * @param pmtSerialized The raw partial Merkle tree
     * @throws BlockStoreException
     * @throws IOException
     */
    public void registerBtcTransaction(
            Transaction rskTx, byte[] btcTxSerialized, int height, byte[] pmtSerialized)
            throws IOException, BlockStoreException {
        Context.propagate(btcContext);

        Sha256Hash btcTxHash = BtcTransactionFormatUtils.calculateBtcTxHash(btcTxSerialized);
        // Check the tx was not already processed
        if (provider.getBtcTxHashesAlreadyProcessed().keySet().contains(btcTxHash)) {
            logger.warn("Supplied Btc Tx {} was already processed", btcTxHash);
            return;
        }

        if (height < 0) {
            String panicMessage =
                    String.format(
                            "Btc Tx %s Supplied Height is %d but should be greater than 0",
                            btcTxHash, height);
            logger.warn(panicMessage);
            panicProcessor.panic("btclock", panicMessage);
            return;
        }

        // Check there are at least N blocks on top of the supplied height
        int btcBestChainHeight = getBtcBlockchainBestChainHeight();
        int confirmations = btcBestChainHeight - height + 1;
        if (confirmations < bridgeConstants.getBtc2RskMinimumAcceptableConfirmations()) {
            logger.warn(
                    "Btc Tx {} at least {} confirmations are required, but there are only {} confirmations",
                    btcTxHash,
                    bridgeConstants.getBtc2RskMinimumAcceptableConfirmations(),
                    confirmations);
            return;
        }

        if (!PartialMerkleTreeFormatUtils.hasExpectedSize(pmtSerialized)) {
            throw new BridgeIllegalArgumentException(
                    "PartialMerkleTree doesn't have expected size");
        }

        Sha256Hash merkleRoot;
        try {
            PartialMerkleTree pmt =
                    new PartialMerkleTree(bridgeConstants.getBtcParams(), pmtSerialized, 0);
            List<Sha256Hash> hashesInPmt = new ArrayList<>();
            merkleRoot = pmt.getTxnHashAndMerkleRoot(hashesInPmt);
            if (!hashesInPmt.contains(btcTxHash)) {
                logger.warn(
                        "Supplied Btc Tx {} is not in the supplied partial merkle tree", btcTxHash);
                return;
            }
        } catch (VerificationException e) {
            throw new BridgeIllegalArgumentException(
                    String.format(
                            "PartialMerkleTree could not be parsed {}",
                            Hex.toHexString(pmtSerialized)),
                    e);
        }

        if (BtcTransactionFormatUtils.getInputsCount(btcTxSerialized) == 0) {
            logger.warn("Btc Tx {} has no inputs ", btcTxHash);
            // this is the exception thrown by co.rsk.bitcoinj.core.BtcTransaction#verify when there
            // are no inputs.
            throw new VerificationException.EmptyInputsOrOutputs();
        }

        // Check the the merkle root equals merkle root of btc block at specified height in the btc
        // best chain
        // BTC blockstore is available since we've already queried the best chain height
        BtcBlock blockHeader = btcBlockStore.getStoredBlockAtMainChainHeight(height).getHeader();
        if (!blockHeader.getMerkleRoot().equals(merkleRoot)) {
            String panicMessage =
                    String.format(
                            "Btc Tx %s Supplied merkle root %s does not match block's merkle root %s",
                            btcTxHash.toString(), merkleRoot, blockHeader.getMerkleRoot());
            logger.warn(panicMessage);
            panicProcessor.panic("btclock", panicMessage);
            return;
        }

        BtcTransaction btcTx = new BtcTransaction(bridgeConstants.getBtcParams(), btcTxSerialized);
        btcTx.verify();

        boolean locked = true;

        Federation activeFederation = getActiveFederation();
        // Specific code for lock/release/none txs
        if (BridgeUtils.isLockTx(btcTx, getLiveFederations(), btcContext, bridgeConstants)) {
            logger.debug("This is a lock tx {}", btcTx);
            Optional<Script> scriptSig = BridgeUtils.getFirstInputScriptSig(btcTx);
            if (!scriptSig.isPresent()) {
                logger.warn(
                        "[btctx:{}] First input does not spend a Pay-to-PubkeyHash {}",
                        btcTx.getHash(),
                        btcTx.getInput(0));
                return;
            }

            // Compute the total amount sent. Value could have been sent both to the
            // currently active federation as well as to the currently retiring federation.
            // Add both amounts up in that case.
            Coin amountToActive = btcTx.getValueSentToMe(getActiveFederationWallet());
            Coin amountToRetiring = Coin.ZERO;
            Wallet retiringFederationWallet = getRetiringFederationWallet();
            if (retiringFederationWallet != null) {
                amountToRetiring = btcTx.getValueSentToMe(retiringFederationWallet);
            }
            Coin totalAmount = amountToActive.add(amountToRetiring);

            // Get the sender public key
            byte[] data = scriptSig.get().getChunks().get(1).data;

            // Tx is a lock tx, check whether the sender is whitelisted
            BtcECKey senderBtcKey = BtcECKey.fromPublicOnly(data);
            Address senderBtcAddress =
                    new Address(btcContext.getParams(), senderBtcKey.getPubKeyHash());

            // If the address is not whitelisted, then return the funds
            // using the exact same utxos sent to us.
            // That is, build a release transaction and get it in the release transaction set.
            // Otherwise, transfer SBTC to the sender of the BTC
            // The RSK account to update is the one that matches the pubkey "spent" on the first
            // bitcoin tx input
            LockWhitelist lockWhitelist = provider.getLockWhitelist();
            if (!lockWhitelist.isWhitelistedFor(senderBtcAddress, totalAmount, height)) {
                locked = false;
                // Build the list of UTXOs in the BTC transaction sent to either the active
                // or retiring federation
                List<UTXO> utxosToUs =
                        btcTx.getWalletOutputs(getNoSpendWalletForLiveFederations()).stream()
                                .map(
                                        output ->
                                                new UTXO(
                                                        btcTx.getHash(),
                                                        output.getIndex(),
                                                        output.getValue(),
                                                        0,
                                                        btcTx.isCoinBase(),
                                                        output.getScriptPubKey()))
                                .collect(Collectors.toList());
                // Use the list of UTXOs to build a transaction builder
                // for the return btc transaction generation
                ReleaseTransactionBuilder txBuilder =
                        new ReleaseTransactionBuilder(
                                btcContext.getParams(),
                                getUTXOBasedWalletForLiveFederations(utxosToUs),
                                senderBtcAddress,
                                getFeePerKb());
                Optional<ReleaseTransactionBuilder.BuildResult> buildReturnResult =
                        txBuilder.buildEmptyWalletTo(senderBtcAddress);
                if (buildReturnResult.isPresent()) {
                    provider.getReleaseTransactionSet()
                            .add(buildReturnResult.get().getBtcTx(), rskExecutionBlock.getNumber());
                    logger.info(
                            "whitelist money return tx build successful to {}. Tx {}. Value {}.",
                            senderBtcAddress,
                            rskTx,
                            totalAmount);
                } else {
                    logger.warn(
                            "whitelist money return tx build for btc tx {} error. Return was to {}. Tx {}. Value {}",
                            btcTx.getHash(),
                            senderBtcAddress,
                            rskTx,
                            totalAmount);
                    panicProcessor.panic(
                            "whitelist-return-funds",
                            String.format(
                                    "whitelist money return tx build for btc tx %s error. Return was to %s. Tx %s. Value %s",
                                    btcTx.getHash(), senderBtcAddress, rskTx, totalAmount));
                }
            } else {
                org.ethereum.crypto.ECKey key = org.ethereum.crypto.ECKey.fromPublicOnly(data);
                RskAddress sender = new RskAddress(key.getAddress());

                rskRepository.transfer(
                        PrecompiledContracts.BRIDGE_ADDR,
                        sender,
                        co.rsk.core.Coin.fromBitcoin(totalAmount));

                logger.info(
                        "Transferring from BTC Address {}. RSK Address: {}.",
                        senderBtcAddress,
                        sender);

                // Consume this whitelisted address
                lockWhitelist.consume(senderBtcAddress);
            }
        } else if (BridgeUtils.isReleaseTx(btcTx, getLiveFederations())) {
            logger.debug("This is a release tx {}", btcTx);
            // do-nothing
            // We could call removeUsedUTXOs(btcTx) here, but we decided to not do that.
            // Used utxos should had been removed when we created the release tx.
            // Invoking removeUsedUTXOs() here would make "some" sense in theses scenarios:
            // a) In testnet, devnet or local: we restart the RSK blockchain whithout changing the
            // federation address. We don't want to have utxos that were already spent.
            // Open problem: TxA spends TxB. registerBtcTransaction() for TxB is called, it spends a
            // utxo the bridge is not yet aware of,
            // so nothing is removed. Then registerBtcTransaction() for TxA and the "already spent"
            // utxo is added as it was not spent.
            // When is not guaranteed to be called in the chronological order, so a Federator can
            // inform
            // b) In prod: Federator created a tx manually or the federation was compromised and
            // some utxos were spent. Better not try to spend them.
            // Open problem: For performance removeUsedUTXOs() just removes 1 utxo
        } else if (BridgeUtils.isMigrationTx(
                btcTx, activeFederation, getRetiringFederation(), btcContext, bridgeConstants)) {
            logger.debug("This is a migration tx {}", btcTx);
        } else {
            logger.warn("This is not a lock, a release nor a migration tx {}", btcTx);
            panicProcessor.panic(
                    "btclock", "This is not a lock, a release nor a migration tx " + btcTx);
            return;
        }

        // Mark tx as processed on this block
        provider.getBtcTxHashesAlreadyProcessed().put(btcTxHash, rskExecutionBlock.getNumber());

        // Save UTXOs from the federation(s) only if we actually
        // locked the funds.
        if (locked) {
            saveNewUTXOs(btcTx);
        }
        logger.info("BTC Tx {} processed in RSK", btcTxHash);
    }

    /*
     Add the btcTx outputs that send btc to the federation(s) to the UTXO list
    */
    private void saveNewUTXOs(BtcTransaction btcTx) throws IOException {
        // Outputs to the active federation
        List<TransactionOutput> outputsToTheActiveFederation =
                btcTx.getWalletOutputs(getActiveFederationWallet());
        for (TransactionOutput output : outputsToTheActiveFederation) {
            UTXO utxo =
                    new UTXO(
                            btcTx.getHash(),
                            output.getIndex(),
                            output.getValue(),
                            0,
                            btcTx.isCoinBase(),
                            output.getScriptPubKey());
            getActiveFederationBtcUTXOs().add(utxo);
        }

        // Outputs to the retiring federation (if any)
        Wallet retiringFederationWallet = getRetiringFederationWallet();
        if (retiringFederationWallet != null) {
            List<TransactionOutput> outputsToTheRetiringFederation =
                    btcTx.getWalletOutputs(retiringFederationWallet);
            for (TransactionOutput output : outputsToTheRetiringFederation) {
                UTXO utxo =
                        new UTXO(
                                btcTx.getHash(),
                                output.getIndex(),
                                output.getValue(),
                                0,
                                btcTx.isCoinBase(),
                                output.getScriptPubKey());
                getRetiringFederationBtcUTXOs().add(utxo);
            }
        }
    }

    /**
     * Initiates the process of sending coins back to BTC. This is the default contract method. The
     * funds will be sent to the bitcoin address controlled by the private key that signed the rsk
     * tx. The amount sent to the bridge in this tx will be the amount sent in the btc network minus
     * fees.
     *
     * @param rskTx The rsk tx being executed.
     * @throws IOException
     */
    public void releaseBtc(Transaction rskTx) throws IOException {

        // as we can't send btc from contracts we want to send them back to the sender
        if (BridgeUtils.isContractTx(rskTx)) {
            logger.trace(
                    "Contract {} tried to release funds. Release is just allowed from standard accounts.",
                    rskTx);
            throw new Program.OutOfGasException("Contract calling releaseBTC");
        }

        Context.propagate(btcContext);
        NetworkParameters btcParams = bridgeConstants.getBtcParams();
        Address btcDestinationAddress =
                BridgeUtils.recoverBtcAddressFromEthTransaction(rskTx, btcParams);
        Coin value = rskTx.getValue().toBitcoin();
        boolean addResult = requestRelease(btcDestinationAddress, value);

        if (addResult) {
            logger.info(
                    "releaseBtc succesful to {}. Tx {}. Value {}.",
                    btcDestinationAddress,
                    rskTx,
                    value);
        } else {
            logger.warn(
                    "releaseBtc ignored because value is considered dust. To {}. Tx {}. Value {}.",
                    btcDestinationAddress,
                    rskTx,
                    value);
        }
    }

    /**
     * Creates a request for BTC release and adds it to the request queue for it to be processed
     * later.
     *
     * @param destinationAddress the destination BTC address.
     * @param value the amount of BTC to release.
     * @return true if the request was successfully added, false if the value to release was
     *     considered dust and therefore ignored.
     * @throws IOException
     */
    private boolean requestRelease(Address destinationAddress, Coin value) throws IOException {
        if (!value.isGreaterThan(bridgeConstants.getMinimumReleaseTxValue())) {
            return false;
        }

        provider.getReleaseRequestQueue().add(destinationAddress, value);

        return true;
    }

    /** @return Current fee per kb in BTC. */
    public Coin getFeePerKb() {
        Coin currentFeePerKb = provider.getFeePerKb();

        if (currentFeePerKb == null) {
            currentFeePerKb = bridgeConstants.getGenesisFeePerKb();
        }

        return currentFeePerKb;
    }

    /**
     * Executed every now and then. Performs a few tasks: processing of any pending btc funds
     * migrations from retiring federations; processing of any outstanding btc release requests; and
     * processing of any outstanding release btc transactions.
     *
     * @throws IOException
     * @param rskTx current RSK transaction
     */
    public void updateCollections(Transaction rskTx) throws IOException {
        Context.propagate(btcContext);

        eventLogger.logUpdateCollections(rskTx);

        processFundsMigration();

        processReleaseRequests();

        processReleaseTransactions(rskTx);
    }

    private boolean federationIsInMigrationAge(Federation federation) {
        long federationAge = rskExecutionBlock.getNumber() - federation.getCreationBlockNumber();
        long ageBegin =
                bridgeConstants.getFederationActivationAge()
                        + bridgeConstants.getFundsMigrationAgeSinceActivationBegin();
        long ageEnd =
                bridgeConstants.getFederationActivationAge()
                        + bridgeConstants.getFundsMigrationAgeSinceActivationEnd();

        return federationAge > ageBegin && federationAge < ageEnd;
    }

    private boolean federationIsPastMigrationAge(Federation federation) {
        long federationAge = rskExecutionBlock.getNumber() - federation.getCreationBlockNumber();
        long ageEnd =
                bridgeConstants.getFederationActivationAge()
                        + bridgeConstants.getFundsMigrationAgeSinceActivationEnd();

        return federationAge >= ageEnd;
    }

    private boolean hasMinimumFundsToMigrate(@Nullable Wallet retiringFederationWallet) {
        // This value is set according to the average 500 bytes transaction size
        Coin minimumFundsToMigrate = getFeePerKb().divide(2);
        return retiringFederationWallet != null
                && retiringFederationWallet.getBalance().isGreaterThan(minimumFundsToMigrate);
    }

    private void processFundsMigration() throws IOException {
        Wallet retiringFederationWallet = getRetiringFederationWallet();
        List<UTXO> availableUTXOs = getRetiringFederationBtcUTXOs();
        ReleaseTransactionSet releaseTransactionSet = provider.getReleaseTransactionSet();
        Federation activeFederation = getActiveFederation();

        if (federationIsInMigrationAge(activeFederation)
                && hasMinimumFundsToMigrate(retiringFederationWallet)) {
            logger.info(
                    "Active federation (age={}) is in migration age and retiring federation has funds to migrate: {}.",
                    rskExecutionBlock.getNumber() - activeFederation.getCreationBlockNumber(),
                    retiringFederationWallet.getBalance().toFriendlyString());

            Pair<BtcTransaction, List<UTXO>> createResult =
                    createMigrationTransaction(
                            retiringFederationWallet, activeFederation.getAddress());
            BtcTransaction btcTx = createResult.getLeft();
            List<UTXO> selectedUTXOs = createResult.getRight();

            // Add the TX to the release set
            releaseTransactionSet.add(btcTx, rskExecutionBlock.getNumber());

            // Mark UTXOs as spent
            availableUTXOs.removeIf(
                    utxo ->
                            selectedUTXOs.stream()
                                    .anyMatch(
                                            selectedUtxo ->
                                                    utxo.getHash().equals(selectedUtxo.getHash())
                                                            && utxo.getIndex()
                                                                    == selectedUtxo.getIndex()));
        }

        if (retiringFederationWallet != null && federationIsPastMigrationAge(activeFederation)) {
            if (retiringFederationWallet.getBalance().isGreaterThan(Coin.ZERO)) {
                logger.info(
                        "Federation is past migration age and will try to migrate remaining balance: {}.",
                        retiringFederationWallet.getBalance().toFriendlyString());

                try {
                    Pair<BtcTransaction, List<UTXO>> createResult =
                            createMigrationTransaction(
                                    retiringFederationWallet, activeFederation.getAddress());
                    BtcTransaction btcTx = createResult.getLeft();
                    List<UTXO> selectedUTXOs = createResult.getRight();

                    // Add the TX to the release set
                    releaseTransactionSet.add(btcTx, rskExecutionBlock.getNumber());

                    // Mark UTXOs as spent
                    availableUTXOs.removeIf(
                            utxo ->
                                    selectedUTXOs.stream()
                                            .anyMatch(
                                                    selectedUtxo ->
                                                            utxo.getHash()
                                                                            .equals(
                                                                                    selectedUtxo
                                                                                            .getHash())
                                                                    && utxo.getIndex()
                                                                            == selectedUtxo
                                                                                    .getIndex()));
                } catch (Exception e) {
                    logger.error(
                            "Unable to complete retiring federation migration. Balance left: {} in {}",
                            retiringFederationWallet.getBalance().toFriendlyString(),
                            getRetiringFederationAddress());
                    panicProcessor.panic(
                            "updateCollection",
                            "Unable to complete retiring federation migration.");
                }
            }

            logger.info(
                    "Retiring federation migration finished. Available UTXOs left: {}.",
                    availableUTXOs.size());
            provider.setOldFederation(null);
        }
    }

    /**
     * Processes the current btc release request queue and tries to build btc transactions using
     * (and marking as spent) the current active federation's utxos. Newly created btc transactions
     * are added to the btc release tx set, and failed attempts are kept in the release queue for
     * future processing.
     */
    private void processReleaseRequests() {
        final Wallet activeFederationWallet;
        final ReleaseRequestQueue releaseRequestQueue;

        try {
            activeFederationWallet = getActiveFederationWallet();
            releaseRequestQueue = provider.getReleaseRequestQueue();
        } catch (IOException e) {
            logger.error(
                    "Unexpected error accessing storage while attempting to process release requests",
                    e);
            return;
        }

        // Releases are attempted using the currently active federation
        // wallet.
        final ReleaseTransactionBuilder txBuilder =
                new ReleaseTransactionBuilder(
                        btcContext.getParams(),
                        activeFederationWallet,
                        getFederationAddress(),
                        getFeePerKb());

        releaseRequestQueue.process(
                MAX_RELEASE_ITERATIONS,
                (ReleaseRequestQueue.Entry releaseRequest) -> {
                    Optional<ReleaseTransactionBuilder.BuildResult> result =
                            txBuilder.buildAmountTo(
                                    releaseRequest.getDestination(), releaseRequest.getAmount());

                    // Couldn't build a transaction to release these funds
                    // Log the event and return false so that the request remains in the
                    // queue for future processing.
                    // Further logging is done at the tx builder level.
                    if (!result.isPresent()) {
                        logger.warn(
                                "Couldn't build a release BTC tx for <{}, {}>",
                                releaseRequest.getDestination().toBase58(),
                                releaseRequest.getAmount());
                        return false;
                    }

                    // We have a BTC transaction, mark the UTXOs as spent and add the tx
                    // to the release set.

                    List<UTXO> selectedUTXOs = result.get().getSelectedUTXOs();
                    BtcTransaction generatedTransaction = result.get().getBtcTx();
                    List<UTXO> availableUTXOs;
                    ReleaseTransactionSet releaseTransactionSet;

                    // Attempt access to storage first
                    // (any of these could fail and would invalidate both
                    // the tx build and utxo selection, so treat as atomic)
                    try {
                        availableUTXOs = getActiveFederationBtcUTXOs();
                        releaseTransactionSet = provider.getReleaseTransactionSet();
                    } catch (IOException exception) {
                        // Unexpected error accessing storage, log and fail
                        logger.error(
                                String.format(
                                        "Unexpected error accessing storage while attempting to add a release BTC tx for <%s, %s>",
                                        releaseRequest.getDestination().toString(),
                                        releaseRequest.getAmount().toString()),
                                exception);
                        return false;
                    }

                    // Add the TX
                    releaseTransactionSet.add(generatedTransaction, rskExecutionBlock.getNumber());

                    // Mark UTXOs as spent
                    availableUTXOs.removeAll(selectedUTXOs);

                    // TODO: (Ariel Mendelzon, 07/12/2017)
                    // TODO: Balance adjustment assumes that change output is output with index 1.
                    // TODO: This will change if we implement multiple releases per BTC tx, so
                    // TODO: it would eventually need to be fixed.
                    // Adjust balances in edge cases
                    adjustBalancesIfChangeOutputWasDust(
                            generatedTransaction, releaseRequest.getAmount());

                    return true;
                });
    }

    /**
     * Processes the current btc release transaction set. It basically looks for transactions with
     * enough confirmations and marks them as ready for signing as well as removes them from the
     * set.
     *
     * @param rskTx the RSK transaction that is causing this processing.
     */
    private void processReleaseTransactions(Transaction rskTx) {
        final Map<Keccak256, BtcTransaction> txsWaitingForSignatures;
        final ReleaseTransactionSet releaseTransactionSet;

        try {
            txsWaitingForSignatures = provider.getRskTxsWaitingForSignatures();
            releaseTransactionSet = provider.getReleaseTransactionSet();
        } catch (IOException e) {
            logger.error(
                    "Unexpected error accessing storage while attempting to process release btc transactions",
                    e);
            return;
        }

        // TODO: (Ariel Mendelzon - 07/12/2017)
        // TODO: at the moment, there can only be one btc transaction
        // TODO: per rsk transaction in the txsWaitingForSignatures
        // TODO: map, and the rest of the processing logic is
        // TODO: dependant upon this. That is the reason we
        // TODO: add only one btc transaction at a time
        // TODO: (at least at this stage).

        // IMPORTANT: sliceWithEnoughConfirmations also modifies the transaction set in place
        Set<BtcTransaction> txsWithEnoughConfirmations =
                releaseTransactionSet.sliceWithConfirmations(
                        rskExecutionBlock.getNumber(),
                        bridgeConstants.getRsk2BtcMinimumAcceptableConfirmations(),
                        Optional.of(1));

        // Add the btc transaction to the 'awaiting signatures' list
        if (txsWithEnoughConfirmations.size() > 0) {
            txsWaitingForSignatures.put(
                    rskTx.getHash(), txsWithEnoughConfirmations.iterator().next());
        }
    }

    /**
     * If federation change output value had to be increased to be non-dust, the federation now has
     * more BTC than it should. So, we burn some sBTC to make balances match.
     *
     * @param btcTx The btc tx that was just completed
     * @param sentByUser The number of sBTC originaly sent by the user
     */
    private void adjustBalancesIfChangeOutputWasDust(BtcTransaction btcTx, Coin sentByUser) {
        if (btcTx.getOutputs().size() <= 1) {
            // If there is no change, do-nothing
            return;
        }
        Coin sumInputs = Coin.ZERO;
        for (TransactionInput transactionInput : btcTx.getInputs()) {
            sumInputs = sumInputs.add(transactionInput.getValue());
        }
        Coin change = btcTx.getOutput(1).getValue();
        Coin spentByFederation = sumInputs.subtract(change);
        if (spentByFederation.isLessThan(sentByUser)) {
            Coin coinsToBurn = sentByUser.subtract(spentByFederation);
            rskRepository.transfer(
                    PrecompiledContracts.BRIDGE_ADDR,
                    BURN_ADDRESS,
                    co.rsk.core.Coin.fromBitcoin(coinsToBurn));
        }
    }

    /**
     * Adds a federator signature to a btc release tx. The hash for the signature must be calculated
     * with Transaction.SigHash.ALL and anyoneCanPay=false. The signature must be canonical. If
     * enough signatures were added, ask federators to broadcast the btc release tx.
     *
     * @param federatorPublicKey Federator who is signing
     * @param signatures 1 signature per btc tx input
     * @param rskTxHash The id of the rsk tx
     */
    public void addSignature(BtcECKey federatorPublicKey, List<byte[]> signatures, byte[] rskTxHash)
            throws Exception {
        Context.propagate(btcContext);
        Federation retiringFederation = getRetiringFederation();
        if (!getActiveFederation().getBtcPublicKeys().contains(federatorPublicKey)
                && (retiringFederation == null
                        || !retiringFederation.getBtcPublicKeys().contains(federatorPublicKey))) {
            logger.warn(
                    "Supplied federator public key {} does not belong to any of the federators.",
                    federatorPublicKey);
            return;
        }
        BtcTransaction btcTx =
                provider.getRskTxsWaitingForSignatures().get(new Keccak256(rskTxHash));
        if (btcTx == null) {
            logger.warn(
                    "No tx waiting for signature for hash {}. Probably fully signed already.",
                    new Keccak256(rskTxHash));
            return;
        }
        if (btcTx.getInputs().size() != signatures.size()) {
            logger.warn(
                    "Expected {} signatures but received {}.",
                    btcTx.getInputs().size(),
                    signatures.size());
            return;
        }
        eventLogger.logAddSignature(federatorPublicKey, btcTx, rskTxHash);
        processSigning(federatorPublicKey, signatures, rskTxHash, btcTx);
    }

    private void processSigning(
            BtcECKey federatorPublicKey,
            List<byte[]> signatures,
            byte[] rskTxHash,
            BtcTransaction btcTx)
            throws IOException {
        // Build input hashes for signatures
        int numInputs = btcTx.getInputs().size();

        List<Sha256Hash> sighashes = new ArrayList<>();
        List<TransactionSignature> txSigs = new ArrayList<>();
        for (int i = 0; i < numInputs; i++) {
            TransactionInput txIn = btcTx.getInput(i);
            Script inputScript = txIn.getScriptSig();
            List<ScriptChunk> chunks = inputScript.getChunks();
            byte[] program = chunks.get(chunks.size() - 1).data;
            Script redeemScript = new Script(program);
            sighashes.add(
                    btcTx.hashForSignature(i, redeemScript, BtcTransaction.SigHash.ALL, false));
        }

        // Verify given signatures are correct before proceeding
        for (int i = 0; i < numInputs; i++) {
            BtcECKey.ECDSASignature sig;
            try {
                sig = BtcECKey.ECDSASignature.decodeFromDER(signatures.get(i));
            } catch (RuntimeException e) {
                logger.warn(
                        "Malformed signature for input {} of tx {}: {}",
                        i,
                        new Keccak256(rskTxHash),
                        Hex.toHexString(signatures.get(i)));
                return;
            }

            Sha256Hash sighash = sighashes.get(i);

            if (!federatorPublicKey.verify(sighash, sig)) {
                logger.warn(
                        "Signature {} {} is not valid for hash {} and public key {}",
                        i,
                        Hex.toHexString(sig.encodeToDER()),
                        sighash,
                        federatorPublicKey);
                return;
            }

            TransactionSignature txSig =
                    new TransactionSignature(sig, BtcTransaction.SigHash.ALL, false);
            txSigs.add(txSig);
            if (!txSig.isCanonical()) {
                logger.warn(
                        "Signature {} {} is not canonical.", i, Hex.toHexString(signatures.get(i)));
                return;
            }
        }

        // All signatures are correct. Proceed to signing
        for (int i = 0; i < numInputs; i++) {
            Sha256Hash sighash = sighashes.get(i);
            TransactionInput input = btcTx.getInput(i);
            Script inputScript = input.getScriptSig();

            boolean alreadySignedByThisFederator =
                    isInputSignedByThisFederator(federatorPublicKey, sighash, input);

            // Sign the input if it wasn't already
            if (!alreadySignedByThisFederator) {
                try {
                    int sigIndex = inputScript.getSigInsertionIndex(sighash, federatorPublicKey);
                    inputScript =
                            ScriptBuilder.updateScriptWithSignature(
                                    inputScript, txSigs.get(i).encodeToBitcoin(), sigIndex, 1, 1);
                    input.setScriptSig(inputScript);
                    logger.debug("Tx input {} for tx {} signed.", i, new Keccak256(rskTxHash));
                } catch (IllegalStateException e) {
                    Federation retiringFederation = getRetiringFederation();
                    if (getActiveFederation().hasBtcPublicKey(federatorPublicKey)) {
                        logger.debug(
                                "A member of the active federation is trying to sign a tx of the retiring one");
                        return;
                    } else if (retiringFederation != null
                            && retiringFederation.hasBtcPublicKey(federatorPublicKey)) {
                        logger.debug(
                                "A member of the retiring federation is trying to sign a tx of the active one");
                        return;
                    }
                    throw e;
                }
            } else {
                logger.warn(
                        "Input {} of tx {} already signed by this federator.",
                        i,
                        new Keccak256(rskTxHash));
                break;
            }
        }

        // If tx fully signed
        if (hasEnoughSignatures(btcTx)) {
            logger.info(
                    "Tx fully signed {}. Hex: {}",
                    btcTx,
                    Hex.toHexString(btcTx.bitcoinSerialize()));
            provider.getRskTxsWaitingForSignatures().remove(new Keccak256(rskTxHash));
            eventLogger.logReleaseBtc(btcTx);
        } else {
            logger.debug("Tx not yet fully signed {}.", new Keccak256(rskTxHash));
        }
    }

    /**
     * Check if the p2sh multisig scriptsig of the given input was already signed by
     * federatorPublicKey.
     *
     * @param federatorPublicKey The key that may have been used to sign
     * @param sighash the sighash that corresponds to the input
     * @param input The input
     * @return true if the input was already signed by the specified key, false otherwise.
     */
    private boolean isInputSignedByThisFederator(
            BtcECKey federatorPublicKey, Sha256Hash sighash, TransactionInput input) {
        List<ScriptChunk> chunks = input.getScriptSig().getChunks();
        for (int j = 1; j < chunks.size() - 1; j++) {
            ScriptChunk chunk = chunks.get(j);

            if (chunk.data.length == 0) {
                continue;
            }

            TransactionSignature sig2 =
                    TransactionSignature.decodeFromBitcoin(chunk.data, false, false);

            if (federatorPublicKey.verify(sighash, sig2)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a btc tx has been signed by the required number of federators.
     *
     * @param btcTx The btc tx to check
     * @return True if was signed by the required number of federators, false otherwise
     */
    private boolean hasEnoughSignatures(BtcTransaction btcTx) {
        // When the tx is constructed OP_0 are placed where signature should go.
        // Check all OP_0 have been replaced with actual signatures in all inputs
        Context.propagate(btcContext);
        for (TransactionInput input : btcTx.getInputs()) {
            Script scriptSig = input.getScriptSig();
            List<ScriptChunk> chunks = scriptSig.getChunks();
            for (int i = 1; i < chunks.size(); i++) {
                ScriptChunk chunk = chunks.get(i);
                if (!chunk.isOpCode() && chunk.data.length == 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns the btc tx that federators need to sign or broadcast
     *
     * @return a StateForFederator serialized in RLP
     */
    public byte[] getStateForBtcReleaseClient() throws IOException {
        StateForFederator stateForFederator =
                new StateForFederator(provider.getRskTxsWaitingForSignatures());
        return stateForFederator.getEncoded();
    }

    /**
     * Returns the insternal state of the bridge
     *
     * @return a BridgeState serialized in RLP
     */
    public byte[] getStateForDebugging() throws IOException, BlockStoreException {
        BridgeState stateForDebugging =
                new BridgeState(getBtcBlockchainBestChainHeight(), provider);

        return stateForDebugging.getEncoded();
    }

    /** Returns the bitcoin blockchain best chain height know by the bridge contract */
    public int getBtcBlockchainBestChainHeight() throws IOException, BlockStoreException {
        return getBtcBlockchainChainHead().getHeight();
    }

    /** Returns the bitcoin blockchain initial stored block height */
    public int getBtcBlockchainInitialBlockHeight() throws IOException {
        return getLowestBlock().getHeight();
    }

    /**
     * @deprecated Returns an array of block hashes known by the bridge contract. Federators can use
     *     this to find what is the latest block in the mainchain the bridge has.
     * @return a List of bitcoin block hashes
     */
    @Deprecated
    public List<Sha256Hash> getBtcBlockchainBlockLocator() throws IOException, BlockStoreException {
        StoredBlock initialBtcStoredBlock = this.getLowestBlock();
        final int maxHashesToInform = 100;
        List<Sha256Hash> blockLocator = new ArrayList<>();
        StoredBlock cursor = getBtcBlockchainChainHead();
        int bestBlockHeight = cursor.getHeight();
        blockLocator.add(cursor.getHeader().getHash());
        if (bestBlockHeight > initialBtcStoredBlock.getHeight()) {
            boolean stop = false;
            int i = 0;
            try {
                while (blockLocator.size() <= maxHashesToInform && !stop) {
                    int blockHeight = (int) (bestBlockHeight - Math.pow(2, i));
                    if (blockHeight <= initialBtcStoredBlock.getHeight()) {
                        blockLocator.add(initialBtcStoredBlock.getHeader().getHash());
                        stop = true;
                    } else {
                        cursor = this.getPrevBlockAtHeight(cursor, blockHeight);
                        blockLocator.add(cursor.getHeader().getHash());
                    }
                    i++;
                }
            } catch (Exception e) {
                logger.error("Failed to walk the block chain whilst constructing a locator");
                panicProcessor.panic(
                        "btcblockchain",
                        "Failed to walk the block chain whilst constructing a locator");
                throw new RuntimeException(e);
            }
            if (!stop) {
                blockLocator.add(initialBtcStoredBlock.getHeader().getHash());
            }
        }
        return blockLocator;
    }

    public Sha256Hash getBtcBlockchainBlockHashAtDepth(int depth)
            throws BlockStoreException, IOException {
        Context.propagate(btcContext);
        this.ensureBtcBlockStore();

        StoredBlock head = btcBlockStore.getChainHead();
        int maxDepth = head.getHeight() - getLowestBlock().getHeight();

        if (depth < 0 || depth > maxDepth) {
            throw new IndexOutOfBoundsException(
                    String.format("Depth must be between 0 and %d", maxDepth));
        }

        StoredBlock blockAtDepth = btcBlockStore.getStoredBlockAtMainChainDepth(depth);
        return blockAtDepth.getHeader().getHash();
    }

    public Long getBtcTransactionConfirmationsGetCost(Object[] args) {
        final long BASIC_COST = 13_600;
        final long STEP_COST = 70;
        final long DOUBLE_HASH_COST = 72 * 2;

        Sha256Hash btcBlockHash;
        int branchHashesSize;
        try {
            btcBlockHash = Sha256Hash.wrap((byte[]) args[1]);
            Object[] merkleBranchHashesArray = (Object[]) args[3];
            branchHashesSize = merkleBranchHashesArray.length;
        } catch (NullPointerException | IllegalArgumentException e) {
            return BASIC_COST;
        }

        // Dynamic cost based on the depth of the block that contains
        // the transaction. Find such depth first, then calculate
        // the cost.
        Context.propagate(btcContext);
        try {
            this.ensureBtcBlockStore();
            final StoredBlock block = btcBlockStore.getFromCache(btcBlockHash);

            // Block not found, default to basic cost
            if (block == null) {
                return BASIC_COST;
            }

            final int bestChainHeight = getBtcBlockchainBestChainHeight();

            // Make sure calculated depth is >= 0
            final int blockDepth = Math.max(0, bestChainHeight - block.getHeight());

            // Block too deep, default to basic cost
            if (blockDepth > BTC_TRANSACTION_CONFIRMATION_MAX_DEPTH) {
                return BASIC_COST;
            }

            return BASIC_COST + blockDepth * STEP_COST + branchHashesSize * DOUBLE_HASH_COST;
        } catch (IOException | BlockStoreException e) {
            logger.warn(
                    "getBtcTransactionConfirmationsGetCost btcBlockHash:{} there was a problem "
                            + "gathering the block depth while calculating the gas cost. "
                            + "Defaulting to basic cost.",
                    btcBlockHash,
                    e);
            return BASIC_COST;
        }
    }

    /**
     * @param btcTxHash The BTC transaction Hash
     * @param btcBlockHash The BTC block hash
     * @param merkleBranch The merkle branch
     * @throws BlockStoreException
     * @throws IOException
     */
    public Integer getBtcTransactionConfirmations(
            Sha256Hash btcTxHash, Sha256Hash btcBlockHash, MerkleBranch merkleBranch)
            throws BlockStoreException, IOException {
        Context.propagate(btcContext);
        this.ensureBtcBlockChain();

        // Get the block using the given block hash
        StoredBlock block = btcBlockStore.getFromCache(btcBlockHash);
        if (block == null) {
            return BTC_TRANSACTION_CONFIRMATION_INEXISTENT_BLOCK_HASH_ERROR_CODE;
        }

        final int bestChainHeight = getBtcBlockchainBestChainHeight();

        // Prevent diving too deep in the blockchain to avoid high processing costs
        final int blockDepth = Math.max(0, bestChainHeight - block.getHeight());
        if (blockDepth > BTC_TRANSACTION_CONFIRMATION_MAX_DEPTH) {
            return BTC_TRANSACTION_CONFIRMATION_BLOCK_TOO_OLD_ERROR_CODE;
        }

        try {
            StoredBlock storedBlock =
                    btcBlockStore.getStoredBlockAtMainChainHeight(block.getHeight());
            // Make sure it belongs to the best chain
            if (storedBlock == null || !storedBlock.equals(block)) {
                return BTC_TRANSACTION_CONFIRMATION_BLOCK_NOT_IN_BEST_CHAIN_ERROR_CODE;
            }
        } catch (BlockStoreException e) {
            logger.warn(
                    String.format("Illegal state trying to get block with hash {}", btcBlockHash),
                    e);
            return BTC_TRANSACTION_CONFIRMATION_INCONSISTENT_BLOCK_ERROR_CODE;
        }

        if (!merkleBranch.proves(btcTxHash, block.getHeader())) {
            return BTC_TRANSACTION_CONFIRMATION_INVALID_MERKLE_BRANCH_ERROR_CODE;
        }

        return bestChainHeight - block.getHeight() + 1;
    }

    private StoredBlock getPrevBlockAtHeight(StoredBlock cursor, int height)
            throws BlockStoreException {
        if (cursor.getHeight() == height) {
            return cursor;
        }

        boolean stop = false;
        StoredBlock current = cursor;
        while (!stop) {
            current = current.getPrev(this.btcBlockStore);
            stop = current.getHeight() == height;
        }
        return current;
    }

    /**
     * Returns whether a given btc transaction hash has already been processed by the bridge.
     *
     * @param btcTxHash the btc tx hash to check.
     * @return a Boolean indicating whether the given btc tx hash was already processed by the
     *     bridge.
     * @throws IOException
     */
    public Boolean isBtcTxHashAlreadyProcessed(Sha256Hash btcTxHash) throws IOException {
        return provider.getBtcTxHashesAlreadyProcessed().containsKey(btcTxHash);
    }

    /**
     * Returns the RSK blockchain height a given btc transaction hash was processed at by the
     * bridge.
     *
     * @param btcTxHash the btc tx hash for which to retrieve the height.
     * @return a Long with the processed height. If the hash was not processed -1 is returned.
     * @throws IOException
     */
    public Long getBtcTxHashProcessedHeight(Sha256Hash btcTxHash) throws IOException {
        Map<Sha256Hash, Long> btcTxHashes = provider.getBtcTxHashesAlreadyProcessed();

        // Return -1 if the transaction hasn't been processed
        if (!btcTxHashes.containsKey(btcTxHash)) {
            return -1L;
        }

        return btcTxHashes.get(btcTxHash);
    }

    /**
     * Returns the currently active federation. See getActiveFederationReference() for details.
     *
     * @return the currently active federation.
     */
    public Federation getActiveFederation() {
        return federationSupport.getActiveFederation();
    }

    /**
     * Returns the currently retiring federation. See getRetiringFederationReference() for details.
     *
     * @return the retiring federation.
     */
    @Nullable
    public Federation getRetiringFederation() {
        return federationSupport.getRetiringFederation();
    }

    private List<UTXO> getActiveFederationBtcUTXOs() throws IOException {
        return federationSupport.getActiveFederationBtcUTXOs();
    }

    private List<UTXO> getRetiringFederationBtcUTXOs() throws IOException {
        return federationSupport.getRetiringFederationBtcUTXOs();
    }

    /**
     * Returns the federation bitcoin address.
     *
     * @return the federation bitcoin address.
     */
    public Address getFederationAddress() {
        return getActiveFederation().getAddress();
    }

    /**
     * Returns the federation's size
     *
     * @return the federation size
     */
    public Integer getFederationSize() {
        return getActiveFederation().getBtcPublicKeys().size();
    }

    /**
     * Returns the federation's minimum required signatures
     *
     * @return the federation minimum required signatures
     */
    public Integer getFederationThreshold() {
        return getActiveFederation().getNumberOfSignaturesRequired();
    }

    /**
     * Returns the public key of the federation's federator at the given index
     *
     * @param index the federator's index (zero-based)
     * @return the federator's public key
     */
    public byte[] getFederatorPublicKey(int index) {
        return federationSupport.getFederatorBtcPublicKey(index);
    }

    /**
     * Returns the public key of given type of the federation's federator at the given index
     *
     * @param index the federator's index (zero-based)
     * @param keyType the key type
     * @return the federator's public key
     */
    public byte[] getFederatorPublicKeyOfType(int index, FederationMember.KeyType keyType) {
        return federationSupport.getFederatorPublicKeyOfType(index, keyType);
    }

    /**
     * Returns the federation's creation time
     *
     * @return the federation creation time
     */
    public Instant getFederationCreationTime() {
        return getActiveFederation().getCreationTime();
    }

    /**
     * Returns the federation's creation block number
     *
     * @return the federation creation block number
     */
    public long getFederationCreationBlockNumber() {
        return getActiveFederation().getCreationBlockNumber();
    }

    /**
     * Returns the retiring federation bitcoin address.
     *
     * @return the retiring federation bitcoin address, null if no retiring federation exists
     */
    public Address getRetiringFederationAddress() {
        Federation retiringFederation = getRetiringFederation();
        if (retiringFederation == null) {
            return null;
        }

        return retiringFederation.getAddress();
    }

    /**
     * Returns the retiring federation's size
     *
     * @return the retiring federation size, -1 if no retiring federation exists
     */
    public Integer getRetiringFederationSize() {
        Federation retiringFederation = getRetiringFederation();
        if (retiringFederation == null) {
            return -1;
        }

        return retiringFederation.getBtcPublicKeys().size();
    }

    /**
     * Returns the retiring federation's minimum required signatures
     *
     * @return the retiring federation minimum required signatures, -1 if no retiring federation
     *     exists
     */
    public Integer getRetiringFederationThreshold() {
        Federation retiringFederation = getRetiringFederation();
        if (retiringFederation == null) {
            return -1;
        }

        return retiringFederation.getNumberOfSignaturesRequired();
    }

    /**
     * Returns the public key of the retiring federation's federator at the given index
     *
     * @param index the retiring federator's index (zero-based)
     * @return the retiring federator's public key, null if no retiring federation exists
     */
    public byte[] getRetiringFederatorPublicKey(int index) {
        Federation retiringFederation = getRetiringFederation();
        if (retiringFederation == null) {
            return null;
        }

        List<BtcECKey> publicKeys = retiringFederation.getBtcPublicKeys();

        if (index < 0 || index >= publicKeys.size()) {
            throw new IndexOutOfBoundsException(
                    String.format(
                            "Retiring federator index must be between 0 and %d",
                            publicKeys.size() - 1));
        }

        return publicKeys.get(index).getPubKey();
    }

    /**
     * Returns the public key of the given type of the retiring federation's federator at the given
     * index
     *
     * @param index the retiring federator's index (zero-based)
     * @param keyType the key type
     * @return the retiring federator's public key of the given type, null if no retiring federation
     *     exists
     */
    public byte[] getRetiringFederatorPublicKeyOfType(int index, FederationMember.KeyType keyType) {
        Federation retiringFederation = getRetiringFederation();
        if (retiringFederation == null) {
            return null;
        }

        return federationSupport.getMemberPublicKeyOfType(
                retiringFederation.getMembers(), index, keyType, "Retiring federator");
    }

    /**
     * Returns the retiring federation's creation time
     *
     * @return the retiring federation creation time, null if no retiring federation exists
     */
    public Instant getRetiringFederationCreationTime() {
        Federation retiringFederation = getRetiringFederation();
        if (retiringFederation == null) {
            return null;
        }

        return retiringFederation.getCreationTime();
    }

    /**
     * Returns the retiring federation's creation block number
     *
     * @return the retiring federation creation block number, -1 if no retiring federation exists
     */
    public long getRetiringFederationCreationBlockNumber() {
        Federation retiringFederation = getRetiringFederation();
        if (retiringFederation == null) {
            return -1L;
        }
        return retiringFederation.getCreationBlockNumber();
    }

    /**
     * Returns the currently live federations This would be the active federation plus potentially
     * the retiring federation
     *
     * @return a list of live federations
     */
    private List<Federation> getLiveFederations() {
        List<Federation> liveFederations = new ArrayList<>();
        liveFederations.add(getActiveFederation());
        Federation retiringFederation = getRetiringFederation();
        if (retiringFederation != null) {
            liveFederations.add(retiringFederation);
        }
        return liveFederations;
    }

    /**
     * Creates a new pending federation If there's currently no pending federation and no funds
     * remain to be moved from a previous federation, a new one is created. Otherwise, -1 is
     * returned if there's already a pending federation, -2 is returned if there is a federation
     * awaiting to be active, or -3 if funds are left from a previous one.
     *
     * @param dryRun whether to just do a dry run
     * @return 1 upon success, -1 when a pending federation is present, -2 when a federation is to
     *     be activated, and if -3 funds are still to be moved between federations.
     */
    private Integer createFederation(boolean dryRun) throws IOException {
        PendingFederation currentPendingFederation = provider.getPendingFederation();

        if (currentPendingFederation != null) {
            return -1;
        }

        if (federationSupport.amAwaitingFederationActivation()) {
            return -2;
        }

        if (getRetiringFederation() != null) {
            return -3;
        }

        if (dryRun) {
            return 1;
        }

        currentPendingFederation = new PendingFederation(Collections.emptyList());

        provider.setPendingFederation(currentPendingFederation);

        // Clear votes on election
        provider.getFederationElection(bridgeConstants.getFederationChangeAuthorizer()).clear();

        return 1;
    }

    /**
     * Adds the given keys to the current pending federation.
     *
     * @param dryRun whether to just do a dry run
     * @param btcKey the BTC public key to add
     * @param rskKey the RSK public key to add
     * @param mstKey the MST public key to add
     * @return 1 upon success, -1 if there was no pending federation, -2 if the key was already in
     *     the pending federation
     */
    private Integer addFederatorPublicKeyMultikey(
            boolean dryRun, BtcECKey btcKey, ECKey rskKey, ECKey mstKey) {
        PendingFederation currentPendingFederation = provider.getPendingFederation();

        if (currentPendingFederation == null) {
            return -1;
        }

        if (currentPendingFederation.getBtcPublicKeys().contains(btcKey)
                || currentPendingFederation.getMembers().stream()
                        .map(m -> m.getRskPublicKey())
                        .anyMatch(k -> k.equals(rskKey))
                || currentPendingFederation.getMembers().stream()
                        .map(m -> m.getMstPublicKey())
                        .anyMatch(k -> k.equals(mstKey))) {
            return -2;
        }

        if (dryRun) {
            return 1;
        }

        FederationMember member = new FederationMember(btcKey, rskKey, mstKey);

        currentPendingFederation = currentPendingFederation.addMember(member);

        provider.setPendingFederation(currentPendingFederation);

        return 1;
    }

    /**
     * Commits the currently pending federation. That is, the retiring federation is set to be the
     * currently active federation, the active federation is replaced with a new federation
     * generated from the pending federation, and the pending federation is wiped out. Also, UTXOs
     * are moved from active to retiring so that the transfer of funds can begin.
     *
     * @param dryRun whether to just do a dry run
     * @param hash the pending federation's hash. This is checked the execution block's pending
     *     federation hash for equality.
     * @return 1 upon success, -1 if there was no pending federation, -2 if the pending federation
     *     was incomplete, -3 if the given hash doesn't match the current pending federation's hash.
     */
    private Integer commitFederation(boolean dryRun, Keccak256 hash) throws IOException {
        PendingFederation currentPendingFederation = provider.getPendingFederation();

        if (currentPendingFederation == null) {
            return -1;
        }

        if (!currentPendingFederation.isComplete()) {
            return -2;
        }

        if (!hash.equals(currentPendingFederation.getHash())) {
            return -3;
        }

        if (dryRun) {
            return 1;
        }

        // Move UTXOs from the new federation into the old federation
        // and clear the new federation's UTXOs
        List<UTXO> utxosToMove = new ArrayList<>(provider.getNewFederationBtcUTXOs());
        provider.getNewFederationBtcUTXOs().clear();
        List<UTXO> oldFederationUTXOs = provider.getOldFederationBtcUTXOs();
        oldFederationUTXOs.clear();
        utxosToMove.forEach(utxo -> oldFederationUTXOs.add(utxo));

        // Network parameters for the new federation are taken from the bridge constants.
        // Creation time is the block's timestamp.
        Instant creationTime = Instant.ofEpochMilli(rskExecutionBlock.getTimestamp());
        provider.setOldFederation(getActiveFederation());
        provider.setNewFederation(
                currentPendingFederation.buildFederation(
                        creationTime,
                        rskExecutionBlock.getNumber(),
                        bridgeConstants.getBtcParams()));
        provider.setPendingFederation(null);

        // Clear votes on election
        provider.getFederationElection(bridgeConstants.getFederationChangeAuthorizer()).clear();

        eventLogger.logCommitFederation(
                rskExecutionBlock, provider.getOldFederation(), provider.getNewFederation());

        return 1;
    }

    /**
     * Rolls back the currently pending federation That is, the pending federation is wiped out.
     *
     * @param dryRun whether to just do a dry run
     * @return 1 upon success, 1 if there was no pending federation
     */
    private Integer rollbackFederation(boolean dryRun) {
        PendingFederation currentPendingFederation = provider.getPendingFederation();

        if (currentPendingFederation == null) {
            return -1;
        }

        if (dryRun) {
            return 1;
        }

        provider.setPendingFederation(null);

        // Clear votes on election
        provider.getFederationElection(bridgeConstants.getFederationChangeAuthorizer()).clear();

        return 1;
    }

    public Integer voteFederationChange(Transaction tx, ABICallSpec callSpec) {
        // Must be on one of the allowed functions
        if (!FEDERATION_CHANGE_FUNCTIONS.contains(callSpec.getFunction())) {
            return FEDERATION_CHANGE_GENERIC_ERROR_CODE;
        }

        AddressBasedAuthorizer authorizer = bridgeConstants.getFederationChangeAuthorizer();

        // Must be authorized to vote (checking for signature)
        if (!authorizer.isAuthorized(tx)) {
            return FEDERATION_CHANGE_GENERIC_ERROR_CODE;
        }

        // Try to do a dry-run and only register the vote if the
        // call would be successful
        ABICallVoteResult result;
        try {
            result = executeVoteFederationChangeFunction(true, callSpec);
        } catch (IOException e) {
            result = new ABICallVoteResult(false, FEDERATION_CHANGE_GENERIC_ERROR_CODE);
        } catch (BridgeIllegalArgumentException e) {
            result = new ABICallVoteResult(false, FEDERATION_CHANGE_GENERIC_ERROR_CODE);
        }

        // Return if the dry run failed or we are on a reversible execution
        if (!result.wasSuccessful()) {
            return (Integer) result.getResult();
        }

        ABICallElection election = provider.getFederationElection(authorizer);
        // Register the vote. It is expected to succeed, since all previous checks succeeded
        if (!election.vote(callSpec, tx.getSender())) {
            logger.warn("Unexpected federation change vote failure");
            return FEDERATION_CHANGE_GENERIC_ERROR_CODE;
        }

        // If enough votes have been reached, then actually execute the function
        ABICallSpec winnerSpec = election.getWinner();
        if (winnerSpec != null) {
            try {
                result = executeVoteFederationChangeFunction(false, winnerSpec);
            } catch (IOException e) {
                logger.warn("Unexpected federation change vote exception: {}", e.getMessage());
                return FEDERATION_CHANGE_GENERIC_ERROR_CODE;
            } finally {
                // Clear the winner so that we don't repeat ourselves
                election.clearWinners();
            }
        }

        return (Integer) result.getResult();
    }

    private ABICallVoteResult executeVoteFederationChangeFunction(
            boolean dryRun, ABICallSpec callSpec) throws IOException {
        // Try to do a dry-run and only register the vote if the
        // call would be successful
        ABICallVoteResult result;
        Integer executionResult;
        switch (callSpec.getFunction()) {
            case "create":
                executionResult = createFederation(dryRun);
                result = new ABICallVoteResult(executionResult == 1, executionResult);
                break;
            case "add":
                byte[] publicKeyBytes = callSpec.getArguments()[0];
                BtcECKey publicKey;
                ECKey publicKeyEc;
                try {
                    publicKey = BtcECKey.fromPublicOnly(publicKeyBytes);
                    publicKeyEc = ECKey.fromPublicOnly(publicKeyBytes);
                } catch (Exception e) {
                    throw new BridgeIllegalArgumentException(
                            "Public key could not be parsed " + Hex.toHexString(publicKeyBytes), e);
                }
                executionResult =
                        addFederatorPublicKeyMultikey(dryRun, publicKey, publicKeyEc, publicKeyEc);
                result = new ABICallVoteResult(executionResult == 1, executionResult);
                break;
            case "add-multi":
                BtcECKey btcPublicKey;
                ECKey rskPublicKey, mstPublicKey;
                try {
                    btcPublicKey = BtcECKey.fromPublicOnly(callSpec.getArguments()[0]);
                } catch (Exception e) {
                    throw new BridgeIllegalArgumentException(
                            "BTC public key could not be parsed "
                                    + Hex.toHexString(callSpec.getArguments()[0]),
                            e);
                }

                try {
                    rskPublicKey = ECKey.fromPublicOnly(callSpec.getArguments()[1]);
                } catch (Exception e) {
                    throw new BridgeIllegalArgumentException(
                            "RSK public key could not be parsed "
                                    + Hex.toHexString(callSpec.getArguments()[1]),
                            e);
                }

                try {
                    mstPublicKey = ECKey.fromPublicOnly(callSpec.getArguments()[2]);
                } catch (Exception e) {
                    throw new BridgeIllegalArgumentException(
                            "MST public key could not be parsed "
                                    + Hex.toHexString(callSpec.getArguments()[2]),
                            e);
                }
                executionResult =
                        addFederatorPublicKeyMultikey(
                                dryRun, btcPublicKey, rskPublicKey, mstPublicKey);
                result = new ABICallVoteResult(executionResult == 1, executionResult);
                break;
            case "commit":
                Keccak256 hash = new Keccak256((byte[]) callSpec.getArguments()[0]);
                executionResult = commitFederation(dryRun, hash);
                result = new ABICallVoteResult(executionResult == 1, executionResult);
                break;
            case "rollback":
                executionResult = rollbackFederation(dryRun);
                result = new ABICallVoteResult(executionResult == 1, executionResult);
                break;
            default:
                // Fail by default
                result = new ABICallVoteResult(false, FEDERATION_CHANGE_GENERIC_ERROR_CODE);
        }

        return result;
    }

    /**
     * Returns the currently pending federation hash, or null if none exists
     *
     * @return the currently pending federation hash, or null if none exists
     */
    public byte[] getPendingFederationHash() {
        PendingFederation currentPendingFederation = provider.getPendingFederation();

        if (currentPendingFederation == null) {
            return null;
        }

        return currentPendingFederation.getHash().getBytes();
    }

    /**
     * Returns the currently pending federation size, or -1 if none exists
     *
     * @return the currently pending federation size, or -1 if none exists
     */
    public Integer getPendingFederationSize() {
        PendingFederation currentPendingFederation = provider.getPendingFederation();

        if (currentPendingFederation == null) {
            return -1;
        }

        return currentPendingFederation.getBtcPublicKeys().size();
    }

    /**
     * Returns the currently pending federation federator's public key at the given index, or null
     * if none exists
     *
     * @param index the federator's index (zero-based)
     * @return the pending federation's federator public key
     */
    public byte[] getPendingFederatorPublicKey(int index) {
        PendingFederation currentPendingFederation = provider.getPendingFederation();

        if (currentPendingFederation == null) {
            return null;
        }

        List<BtcECKey> publicKeys = currentPendingFederation.getBtcPublicKeys();

        if (index < 0 || index >= publicKeys.size()) {
            throw new IndexOutOfBoundsException(
                    String.format(
                            "Federator index must be between 0 and %d", publicKeys.size() - 1));
        }

        return publicKeys.get(index).getPubKey();
    }

    /**
     * Returns the public key of the given type of the pending federation's federator at the given
     * index
     *
     * @param index the federator's index (zero-based)
     * @param keyType the key type
     * @return the pending federation's federator public key of given type
     */
    public byte[] getPendingFederatorPublicKeyOfType(int index, FederationMember.KeyType keyType) {
        PendingFederation currentPendingFederation = provider.getPendingFederation();

        if (currentPendingFederation == null) {
            return null;
        }

        return federationSupport.getMemberPublicKeyOfType(
                currentPendingFederation.getMembers(), index, keyType, "Federator");
    }

    /**
     * Returns the lock whitelist size, that is, the number of whitelisted addresses
     *
     * @return the lock whitelist size
     */
    public Integer getLockWhitelistSize() {
        return provider.getLockWhitelist().getSize();
    }

    /**
     * Returns the lock whitelist address stored at the given index, or null if the index is out of
     * bounds
     *
     * @param index the index at which to get the address
     * @return the base58-encoded address stored at the given index, or null if index is out of
     *     bounds
     */
    public LockWhitelistEntry getLockWhitelistEntryByIndex(int index) {
        List<LockWhitelistEntry> entries = provider.getLockWhitelist().getAll();

        if (index < 0 || index >= entries.size()) {
            return null;
        }

        return entries.get(index);
    }

    /**
     * @param addressBase58
     * @return
     */
    public LockWhitelistEntry getLockWhitelistEntryByAddress(String addressBase58) {
        try {
            Address address = getParsedAddress(addressBase58);
            return provider.getLockWhitelist().get(address);
        } catch (AddressFormatException e) {
            logger.warn(INVALID_ADDRESS_FORMAT_MESSAGE, e);
            return null;
        }
    }

    /**
     * Adds the given address to the lock whitelist. Returns 1 upon success, or -1 if the address
     * was already in the whitelist.
     *
     * @param addressBase58 the base58-encoded address to add to the whitelist
     * @param maxTransferValue the max amount of satoshis enabled to transfer for this address
     * @return 1 upon success, -1 if the address was already in the whitelist, -2 if address is
     *     invalid LOCK_WHITELIST_GENERIC_ERROR_CODE otherwise.
     */
    public Integer addOneOffLockWhitelistAddress(
            Transaction tx, String addressBase58, BigInteger maxTransferValue) {
        try {
            Address address = getParsedAddress(addressBase58);
            Coin maxTransferValueCoin = Coin.valueOf(maxTransferValue.longValueExact());
            return this.addLockWhitelistAddress(
                    tx, new OneOffWhiteListEntry(address, maxTransferValueCoin));
        } catch (AddressFormatException e) {
            logger.warn(INVALID_ADDRESS_FORMAT_MESSAGE, e);
            return LOCK_WHITELIST_INVALID_ADDRESS_FORMAT_ERROR_CODE;
        }
    }

    public Integer addUnlimitedLockWhitelistAddress(Transaction tx, String addressBase58) {
        try {
            Address address = getParsedAddress(addressBase58);
            return this.addLockWhitelistAddress(tx, new UnlimitedWhiteListEntry(address));
        } catch (AddressFormatException e) {
            logger.warn(INVALID_ADDRESS_FORMAT_MESSAGE, e);
            return LOCK_WHITELIST_INVALID_ADDRESS_FORMAT_ERROR_CODE;
        }
    }

    private Integer addLockWhitelistAddress(Transaction tx, LockWhitelistEntry entry) {
        if (!isLockWhitelistChangeAuthorized(tx)) {
            return LOCK_WHITELIST_GENERIC_ERROR_CODE;
        }

        LockWhitelist whitelist = provider.getLockWhitelist();

        try {
            if (whitelist.isWhitelisted(entry.address())) {
                return LOCK_WHITELIST_ALREADY_EXISTS_ERROR_CODE;
            }
            whitelist.put(entry.address(), entry);
            return LOCK_WHITELIST_SUCCESS_CODE;
        } catch (Exception e) {
            logger.error("Unexpected error in addLockWhitelistAddress: {}", e);
            panicProcessor.panic("lock-whitelist", e.getMessage());
            return LOCK_WHITELIST_UNKNOWN_ERROR_CODE;
        }
    }

    private boolean isLockWhitelistChangeAuthorized(Transaction tx) {
        AddressBasedAuthorizer authorizer = bridgeConstants.getLockWhitelistChangeAuthorizer();

        return authorizer.isAuthorized(tx);
    }

    /**
     * Removes the given address from the lock whitelist. Returns 1 upon success, or -1 if the
     * address was not in the whitelist.
     *
     * @param addressBase58 the base58-encoded address to remove from the whitelist
     * @return 1 upon success, -1 if the address was not in the whitelist, -2 if the address is
     *     invalid, LOCK_WHITELIST_GENERIC_ERROR_CODE otherwise.
     */
    public Integer removeLockWhitelistAddress(Transaction tx, String addressBase58) {
        if (!isLockWhitelistChangeAuthorized(tx)) {
            return LOCK_WHITELIST_GENERIC_ERROR_CODE;
        }

        LockWhitelist whitelist = provider.getLockWhitelist();

        try {
            Address address = getParsedAddress(addressBase58);

            if (!whitelist.remove(address)) {
                return -1;
            }

            return 1;
        } catch (AddressFormatException e) {
            return -2;
        } catch (Exception e) {
            logger.error("Unexpected error in removeLockWhitelistAddress: {}", e.getMessage());
            panicProcessor.panic("lock-whitelist", e.getMessage());
            return 0;
        }
    }

    /**
     * Returns the minimum amount of satoshis a user should send to the federation.
     *
     * @return the minimum amount of satoshis a user should send to the federation.
     */
    public Coin getMinimumLockTxValue() {
        return bridgeConstants.getMinimumLockTxValue();
    }

    /**
     * Votes for a fee per kb value.
     *
     * @return 1 upon successful vote, -1 when the vote was unsuccessful,
     *     FEE_PER_KB_GENERIC_ERROR_CODE when there was an un expected error.
     */
    public Integer voteFeePerKbChange(Transaction tx, Coin feePerKb) {
        AddressBasedAuthorizer authorizer = bridgeConstants.getFeePerKbChangeAuthorizer();
        if (!authorizer.isAuthorized(tx)) {
            return FEE_PER_KB_GENERIC_ERROR_CODE;
        }

        ABICallElection feePerKbElection = provider.getFeePerKbElection(authorizer);
        ABICallSpec feeVote =
                new ABICallSpec(
                        "setFeePerKb",
                        new byte[][] {BridgeSerializationUtils.serializeCoin(feePerKb)});
        boolean successfulVote = feePerKbElection.vote(feeVote, tx.getSender());
        if (!successfulVote) {
            return -1;
        }

        ABICallSpec winner = feePerKbElection.getWinner();
        if (winner == null) {
            logger.info("Successful fee per kb vote for {}", feePerKb);
            return 1;
        }

        Coin winnerFee;
        try {
            winnerFee = BridgeSerializationUtils.deserializeCoin(winner.getArguments()[0]);
        } catch (Exception e) {
            logger.warn("Exception deserializing winner feePerKb", e);
            return FEE_PER_KB_GENERIC_ERROR_CODE;
        }

        if (winnerFee == null) {
            logger.warn("Invalid winner feePerKb: feePerKb can't be null");
            return FEE_PER_KB_GENERIC_ERROR_CODE;
        }

        if (!winnerFee.equals(feePerKb)) {
            logger.debug(
                    "Winner fee is different than the last vote: maybe you forgot to clear winners");
        }

        logger.info("Fee per kb changed to {}", winnerFee);
        provider.setFeePerKb(winnerFee);
        feePerKbElection.clear();
        return 1;
    }

    /**
     * Sets a delay in the BTC best chain to disable lock whitelist
     *
     * @param tx current RSK transaction
     * @param disableBlockDelayBI block since current BTC best chain height to disable lock
     *     whitelist
     * @return 1 if it was successful, -1 if a delay was already set, -2 if disableBlockDelay
     *     contains an invalid value
     */
    public Integer setLockWhitelistDisableBlockDelay(Transaction tx, BigInteger disableBlockDelayBI)
            throws IOException, BlockStoreException {
        if (!isLockWhitelistChangeAuthorized(tx)) {
            return LOCK_WHITELIST_GENERIC_ERROR_CODE;
        }
        LockWhitelist lockWhitelist = provider.getLockWhitelist();
        if (lockWhitelist.isDisableBlockSet()) {
            return -1;
        }
        int disableBlockDelay = disableBlockDelayBI.intValueExact();
        int bestChainHeight = getBtcBlockchainBestChainHeight();
        if (disableBlockDelay + bestChainHeight <= bestChainHeight) {
            return -2;
        }
        lockWhitelist.setDisableBlockHeight(bestChainHeight + disableBlockDelay);
        return 1;
    }

    private StoredBlock getBtcBlockchainChainHead() throws IOException, BlockStoreException {
        // Gather the current btc chain's head
        // IMPORTANT: we assume that getting the chain head from the btc blockstore
        // is enough since we're not manipulating the blockchain here, just querying it.
        this.ensureBtcBlockStore();
        return btcBlockStore.getChainHead();
    }

    /** Returns the first bitcoin block we have. It is either a checkpoint or the genesis */
    private StoredBlock getLowestBlock() throws IOException {
        InputStream checkpoints = this.getCheckPoints();
        if (checkpoints == null) {
            BtcBlock genesis = bridgeConstants.getBtcParams().getGenesisBlock();
            return new StoredBlock(genesis, genesis.getWork(), 0);
        }
        CheckpointManager manager =
                new CheckpointManager(bridgeConstants.getBtcParams(), checkpoints);
        long time = getActiveFederation().getCreationTime().toEpochMilli();
        // Go back 1 week to match CheckpointManager.checkpoint() behaviour
        time -= 86400 * 7;
        return manager.getCheckpointBefore(time);
    }

    private Pair<BtcTransaction, List<UTXO>> createMigrationTransaction(
            Wallet originWallet, Address destinationAddress) {
        Coin expectedMigrationValue = originWallet.getBalance();
        for (; ; ) {
            BtcTransaction migrationBtcTx = new BtcTransaction(originWallet.getParams());
            migrationBtcTx.addOutput(expectedMigrationValue, destinationAddress);

            SendRequest sr = SendRequest.forTx(migrationBtcTx);
            sr.changeAddress = destinationAddress;
            sr.feePerKb = getFeePerKb();
            sr.missingSigsMode = Wallet.MissingSigsMode.USE_OP_ZERO;
            sr.recipientsPayFees = true;
            try {
                originWallet.completeTx(sr);
                for (TransactionInput transactionInput : migrationBtcTx.getInputs()) {
                    transactionInput.disconnect();
                }

                List<UTXO> selectedUTXOs =
                        originWallet.getUTXOProvider()
                                .getOpenTransactionOutputs(originWallet.getWatchedAddresses())
                                .stream()
                                .filter(
                                        utxo ->
                                                migrationBtcTx.getInputs().stream()
                                                        .anyMatch(
                                                                input ->
                                                                        input.getOutpoint()
                                                                                        .getHash()
                                                                                        .equals(
                                                                                                utxo
                                                                                                        .getHash())
                                                                                && input.getOutpoint()
                                                                                                .getIndex()
                                                                                        == utxo
                                                                                                .getIndex()))
                                .collect(Collectors.toList());

                return Pair.of(migrationBtcTx, selectedUTXOs);
            } catch (InsufficientMoneyException
                    | Wallet.ExceededMaxTransactionSize
                    | Wallet.CouldNotAdjustDownwards e) {
                expectedMigrationValue = expectedMigrationValue.divide(2);
            } catch (Wallet.DustySendRequested e) {
                throw new IllegalStateException("Retiring federation wallet cannot be emptied", e);
            } catch (UTXOProviderException e) {
                throw new RuntimeException("Unexpected UTXO provider error", e);
            }
        }
    }

    // Make sure the local bitcoin blockchain is instantiated
    private void ensureBtcBlockChain() throws IOException, BlockStoreException {
        this.ensureBtcBlockStore();

        if (this.btcBlockChain == null) {
            this.btcBlockChain = new BtcBlockChain(btcContext, btcBlockStore);
        }
    }

    // Make sure the local bitcoin blockstore is instantiated
    private void ensureBtcBlockStore() throws IOException, BlockStoreException {
        if (btcBlockStore == null) {
            btcBlockStore = btcBlockStoreFactory.newInstance(rskRepository);
            NetworkParameters btcParams = this.bridgeConstants.getBtcParams();

            if (this.btcBlockStore
                    .getChainHead()
                    .getHeader()
                    .getHash()
                    .equals(btcParams.getGenesisBlock().getHash())) {
                // We are building the blockstore for the first time, so we have not set the
                // checkpoints yet.
                long time =
                        federationSupport.getActiveFederation().getCreationTime().toEpochMilli();
                InputStream checkpoints = this.getCheckPoints();
                if (time > 0 && checkpoints != null) {
                    CheckpointManager.checkpoint(btcParams, checkpoints, this.btcBlockStore, time);
                }
            }
        }
    }

    private Address getParsedAddress(String base58Address) throws AddressFormatException {
        return Address.fromBase58(btcContext.getParams(), base58Address);
    }
}
