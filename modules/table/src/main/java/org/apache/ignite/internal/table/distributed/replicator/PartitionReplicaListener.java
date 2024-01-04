/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.table.distributed.replicator;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.apache.ignite.internal.hlc.HybridTimestamp.hybridTimestampToLong;
import static org.apache.ignite.internal.lang.IgniteStringFormatter.format;
import static org.apache.ignite.internal.tx.TxState.ABANDONED;
import static org.apache.ignite.internal.tx.TxState.ABORTED;
import static org.apache.ignite.internal.tx.TxState.COMMITTED;
import static org.apache.ignite.internal.tx.TxState.FINISHING;
import static org.apache.ignite.internal.tx.TxState.PENDING;
import static org.apache.ignite.internal.tx.TxState.isFinalState;
import static org.apache.ignite.internal.util.CollectionUtils.nullOrEmpty;
import static org.apache.ignite.internal.util.CompletableFutures.emptyCollectionCompletedFuture;
import static org.apache.ignite.internal.util.CompletableFutures.emptyListCompletedFuture;
import static org.apache.ignite.internal.util.CompletableFutures.falseCompletedFuture;
import static org.apache.ignite.internal.util.CompletableFutures.nullCompletedFuture;
import static org.apache.ignite.internal.util.IgniteUtils.findAny;
import static org.apache.ignite.internal.util.IgniteUtils.findFirst;
import static org.apache.ignite.internal.util.IgniteUtils.inBusyLock;
import static org.apache.ignite.internal.util.IgniteUtils.inBusyLockAsync;
import static org.apache.ignite.lang.ErrorGroups.Transactions.TX_COMMIT_ERR;
import static org.apache.ignite.lang.ErrorGroups.Transactions.TX_FAILED_READ_WRITE_OPERATION_ERR;
import static org.apache.ignite.lang.ErrorGroups.Transactions.TX_ROLLBACK_ERR;
import static org.apache.ignite.raft.jraft.util.internal.ThrowUtil.hasCause;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.apache.ignite.internal.binarytuple.BinaryTupleCommon;
import org.apache.ignite.internal.catalog.CatalogService;
import org.apache.ignite.internal.hlc.HybridClock;
import org.apache.ignite.internal.hlc.HybridTimestamp;
import org.apache.ignite.internal.lang.IgniteBiTuple;
import org.apache.ignite.internal.lang.IgniteInternalException;
import org.apache.ignite.internal.lang.IgniteTriFunction;
import org.apache.ignite.internal.lang.IgniteUuid;
import org.apache.ignite.internal.lang.NodeStoppingException;
import org.apache.ignite.internal.lang.SafeTimeReorderException;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.placementdriver.PlacementDriver;
import org.apache.ignite.internal.placementdriver.event.PrimaryReplicaEventParameters;
import org.apache.ignite.internal.raft.Command;
import org.apache.ignite.internal.raft.service.RaftCommandRunner;
import org.apache.ignite.internal.replicator.ReplicaResult;
import org.apache.ignite.internal.replicator.TablePartitionId;
import org.apache.ignite.internal.replicator.command.SafeTimePropagatingCommand;
import org.apache.ignite.internal.replicator.exception.PrimaryReplicaMissException;
import org.apache.ignite.internal.replicator.exception.ReplicationException;
import org.apache.ignite.internal.replicator.exception.ReplicationMaxRetriesExceededException;
import org.apache.ignite.internal.replicator.exception.ReplicationTimeoutException;
import org.apache.ignite.internal.replicator.exception.UnsupportedReplicaRequestException;
import org.apache.ignite.internal.replicator.listener.ReplicaListener;
import org.apache.ignite.internal.replicator.message.PrimaryReplicaRequest;
import org.apache.ignite.internal.replicator.message.ReadOnlyDirectReplicaRequest;
import org.apache.ignite.internal.replicator.message.ReplicaMessagesFactory;
import org.apache.ignite.internal.replicator.message.ReplicaRequest;
import org.apache.ignite.internal.replicator.message.ReplicaSafeTimeSyncRequest;
import org.apache.ignite.internal.replicator.message.SchemaVersionAwareReplicaRequest;
import org.apache.ignite.internal.schema.BinaryRow;
import org.apache.ignite.internal.schema.BinaryTuple;
import org.apache.ignite.internal.schema.BinaryTuplePrefix;
import org.apache.ignite.internal.schema.NullBinaryRow;
import org.apache.ignite.internal.schema.SchemaRegistry;
import org.apache.ignite.internal.storage.MvPartitionStorage;
import org.apache.ignite.internal.storage.PartitionTimestampCursor;
import org.apache.ignite.internal.storage.ReadResult;
import org.apache.ignite.internal.storage.RowId;
import org.apache.ignite.internal.storage.index.BinaryTupleComparator;
import org.apache.ignite.internal.storage.index.IndexRow;
import org.apache.ignite.internal.storage.index.IndexRowImpl;
import org.apache.ignite.internal.storage.index.IndexStorage;
import org.apache.ignite.internal.storage.index.SortedIndexStorage;
import org.apache.ignite.internal.table.distributed.IndexLocker;
import org.apache.ignite.internal.table.distributed.SortedIndexLocker;
import org.apache.ignite.internal.table.distributed.StorageUpdateHandler;
import org.apache.ignite.internal.table.distributed.TableMessagesFactory;
import org.apache.ignite.internal.table.distributed.TableSchemaAwareIndexStorage;
import org.apache.ignite.internal.table.distributed.command.BuildIndexCommand;
import org.apache.ignite.internal.table.distributed.command.FinishTxCommandBuilder;
import org.apache.ignite.internal.table.distributed.command.MarkLocksReleasedCommand;
import org.apache.ignite.internal.table.distributed.command.TablePartitionIdMessage;
import org.apache.ignite.internal.table.distributed.command.TimedBinaryRowMessage;
import org.apache.ignite.internal.table.distributed.command.TimedBinaryRowMessageBuilder;
import org.apache.ignite.internal.table.distributed.command.UpdateAllCommand;
import org.apache.ignite.internal.table.distributed.command.UpdateCommand;
import org.apache.ignite.internal.table.distributed.command.UpdateCommandBuilder;
import org.apache.ignite.internal.table.distributed.command.WriteIntentSwitchCommand;
import org.apache.ignite.internal.table.distributed.raft.UnexpectedTransactionStateException;
import org.apache.ignite.internal.table.distributed.replication.request.BinaryRowMessage;
import org.apache.ignite.internal.table.distributed.replication.request.BinaryTupleMessage;
import org.apache.ignite.internal.table.distributed.replication.request.BuildIndexReplicaRequest;
import org.apache.ignite.internal.table.distributed.replication.request.CommittableTxRequest;
import org.apache.ignite.internal.table.distributed.replication.request.ReadOnlyDirectMultiRowReplicaRequest;
import org.apache.ignite.internal.table.distributed.replication.request.ReadOnlyDirectSingleRowReplicaRequest;
import org.apache.ignite.internal.table.distributed.replication.request.ReadOnlyMultiRowPkReplicaRequest;
import org.apache.ignite.internal.table.distributed.replication.request.ReadOnlyReplicaRequest;
import org.apache.ignite.internal.table.distributed.replication.request.ReadOnlyScanRetrieveBatchReplicaRequest;
import org.apache.ignite.internal.table.distributed.replication.request.ReadOnlySingleRowPkReplicaRequest;
import org.apache.ignite.internal.table.distributed.replication.request.ReadWriteMultiRowPkReplicaRequest;
import org.apache.ignite.internal.table.distributed.replication.request.ReadWriteMultiRowReplicaRequest;
import org.apache.ignite.internal.table.distributed.replication.request.ReadWriteReplicaRequest;
import org.apache.ignite.internal.table.distributed.replication.request.ReadWriteScanCloseReplicaRequest;
import org.apache.ignite.internal.table.distributed.replication.request.ReadWriteScanRetrieveBatchReplicaRequest;
import org.apache.ignite.internal.table.distributed.replication.request.ReadWriteSingleRowPkReplicaRequest;
import org.apache.ignite.internal.table.distributed.replication.request.ReadWriteSingleRowReplicaRequest;
import org.apache.ignite.internal.table.distributed.replication.request.ReadWriteSwapRowReplicaRequest;
import org.apache.ignite.internal.table.distributed.replicator.action.RequestType;
import org.apache.ignite.internal.table.distributed.schema.SchemaSyncService;
import org.apache.ignite.internal.table.distributed.schema.ValidationSchemasSource;
import org.apache.ignite.internal.tx.HybridTimestampTracker;
import org.apache.ignite.internal.tx.Lock;
import org.apache.ignite.internal.tx.LockKey;
import org.apache.ignite.internal.tx.LockManager;
import org.apache.ignite.internal.tx.LockMode;
import org.apache.ignite.internal.tx.TransactionAlreadyFinishedException;
import org.apache.ignite.internal.tx.TransactionIds;
import org.apache.ignite.internal.tx.TransactionMeta;
import org.apache.ignite.internal.tx.TransactionResult;
import org.apache.ignite.internal.tx.TxManager;
import org.apache.ignite.internal.tx.TxMeta;
import org.apache.ignite.internal.tx.TxState;
import org.apache.ignite.internal.tx.TxStateMeta;
import org.apache.ignite.internal.tx.TxStateMetaFinishing;
import org.apache.ignite.internal.tx.message.TxFinishReplicaRequest;
import org.apache.ignite.internal.tx.message.TxRecoveryMessage;
import org.apache.ignite.internal.tx.message.TxStateCommitPartitionRequest;
import org.apache.ignite.internal.tx.message.WriteIntentSwitchReplicaRequest;
import org.apache.ignite.internal.tx.storage.state.TxStateStorage;
import org.apache.ignite.internal.util.CompletableFutures;
import org.apache.ignite.internal.util.Cursor;
import org.apache.ignite.internal.util.CursorUtils;
import org.apache.ignite.internal.util.ExceptionUtils;
import org.apache.ignite.internal.util.IgniteSpinBusyLock;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.internal.util.Lazy;
import org.apache.ignite.internal.util.PendingComparableValuesTracker;
import org.apache.ignite.internal.util.TrackerClosedException;
import org.apache.ignite.lang.ErrorGroups.Replicator;
import org.apache.ignite.network.ClusterNode;
import org.apache.ignite.network.ClusterNodeResolver;
import org.apache.ignite.tx.TransactionException;
import org.jetbrains.annotations.Nullable;

/** Partition replication listener. */
public class PartitionReplicaListener implements ReplicaListener {
    /** Logger. */
    private static final IgniteLogger LOG = Loggers.forClass(PartitionReplicaListener.class);

    /** Factory to create RAFT command messages. */
    private static final TableMessagesFactory MSG_FACTORY = new TableMessagesFactory();

    /** Factory for creating replica command messages. */
    private static final ReplicaMessagesFactory REPLICA_MESSAGES_FACTORY = new ReplicaMessagesFactory();

    /** Replication retries limit. */
    private static final int MAX_RETIES_ON_SAFE_TIME_REORDERING = 1000;

    /** Replication group id. */
    private final TablePartitionId replicationGroupId;

    /** Primary key index. */
    private final Lazy<TableSchemaAwareIndexStorage> pkIndexStorage;

    /** Secondary indices. */
    private final Supplier<Map<Integer, TableSchemaAwareIndexStorage>> secondaryIndexStorages;

    /** Versioned partition storage. */
    private final MvPartitionStorage mvDataStorage;

    /** Raft client. */
    private final RaftCommandRunner raftClient;

    /** Tx manager. */
    private final TxManager txManager;

    /** Lock manager. */
    private final LockManager lockManager;

    /** Handler that processes updates writing them to storage. */
    private final StorageUpdateHandler storageUpdateHandler;

    /**
     * Cursors map. The key of the map is internal Ignite uuid which consists of a transaction id ({@link UUID}) and a cursor id
     * ({@link Long}).
     */
    private final ConcurrentNavigableMap<IgniteUuid, Cursor<?>> cursors;

    /** Tx state storage. */
    private final TxStateStorage txStateStorage;

    /** Hybrid clock. */
    private final HybridClock hybridClock;

    /** Safe time. */
    private final PendingComparableValuesTracker<HybridTimestamp, Void> safeTime;

    /** Transaction state resolver. */
    private final TransactionStateResolver transactionStateResolver;

    /** Runs async scan tasks for effective tail recursion execution (avoid deep recursive calls). */
    private final Executor scanRequestExecutor;

    private final Supplier<Map<Integer, IndexLocker>> indexesLockers;

    private final ConcurrentMap<UUID, TxCleanupReadyFutureList> txCleanupReadyFutures = new ConcurrentHashMap<>();

    /** Cleanup futures. */
    private final ConcurrentHashMap<RowId, CompletableFuture<?>> rowCleanupMap = new ConcurrentHashMap<>();

    private final SchemaCompatibilityValidator schemaCompatValidator;

    /** Instance of the local node. */
    private final ClusterNode localNode;

    private final SchemaSyncService schemaSyncService;

    private final CatalogService catalogService;

    /** Busy lock to stop synchronously. */
    private final IgniteSpinBusyLock busyLock = new IgniteSpinBusyLock();

    /** Prevents double stopping. */
    private final AtomicBoolean stopGuard = new AtomicBoolean();

    /** Placement driver. */
    private final PlacementDriver placementDriver;

    /**
     * Mutex for command processing linearization.
     * Some actions like update or updateAll require strict ordering within their application to storage on all nodes in replication group.
     * Given ordering should match corresponding command's safeTime.
     */
    private final Object commandProcessingLinearizationMutex = new Object();

    private final ClusterNodeResolver clusterNodeResolver;

    /**
     * The constructor.
     *
     * @param mvDataStorage Data storage.
     * @param raftClient Raft client.
     * @param txManager Transaction manager.
     * @param lockManager Lock manager.
     * @param partId Partition id.
     * @param tableId Table id.
     * @param indexesLockers Index lock helper objects.
     * @param pkIndexStorage Pk index storage.
     * @param secondaryIndexStorages Secondary index storages.
     * @param hybridClock Hybrid clock.
     * @param safeTime Safe time clock.
     * @param txStateStorage Transaction state storage.
     * @param transactionStateResolver Transaction state resolver.
     * @param storageUpdateHandler Handler that processes updates writing them to storage.
     * @param localNode Instance of the local node.
     * @param catalogService Catalog service.
     * @param placementDriver Placement driver.
     */
    public PartitionReplicaListener(
            MvPartitionStorage mvDataStorage,
            RaftCommandRunner raftClient,
            TxManager txManager,
            LockManager lockManager,
            Executor scanRequestExecutor,
            int partId,
            int tableId,
            Supplier<Map<Integer, IndexLocker>> indexesLockers,
            Lazy<TableSchemaAwareIndexStorage> pkIndexStorage,
            Supplier<Map<Integer, TableSchemaAwareIndexStorage>> secondaryIndexStorages,
            HybridClock hybridClock,
            PendingComparableValuesTracker<HybridTimestamp, Void> safeTime,
            TxStateStorage txStateStorage,
            TransactionStateResolver transactionStateResolver,
            StorageUpdateHandler storageUpdateHandler,
            ValidationSchemasSource validationSchemasSource,
            ClusterNode localNode,
            SchemaSyncService schemaSyncService,
            CatalogService catalogService,
            PlacementDriver placementDriver,
            ClusterNodeResolver clusterNodeResolver
    ) {
        this.mvDataStorage = mvDataStorage;
        this.raftClient = raftClient;
        this.txManager = txManager;
        this.lockManager = lockManager;
        this.scanRequestExecutor = scanRequestExecutor;
        this.indexesLockers = indexesLockers;
        this.pkIndexStorage = pkIndexStorage;
        this.secondaryIndexStorages = secondaryIndexStorages;
        this.hybridClock = hybridClock;
        this.safeTime = safeTime;
        this.txStateStorage = txStateStorage;
        this.transactionStateResolver = transactionStateResolver;
        this.storageUpdateHandler = storageUpdateHandler;
        this.localNode = localNode;
        this.schemaSyncService = schemaSyncService;
        this.catalogService = catalogService;
        this.placementDriver = placementDriver;
        this.clusterNodeResolver = clusterNodeResolver;

        this.replicationGroupId = new TablePartitionId(tableId, partId);

        cursors = new ConcurrentSkipListMap<>(IgniteUuid.globalOrderComparator());

        schemaCompatValidator = new SchemaCompatibilityValidator(validationSchemasSource, catalogService, schemaSyncService);
    }

    @Override
    public CompletableFuture<Boolean> onPrimaryElected(PrimaryReplicaEventParameters evt, @Nullable Throwable exception) {
        assert replicationGroupId.equals(evt.groupId()) : format(
                "The replication group listener does not match the event [grp={}, eventGrp={}]",
                replicationGroupId,
                evt.groupId()
        );

        if (!localNode.name().equals(evt.leaseholder())) {
            return falseCompletedFuture();
        }

        List<CompletableFuture<?>> cleanupFutures = new ArrayList<>();

        Cursor<IgniteBiTuple<UUID, TxMeta>> txs;

        try {
            txs = txStateStorage.scan();
        } catch (IgniteInternalException e) {
            return falseCompletedFuture();
        }

        for (IgniteBiTuple<UUID, TxMeta> tx : txs) {
            UUID txId = tx.getKey();
            TxMeta txMeta = tx.getValue();

            assert !txMeta.enlistedPartitions().isEmpty();

            if (isFinalState(txMeta.txState()) && !txMeta.locksReleased()) {
                CompletableFuture<?> cleanupFuture = txManager.executeCleanupAsync(() -> durableCleanup(txId, txMeta));

                cleanupFutures.add(cleanupFuture);
            }
        }

        allOf(cleanupFutures.toArray(new CompletableFuture<?>[0]))
                .whenComplete((v, e) -> {
                    if (e != null) {
                        LOG.error("Failure occurred while triggering cleanup on commit partition primary replica election "
                                + "[commitPartition={}]", e, replicationGroupId);
                    }
                });

        // The future returned by this event handler can't wait for all cleanups because it's not necessary and it can block
        // meta storage notification thread for a while, preventing it from delivering further updates (including leases) and therefore
        // causing deadlock on primary replica waiting.
        return falseCompletedFuture();
    }

    private CompletableFuture<?> durableCleanup(UUID txId, TxMeta txMeta) {
        Collection<TablePartitionId> enlistedPartitions = txMeta.enlistedPartitions();

        boolean commit = txMeta.txState() == COMMITTED;

        HybridTimestamp commitTimestamp = txMeta.commitTimestamp();

        return txManager.cleanup(enlistedPartitions, commit, commitTimestamp, txId)
                .handle((v, e) -> {
                    if (e == null) {
                        return txManager.executeCleanupAsync(() -> markLocksReleased(txId));
                    } else {
                        LOG.warn("Failed to execute cleanup on commit partition primary replica switch [txId={}, commitPartition={}]",
                                e, txId, replicationGroupId);

                        if (hasCause(e, null, NodeStoppingException.class)) {
                            return nullCompletedFuture();
                        } else {
                            return txManager.executeCleanupAsync(() -> durableCleanup(txId, txMeta));
                        }
                    }
                })
                .thenCompose(f -> f);
    }

    private void markLocksReleased(UUID txId) {
        reliableCatalogVersionFor(hybridClock.now()).thenAccept(catalogVersion -> {
            MarkLocksReleasedCommand cmd = MSG_FACTORY.markLocksReleasedCommand()
                    .txId(txId)
                    .safeTimeLong(hybridClock.nowLong())
                    .requiredCatalogVersion(catalogVersion)
                    .build();

            raftClient.run(cmd);
        });
    }

    @Override
    public CompletableFuture<Boolean> onPrimaryExpired(PrimaryReplicaEventParameters evt, @Nullable Throwable exception) {
        assert replicationGroupId.equals(evt.groupId()) : format(
                "The replication group listener does not match the event [grp={}, eventGrp={}]",
                replicationGroupId,
                evt.groupId()
        );

        if (!localNode.name().equals(evt.leaseholder())) {
            return falseCompletedFuture();
        }

        LOG.info("Primary replica expired [grp={}]", replicationGroupId);

        ArrayList<CompletableFuture<?>> futs = new ArrayList<>();

        for (UUID txId : txCleanupReadyFutures.keySet()) {
            txCleanupReadyFutures.compute(txId, (id, txOps) -> {
                if (txOps == null) {
                    return null;
                }

                if (!txOps.futures.isEmpty()) {
                    CompletableFuture<?>[] txFuts = txOps.futures.values().stream()
                            .flatMap(Collection::stream)
                            .toArray(CompletableFuture[]::new);

                    futs.add(allOf(txFuts).whenComplete((unused, throwable) -> releaseTxLocks(txId)));

                    txOps.futures.clear();
                }

                return txOps;
            });
        }

        return allOf(futs.toArray(CompletableFuture[]::new)).thenApply(unused -> false);
    }

    @Override
    public CompletableFuture<ReplicaResult> invoke(ReplicaRequest request, String senderId) {
        return ensureReplicaIsPrimary(request)
                .thenCompose(isPrimary -> processRequest(request, isPrimary, senderId))
                .thenApply(res -> {
                    if (res instanceof ReplicaResult) {
                        return (ReplicaResult) res;
                    } else {
                        return new ReplicaResult(res, null);
                    }
                });
    }

    private CompletableFuture<?> processRequest(ReplicaRequest request, @Nullable Boolean isPrimary, String senderId) {
        if (request instanceof SchemaVersionAwareReplicaRequest) {
            assert ((SchemaVersionAwareReplicaRequest) request).schemaVersion() > 0 : "No schema version passed?";
        }

        if (request instanceof CommittableTxRequest) {
            var req = (CommittableTxRequest) request;

            // Saving state is not needed for full transactions.
            if (!req.full()) {
                txManager.updateTxMeta(req.transactionId(), old -> new TxStateMeta(
                        PENDING,
                        senderId,
                        req.commitPartitionId().asTablePartitionId(),
                        null
                ));
            }
        }

        if (request instanceof TxRecoveryMessage) {
            return processTxRecoveryMessage((TxRecoveryMessage) request, senderId);
        }

        HybridTimestamp opTsIfDirectRo = (request instanceof ReadOnlyDirectReplicaRequest) ? hybridClock.now() : null;

        return validateTableExistence(request, opTsIfDirectRo)
                .thenCompose(unused -> validateSchemaMatch(request, opTsIfDirectRo))
                .thenCompose(unused -> waitForSchemasBeforeReading(request, opTsIfDirectRo))
                .thenCompose(opStartTimestamp -> processOperationRequest(request, isPrimary, senderId, opTsIfDirectRo));
    }

    /**
     * Processes transaction recovery request on a commit partition.
     *
     * @param request Tx recovery request.
     * @return The future is complete when the transaction state is finalized.
     */
    private CompletableFuture<Void> processTxRecoveryMessage(TxRecoveryMessage request, String senderId) {
        UUID txId = request.txId();

        TxMeta txMeta = txStateStorage.get(txId);

        // Check whether a transaction has already been finished.
        if (txMeta != null && isFinalState(txMeta.txState())) {
            return recoverFinishedTx(txId, txMeta)
                    // If the sender has sent a recovery message, it failed to handle it on its own,
                    // so sending cleanup to the sender for the transaction we know is finished.
                    .whenComplete((v, ex) -> runCleanupOnNode(txId, senderId));
        }

        LOG.info("Orphan transaction has to be aborted [tx={}].", txId);

        return triggerTxRecovery(txId, senderId);
    }

    private CompletableFuture<Void> recoverFinishedTx(UUID txId, TxMeta txMeta) {
        if (txMeta.locksReleased() || txMeta.enlistedPartitions().isEmpty()) {
            // Nothing to do if the locks have been released already or there are no enlistedPartitions available.
            return nullCompletedFuture();
        }

        // Otherwise run a cleanup on the known set of partitions.
        return (CompletableFuture<Void>) durableCleanup(txId, txMeta);
    }

    /**
     * Run cleanup on a node.
     *
     * @param txId Transaction id.
     * @param nodeId Node id (inconsistent).
     */
    private CompletableFuture<Void> runCleanupOnNode(UUID txId, String nodeId) {
        // Get node id of the sender to send back cleanup requests.
        String nodeConsistentId = clusterNodeResolver.getConsistentIdById(nodeId);

        return nodeConsistentId == null ? nullCompletedFuture() : txManager.cleanup(nodeConsistentId, txId);
    }

    /**
     * Abort the abandoned transaction.
     *
     * @param txId Transaction id.
     * @param senderId Sender inconsistent id.
     */
    private CompletableFuture<Void> triggerTxRecovery(UUID txId, String senderId) {
        // If the transaction state is pending, then the transaction should be rolled back,
        // meaning that the state is changed to aborted and a corresponding cleanup request
        // is sent in a common durable manner to a partition that have initiated recovery.
        return txManager.finish(
                        new HybridTimestampTracker(),
                        replicationGroupId,
                        false,
                        Map.of(replicationGroupId, 0L), // term is not required for the rollback.
                        txId
                )
                .whenComplete((v, ex) -> runCleanupOnNode(txId, senderId));
    }

    /**
     * Validates that the table exists at a timestamp corresponding to the request operation.
     *
     * <ul>
     *     <li>For a read/write in an RW transaction, it's 'now'</li>
     *     <li>For an RO read (with readTimestamp), it's readTimestamp (matches readTimestamp in the transaction)</li>
     *     <li>For a direct read in an RO implicit transaction, it's the timestamp chosen (as 'now') to process the request</li>
     * </ul>
     *
     * <p>For other requests, the validation is skipped.
     *
     * @param request Replica request corresponding to the operation.
     * @param opTsIfDirectRo Operation timestamp for a direct RO, {@code null} otherwise.
     * @return Future completed when the validation is finished.
     */
    private CompletableFuture<Void> validateTableExistence(ReplicaRequest request, @Nullable HybridTimestamp opTsIfDirectRo) {
        HybridTimestamp opStartTs;

        if (request instanceof ReadWriteScanCloseReplicaRequest) {
            // We don't need to validate close request for table existence.
            opStartTs = null;
        } else if (request instanceof ReadWriteReplicaRequest) {
            opStartTs = hybridClock.now();
        } else if (request instanceof ReadOnlyReplicaRequest) {
            opStartTs = ((ReadOnlyReplicaRequest) request).readTimestamp();
        } else if (request instanceof ReadOnlyDirectReplicaRequest) {
            assert opTsIfDirectRo != null;

            opStartTs = opTsIfDirectRo;
        } else {
            opStartTs = null;
        }

        if (opStartTs == null) {
            return nullCompletedFuture();
        }

        return schemaSyncService.waitForMetadataCompleteness(opStartTs)
                .thenRun(() -> schemaCompatValidator.failIfTableDoesNotExistAt(opStartTs, tableId()));
    }

    /**
     * Makes sure that {@link SchemaVersionAwareReplicaRequest#schemaVersion()} sent in a request matches table schema version
     * corresponding to the operation.
     *
     * @param request Replica request corresponding to the operation.
     * @param opTsIfDirectRo Operation timestamp for a direct RO, {@code null} otherwise.
     * @return Future completed when the validation is finished.
     */
    private CompletableFuture<Void> validateSchemaMatch(ReplicaRequest request, @Nullable HybridTimestamp opTsIfDirectRo) {
        if (!(request instanceof SchemaVersionAwareReplicaRequest)) {
            return nullCompletedFuture();
        }

        HybridTimestamp tsToWaitForSchema = getTxStartTimestamp(request);
        if (tsToWaitForSchema == null) {
            tsToWaitForSchema = opTsIfDirectRo;
        }

        if (tsToWaitForSchema == null) {
            return nullCompletedFuture();
        }

        HybridTimestamp finalTsToWaitForSchema = tsToWaitForSchema;
        return schemaSyncService.waitForMetadataCompleteness(finalTsToWaitForSchema)
                .thenRun(() -> {
                    SchemaVersionAwareReplicaRequest versionAwareRequest = (SchemaVersionAwareReplicaRequest) request;

                    schemaCompatValidator.failIfRequestSchemaDiffersFromTxTs(
                            finalTsToWaitForSchema,
                            versionAwareRequest.schemaVersion(),
                            tableId()
                    );
                });
    }

    /**
     * Makes sure that we have schemas corresponding to the moment of tx start; this makes PK extraction safe WRT
     * {@link SchemaRegistry#schema(int)}.
     *
     * @param request Replica request corresponding to the operation.
     * @param opTsIfDirectRo Operation timestamp for a direct RO, {@code null} otherwise.
     * @return Future completed when the validation is finished.
     */
    private CompletableFuture<Void> waitForSchemasBeforeReading(ReplicaRequest request, @Nullable HybridTimestamp opTsIfDirectRo) {
        HybridTimestamp tsToWaitForSchema = getTxStartTimestamp(request);
        if (tsToWaitForSchema == null) {
            tsToWaitForSchema = opTsIfDirectRo;
        }

        return tsToWaitForSchema == null ? nullCompletedFuture() : schemaSyncService.waitForMetadataCompleteness(tsToWaitForSchema);
    }

    /**
     * Returns timestamp of transaction start (for RW/timestamped RO requests) or @{code null} for other requests.
     *
     * @param request Replica request corresponding to the operation.
     */
    private static @Nullable HybridTimestamp getTxStartTimestamp(ReplicaRequest request) {
        HybridTimestamp txStartTimestamp;

        if (request instanceof ReadWriteReplicaRequest) {
            txStartTimestamp = TransactionIds.beginTimestamp(((ReadWriteReplicaRequest) request).transactionId());
        } else if (request instanceof ReadOnlyReplicaRequest) {
            txStartTimestamp = ((ReadOnlyReplicaRequest) request).readTimestamp();
        } else {
            txStartTimestamp = null;
        }
        return txStartTimestamp;
    }

    private CompletableFuture<?> processOperationRequest(
            ReplicaRequest request,
            @Nullable Boolean isPrimary,
            String senderId,
            @Nullable HybridTimestamp opStartTsIfDirectRo
    ) {
        if (request instanceof ReadWriteSingleRowReplicaRequest) {
            var req = (ReadWriteSingleRowReplicaRequest) request;

            return appendTxCommand(req.transactionId(), req.requestType(), req.full(), () -> processSingleEntryAction(req, senderId));
        } else if (request instanceof ReadWriteSingleRowPkReplicaRequest) {
            var req = (ReadWriteSingleRowPkReplicaRequest) request;

            return appendTxCommand(req.transactionId(), req.requestType(), req.full(), () -> processSingleEntryAction(req, senderId));
        } else if (request instanceof ReadWriteMultiRowReplicaRequest) {
            var req = (ReadWriteMultiRowReplicaRequest) request;

            return appendTxCommand(req.transactionId(), req.requestType(), req.full(), () -> processMultiEntryAction(req, senderId));
        } else if (request instanceof ReadWriteMultiRowPkReplicaRequest) {
            var req = (ReadWriteMultiRowPkReplicaRequest) request;

            return appendTxCommand(req.transactionId(), req.requestType(), req.full(), () -> processMultiEntryAction(req, senderId));
        } else if (request instanceof ReadWriteSwapRowReplicaRequest) {
            var req = (ReadWriteSwapRowReplicaRequest) request;

            return appendTxCommand(req.transactionId(), req.requestType(), req.full(), () -> processTwoEntriesAction(req, senderId));
        } else if (request instanceof ReadWriteScanRetrieveBatchReplicaRequest) {
            var req = (ReadWriteScanRetrieveBatchReplicaRequest) request;

            // Scan's request.full() has a slightly different semantics than the same field in other requests -
            // it identifies an implicit transaction. Please note that request.full() is always false in the following `appendTxCommand`.
            // We treat SCAN as 2pc and only switch to a 1pc mode if all table rows fit in the bucket and the transaction is implicit.
            // See `req.full() && (err != null || rows.size() < req.batchSize())` condition.
            // If they don't fit the bucket, the transaction is treated as 2pc.
            txManager.updateTxMeta(req.transactionId(), old -> new TxStateMeta(
                    PENDING,
                    senderId,
                    req.commitPartitionId().asTablePartitionId(),
                    null
            ));

            // Implicit RW scan can be committed locally on a last batch or error.
            return appendTxCommand(req.transactionId(), RequestType.RW_SCAN, false, () -> processScanRetrieveBatchAction(req, senderId))
                    .thenCompose(rows -> {
                        if (allElementsAreNull(rows)) {
                            return completedFuture(rows);
                        } else {
                            return validateRwReadAgainstSchemaAfterTakingLocks(req.transactionId())
                                    .thenApply(ignored -> rows);
                        }
                    })
                    .handle((rows, err) -> {
                        if (req.full() && (err != null || rows.size() < req.batchSize())) {
                            releaseTxLocks(req.transactionId());
                        }

                        if (err != null) {
                            ExceptionUtils.sneakyThrow(err);
                        }

                        return rows;
                    });
        } else if (request instanceof ReadWriteScanCloseReplicaRequest) {
            processScanCloseAction((ReadWriteScanCloseReplicaRequest) request);

            return nullCompletedFuture();
        } else if (request instanceof TxFinishReplicaRequest) {
            return processTxFinishAction((TxFinishReplicaRequest) request, senderId);
        } else if (request instanceof WriteIntentSwitchReplicaRequest) {
            return processWriteIntentSwitchAction((WriteIntentSwitchReplicaRequest) request);
        } else if (request instanceof ReadOnlySingleRowPkReplicaRequest) {
            return processReadOnlySingleEntryAction((ReadOnlySingleRowPkReplicaRequest) request, isPrimary);
        } else if (request instanceof ReadOnlyMultiRowPkReplicaRequest) {
            return processReadOnlyMultiEntryAction((ReadOnlyMultiRowPkReplicaRequest) request, isPrimary);
        } else if (request instanceof ReadOnlyScanRetrieveBatchReplicaRequest) {
            return processReadOnlyScanRetrieveBatchAction((ReadOnlyScanRetrieveBatchReplicaRequest) request, isPrimary);
        } else if (request instanceof ReplicaSafeTimeSyncRequest) {
            return processReplicaSafeTimeSyncRequest((ReplicaSafeTimeSyncRequest) request, isPrimary);
        } else if (request instanceof BuildIndexReplicaRequest) {
            return raftClient.run(toBuildIndexCommand((BuildIndexReplicaRequest) request));
        } else if (request instanceof ReadOnlyDirectSingleRowReplicaRequest) {
            return processReadOnlyDirectSingleEntryAction((ReadOnlyDirectSingleRowReplicaRequest) request, opStartTsIfDirectRo);
        } else if (request instanceof ReadOnlyDirectMultiRowReplicaRequest) {
            return processReadOnlyDirectMultiEntryAction((ReadOnlyDirectMultiRowReplicaRequest) request, opStartTsIfDirectRo);
        } else if (request instanceof TxStateCommitPartitionRequest) {
            return processTxStateCommitPartitionRequest((TxStateCommitPartitionRequest) request);
        } else {
            throw new UnsupportedReplicaRequestException(request.getClass());
        }
    }

    /**
     * Processes a transaction state request.
     *
     * @param request Transaction state request.
     * @return Result future.
     */
    private CompletableFuture<TransactionMeta> processTxStateCommitPartitionRequest(TxStateCommitPartitionRequest request) {
        return placementDriver.getPrimaryReplica(replicationGroupId, hybridClock.now())
                .thenCompose(replicaMeta -> {
                    if (replicaMeta == null || replicaMeta.getLeaseholder() == null) {
                        return failedFuture(new PrimaryReplicaMissException(localNode.name(), null, null, null, null));
                    }

                    if (!isLocalPeer(replicaMeta.getLeaseholder())) {
                        return failedFuture(
                                new PrimaryReplicaMissException(localNode.name(), replicaMeta.getLeaseholder(), null, null, null)
                        );
                    }

                    UUID txId = request.txId();

                    TxStateMeta txMeta = txManager.stateMeta(txId);

                    if (txMeta != null && txMeta.txState() == FINISHING) {
                        assert txMeta instanceof TxStateMetaFinishing : txMeta;

                        return ((TxStateMetaFinishing) txMeta).txFinishFuture();
                    } else if (txMeta == null || !isFinalState(txMeta.txState())) {
                        // Try to trigger recovery, if needed. If the transaction will be aborted, the proper ABORTED state will be sent
                        // in response.
                        return triggerTxRecoveryOnTxStateResolutionIfNeeded(txId, txMeta);
                    } else {
                        return completedFuture(txMeta);
                    }
                });
    }

    /**
     * Checks whether tx recovery is needed with given tx meta and triggers it of needed.
     *
     * @param txId Transaction id.
     * @param txStateMeta Transaction meta.
     * @return Tx recovery future, or completed future if the recovery isn't needed, or failed future if the recovery is not possible.
     */
    private CompletableFuture<TransactionMeta> triggerTxRecoveryOnTxStateResolutionIfNeeded(
            UUID txId,
            @Nullable TxStateMeta txStateMeta
    ) {
        // The state is either null or PENDING or ABANDONED, other states have been filtered out previously.
        assert txStateMeta == null || txStateMeta.txState() == PENDING || txStateMeta.txState() == ABANDONED
                : "Unexpected transaction state: " + txStateMeta;

        TxMeta txMeta = txStateStorage.get(txId);

        if (txMeta == null) {
            // This means the transaction is pending and we should trigger the recovery if there is no tx coordinator in topology.
            if (txStateMeta == null
                    || txStateMeta.txState() == ABANDONED
                    || txStateMeta.txCoordinatorId() == null
                    || clusterNodeResolver.getById(txStateMeta.txCoordinatorId()) == null) {
                // This means that primary replica for commit partition has changed, since the local node doesn't have the volatile tx
                // state; and there is no final tx state in txStateStorage, or the tx coordinator left the cluster. But we can assume
                // that as the coordinator (or information about it) is missing, there is  no need to wait a finish request from
                // tx coordinator, the transaction can't be committed at all.
                return triggerTxRecovery(txId, localNode.id())
                        .handle((v, ex) ->
                                CompletableFuture.<TransactionMeta>completedFuture(txManager.stateMeta(txId)))
                        .thenCompose(v -> v);
            } else {
                assert txStateMeta != null && txStateMeta.txState() == PENDING : "Unexpected transaction state: " + txStateMeta;

                return completedFuture(txStateMeta);
            }
        } else {
            // Recovery is not needed.
            assert isFinalState(txMeta.txState()) : "Unexpected transaction state: " + txMeta;

            return completedFuture(txMeta);
        }
    }

    /**
     * Processes retrieve batch for read only transaction.
     *
     * @param request Read only retrieve batch request.
     * @param isPrimary Whether the given replica is primary.
     * @return Result future.
     */
    private CompletableFuture<List<BinaryRow>> processReadOnlyScanRetrieveBatchAction(
            ReadOnlyScanRetrieveBatchReplicaRequest request,
            Boolean isPrimary
    ) {
        requireNonNull(isPrimary);

        UUID txId = request.transactionId();
        int batchCount = request.batchSize();
        HybridTimestamp readTimestamp = request.readTimestamp();

        IgniteUuid cursorId = new IgniteUuid(txId, request.scanId());

        CompletableFuture<Void> safeReadFuture = isPrimaryInTimestamp(isPrimary, readTimestamp) ? nullCompletedFuture()
                : safeTime.waitFor(readTimestamp);

        if (request.indexToUse() != null) {
            TableSchemaAwareIndexStorage indexStorage = secondaryIndexStorages.get().get(request.indexToUse());

            if (indexStorage == null) {
                throw new AssertionError("Index not found: uuid=" + request.indexToUse());
            }

            if (request.exactKey() != null) {
                assert request.lowerBoundPrefix() == null && request.upperBoundPrefix() == null : "Index lookup doesn't allow bounds.";

                return safeReadFuture.thenCompose(unused -> lookupIndex(request, indexStorage));
            }

            assert indexStorage.storage() instanceof SortedIndexStorage;

            return safeReadFuture.thenCompose(unused -> scanSortedIndex(request, indexStorage));
        }

        return safeReadFuture.thenCompose(unused -> retrieveExactEntriesUntilCursorEmpty(txId, readTimestamp, cursorId, batchCount));
    }

    /**
     * Extracts exact amount of entries, or less if cursor is become empty, from a cursor on the specific time.
     *
     * @param txId Transaction id is used for RW only.
     * @param readTimestamp Timestamp of the moment when that moment when the data will be extracted.
     * @param cursorId Cursor id.
     * @param count Amount of entries which sill be extracted.
     * @return Result future.
     */
    private CompletableFuture<List<BinaryRow>> retrieveExactEntriesUntilCursorEmpty(
            UUID txId,
            @Nullable HybridTimestamp readTimestamp,
            IgniteUuid cursorId,
            int count
    ) {
        @SuppressWarnings("resource") PartitionTimestampCursor cursor = (PartitionTimestampCursor) cursors.computeIfAbsent(cursorId,
                id -> mvDataStorage.scan(readTimestamp == null ? HybridTimestamp.MAX_VALUE : readTimestamp));

        var resolutionFuts = new ArrayList<CompletableFuture<TimedBinaryRow>>(count);

        while (resolutionFuts.size() < count && cursor.hasNext()) {
            ReadResult readResult = cursor.next();
            HybridTimestamp newestCommitTimestamp = readResult.newestCommitTimestamp();

            TimedBinaryRow candidate;
            if (newestCommitTimestamp == null || !readResult.isWriteIntent()) {
                candidate = null;
            } else {
                BinaryRow committedRow = cursor.committed(newestCommitTimestamp);

                candidate = committedRow == null ? null : new TimedBinaryRow(committedRow, newestCommitTimestamp);
            }

            resolutionFuts.add(resolveReadResult(readResult, txId, readTimestamp, () -> candidate));
        }

        return allOf(resolutionFuts.toArray(new CompletableFuture[0])).thenCompose(unused -> {
            var rows = new ArrayList<BinaryRow>(count);

            for (CompletableFuture<TimedBinaryRow> resolutionFut : resolutionFuts) {
                TimedBinaryRow resolvedReadResult = resolutionFut.join();

                if (resolvedReadResult != null && resolvedReadResult.binaryRow() != null) {
                    rows.add(resolvedReadResult.binaryRow());
                }
            }

            if (rows.size() < count && cursor.hasNext()) {
                return retrieveExactEntriesUntilCursorEmpty(txId, readTimestamp, cursorId, count - rows.size()).thenApply(binaryRows -> {
                    rows.addAll(binaryRows);

                    return rows;
                });
            } else {
                return completedFuture(rows);
            }
        });
    }

    /**
     * Extracts exact amount of entries, or less if cursor is become empty, from a cursor on the specific time. Use it for RW.
     *
     * @param txId Transaction id.
     * @param cursorId Cursor id.
     * @return Future finishes with the resolved binary row.
     */
    private CompletableFuture<List<BinaryRow>> retrieveExactEntriesUntilCursorEmpty(UUID txId, IgniteUuid cursorId, int count) {
        return retrieveExactEntriesUntilCursorEmpty(txId, null, cursorId, count).thenCompose(rows -> {
            if (nullOrEmpty(rows)) {
                return emptyListCompletedFuture();
            }

            CompletableFuture<?>[] futs = new CompletableFuture[rows.size()];

            for (int i = 0; i < rows.size(); i++) {
                BinaryRow row = rows.get(i);

                futs[i] = schemaCompatValidator.validateBackwards(row.schemaVersion(), tableId(), txId)
                        .thenCompose(validationResult -> {
                            if (validationResult.isSuccessful()) {
                                return completedFuture(row);
                            } else {
                                throw new IncompatibleSchemaException("Operation failed because schema "
                                        + validationResult.fromSchemaVersion() + " is not backward-compatible with "
                                        + validationResult.toSchemaVersion() + " for table " + validationResult.failedTableId());
                            }
                        });
            }

            return allOf(futs).thenApply((unused) -> rows);
        });
    }

    /**
     * Processes single entry request for read only transaction.
     *
     * @param request Read only single entry request.
     * @param isPrimary Whether the given replica is primary.
     * @return Result future.
     */
    private CompletableFuture<BinaryRow> processReadOnlySingleEntryAction(ReadOnlySingleRowPkReplicaRequest request, Boolean isPrimary) {
        BinaryTuple primaryKey = resolvePk(request.primaryKey());
        HybridTimestamp readTimestamp = request.readTimestamp();

        if (request.requestType() != RequestType.RO_GET) {
            throw new IgniteInternalException(Replicator.REPLICA_COMMON_ERR,
                    format("Unknown single request [actionType={}]", request.requestType()));
        }

        CompletableFuture<Void> safeReadFuture = isPrimaryInTimestamp(isPrimary, readTimestamp) ? nullCompletedFuture()
                : safeTime.waitFor(request.readTimestamp());

        return safeReadFuture.thenCompose(unused -> resolveRowByPkForReadOnly(primaryKey, readTimestamp));
    }

    /**
     * Checks that the node is primary and {@code timestamp} is already passed in the reference system of the current node.
     *
     * @param isPrimary True if the node is primary, false otherwise.
     * @param timestamp Timestamp to check.
     * @return True if the timestamp is already passed in the reference system of the current node and node is primary, false otherwise.
     */
    private boolean isPrimaryInTimestamp(Boolean isPrimary, HybridTimestamp timestamp) {
        return isPrimary && hybridClock.now().compareTo(timestamp) > 0;
    }

    /**
     * Processes multiple entries request for read only transaction.
     *
     * @param request Read only multiple entries request.
     * @param isPrimary Whether the given replica is primary.
     * @return Result future.
     */
    private CompletableFuture<List<BinaryRow>> processReadOnlyMultiEntryAction(
            ReadOnlyMultiRowPkReplicaRequest request,
            Boolean isPrimary
    ) {
        List<BinaryTuple> primaryKeys = resolvePks(request.primaryKeys());
        HybridTimestamp readTimestamp = request.readTimestamp();

        if (request.requestType() != RequestType.RO_GET_ALL) {
            throw new IgniteInternalException(Replicator.REPLICA_COMMON_ERR,
                    format("Unknown single request [actionType={}]", request.requestType()));
        }

        CompletableFuture<Void> safeReadFuture = isPrimaryInTimestamp(isPrimary, readTimestamp) ? nullCompletedFuture()
                : safeTime.waitFor(request.readTimestamp());

        return safeReadFuture.thenCompose(unused -> {
            CompletableFuture<BinaryRow>[] resolutionFuts = new CompletableFuture[primaryKeys.size()];

            for (int i = 0; i < primaryKeys.size(); i++) {
                resolutionFuts[i] = resolveRowByPkForReadOnly(primaryKeys.get(i), readTimestamp);
            }

            return CompletableFutures.allOf(resolutionFuts);
        });
    }

    /**
     * Handler to process {@link ReplicaSafeTimeSyncRequest}.
     *
     * @param request Request.
     * @param isPrimary Whether is primary replica.
     * @return Future.
     */
    private CompletableFuture<Void> processReplicaSafeTimeSyncRequest(ReplicaSafeTimeSyncRequest request, Boolean isPrimary) {
        requireNonNull(isPrimary);

        if (!isPrimary) {
            return nullCompletedFuture();
        }

        CompletableFuture<Object> resultFuture = new CompletableFuture<>();

        applyCmdWithRetryOnSafeTimeReorderException(
                REPLICA_MESSAGES_FACTORY.safeTimeSyncCommand().safeTimeLong(hybridClock.nowLong()).build(),
                resultFuture
        );

        return resultFuture.thenApply(res -> null);
    }

    /**
     * Close all cursors connected with a transaction.
     *
     * @param txId Transaction id.
     * @throws Exception When an issue happens on cursor closing.
     */
    private void closeAllTransactionCursors(UUID txId) {
        var lowCursorId = new IgniteUuid(txId, Long.MIN_VALUE);
        var upperCursorId = new IgniteUuid(txId, Long.MAX_VALUE);

        Map<IgniteUuid, ? extends Cursor<?>> txCursors = cursors.subMap(lowCursorId, true, upperCursorId, true);

        ReplicationException ex = null;

        for (AutoCloseable cursor : txCursors.values()) {
            try {
                cursor.close();
            } catch (Exception e) {
                if (ex == null) {
                    ex = new ReplicationException(Replicator.REPLICA_COMMON_ERR,
                            format("Close cursor exception [replicaGrpId={}, msg={}]", replicationGroupId,
                                    e.getMessage()), e);
                } else {
                    ex.addSuppressed(e);
                }
            }
        }

        txCursors.clear();

        if (ex != null) {
            throw ex;
        }
    }

    /**
     * Processes scan close request.
     *
     * @param request Scan close request operation.
     */
    private void processScanCloseAction(ReadWriteScanCloseReplicaRequest request) {
        UUID txId = request.transactionId();

        IgniteUuid cursorId = new IgniteUuid(txId, request.scanId());

        Cursor<?> cursor = cursors.remove(cursorId);

        if (cursor != null) {
            try {
                cursor.close();
            } catch (Exception e) {
                throw new ReplicationException(Replicator.REPLICA_COMMON_ERR,
                        format("Close cursor exception [replicaGrpId={}, msg={}]", replicationGroupId,
                                e.getMessage()), e);
            }
        }
    }

    /**
     * Processes scan retrieve batch request.
     *
     * @param request Scan retrieve batch request operation.
     * @return Listener response.
     */
    private CompletableFuture<List<BinaryRow>> processScanRetrieveBatchAction(
            ReadWriteScanRetrieveBatchReplicaRequest request,
            String txCoordinatorId
    ) {
        if (request.indexToUse() != null) {
            TableSchemaAwareIndexStorage indexStorage = secondaryIndexStorages.get().get(request.indexToUse());

            if (indexStorage == null) {
                throw new AssertionError("Index not found: uuid=" + request.indexToUse());
            }

            if (request.exactKey() != null) {
                assert request.lowerBoundPrefix() == null && request.upperBoundPrefix() == null : "Index lookup doesn't allow bounds.";

                return lookupIndex(request, indexStorage.storage());
            }

            assert indexStorage.storage() instanceof SortedIndexStorage;

            return scanSortedIndex(request, indexStorage);
        }

        UUID txId = request.transactionId();
        int batchCount = request.batchSize();

        IgniteUuid cursorId = new IgniteUuid(txId, request.scanId());

        return lockManager.acquire(txId, new LockKey(tableId()), LockMode.S)
                .thenCompose(tblLock -> retrieveExactEntriesUntilCursorEmpty(txId, cursorId, batchCount));
    }

    /**
     * Lookup sorted index in RO tx.
     *
     * @param request Index scan request.
     * @param schemaAwareIndexStorage Index storage.
     * @return Operation future.
     */
    private CompletableFuture<List<BinaryRow>> lookupIndex(
            ReadOnlyScanRetrieveBatchReplicaRequest request,
            TableSchemaAwareIndexStorage schemaAwareIndexStorage
    ) {
        IndexStorage indexStorage = schemaAwareIndexStorage.storage();

        int batchCount = request.batchSize();
        HybridTimestamp timestamp = request.readTimestamp();

        IgniteUuid cursorId = new IgniteUuid(request.transactionId(), request.scanId());

        BinaryTuple key = request.exactKey().asBinaryTuple();

        Cursor<RowId> cursor = (Cursor<RowId>) cursors.computeIfAbsent(cursorId,
                id -> indexStorage.get(key));

        var result = new ArrayList<BinaryRow>(batchCount);

        Cursor<IndexRow> indexRowCursor = CursorUtils.map(cursor, rowId -> new IndexRowImpl(key, rowId));

        return continueReadOnlyIndexScan(schemaAwareIndexStorage, indexRowCursor, timestamp, batchCount, result)
                .thenCompose(ignore -> completedFuture(result));
    }

    private CompletableFuture<List<BinaryRow>> lookupIndex(
            ReadWriteScanRetrieveBatchReplicaRequest request,
            IndexStorage indexStorage
    ) {
        UUID txId = request.transactionId();
        int batchCount = request.batchSize();

        IgniteUuid cursorId = new IgniteUuid(txId, request.scanId());

        Integer indexId = request.indexToUse();

        BinaryTuple exactKey = request.exactKey().asBinaryTuple();

        return lockManager.acquire(txId, new LockKey(indexId), LockMode.IS).thenCompose(idxLock -> { // Index IS lock
            return lockManager.acquire(txId, new LockKey(tableId()), LockMode.IS) // Table IS lock
                    .thenCompose(tblLock -> {
                        return lockManager.acquire(txId, new LockKey(indexId, exactKey.byteBuffer()), LockMode.S)
                                .thenCompose(indRowLock -> { // Hash index bucket S lock
                                    Cursor<RowId> cursor = (Cursor<RowId>) cursors.computeIfAbsent(cursorId,
                                            id -> indexStorage.get(exactKey));

                                    var result = new ArrayList<BinaryRow>(batchCount);

                                    return continueIndexLookup(txId, cursor, batchCount, result)
                                            .thenApply(ignore -> result);
                                });
                    });
        });
    }

    /**
     * Scans sorted index in RW tx.
     *
     * @param request Index scan request.
     * @param schemaAwareIndexStorage Sorted index storage.
     * @return Operation future.
     */
    private CompletableFuture<List<BinaryRow>> scanSortedIndex(
            ReadWriteScanRetrieveBatchReplicaRequest request,
            TableSchemaAwareIndexStorage schemaAwareIndexStorage
    ) {
        var indexStorage = (SortedIndexStorage) schemaAwareIndexStorage.storage();

        UUID txId = request.transactionId();
        int batchCount = request.batchSize();

        IgniteUuid cursorId = new IgniteUuid(txId, request.scanId());

        Integer indexId = request.indexToUse();

        BinaryTupleMessage lowerBoundMessage = request.lowerBoundPrefix();
        BinaryTupleMessage upperBoundMessage = request.upperBoundPrefix();

        BinaryTuplePrefix lowerBound = lowerBoundMessage == null ? null : lowerBoundMessage.asBinaryTuplePrefix();
        BinaryTuplePrefix upperBound = upperBoundMessage == null ? null : upperBoundMessage.asBinaryTuplePrefix();

        int flags = request.flags();

        return lockManager.acquire(txId, new LockKey(indexId), LockMode.IS).thenCompose(idxLock -> { // Index IS lock
            return lockManager.acquire(txId, new LockKey(tableId()), LockMode.IS) // Table IS lock
                    .thenCompose(tblLock -> {
                        var comparator = new BinaryTupleComparator(indexStorage.indexDescriptor().columns());

                        Predicate<IndexRow> isUpperBoundAchieved = indexRow -> {
                            if (indexRow == null) {
                                return true;
                            }

                            if (upperBound == null) {
                                return false;
                            }

                            ByteBuffer buffer = upperBound.byteBuffer();

                            if ((flags & SortedIndexStorage.LESS_OR_EQUAL) != 0) {
                                byte boundFlags = buffer.get(0);

                                buffer.put(0, (byte) (boundFlags | BinaryTupleCommon.EQUALITY_FLAG));
                            }

                            return comparator.compare(indexRow.indexColumns().byteBuffer(), buffer) >= 0;
                        };

                        Cursor<IndexRow> cursor = (Cursor<IndexRow>) cursors.computeIfAbsent(cursorId,
                                id -> indexStorage.scan(
                                        lowerBound,
                                        // We have to handle upperBound on a level of replication listener,
                                        // for correctness of taking of a range lock.
                                        null,
                                        flags
                                ));

                        SortedIndexLocker indexLocker = (SortedIndexLocker) indexesLockers.get().get(indexId);

                        var result = new ArrayList<BinaryRow>(batchCount);

                        return continueIndexScan(txId, schemaAwareIndexStorage, indexLocker, cursor, batchCount, result,
                                isUpperBoundAchieved)
                                .thenApply(ignore -> result);
                    });
        });
    }

    /**
     * Scans sorted index in RO tx.
     *
     * @param request Index scan request.
     * @param schemaAwareIndexStorage Sorted index storage.
     * @return Operation future.
     */
    private CompletableFuture<List<BinaryRow>> scanSortedIndex(
            ReadOnlyScanRetrieveBatchReplicaRequest request,
            TableSchemaAwareIndexStorage schemaAwareIndexStorage
    ) {
        var indexStorage = (SortedIndexStorage) schemaAwareIndexStorage.storage();

        UUID txId = request.transactionId();
        int batchCount = request.batchSize();
        HybridTimestamp timestamp = request.readTimestamp();

        IgniteUuid cursorId = new IgniteUuid(txId, request.scanId());

        BinaryTupleMessage lowerBoundMessage = request.lowerBoundPrefix();
        BinaryTupleMessage upperBoundMessage = request.upperBoundPrefix();

        BinaryTuplePrefix lowerBound = lowerBoundMessage == null ? null : lowerBoundMessage.asBinaryTuplePrefix();
        BinaryTuplePrefix upperBound = upperBoundMessage == null ? null : upperBoundMessage.asBinaryTuplePrefix();

        int flags = request.flags();

        Cursor<IndexRow> cursor = (Cursor<IndexRow>) cursors.computeIfAbsent(cursorId,
                id -> indexStorage.scan(
                        lowerBound,
                        upperBound,
                        flags
                ));

        var result = new ArrayList<BinaryRow>(batchCount);

        return continueReadOnlyIndexScan(schemaAwareIndexStorage, cursor, timestamp, batchCount, result)
                .thenApply(ignore -> result);
    }

    private CompletableFuture<Void> continueReadOnlyIndexScan(
            TableSchemaAwareIndexStorage schemaAwareIndexStorage,
            Cursor<IndexRow> cursor,
            HybridTimestamp timestamp,
            int batchSize,
            List<BinaryRow> result
    ) {
        if (result.size() >= batchSize || !cursor.hasNext()) {
            return nullCompletedFuture();
        }

        IndexRow indexRow = cursor.next();

        RowId rowId = indexRow.rowId();

        return resolvePlainReadResult(rowId, null, timestamp).thenComposeAsync(resolvedReadResult -> {
            if (resolvedReadResult != null
                    && resolvedReadResult.binaryRow() != null
                    && indexRowMatches(indexRow, resolvedReadResult.binaryRow(), schemaAwareIndexStorage)) {
                result.add(resolvedReadResult.binaryRow());
            }

            return continueReadOnlyIndexScan(schemaAwareIndexStorage, cursor, timestamp, batchSize, result);
        }, scanRequestExecutor);
    }

    /**
     * Index scan loop. Retrieves next row from index, takes locks, fetches associated data row and collects to the result.
     *
     * @param txId Transaction id.
     * @param schemaAwareIndexStorage Index storage.
     * @param indexLocker Index locker.
     * @param indexCursor Index cursor.
     * @param batchSize Batch size.
     * @param result Result collection.
     * @param isUpperBoundAchieved Function to stop on upper bound.
     * @return Future.
     */
    private CompletableFuture<Void> continueIndexScan(
            UUID txId,
            TableSchemaAwareIndexStorage schemaAwareIndexStorage,
            SortedIndexLocker indexLocker,
            Cursor<IndexRow> indexCursor,
            int batchSize,
            List<BinaryRow> result,
            Predicate<IndexRow> isUpperBoundAchieved
    ) {
        if (result.size() == batchSize) { // Batch is full, exit loop.
            return nullCompletedFuture();
        }

        return indexLocker.locksForScan(txId, indexCursor)
                .thenCompose(currentRow -> { // Index row S lock
                    if (isUpperBoundAchieved.test(currentRow)) {
                        return nullCompletedFuture(); // End of range reached. Exit loop.
                    }

                    RowId rowId = currentRow.rowId();

                    return lockManager.acquire(txId, new LockKey(tableId(), rowId), LockMode.S)
                            .thenComposeAsync(rowLock -> { // Table row S lock
                                return resolvePlainReadResult(rowId, txId).thenCompose(resolvedReadResult -> {
                                    if (resolvedReadResult != null
                                            && resolvedReadResult.binaryRow() != null
                                            && indexRowMatches(currentRow, resolvedReadResult.binaryRow(), schemaAwareIndexStorage)) {
                                        result.add(resolvedReadResult.binaryRow());
                                    }

                                    // Proceed scan.
                                    return continueIndexScan(
                                            txId,
                                            schemaAwareIndexStorage,
                                            indexLocker,
                                            indexCursor,
                                            batchSize,
                                            result,
                                            isUpperBoundAchieved
                                    );
                                });
                            }, scanRequestExecutor);
                });
    }

    /**
     * Checks whether passed index row corresponds to the binary row.
     *
     * @param indexRow Index row, read from index storage.
     * @param binaryRow Binary row, read from MV storage.
     * @param schemaAwareIndexStorage Schema aware index storage, to resolve values of indexed columns in a binary row.
     * @return {@code true} if index row matches the binary row, {@code false} otherwise.
     */
    private static boolean indexRowMatches(IndexRow indexRow, BinaryRow binaryRow, TableSchemaAwareIndexStorage schemaAwareIndexStorage) {
        BinaryTuple actualIndexRow = schemaAwareIndexStorage.indexRowResolver().extractColumns(binaryRow);

        return indexRow.indexColumns().byteBuffer().equals(actualIndexRow.byteBuffer());
    }

    private CompletableFuture<Void> continueIndexLookup(
            UUID txId,
            Cursor<RowId> indexCursor,
            int batchSize,
            List<BinaryRow> result
    ) {
        if (result.size() >= batchSize || !indexCursor.hasNext()) {
            return nullCompletedFuture();
        }

        RowId rowId = indexCursor.next();

        return lockManager.acquire(txId, new LockKey(tableId(), rowId), LockMode.S)
                .thenComposeAsync(rowLock -> { // Table row S lock
                    return resolvePlainReadResult(rowId, txId).thenCompose(resolvedReadResult -> {
                        if (resolvedReadResult != null && resolvedReadResult.binaryRow() != null) {
                            result.add(resolvedReadResult.binaryRow());
                        }

                        // Proceed lookup.
                        return continueIndexLookup(txId, indexCursor, batchSize, result);
                    });
                }, scanRequestExecutor);
    }

    /**
     * Resolves a result received from a direct storage read.
     *
     * @param rowId Row id to resolve.
     * @param txId Transaction id is used for RW only.
     * @param timestamp Read timestamp.
     * @return Future finishes with the resolved binary row.
     */
    private CompletableFuture<@Nullable TimedBinaryRow> resolvePlainReadResult(
            RowId rowId,
            @Nullable UUID txId,
            @Nullable HybridTimestamp timestamp
    ) {
        ReadResult readResult = mvDataStorage.read(rowId, timestamp == null ? HybridTimestamp.MAX_VALUE : timestamp);

        return resolveReadResult(readResult, txId, timestamp, () -> {
            if (readResult.newestCommitTimestamp() == null) {
                return null;
            }

            ReadResult committedReadResult = mvDataStorage.read(rowId, readResult.newestCommitTimestamp());

            assert !committedReadResult.isWriteIntent() :
                    "The result is not committed [rowId=" + rowId + ", timestamp="
                            + readResult.newestCommitTimestamp() + ']';

            return new TimedBinaryRow(committedReadResult.binaryRow(), committedReadResult.commitTimestamp());
        });
    }

    /**
     * Resolves a result received from a direct storage read. Use it for RW.
     *
     * @param rowId Row id.
     * @param txId Transaction id.
     * @return Future finishes with the resolved binary row.
     */
    private CompletableFuture<@Nullable TimedBinaryRow> resolvePlainReadResult(RowId rowId, UUID txId) {
        return resolvePlainReadResult(rowId, txId, null).thenCompose(row -> {
            if (row == null || row.binaryRow() == null) {
                return nullCompletedFuture();
            }

            return schemaCompatValidator.validateBackwards(row.binaryRow().schemaVersion(), tableId(), txId)
                    .thenApply(validationResult -> {
                        if (validationResult.isSuccessful()) {
                            return row;
                        } else {
                            throw new IncompatibleSchemaException("Operation failed because schema "
                                    + validationResult.fromSchemaVersion() + " is not backward-compatible with "
                                    + validationResult.toSchemaVersion() + " for table " + validationResult.failedTableId());
                        }
                    });
        });
    }

    /**
     * Processes transaction finish request.
     * <ol>
     *     <li>Get commit timestamp from finish replica request.</li>
     *     <li>If attempting a commit, validate commit (and, if not valid, switch to abort)</li>
     *     <li>Run specific raft {@code FinishTxCommand} command, that will apply txn state to corresponding txStateStorage.</li>
     *     <li>Send cleanup requests to all enlisted primary replicas.</li>
     * </ol>
     *
     * @param request Transaction finish request.
     * @param txCoordinatorId Transaction coordinator id.
     * @return future result of the operation.
     */
    // TODO: need to properly handle primary replica changes https://issues.apache.org/jira/browse/IGNITE-17615
    private CompletableFuture<TransactionResult> processTxFinishAction(TxFinishReplicaRequest request, String txCoordinatorId) {
        // TODO: https://issues.apache.org/jira/browse/IGNITE-19170 Use ZonePartitionIdMessage and remove cast
        Collection<TablePartitionId> enlistedGroups = (Collection<TablePartitionId>) (Collection<?>) request.groups();

        UUID txId = request.txId();

        if (request.commit()) {
            HybridTimestamp commitTimestamp = request.commitTimestamp();

            return schemaCompatValidator.validateCommit(txId, enlistedGroups, commitTimestamp)
                    .thenCompose(validationResult ->
                            finishAndCleanup(
                                    enlistedGroups,
                                    validationResult.isSuccessful(),
                                    validationResult.isSuccessful() ? commitTimestamp : null,
                                    txId,
                                    txCoordinatorId
                            ).thenApply(txResult -> {
                                throwIfSchemaValidationOnCommitFailed(validationResult);
                                return txResult;
                            }));
        } else {
            // Aborting.
            return finishAndCleanup(enlistedGroups, false, null, txId, txCoordinatorId);
        }
    }

    private static void throwIfSchemaValidationOnCommitFailed(CompatValidationResult validationResult) {
        if (!validationResult.isSuccessful()) {
            if (validationResult.isTableDropped()) {
                // TODO: IGNITE-20966 - improve error message.
                throw new IncompatibleSchemaAbortException(
                        format("Commit failed because a table was already dropped [tableId={}]", validationResult.failedTableId())
                );
            } else {
                // TODO: IGNITE-20966 - improve error message.
                throw new IncompatibleSchemaAbortException("Commit failed because schema "
                        + validationResult.fromSchemaVersion() + " is not forward-compatible with "
                        + validationResult.toSchemaVersion() + " for table " + validationResult.failedTableId());
            }
        }
    }

    private CompletableFuture<TransactionResult> finishAndCleanup(
            Collection<TablePartitionId> enlistedPartitions,
            boolean commit,
            @Nullable HybridTimestamp commitTimestamp,
            UUID txId,
            String txCoordinatorId
    ) {
        // Read TX state from the storage, we will need this state to check if the locks are released.
        // Since this state is written only on the transaction finish (see PartitionListener.handleFinishTxCommand),
        // the value of txMeta can be either null or COMMITTED/ABORTED. No other values is expected.
        TxMeta txMeta = txStateStorage.get(txId);

        // Check whether a transaction has already been finished.
        boolean transactionAlreadyFinished = txMeta != null && isFinalState(txMeta.txState());

        if (transactionAlreadyFinished) {
            // Check locksReleased flag. If it is already set, do nothing and return a successful result.
            // Even if the outcome is different (the transaction was aborted, but we want to commit it),
            // we return 'success' to be in alignment with common transaction handling.
            if (txMeta.locksReleased()) {
                return completedFuture(new TransactionResult(txMeta.txState(), txMeta.commitTimestamp()));
            }

            // The transaction is finished, but the locks are not released.
            // If we got here, it means we are retrying the finish request.
            // Let's make sure the desired state is valid.
            // - The Coordinator calls use same tx state over retries, both abort and commit are possible.
            // - Server side recovery may only change tx state to aborted.
            // - The Coordinator itself should prevent user calls with different proposed state to the one,
            //   that was already triggered (e.g. the client side -> txCoordinator.commitAsync(); txCoordinator.rollbackAsync()).
            // - A coordinator might send a commit, then die, but the commit message might still arrive at the commit partition primary.
            //   If it arrived with a delay, another node might come across a write intent/lock from that tx
            //   and realize that the coordinator is no longer available and start tx recovery.
            //   The original commit message might arrive later than the recovery one,
            //   hence a 'commit over rollback' case.
            // The possible states that a 'commit' is allowed to see:
            // - null (if it's the first change state attempt)
            // - committed (if it was already updated in the previous attempt)
            // - aborted (if it was aborted by the initiate recovery logic,
            //   though this is a very unlikely case because initiate recovery will only roll back the tx if coordinator is dead).
            //
            // Within 'roll back' it's allowed to see:
            // - null (if it's the first change state attempt)
            // - aborted  (if it was already updated in the previous attempt or the result of a concurrent recovery)
            // - commit (if initiate recovery has started, but a delayed message from the coordinator finally arrived and executed earlier).

            // Let the client know a transaction has finished with a different outcome.
            if (commit != (txMeta.txState() == COMMITTED)) {
                LOG.error("Failed to finish a transaction that is already finished [txId={}, expectedState={}, actualState={}].",
                        txId,
                        commit ? COMMITTED : ABORTED,
                        txMeta.txState()
                );

                throw new TransactionAlreadyFinishedException(
                        "Failed to change the outcome of a finished transaction [txId=" + txId + ", txState=" + txMeta.txState() + "].",
                        new TransactionResult(txMeta.txState(), txMeta.commitTimestamp())
                );
            }
        }

        return finishTransaction(enlistedPartitions, txId, commit, commitTimestamp, txCoordinatorId)
                .thenCompose(txResult ->
                        txManager.cleanup(enlistedPartitions, commit, commitTimestamp, txId)
                                .thenRun(() -> markLocksReleased(txId))
                                .thenApply(v -> txResult)
                );
    }

    /**
     * Finishes a transaction. This operation is idempotent.
     *
     * @param aggregatedGroupIds Partition identifies which are enlisted in the transaction.
     * @param txId Transaction id.
     * @param commit True is the transaction is committed, false otherwise.
     * @param commitTimestamp Commit timestamp, if applicable.
     * @param txCoordinatorId Transaction coordinator id.
     * @return Future to wait of the finish.
     */
    private CompletableFuture<TransactionResult> finishTransaction(
            Collection<TablePartitionId> aggregatedGroupIds,
            UUID txId,
            boolean commit,
            @Nullable HybridTimestamp commitTimestamp,
            String txCoordinatorId
    ) {
        assert !commit || (commitTimestamp != null);

        HybridTimestamp tsForCatalogVersion = commit ? commitTimestamp : hybridClock.now();

        return reliableCatalogVersionFor(tsForCatalogVersion)
                .thenCompose(catalogVersion -> applyFinishCommand(
                                txId,
                                commit,
                                commitTimestamp,
                                txCoordinatorId,
                                catalogVersion,
                                aggregatedGroupIds.stream()
                                        .map(PartitionReplicaListener::tablePartitionId)
                                        .collect(toList())
                        )
                )
                .handle((txOutcome, ex) -> {
                    if (ex != null) {
                        // RAFT 'finish' command failed because the state has already been written by someone else.
                        // In that case we throw a corresponding exception.
                        if (ex instanceof UnexpectedTransactionStateException) {
                            UnexpectedTransactionStateException utse = (UnexpectedTransactionStateException) ex;
                            TransactionResult result = utse.transactionResult();

                            markFinished(txId, result.transactionState(), result.commitTimestamp());

                            throw new TransactionAlreadyFinishedException(utse.getMessage(), utse.transactionResult());
                        }
                        // Otherwise we convert from the internal exception to the client one.
                        throw new TransactionException(commit ? TX_COMMIT_ERR : TX_ROLLBACK_ERR, ex);
                    }

                    TransactionResult result = (TransactionResult) txOutcome;

                    markFinished(txId, result.transactionState(), result.commitTimestamp());

                    return result;
                });
    }

    private CompletableFuture<Object> applyFinishCommand(
            UUID transactionId,
            boolean commit,
            HybridTimestamp commitTimestamp,
            String txCoordinatorId,
            int catalogVersion,
            List<TablePartitionIdMessage> tablePartitionIds
    ) {
        synchronized (commandProcessingLinearizationMutex) {
            FinishTxCommandBuilder finishTxCmdBldr = MSG_FACTORY.finishTxCommand()
                    .txId(transactionId)
                    .commit(commit)
                    .safeTimeLong(hybridClock.nowLong())
                    .txCoordinatorId(txCoordinatorId)
                    .requiredCatalogVersion(catalogVersion)
                    .tablePartitionIds(tablePartitionIds);

            if (commit) {
                finishTxCmdBldr.commitTimestampLong(commitTimestamp.longValue());
            }
            CompletableFuture<Object> resultFuture = new CompletableFuture<>();

            applyCmdWithRetryOnSafeTimeReorderException(finishTxCmdBldr.build(), resultFuture);

            return resultFuture;
        }
    }

    /**
     * Processes transaction cleanup request:
     * <ol>
     *     <li>Waits for finishing of local transactional operations;</li>
     *     <li>Runs asynchronously the specific raft {@code TxCleanupCommand} command, that will convert all pending entries(writeIntents)
     *     to either regular values({@link TxState#COMMITTED}) or removing them ({@link TxState#ABORTED});</li>
     *     <li>Releases all locks that were held on local Replica by given transaction.</li>
     * </ol>
     * This operation is idempotent, so it's safe to retry it.
     *
     * @param request Transaction cleanup request.
     * @return CompletableFuture of void.
     */
    // TODO: need to properly handle primary replica changes https://issues.apache.org/jira/browse/IGNITE-17615
    private CompletableFuture<Void> processWriteIntentSwitchAction(WriteIntentSwitchReplicaRequest request) {
        try {
            closeAllTransactionCursors(request.txId());
        } catch (Exception e) {
            return failedFuture(e);
        }

        TxState txState = request.commit() ? COMMITTED : ABORTED;

        markFinished(request.txId(), txState, request.commitTimestamp());

        return awaitCleanupReadyFutures(request.txId(), request.commit())
                .thenCompose(res -> {
                    if (res.hadUpdateFutures()) {
                        HybridTimestamp commandTimestamp = hybridClock.now();

                        return reliableCatalogVersionFor(commandTimestamp)
                                .thenCompose(catalogVersion -> {
                                    applyWriteIntentSwitchCommand(
                                            request.txId(),
                                            request.commit(),
                                            request.commitTimestamp(),
                                            request.commitTimestampLong(),
                                            catalogVersion
                                    );

                                    return nullCompletedFuture();
                                });
                    } else {
                        return nullCompletedFuture();
                    }
                });
    }

    private CompletableFuture<FuturesCleanupResult> awaitCleanupReadyFutures(UUID txId, boolean commit) {
        List<CompletableFuture<?>> txUpdateFutures = new ArrayList<>();
        List<CompletableFuture<?>> txReadFutures = new ArrayList<>();

        // TODO https://issues.apache.org/jira/browse/IGNITE-18617
        txCleanupReadyFutures.compute(txId, (id, txOps) -> {
            if (txOps == null) {
                return null;
            }

            txOps.futures.forEach((opType, futures) -> {
                if (opType.isRwRead()) {
                    txReadFutures.addAll(futures);
                } else {
                    txUpdateFutures.addAll(futures);
                }
            });

            txOps.futures.clear();

            return txOps;
        });

        return allOfFuturesExceptionIgnored(txUpdateFutures, commit, txId)
                .thenCompose(v -> allOfFuturesExceptionIgnored(txReadFutures, commit, txId))
                .thenApply(v -> new FuturesCleanupResult(!txReadFutures.isEmpty(), !txUpdateFutures.isEmpty()));
    }

    private CompletableFuture<Void> applyWriteIntentSwitchCommand(
            UUID transactionId,
            boolean commit,
            HybridTimestamp commitTimestamp,
            long commitTimestampLong,
            int catalogVersion
    ) {
        WriteIntentSwitchCommand wiSwitchCmd = MSG_FACTORY.writeIntentSwitchCommand()
                .txId(transactionId)
                .commit(commit)
                .commitTimestampLong(commitTimestampLong)
                .safeTimeLong(hybridClock.nowLong())
                .txCoordinatorId(getTxCoordinatorId(transactionId))
                .requiredCatalogVersion(catalogVersion)
                .build();

        storageUpdateHandler.switchWriteIntents(transactionId, commit, commitTimestamp);

        CompletableFuture<Object> resultFuture = new CompletableFuture<>();

        applyCmdWithRetryOnSafeTimeReorderException(wiSwitchCmd, resultFuture);

        return resultFuture
                .exceptionally(e -> {
                    LOG.warn("Failed to complete transaction cleanup command [txId=" + transactionId + ']', e);

                    return nullCompletedFuture();
                })
                .thenApply(res -> null);
    }

    private @Nullable String getTxCoordinatorId(UUID txId) {
        TxStateMeta meta = txManager.stateMeta(txId);

        assert meta != null : "Trying to cleanup a transaction that was not enlisted, txId=" + txId;

        return meta.txCoordinatorId();
    }

    /**
     * Creates a future that waits all transaction operations are completed.
     *
     * @param txFutures Transaction operation futures.
     * @param commit If {@code true} this is a commit otherwise a rollback.
     * @param txId Transaction id.
     * @return The future completes when all futures in passed list are completed.
     */
    private static CompletableFuture<Void> allOfFuturesExceptionIgnored(List<CompletableFuture<?>> txFutures, boolean commit, UUID txId) {
        return allOf(txFutures.toArray(new CompletableFuture<?>[0]))
                .exceptionally(e -> {
                    assert !commit :
                            "Transaction is committing, but an operation has completed with exception [txId=" + txId
                                    + ", err=" + e.getMessage() + ']';

                    return null;
                });
    }

    private void releaseTxLocks(UUID txId) {
        lockManager.releaseAll(txId);
    }

    /**
     * Finds the row and its identifier by given pk search row.
     *
     * @param pk Binary Tuple representing a primary key.
     * @param txId An identifier of the transaction regarding which we need to resolve the given row.
     * @param action An action to perform on a resolved row.
     * @param <T> A type of the value returned by action.
     * @return A future object representing the result of the given action.
     */
    private <T> CompletableFuture<T> resolveRowByPk(
            BinaryTuple pk,
            UUID txId,
            IgniteTriFunction<@Nullable RowId, @Nullable BinaryRow, @Nullable HybridTimestamp, CompletableFuture<T>> action
    ) {
        IndexLocker pkLocker = indexesLockers.get().get(pkIndexStorage.get().id());

        assert pkLocker != null;

        return pkLocker.locksForLookupByKey(txId, pk)
                .thenCompose(ignored -> {

                    boolean cursorClosureSetUp = false;
                    Cursor<RowId> cursor = null;

                    try {
                        cursor = getFromPkIndex(pk);

                        Cursor<RowId> finalCursor = cursor;
                        CompletableFuture<T> resolvingFuture = continueResolvingByPk(cursor, txId, action)
                                .whenComplete((res, ex) -> finalCursor.close());

                        cursorClosureSetUp = true;

                        return resolvingFuture;
                    } finally {
                        if (!cursorClosureSetUp && cursor != null) {
                            cursor.close();
                        }
                    }
                });
    }

    private <T> CompletableFuture<T> continueResolvingByPk(
            Cursor<RowId> cursor,
            UUID txId,
            IgniteTriFunction<@Nullable RowId, @Nullable BinaryRow, @Nullable HybridTimestamp, CompletableFuture<T>> action
    ) {
        if (!cursor.hasNext()) {
            return action.apply(null, null, null);
        }

        RowId rowId = cursor.next();

        return resolvePlainReadResult(rowId, txId).thenCompose(row -> {
            if (row != null && row.binaryRow() != null) {
                return action.apply(rowId, row.binaryRow(), row.commitTimestamp());
            } else {
                return continueResolvingByPk(cursor, txId, action);
            }
        });
    }

    /**
     * Appends an operation to prevent the race between commit/rollback and the operation execution.
     *
     * @param txId Transaction id.
     * @param cmdType Command type.
     * @param full {@code True} if a full transaction and can be immediately committed.
     * @param op Operation closure.
     * @return A future object representing the result of the given operation.
     */
    private <T> CompletableFuture<T> appendTxCommand(UUID txId, RequestType cmdType, boolean full, Supplier<CompletableFuture<T>> op) {
        if (full) {
            return op.get().whenComplete((v, th) -> {
                // Fast unlock.
                releaseTxLocks(txId);
            });
        }

        var cleanupReadyFut = new CompletableFuture<Void>();

        txCleanupReadyFutures.compute(txId, (id, txOps) -> {
            if (txOps == null) {
                txOps = new TxCleanupReadyFutureList();
            }

            TxStateMeta txStateMeta = txManager.stateMeta(txId);

            if (txStateMeta == null || isFinalState(txStateMeta.txState())) {
                cleanupReadyFut.completeExceptionally(new Exception());
            } else {
                txOps.futures.computeIfAbsent(cmdType, type -> new ArrayList<>()).add(cleanupReadyFut);
            }

            return txOps;
        });

        if (cleanupReadyFut.isCompletedExceptionally()) {
            return failedFuture(new TransactionException(TX_FAILED_READ_WRITE_OPERATION_ERR, "Transaction is already finished."));
        }

        CompletableFuture<T> fut = op.get();

        fut.whenComplete((v, th) -> {
            if (th != null) {
                cleanupReadyFut.completeExceptionally(th);
            } else {
                if (v instanceof ReplicaResult) {
                    ReplicaResult res = (ReplicaResult) v;

                    if (res.replicationFuture() != null) {
                        res.replicationFuture().whenComplete((v0, th0) -> {
                            if (th0 != null) {
                                cleanupReadyFut.completeExceptionally(th0);
                            } else {
                                cleanupReadyFut.complete(null);
                            }
                        });
                    } else {
                        cleanupReadyFut.complete(null);
                    }
                } else {
                    cleanupReadyFut.complete(null);
                }
            }
        });

        return fut;
    }

    /**
     * Finds the row and its identifier by given pk search row.
     *
     * @param pk Binary Tuple bytes representing a primary key.
     * @param ts A timestamp regarding which we need to resolve the given row.
     * @return Result of the given action.
     */
    private CompletableFuture<@Nullable BinaryRow> resolveRowByPkForReadOnly(BinaryTuple pk, HybridTimestamp ts) {
        // Indexes store values associated with different versions of one entry.
        // It's possible to have multiple entries for a particular search key
        // only if we insert, delete and again insert an entry with the same indexed fields.
        // It means that there exists one and only one non-empty readResult for any read timestamp for the given key.
        // Which in turn means that if we have found non empty readResult during PK index iteration
        // we can proceed with readResult resolution and stop the iteration.
        try (Cursor<RowId> cursor = getFromPkIndex(pk)) {
            // TODO https://issues.apache.org/jira/browse/IGNITE-18767 scan of multiple write intents should not be needed
            List<ReadResult> writeIntents = new ArrayList<>();
            List<ReadResult> regularEntries = new ArrayList<>();

            for (RowId rowId : cursor) {
                ReadResult readResult = mvDataStorage.read(rowId, ts);

                if (readResult.isWriteIntent()) {
                    writeIntents.add(readResult);
                } else if (!readResult.isEmpty()) {
                    regularEntries.add(readResult);
                }
            }

            // Nothing found in the storage, return null.
            if (writeIntents.isEmpty() && regularEntries.isEmpty()) {
                return nullCompletedFuture();
            }

            if (writeIntents.isEmpty()) {
                // No write intents, then return the committed value. We already know that regularEntries is not empty.
                return completedFuture(regularEntries.get(0).binaryRow());
            } else {
                ReadResult writeIntent = writeIntents.get(0);

                // Assume that all write intents for the same key belong to the same transaction, as the key should be exclusively locked.
                // This means that we can just resolve the state of this transaction.
                checkWriteIntentsBelongSameTx(writeIntents);

                return inBusyLockAsync(busyLock, () ->
                        resolveWriteIntentReadability(writeIntent, ts)
                                .thenApply(writeIntentReadable ->
                                        inBusyLock(busyLock, () -> {
                                            if (writeIntentReadable) {
                                                return findAny(writeIntents, wi -> !wi.isEmpty()).map(ReadResult::binaryRow).orElse(null);
                                            } else {
                                                for (ReadResult wi : writeIntents) {
                                                    HybridTimestamp newestCommitTimestamp = wi.newestCommitTimestamp();

                                                    if (newestCommitTimestamp == null) {
                                                        continue;
                                                    }

                                                    ReadResult committedReadResult = mvDataStorage.read(wi.rowId(), newestCommitTimestamp);

                                                    assert !committedReadResult.isWriteIntent() :
                                                            "The result is not committed [rowId=" + wi.rowId() + ", timestamp="
                                                                    + newestCommitTimestamp + ']';

                                                    return committedReadResult.binaryRow();
                                                }

                                                // No suitable value found in write intents, read the committed value (if exists)
                                                return findFirst(regularEntries).map(ReadResult::binaryRow).orElse(null);
                                            }
                                        }))
                );
            }
        } catch (Exception e) {
            throw new IgniteInternalException(Replicator.REPLICA_COMMON_ERR,
                    format("Unable to close cursor [tableId={}]", tableId()), e);
        }
    }

    /**
     * Check that all given write intents belong to the same transaction.
     *
     * @param writeIntents Write intents.
     */
    private static void checkWriteIntentsBelongSameTx(Collection<ReadResult> writeIntents) {
        ReadResult writeIntent = findAny(writeIntents).orElseThrow();

        for (ReadResult wi : writeIntents) {
            assert Objects.equals(wi.transactionId(), writeIntent.transactionId())
                    : "Unexpected write intent, tx1=" + writeIntent.transactionId() + ", tx2=" + wi.transactionId();

            assert Objects.equals(wi.commitTableId(), writeIntent.commitTableId())
                    : "Unexpected write intent, commitTableId1=" + writeIntent.commitTableId() + ", commitTableId2=" + wi.commitTableId();

            assert wi.commitPartitionId() == writeIntent.commitPartitionId()
                    : "Unexpected write intent, commitPartitionId1=" + writeIntent.commitPartitionId()
                    + ", commitPartitionId2=" + wi.commitPartitionId();
        }
    }

    /**
     * Tests row values for equality.
     *
     * @param row Row.
     * @param row2 Row.
     * @return {@code true} if rows are equal.
     */
    private static boolean equalValues(BinaryRow row, BinaryRow row2) {
        return row.tupleSlice().compareTo(row2.tupleSlice()) == 0;
    }

    /**
     * Processes multiple entries direct request for read only transaction.
     *
     * @param request Read only multiple entries request.
     * @param opStartTimestamp Moment when the operation processing was started in this class.
     * @return Result future.
     */
    private CompletableFuture<List<BinaryRow>> processReadOnlyDirectMultiEntryAction(
            ReadOnlyDirectMultiRowReplicaRequest request,
            HybridTimestamp opStartTimestamp) {
        List<BinaryTuple> primaryKeys = resolvePks(request.primaryKeys());
        HybridTimestamp readTimestamp = opStartTimestamp;

        if (request.requestType() != RequestType.RO_GET_ALL) {
            throw new IgniteInternalException(Replicator.REPLICA_COMMON_ERR,
                    format("Unknown single request [actionType={}]", request.requestType()));
        }

        CompletableFuture<BinaryRow>[] resolutionFuts = new CompletableFuture[primaryKeys.size()];

        for (int i = 0; i < primaryKeys.size(); i++) {
            resolutionFuts[i] = resolveRowByPkForReadOnly(primaryKeys.get(i), readTimestamp);
        }

        return CompletableFutures.allOf(resolutionFuts);
    }

    /**
     * Precesses multi request.
     *
     * @param request Multi request operation.
     * @param txCoordinatorId Transaction coordinator id.
     * @return Listener response.
     */
    private CompletableFuture<ReplicaResult> processMultiEntryAction(ReadWriteMultiRowReplicaRequest request, String txCoordinatorId) {
        UUID txId = request.transactionId();
        TablePartitionId commitPartitionId = request.commitPartitionId().asTablePartitionId();
        List<BinaryRow> searchRows = request.binaryRows();

        assert commitPartitionId != null : "Commit partition is null [type=" + request.requestType() + ']';

        switch (request.requestType()) {
            case RW_DELETE_EXACT_ALL: {
                CompletableFuture<RowId>[] deleteExactLockFuts = new CompletableFuture[searchRows.size()];

                Map<UUID, HybridTimestamp> lastCommitTimes = new HashMap<>();

                for (int i = 0; i < searchRows.size(); i++) {
                    BinaryRow searchRow = searchRows.get(i);

                    deleteExactLockFuts[i] = resolveRowByPk(extractPk(searchRow), txId, (rowId, row, lastCommitTime) -> {
                        if (rowId == null) {
                            return nullCompletedFuture();
                        }

                        if (lastCommitTime != null) {
                            lastCommitTimes.put(rowId.uuid(), lastCommitTime);
                        }

                        return takeLocksForDeleteExact(searchRow, rowId, row, txId);
                    });
                }

                return allOf(deleteExactLockFuts).thenCompose(ignore -> {
                    Map<UUID, TimedBinaryRowMessage> rowIdsToDelete = new HashMap<>();
                    // TODO:IGNITE-20669 Replace the result to BitSet.
                    Collection<BinaryRow> result = new ArrayList<>();
                    List<RowId> rows = new ArrayList<>();

                    for (int i = 0; i < searchRows.size(); i++) {
                        RowId lockedRowId = deleteExactLockFuts[i].join();

                        if (lockedRowId != null) {
                            rowIdsToDelete.put(lockedRowId.uuid(), MSG_FACTORY.timedBinaryRowMessage()
                                    .timestamp(hybridTimestampToLong(lastCommitTimes.get(lockedRowId.uuid())))
                                    .build());

                            result.add(new NullBinaryRow());

                            rows.add(lockedRowId);
                        } else {
                            result.add(null);
                        }
                    }

                    if (rowIdsToDelete.isEmpty()) {
                        return completedFuture(new ReplicaResult(result, null));
                    }

                    return validateWriteAgainstSchemaAfterTakingLocks(request.transactionId())
                            .thenCompose(catalogVersion -> awaitCleanup(rows, catalogVersion))
                            .thenCompose(
                                    catalogVersion -> applyUpdateAllCommand(
                                            request,
                                            rowIdsToDelete,
                                            txCoordinatorId,
                                            catalogVersion
                                    )
                            )
                            .thenApply(res -> new ReplicaResult(result, res));
                });
            }
            case RW_INSERT_ALL: {
                List<BinaryTuple> pks = new ArrayList<>(searchRows.size());

                CompletableFuture<RowId>[] pkReadLockFuts = new CompletableFuture[searchRows.size()];

                for (int i = 0; i < searchRows.size(); i++) {
                    BinaryTuple pk = extractPk(searchRows.get(i));

                    pks.add(pk);

                    pkReadLockFuts[i] = resolveRowByPk(pk, txId, (rowId, row, lastCommitTime) -> completedFuture(rowId));
                }

                return allOf(pkReadLockFuts).thenCompose(ignore -> {
                    // TODO:IGNITE-20669 Replace the result to BitSet.
                    Collection<BinaryRow> result = new ArrayList<>();
                    Map<RowId, BinaryRow> rowsToInsert = new HashMap<>();
                    Set<ByteBuffer> uniqueKeys = new HashSet<>();

                    for (int i = 0; i < searchRows.size(); i++) {
                        BinaryRow row = searchRows.get(i);
                        RowId lockedRow = pkReadLockFuts[i].join();

                        if (lockedRow == null && uniqueKeys.add(pks.get(i).byteBuffer())) {
                            rowsToInsert.put(new RowId(partId(), UUID.randomUUID()), row);

                            result.add(new NullBinaryRow());
                        } else {
                            result.add(null);
                        }
                    }

                    if (rowsToInsert.isEmpty()) {
                        return completedFuture(new ReplicaResult(result, null));
                    }

                    CompletableFuture<IgniteBiTuple<RowId, Collection<Lock>>>[] insertLockFuts = new CompletableFuture[rowsToInsert.size()];

                    int idx = 0;

                    for (Map.Entry<RowId, BinaryRow> entry : rowsToInsert.entrySet()) {
                        insertLockFuts[idx++] = takeLocksForInsert(entry.getValue(), entry.getKey(), txId);
                    }

                    Map<UUID, TimedBinaryRowMessage> convertedMap = rowsToInsert.entrySet().stream()
                            .collect(toMap(
                                    e -> e.getKey().uuid(),
                                    e -> MSG_FACTORY.timedBinaryRowMessage()
                                            .binaryRowMessage(binaryRowMessage(e.getValue()))
                                            .build()
                            ));

                    return allOf(insertLockFuts)
                            .thenCompose(ignored ->
                                    // We are inserting completely new rows - no need to cleanup anything in this case, hence empty times.
                                    validateWriteAgainstSchemaAfterTakingLocks(request.transactionId())
                            )
                            .thenCompose(catalogVersion -> applyUpdateAllCommand(
                                            request,
                                            convertedMap,
                                            txCoordinatorId,
                                            catalogVersion
                                    )
                            )
                            .thenApply(res -> {
                                // Release short term locks.
                                for (CompletableFuture<IgniteBiTuple<RowId, Collection<Lock>>> insertLockFut : insertLockFuts) {
                                    insertLockFut.join().get2()
                                            .forEach(lock -> lockManager.release(lock.txId(), lock.lockKey(), lock.lockMode()));
                                }

                                return new ReplicaResult(result, res);
                            });
                });
            }
            case RW_UPSERT_ALL: {
                CompletableFuture<IgniteBiTuple<RowId, Collection<Lock>>>[] rowIdFuts = new CompletableFuture[searchRows.size()];

                Map<UUID, HybridTimestamp> lastCommitTimes = new HashMap<>();

                for (int i = 0; i < searchRows.size(); i++) {
                    BinaryRow searchRow = searchRows.get(i);

                    rowIdFuts[i] = resolveRowByPk(extractPk(searchRow), txId, (rowId, row, lastCommitTime) -> {
                        boolean insert = rowId == null;

                        RowId rowId0 = insert ? new RowId(partId(), UUID.randomUUID()) : rowId;

                        if (lastCommitTime != null) {
                            lastCommitTimes.put(rowId.uuid(), lastCommitTime);
                        }

                        return insert
                                ? takeLocksForInsert(searchRow, rowId0, txId)
                                : takeLocksForUpdate(searchRow, rowId0, txId);
                    });
                }

                return allOf(rowIdFuts).thenCompose(ignore -> {
                    Map<UUID, TimedBinaryRowMessage> rowsToUpdate = IgniteUtils.newHashMap(searchRows.size());
                    List<RowId> rows = new ArrayList<>();

                    for (int i = 0; i < searchRows.size(); i++) {
                        RowId lockedRow = rowIdFuts[i].join().get1();

                        rowsToUpdate.put(lockedRow.uuid(),
                                MSG_FACTORY.timedBinaryRowMessage()
                                        .binaryRowMessage(binaryRowMessage(searchRows.get(i)))
                                        .timestamp(hybridTimestampToLong(lastCommitTimes.get(lockedRow.uuid())))
                                        .build());

                        rows.add(lockedRow);
                    }

                    if (rowsToUpdate.isEmpty()) {
                        return completedFuture(new ReplicaResult(null, null));
                    }

                    return validateWriteAgainstSchemaAfterTakingLocks(request.transactionId())
                            .thenCompose(catalogVersion -> awaitCleanup(rows, catalogVersion))
                            .thenCompose(
                                    catalogVersion -> applyUpdateAllCommand(
                                            request,
                                            rowsToUpdate,
                                            txCoordinatorId,
                                            catalogVersion
                                    )
                            )
                            .thenApply(res -> {
                                // Release short term locks.
                                for (CompletableFuture<IgniteBiTuple<RowId, Collection<Lock>>> rowIdFut : rowIdFuts) {
                                    rowIdFut.join().get2()
                                            .forEach(lock -> lockManager.release(lock.txId(), lock.lockKey(), lock.lockMode()));
                                }

                                return new ReplicaResult(null, res);
                            });
                });
            }
            default: {
                throw new IgniteInternalException(Replicator.REPLICA_COMMON_ERR,
                        format("Unknown multi request [actionType={}]", request.requestType()));
            }
        }
    }

    /**
     * Precesses multi request.
     *
     * @param request Multi request operation.
     * @param txCoordinatorId Transaction coordinator id.
     * @return Listener response.
     */
    private CompletableFuture<?> processMultiEntryAction(ReadWriteMultiRowPkReplicaRequest request, String txCoordinatorId) {
        UUID txId = request.transactionId();
        TablePartitionId committedPartitionId = request.commitPartitionId().asTablePartitionId();
        List<BinaryTuple> primaryKeys = resolvePks(request.primaryKeys());

        assert committedPartitionId != null || request.requestType() == RequestType.RW_GET_ALL
                : "Commit partition is null [type=" + request.requestType() + ']';

        switch (request.requestType()) {
            case RW_GET_ALL: {
                CompletableFuture<BinaryRow>[] rowFuts = new CompletableFuture[primaryKeys.size()];

                for (int i = 0; i < primaryKeys.size(); i++) {
                    rowFuts[i] = resolveRowByPk(primaryKeys.get(i), txId, (rowId, row, lastCommitTime) -> {
                        if (rowId == null) {
                            return nullCompletedFuture();
                        }

                        return takeLocksForGet(rowId, txId)
                                .thenApply(ignored -> row);
                    });
                }

                return allOf(rowFuts)
                        .thenCompose(ignored -> {
                            var result = new ArrayList<BinaryRow>(primaryKeys.size());

                            for (CompletableFuture<BinaryRow> rowFut : rowFuts) {
                                result.add(rowFut.join());
                            }

                            if (allElementsAreNull(result)) {
                                return completedFuture(result);
                            }

                            return validateRwReadAgainstSchemaAfterTakingLocks(txId)
                                    .thenApply(unused -> new ReplicaResult(result, null));
                        });
            }
            case RW_DELETE_ALL: {
                CompletableFuture<RowId>[] rowIdLockFuts = new CompletableFuture[primaryKeys.size()];

                Map<UUID, HybridTimestamp> lastCommitTimes = new HashMap<>();

                for (int i = 0; i < primaryKeys.size(); i++) {
                    rowIdLockFuts[i] = resolveRowByPk(primaryKeys.get(i), txId, (rowId, row, lastCommitTime) -> {
                        if (rowId == null) {
                            return nullCompletedFuture();
                        }

                        if (lastCommitTime != null) {
                            lastCommitTimes.put(rowId.uuid(), lastCommitTime);
                        }

                        return takeLocksForDelete(row, rowId, txId);
                    });
                }

                return allOf(rowIdLockFuts).thenCompose(ignore -> {
                    Map<UUID, TimedBinaryRowMessage> rowIdsToDelete = new HashMap<>();
                    // TODO:IGNITE-20669 Replace the result to BitSet.
                    Collection<BinaryRow> result = new ArrayList<>();
                    List<RowId> rows = new ArrayList<>();

                    for (CompletableFuture<RowId> lockFut : rowIdLockFuts) {
                        RowId lockedRowId = lockFut.join();

                        if (lockedRowId != null) {
                            rowIdsToDelete.put(lockedRowId.uuid(), MSG_FACTORY.timedBinaryRowMessage()
                                    .timestamp(hybridTimestampToLong(lastCommitTimes.get(lockedRowId.uuid())))
                                    .build());

                            rows.add(lockedRowId);

                            result.add(new NullBinaryRow());
                        } else {
                            result.add(null);
                        }
                    }

                    if (rowIdsToDelete.isEmpty()) {
                        return completedFuture(new ReplicaResult(result, null));
                    }

                    return validateWriteAgainstSchemaAfterTakingLocks(request.transactionId())
                            .thenCompose(catalogVersion -> awaitCleanup(rows, catalogVersion))
                            .thenCompose(
                                    catalogVersion -> applyUpdateAllCommand(
                                            rowIdsToDelete,
                                            request.commitPartitionId(),
                                            request.transactionId(),
                                            request.full(),
                                            txCoordinatorId,
                                            catalogVersion,
                                            request.skipDelayedAck()
                                    )
                            )
                            .thenApply(res -> new ReplicaResult(result, res));
                });
            }
            default: {
                throw new IgniteInternalException(Replicator.REPLICA_COMMON_ERR,
                        format("Unknown multi request [actionType={}]", request.requestType()));
            }
        }
    }

    private static <T> boolean allElementsAreNull(List<T> list) {
        for (T element : list) {
            if (element != null) {
                return false;
            }
        }

        return true;
    }

    /**
     * Executes a command and handles exceptions. A result future can be finished with exception by following rules:
     * <ul>
     *     <li>If RAFT command cannot finish due to timeout, the future finished with {@link ReplicationTimeoutException}.</li>
     *     <li>If RAFT command finish with a runtime exception, the exception is moved to the result future.</li>
     *     <li>If RAFT command finish with any other exception, the future finished with {@link ReplicationException}.
     *     The original exception is set as cause.</li>
     * </ul>
     *
     * @param cmd Raft command.
     * @return Raft future.
     */
    private CompletableFuture<Object> applyCmdWithExceptionHandling(Command cmd, CompletableFuture<Object> resultFuture) {
        applyCmdWithRetryOnSafeTimeReorderException(cmd, resultFuture);

        return resultFuture.exceptionally(throwable -> {
            if (throwable instanceof TimeoutException) {
                throw new ReplicationTimeoutException(replicationGroupId);
            } else if (throwable instanceof RuntimeException) {
                throw (RuntimeException) throwable;
            } else {
                throw new ReplicationException(replicationGroupId, throwable);
            }
        });
    }

    private <T> void applyCmdWithRetryOnSafeTimeReorderException(Command cmd, CompletableFuture<T> resultFuture) {
        applyCmdWithRetryOnSafeTimeReorderException(cmd, resultFuture, 0);
    }

    private <T> void applyCmdWithRetryOnSafeTimeReorderException(Command cmd, CompletableFuture<T> resultFuture, int attemptsCounter) {
        attemptsCounter++;
        if (attemptsCounter >= MAX_RETIES_ON_SAFE_TIME_REORDERING) {
            resultFuture.completeExceptionally(
                    new ReplicationMaxRetriesExceededException(replicationGroupId, MAX_RETIES_ON_SAFE_TIME_REORDERING));
        }

        raftClient.run(cmd).whenComplete((res, ex) -> {
            if (ex != null) {
                if (ex instanceof SafeTimeReorderException || ex.getCause() instanceof SafeTimeReorderException) {
                    assert cmd instanceof SafeTimePropagatingCommand;

                    SafeTimePropagatingCommand safeTimePropagatingCommand = (SafeTimePropagatingCommand) cmd;

                    HybridTimestamp safeTimeForRetry = hybridClock.now();

                    // Within primary replica it's required to update safe time in order to prevent double storage updates in case of !1PC.
                    // Otherwise, it may be possible that a newer entry will be overwritten by an older one that came as part of the raft
                    // replication flow:
                    // tx1 = transactions.begin();
                    // tx1.put(k1, v1) -> primary.apply(k1,v1) + asynchronous raft replication (k1,v1)
                    // tx1.put(k1, v2) -> primary.apply(k1,v2) + asynchronous raft replication (k1,v1)
                    // (k1,v1) replication overrides newer (k1, v2). Eventually (k1,v2) replication will restore proper value.
                    // However it's possible that tx1.get(k1) will see v1 instead of v2.
                    // TODO: https://issues.apache.org/jira/browse/IGNITE-20124 Better solution requied. Given one is correct, but fragile.
                    if ((cmd instanceof UpdateCommand && !((UpdateCommand) cmd).full())
                            || (cmd instanceof UpdateAllCommand && !((UpdateAllCommand) cmd).full())) {
                        synchronized (safeTime) {
                            updateTrackerIgnoringTrackerClosedException(safeTime, safeTimeForRetry);
                        }
                    }

                    SafeTimePropagatingCommand clonedSafeTimePropagatingCommand =
                            (SafeTimePropagatingCommand) safeTimePropagatingCommand.clone();
                    clonedSafeTimePropagatingCommand.safeTimeLong(safeTimeForRetry.longValue());

                    applyCmdWithRetryOnSafeTimeReorderException(clonedSafeTimePropagatingCommand, resultFuture);
                } else {
                    resultFuture.completeExceptionally(ex);
                }
            } else {
                resultFuture.complete((T) res);
            }
        });
    }

    /**
     * Executes an Update command.
     *
     * @param tablePartId {@link TablePartitionId} object.
     * @param rowUuid Row UUID.
     * @param row Row.
     * @param lastCommitTimestamp The timestamp of the last committed entry for the row.
     * @param txId Transaction ID.
     * @param full {@code True} if this is a full transaction.
     * @param txCoordinatorId Transaction coordinator id.
     * @param catalogVersion Validated catalog version associated with given operation.
     * @return A local update ready future, possibly having a nested replication future as a result for delayed ack purpose.
     */
    private CompletableFuture<CompletableFuture<?>> applyUpdateCommand(
            TablePartitionId tablePartId,
            UUID rowUuid,
            @Nullable BinaryRow row,
            @Nullable HybridTimestamp lastCommitTimestamp,
            UUID txId,
            boolean full,
            String txCoordinatorId,
            int catalogVersion
    ) {
        synchronized (commandProcessingLinearizationMutex) {
            UpdateCommand cmd = updateCommand(
                    tablePartId,
                    rowUuid,
                    row,
                    lastCommitTimestamp,
                    txId,
                    full,
                    txCoordinatorId,
                    hybridClock.now(),
                    catalogVersion
            );

            if (!cmd.full()) {
                // TODO: https://issues.apache.org/jira/browse/IGNITE-20124 Temporary code below
                synchronized (safeTime) {
                    storageUpdateHandler.handleUpdate(
                            cmd.txId(),
                            cmd.rowUuid(),
                            cmd.tablePartitionId().asTablePartitionId(),
                            cmd.rowToUpdate(),
                            true,
                            null,
                            null,
                            null);

                    updateTrackerIgnoringTrackerClosedException(safeTime, cmd.safeTime());
                }

                CompletableFuture<UUID> fut = applyCmdWithExceptionHandling(cmd, new CompletableFuture<>())
                        .thenApply(res -> {
                            // This check guaranties the result will never be lost. Currently always null.
                            assert res == null : "Replication result is lost";

                            // Set context for delayed response.
                            return cmd.txId();
                        });

                return completedFuture(fut);
            } else {
                CompletableFuture<Object> resultFuture = new CompletableFuture<>();

                applyCmdWithExceptionHandling(cmd, resultFuture);

                return resultFuture.thenApply(res -> {
                    // This check guaranties the result will never be lost. Currently always null.
                    assert res == null : "Replication result is lost";

                    // TODO: https://issues.apache.org/jira/browse/IGNITE-20124 Temporary code below
                    // Try to avoid double write if an entry is already replicated.
                    synchronized (safeTime) {
                        if (cmd.safeTime().compareTo(safeTime.current()) > 0) {
                            storageUpdateHandler.handleUpdate(
                                    cmd.txId(),
                                    cmd.rowUuid(),
                                    cmd.tablePartitionId().asTablePartitionId(),
                                    cmd.rowToUpdate(),
                                    false,
                                    null,
                                    cmd.safeTime(),
                                    null);

                            updateTrackerIgnoringTrackerClosedException(safeTime, cmd.safeTime());
                        }
                    }

                    return null;
                });
            }
        }
    }

    /**
     * Executes an Update command.
     *
     * @param request Read write single row replica request.
     * @param rowUuid Row UUID.
     * @param row Row.
     * @param lastCommitTimestamp The timestamp of the last committed entry for the row.
     * @param txCoordinatorId Transaction coordinator id.
     * @param catalogVersion Validated catalog version associated with given operation.
     * @return A local update ready future, possibly having a nested replication future as a result for delayed ack purpose.
     */
    private CompletableFuture<CompletableFuture<?>> applyUpdateCommand(
            ReadWriteSingleRowReplicaRequest request,
            UUID rowUuid,
            @Nullable BinaryRow row,
            @Nullable HybridTimestamp lastCommitTimestamp,
            String txCoordinatorId,
            int catalogVersion
    ) {
        return applyUpdateCommand(
                request.commitPartitionId().asTablePartitionId(),
                rowUuid,
                row,
                lastCommitTimestamp,
                request.transactionId(),
                request.full(),
                txCoordinatorId,
                catalogVersion
        );
    }

    /**
     * Executes an UpdateAll command.
     *
     * @param rowsToUpdate All {@link BinaryRow}s represented as {@link TimedBinaryRowMessage}s to be updated.
     * @param commitPartitionId Partition ID that these rows belong to.
     * @param transactionId Transaction ID.
     * @param full {@code true} if this is a single-command transaction.
     * @param txCoordinatorId Transaction coordinator id.
     * @param catalogVersion Validated catalog version associated with given operation.
     * @param skipDelayedAck {@code true} to disable the delayed ack optimization.
     * @return Raft future, see {@link #applyCmdWithExceptionHandling(Command, CompletableFuture)}.
     */
    private CompletableFuture<CompletableFuture<?>> applyUpdateAllCommand(
            Map<UUID, TimedBinaryRowMessage> rowsToUpdate,
            TablePartitionIdMessage commitPartitionId,
            UUID transactionId,
            boolean full,
            String txCoordinatorId,
            int catalogVersion,
            boolean skipDelayedAck
    ) {
        synchronized (commandProcessingLinearizationMutex) {
            UpdateAllCommand cmd = updateAllCommand(
                    rowsToUpdate,
                    commitPartitionId,
                    transactionId,
                    hybridClock.now(),
                    full,
                    txCoordinatorId,
                    catalogVersion
            );

            if (!cmd.full()) {
                if (skipDelayedAck) {
                    // TODO: https://issues.apache.org/jira/browse/IGNITE-20124 Temporary code below
                    synchronized (safeTime) {
                        storageUpdateHandler.handleUpdateAll(
                                cmd.txId(),
                                cmd.rowsToUpdate(),
                                cmd.tablePartitionId().asTablePartitionId(),
                                true,
                                null,
                                null
                        );

                        updateTrackerIgnoringTrackerClosedException(safeTime, cmd.safeTime());
                    }

                    return applyCmdWithExceptionHandling(cmd, new CompletableFuture<>()).thenApply(res -> null);
                } else {
                    // TODO: https://issues.apache.org/jira/browse/IGNITE-20124 Temporary code below
                    synchronized (safeTime) {
                        storageUpdateHandler.handleUpdateAll(
                                cmd.txId(),
                                cmd.rowsToUpdate(),
                                cmd.tablePartitionId().asTablePartitionId(),
                                true,
                                null,
                                null
                        );

                        updateTrackerIgnoringTrackerClosedException(safeTime, cmd.safeTime());
                    }

                    CompletableFuture<Object> fut = applyCmdWithExceptionHandling(cmd, new CompletableFuture<>())
                            .thenApply(res -> {
                                // Currently result is always null on a successfull execution of a replication command.
                                // This check guaranties the result will never be lost.
                                assert res == null : "Replication result is lost";

                                // Set context for delayed response.
                                return cmd.txId();
                            });

                    return completedFuture(fut);
                }
            } else {
                return applyCmdWithExceptionHandling(cmd, new CompletableFuture<>())
                        .thenApply(res -> {
                            assert res == null : "Replication result is lost";

                            // TODO: https://issues.apache.org/jira/browse/IGNITE-20124 Temporary code below
                            // Try to avoid double write if an entry is already replicated.
                            synchronized (safeTime) {
                                if (cmd.safeTime().compareTo(safeTime.current()) > 0) {
                                    storageUpdateHandler.handleUpdateAll(
                                            cmd.txId(),
                                            cmd.rowsToUpdate(),
                                            cmd.tablePartitionId().asTablePartitionId(),
                                            false,
                                            null,
                                            cmd.safeTime()
                                    );

                                    updateTrackerIgnoringTrackerClosedException(safeTime, cmd.safeTime());
                                }
                            }

                            return null;
                        });
            }
        }
    }

    /**
     * Executes an UpdateAll command.
     *
     * @param request Read write multi rows replica request.
     * @param rowsToUpdate All {@link BinaryRow}s represented as {@link TimedBinaryRowMessage}s to be updated.
     * @param txCoordinatorId Transaction coordinator id.
     * @param catalogVersion Validated catalog version associated with given operation.
     * @return Raft future, see {@link #applyCmdWithExceptionHandling(Command, CompletableFuture)}.
     */
    private CompletableFuture<CompletableFuture<?>> applyUpdateAllCommand(
            ReadWriteMultiRowReplicaRequest request,
            Map<UUID, TimedBinaryRowMessage> rowsToUpdate,
            String txCoordinatorId,
            int catalogVersion
    ) {
        return applyUpdateAllCommand(
                rowsToUpdate,
                request.commitPartitionId(),
                request.transactionId(),
                request.full(),
                txCoordinatorId,
                catalogVersion,
                request.skipDelayedAck()
        );
    }

    /**
     * Processes single entry direct request for read only transaction.
     *
     * @param request Read only single entry request.
     * @param opStartTimestamp Moment when the operation processing was started in this class.
     * @return Result future.
     */
    private CompletableFuture<BinaryRow> processReadOnlyDirectSingleEntryAction(
            ReadOnlyDirectSingleRowReplicaRequest request,
            HybridTimestamp opStartTimestamp
    ) {
        BinaryTuple primaryKey = resolvePk(request.primaryKey());
        HybridTimestamp readTimestamp = opStartTimestamp;

        if (request.requestType() != RequestType.RO_GET) {
            throw new IgniteInternalException(Replicator.REPLICA_COMMON_ERR,
                    format("Unknown single request [actionType={}]", request.requestType()));
        }

        return resolveRowByPkForReadOnly(primaryKey, readTimestamp);
    }

    /**
     * Precesses single request.
     *
     * @param request Single request operation.
     * @param txCoordinatorId Transaction coordinator id.
     * @return Listener response.
     */
    private CompletableFuture<ReplicaResult> processSingleEntryAction(ReadWriteSingleRowReplicaRequest request, String txCoordinatorId) {
        UUID txId = request.transactionId();
        BinaryRow searchRow = request.binaryRow();
        TablePartitionId commitPartitionId = request.commitPartitionId().asTablePartitionId();

        assert commitPartitionId != null : "Commit partition is null [type=" + request.requestType() + ']';

        switch (request.requestType()) {
            case RW_DELETE_EXACT: {
                return resolveRowByPk(extractPk(searchRow), txId, (rowId, row, lastCommitTime) -> {
                    if (rowId == null) {
                        return completedFuture(new ReplicaResult(false, null));
                    }

                    return takeLocksForDeleteExact(searchRow, rowId, row, txId)
                            .thenCompose(validatedRowId -> {
                                if (validatedRowId == null) {
                                    return completedFuture(new ReplicaResult(false, request.full() ? null : nullCompletedFuture()));
                                }

                                return validateWriteAgainstSchemaAfterTakingLocks(request.transactionId())
                                        .thenCompose(catalogVersion -> awaitCleanup(validatedRowId, catalogVersion))
                                        .thenCompose(
                                                catalogVersion -> applyUpdateCommand(
                                                        request,
                                                        validatedRowId.uuid(),
                                                        null,
                                                        lastCommitTime,
                                                        txCoordinatorId,
                                                        catalogVersion
                                                )
                                        )
                                        .thenApply(res -> new ReplicaResult(true, res));
                            });
                });
            }
            case RW_INSERT: {
                return resolveRowByPk(extractPk(searchRow), txId, (rowId, row, lastCommitTime) -> {
                    if (rowId != null) {
                        return completedFuture(new ReplicaResult(false, null));
                    }

                    RowId rowId0 = new RowId(partId(), UUID.randomUUID());

                    return takeLocksForInsert(searchRow, rowId0, txId)
                            .thenCompose(rowIdLock -> validateWriteAgainstSchemaAfterTakingLocks(request.transactionId())
                                    .thenCompose(
                                            catalogVersion -> applyUpdateCommand(
                                                    request,
                                                    rowId0.uuid(),
                                                    searchRow,
                                                    lastCommitTime,
                                                    txCoordinatorId,
                                                    catalogVersion
                                            )
                                    )
                                    .thenApply(res -> new IgniteBiTuple<>(res, rowIdLock)))
                            .thenApply(tuple -> {
                                // Release short term locks.
                                tuple.get2().get2().forEach(lock -> lockManager.release(lock.txId(), lock.lockKey(), lock.lockMode()));

                                return new ReplicaResult(true, tuple.get1());
                            });
                });
            }
            case RW_UPSERT: {
                return resolveRowByPk(extractPk(searchRow), txId, (rowId, row, lastCommitTime) -> {
                    boolean insert = rowId == null;

                    RowId rowId0 = insert ? new RowId(partId(), UUID.randomUUID()) : rowId;

                    CompletableFuture<IgniteBiTuple<RowId, Collection<Lock>>> lockFut = insert
                            ? takeLocksForInsert(searchRow, rowId0, txId)
                            : takeLocksForUpdate(searchRow, rowId0, txId);

                    return lockFut
                            .thenCompose(rowIdLock -> validateWriteAgainstSchemaAfterTakingLocks(request.transactionId())
                                    .thenCompose(catalogVersion -> awaitCleanup(rowId, catalogVersion))
                                    .thenCompose(
                                            catalogVersion -> applyUpdateCommand(
                                                    request,
                                                    rowId0.uuid(),
                                                    searchRow,
                                                    lastCommitTime,
                                                    txCoordinatorId,
                                                    catalogVersion
                                            )
                                    )
                                    .thenApply(res -> new IgniteBiTuple<>(res, rowIdLock)))
                            .thenApply(tuple -> {
                                // Release short term locks.
                                tuple.get2().get2().forEach(lock -> lockManager.release(lock.txId(), lock.lockKey(), lock.lockMode()));

                                return new ReplicaResult(null, tuple.get1());
                            });
                });
            }
            case RW_GET_AND_UPSERT: {
                return resolveRowByPk(extractPk(searchRow), txId, (rowId, row, lastCommitTime) -> {
                    boolean insert = rowId == null;

                    RowId rowId0 = insert ? new RowId(partId(), UUID.randomUUID()) : rowId;

                    CompletableFuture<IgniteBiTuple<RowId, Collection<Lock>>> lockFut = insert
                            ? takeLocksForInsert(searchRow, rowId0, txId)
                            : takeLocksForUpdate(searchRow, rowId0, txId);

                    return lockFut
                            .thenCompose(rowIdLock -> validateWriteAgainstSchemaAfterTakingLocks(request.transactionId())
                                    .thenCompose(catalogVersion -> awaitCleanup(rowId, catalogVersion))
                                    .thenCompose(
                                            catalogVersion -> applyUpdateCommand(
                                                    request,
                                                    rowId0.uuid(),
                                                    searchRow,
                                                    lastCommitTime,
                                                    txCoordinatorId,
                                                    catalogVersion
                                            )
                                    )
                                    .thenApply(res -> new IgniteBiTuple<>(res, rowIdLock)))
                            .thenApply(tuple -> {
                                // Release short term locks.
                                tuple.get2().get2().forEach(lock -> lockManager.release(lock.txId(), lock.lockKey(), lock.lockMode()));

                                return new ReplicaResult(row, tuple.get1());
                            });
                });
            }
            case RW_GET_AND_REPLACE: {
                return resolveRowByPk(extractPk(searchRow), txId, (rowId, row, lastCommitTime) -> {
                    if (rowId == null) {
                        return completedFuture(new ReplicaResult(null, null));
                    }

                    return takeLocksForUpdate(searchRow, rowId, txId)
                            .thenCompose(rowIdLock -> validateWriteAgainstSchemaAfterTakingLocks(request.transactionId())
                                    .thenCompose(catalogVersion -> awaitCleanup(rowId, catalogVersion))
                                    .thenCompose(
                                            catalogVersion -> applyUpdateCommand(
                                                    request,
                                                    rowId.uuid(),
                                                    searchRow,
                                                    lastCommitTime,
                                                    txCoordinatorId,
                                                    catalogVersion
                                            )
                                    )
                                    .thenApply(res -> new IgniteBiTuple<>(res, rowIdLock)))
                            .thenApply(tuple -> {
                                // Release short term locks.
                                tuple.get2().get2().forEach(lock -> lockManager.release(lock.txId(), lock.lockKey(), lock.lockMode()));

                                return new ReplicaResult(row, tuple.get1());
                            });
                });
            }
            case RW_REPLACE_IF_EXIST: {
                return resolveRowByPk(extractPk(searchRow), txId, (rowId, row, lastCommitTime) -> {
                    if (rowId == null) {
                        return completedFuture(new ReplicaResult(false, null));
                    }

                    return takeLocksForUpdate(searchRow, rowId, txId)
                            .thenCompose(rowIdLock -> validateWriteAgainstSchemaAfterTakingLocks(request.transactionId())
                                    .thenCompose(catalogVersion -> awaitCleanup(rowId, catalogVersion))
                                    .thenCompose(
                                            catalogVersion -> applyUpdateCommand(
                                                    request,
                                                    rowId.uuid(),
                                                    searchRow,
                                                    lastCommitTime,
                                                    txCoordinatorId,
                                                    catalogVersion
                                            )
                                    )
                                    .thenApply(res -> new IgniteBiTuple<>(res, rowIdLock)))
                            .thenApply(tuple -> {
                                // Release short term locks.
                                tuple.get2().get2().forEach(lock -> lockManager.release(lock.txId(), lock.lockKey(), lock.lockMode()));

                                return new ReplicaResult(true, tuple.get1());
                            });
                });
            }
            default: {
                throw new IgniteInternalException(Replicator.REPLICA_COMMON_ERR,
                        format("Unknown single request [actionType={}]", request.requestType()));
            }
        }
    }

    /**
     * Precesses single request.
     *
     * @param request Single request operation.
     * @param txCoordinatorId Transaction coordinator id.
     * @return Listener response.
     */
    private CompletableFuture<ReplicaResult> processSingleEntryAction(ReadWriteSingleRowPkReplicaRequest request, String txCoordinatorId) {
        UUID txId = request.transactionId();
        BinaryTuple primaryKey = resolvePk(request.primaryKey());
        TablePartitionId commitPartitionId = request.commitPartitionId().asTablePartitionId();

        assert commitPartitionId != null || request.requestType() == RequestType.RW_GET :
                "Commit partition is null [type=" + request.requestType() + ']';

        switch (request.requestType()) {
            case RW_GET: {
                return resolveRowByPk(primaryKey, txId, (rowId, row, lastCommitTime) -> {
                    if (rowId == null) {
                        return nullCompletedFuture();
                    }

                    return takeLocksForGet(rowId, txId)
                            .thenCompose(ignored -> validateRwReadAgainstSchemaAfterTakingLocks(txId))
                            .thenApply(ignored -> new ReplicaResult(row, null));
                });
            }
            case RW_DELETE: {
                return resolveRowByPk(primaryKey, txId, (rowId, row, lastCommitTime) -> {
                    if (rowId == null) {
                        return completedFuture(new ReplicaResult(false, null));
                    }

                    return takeLocksForDelete(row, rowId, txId)
                            .thenCompose(rowLock -> validateWriteAgainstSchemaAfterTakingLocks(request.transactionId()))
                            .thenCompose(catalogVersion -> awaitCleanup(rowId, catalogVersion))
                            .thenCompose(
                                    catalogVersion -> applyUpdateCommand(
                                            request.commitPartitionId().asTablePartitionId(),
                                            rowId.uuid(),
                                            null,
                                            lastCommitTime,
                                            request.transactionId(),
                                            request.full(),
                                            txCoordinatorId,
                                            catalogVersion
                                    )
                            )
                            .thenApply(res -> new ReplicaResult(true, res));
                });
            }
            case RW_GET_AND_DELETE: {
                return resolveRowByPk(primaryKey, txId, (rowId, row, lastCommitTime) -> {
                    if (rowId == null) {
                        return nullCompletedFuture();
                    }

                    return takeLocksForDelete(row, rowId, txId)
                            .thenCompose(ignored -> validateWriteAgainstSchemaAfterTakingLocks(request.transactionId()))
                            .thenCompose(catalogVersion -> awaitCleanup(rowId, catalogVersion))
                            .thenCompose(
                                    catalogVersion -> applyUpdateCommand(
                                            request.commitPartitionId().asTablePartitionId(),
                                            rowId.uuid(),
                                            null,
                                            lastCommitTime,
                                            request.transactionId(),
                                            request.full(),
                                            txCoordinatorId,
                                            catalogVersion
                                    )
                            )
                            .thenApply(res -> new ReplicaResult(row, res));
                });
            }
            default: {
                throw new IgniteInternalException(Replicator.REPLICA_COMMON_ERR,
                        format("Unknown single request [actionType={}]", request.requestType()));
            }
        }
    }

    /**
     *  Wait for the async cleanup of the provided row to finish.
     *
     * @param rowId Row Ids of existing row that the transaction affects.
     * @param result The value that the returned future will wrap.
     *
     * @param <T> Type of the {@code result}.
     */
    private <T> CompletableFuture<T> awaitCleanup(@Nullable RowId rowId, T result) {
        return (rowId == null ? nullCompletedFuture() : rowCleanupMap.getOrDefault(rowId, nullCompletedFuture()))
                .thenApply(ignored -> result);
    }

    /**
     * Wait for the async cleanup of the provided rows to finish.
     *
     * @param rowIds Row Ids of existing rows that the transaction affects.
     * @param result The value that the returned future will wrap.
     *
     * @param <T> Type of the {@code result}.
     */
    private <T> CompletableFuture<T> awaitCleanup(Collection<RowId> rowIds, T result) {
        if (rowCleanupMap.isEmpty()) {
            return completedFuture(result);
        }

        List<CompletableFuture<?>> list = new ArrayList<>(rowIds.size());

        for (RowId rowId : rowIds) {
            CompletableFuture<?> completableFuture = rowCleanupMap.get(rowId);
            if (completableFuture != null) {
                list.add(completableFuture);
            }
        }
        if (list.isEmpty()) {
            return completedFuture(result);
        }

        return allOf(list.toArray(new CompletableFuture[0]))
                .thenApply(unused -> result);
    }

    /**
     * Extracts a binary tuple corresponding to a part of the row comprised of PK columns.
     *
     * <p>This method must ONLY be invoked when we're sure that a schema version equal to {@code row.schemaVersion()}
     * is already available on this node (see {@link SchemaSyncService}).
     *
     * @param row Row for which to do extraction.
     */
    private BinaryTuple extractPk(BinaryRow row) {
        return pkIndexStorage.get().indexRowResolver().extractColumns(row);
    }

    private BinaryTuple resolvePk(ByteBuffer bytes) {
        return pkIndexStorage.get().resolve(bytes);
    }

    private List<BinaryTuple> resolvePks(List<ByteBuffer> bytesList) {
        var pks = new ArrayList<BinaryTuple>(bytesList.size());

        for (ByteBuffer bytes : bytesList) {
            pks.add(resolvePk(bytes));
        }

        return pks;
    }

    private Cursor<RowId> getFromPkIndex(BinaryTuple key) {
        return pkIndexStorage.get().storage().get(key);
    }

    /**
     * Takes all required locks on a key, before upserting.
     *
     * @param txId Transaction id.
     * @return Future completes with tuple {@link RowId} and collection of {@link Lock}.
     */
    private CompletableFuture<IgniteBiTuple<RowId, Collection<Lock>>> takeLocksForUpdate(BinaryRow binaryRow, RowId rowId, UUID txId) {
        return lockManager.acquire(txId, new LockKey(tableId()), LockMode.IX)
                .thenCompose(ignored -> lockManager.acquire(txId, new LockKey(tableId(), rowId), LockMode.X))
                .thenCompose(ignored -> takePutLockOnIndexes(binaryRow, rowId, txId))
                .thenApply(shortTermLocks -> new IgniteBiTuple<>(rowId, shortTermLocks));
    }

    /**
     * Takes all required locks on a key, before inserting the value.
     *
     * @param binaryRow Table row.
     * @param txId Transaction id.
     * @return Future completes with tuple {@link RowId} and collection of {@link Lock}.
     */
    private CompletableFuture<IgniteBiTuple<RowId, Collection<Lock>>> takeLocksForInsert(BinaryRow binaryRow, RowId rowId, UUID txId) {
        return lockManager.acquire(txId, new LockKey(tableId()), LockMode.IX) // IX lock on table
                .thenCompose(ignored -> takePutLockOnIndexes(binaryRow, rowId, txId))
                .thenApply(shortTermLocks -> new IgniteBiTuple<>(rowId, shortTermLocks));
    }

    private CompletableFuture<Collection<Lock>> takePutLockOnIndexes(BinaryRow binaryRow, RowId rowId, UUID txId) {
        Collection<IndexLocker> indexes = indexesLockers.get().values();

        if (nullOrEmpty(indexes)) {
            return emptyCollectionCompletedFuture();
        }

        CompletableFuture<Lock>[] locks = new CompletableFuture[indexes.size()];
        int idx = 0;

        for (IndexLocker locker : indexes) {
            locks[idx++] = locker.locksForInsert(txId, binaryRow, rowId);
        }

        return allOf(locks).thenApply(unused -> {
            var shortTermLocks = new ArrayList<Lock>();

            for (CompletableFuture<Lock> lockFut : locks) {
                Lock shortTermLock = lockFut.join();

                if (shortTermLock != null) {
                    shortTermLocks.add(shortTermLock);
                }
            }

            return shortTermLocks;
        });
    }

    private CompletableFuture<?> takeRemoveLockOnIndexes(BinaryRow binaryRow, RowId rowId, UUID txId) {
        Collection<IndexLocker> indexes = indexesLockers.get().values();

        if (nullOrEmpty(indexes)) {
            return nullCompletedFuture();
        }

        CompletableFuture<?>[] locks = new CompletableFuture[indexes.size()];
        int idx = 0;

        for (IndexLocker locker : indexes) {
            locks[idx++] = locker.locksForRemove(txId, binaryRow, rowId);
        }

        return allOf(locks);
    }

    /**
     * Takes all required locks on a key, before deleting the value.
     *
     * @param txId Transaction id.
     * @return Future completes with {@link RowId} or {@code null} if there is no value for remove.
     */
    private CompletableFuture<RowId> takeLocksForDeleteExact(BinaryRow expectedRow, RowId rowId, BinaryRow actualRow, UUID txId) {
        return lockManager.acquire(txId, new LockKey(tableId()), LockMode.IX) // IX lock on table
                .thenCompose(ignored -> lockManager.acquire(txId, new LockKey(tableId(), rowId), LockMode.S)) // S lock on RowId
                .thenCompose(ignored -> {
                    if (equalValues(actualRow, expectedRow)) {
                        return lockManager.acquire(txId, new LockKey(tableId(), rowId), LockMode.X) // X lock on RowId
                                .thenCompose(ignored0 -> takeRemoveLockOnIndexes(actualRow, rowId, txId))
                                .thenApply(exclusiveRowLock -> rowId);
                    }

                    return nullCompletedFuture();
                });
    }

    /**
     * Takes all required locks on a key, before deleting the value.
     *
     * @param txId Transaction id.
     * @return Future completes with {@link RowId} or {@code null} if there is no value for the key.
     */
    private CompletableFuture<RowId> takeLocksForDelete(BinaryRow binaryRow, RowId rowId, UUID txId) {
        return lockManager.acquire(txId, new LockKey(tableId()), LockMode.IX) // IX lock on table
                .thenCompose(ignored -> lockManager.acquire(txId, new LockKey(tableId(), rowId), LockMode.X)) // X lock on RowId
                .thenCompose(ignored -> takeRemoveLockOnIndexes(binaryRow, rowId, txId))
                .thenApply(ignored -> rowId);
    }

    /**
     * Takes all required locks on a key, before getting the value.
     *
     * @param txId Transaction id.
     * @return Future completes with {@link RowId} or {@code null} if there is no value for the key.
     */
    private CompletableFuture<RowId> takeLocksForGet(RowId rowId, UUID txId) {
        return lockManager.acquire(txId, new LockKey(tableId()), LockMode.IS) // IS lock on table
                .thenCompose(tblLock -> lockManager.acquire(txId, new LockKey(tableId(), rowId), LockMode.S)) // S lock on RowId
                .thenApply(ignored -> rowId);
    }

    /**
     * Precesses two actions.
     *
     * @param request Two actions operation request.
     * @param txCoordinatorId Transaction coordinator id.
     * @return Listener response.
     */
    private CompletableFuture<ReplicaResult> processTwoEntriesAction(
            ReadWriteSwapRowReplicaRequest request,
            String txCoordinatorId
    ) {
        BinaryRow newRow = request.newBinaryRow();
        BinaryRow expectedRow = request.oldBinaryRow();
        TablePartitionIdMessage commitPartitionId = request.commitPartitionId();

        assert commitPartitionId != null : "Commit partition is null [type=" + request.requestType() + ']';

        UUID txId = request.transactionId();

        if (request.requestType() == RequestType.RW_REPLACE) {
            return resolveRowByPk(extractPk(newRow), txId, (rowId, row, lastCommitTime) -> {
                if (rowId == null) {
                    return completedFuture(new ReplicaResult(false, null));
                }

                return takeLocksForReplace(expectedRow, row, newRow, rowId, txId)
                        .thenCompose(rowIdLock -> {
                            if (rowIdLock == null) {
                                return completedFuture(new ReplicaResult(false, null));
                            }

                            return validateWriteAgainstSchemaAfterTakingLocks(txId)
                                    .thenCompose(catalogVersion -> awaitCleanup(rowIdLock.get1(), catalogVersion))
                                    .thenCompose(
                                            catalogVersion -> applyUpdateCommand(
                                                    commitPartitionId.asTablePartitionId(),
                                                    rowIdLock.get1().uuid(),
                                                    newRow,
                                                    lastCommitTime,
                                                    txId,
                                                    request.full(),
                                                    txCoordinatorId,
                                                    catalogVersion
                                            )
                                    )
                                    .thenApply(res -> new IgniteBiTuple<>(res, rowIdLock))
                                    .thenApply(tuple -> {
                                        // Release short term locks.
                                        tuple.get2().get2()
                                                .forEach(lock -> lockManager.release(lock.txId(), lock.lockKey(), lock.lockMode()));

                                        return new ReplicaResult(true, tuple.get1());
                                    });
                        });
            });
        }

        throw new IgniteInternalException(Replicator.REPLICA_COMMON_ERR,
                format("Unknown two actions operation [actionType={}]", request.requestType()));
    }

    /**
     * Takes all required locks on a key, before updating the value.
     *
     * @param txId Transaction id.
     * @return Future completes with tuple {@link RowId} and collection of {@link Lock} or {@code null} if there is no suitable row.
     */
    private CompletableFuture<IgniteBiTuple<RowId, Collection<Lock>>> takeLocksForReplace(BinaryRow expectedRow, @Nullable BinaryRow oldRow,
            BinaryRow newRow, RowId rowId, UUID txId) {
        return lockManager.acquire(txId, new LockKey(tableId()), LockMode.IX)
                .thenCompose(ignored -> lockManager.acquire(txId, new LockKey(tableId(), rowId), LockMode.S))
                .thenCompose(ignored -> {
                    if (oldRow != null && equalValues(oldRow, expectedRow)) {
                        return lockManager.acquire(txId, new LockKey(tableId(), rowId), LockMode.X) // X lock on RowId
                                .thenCompose(ignored1 -> takePutLockOnIndexes(newRow, rowId, txId))
                                .thenApply(shortTermLocks -> new IgniteBiTuple<>(rowId, shortTermLocks));
                    }

                    return nullCompletedFuture();
                });
    }

    /**
     * Ensure that the primary replica was not changed.
     *
     * @param request Replica request.
     * @return Future. The result is not {@code null} only for {@link ReadOnlyReplicaRequest}. If {@code true}, then replica is primary.
     */
    private CompletableFuture<Boolean> ensureReplicaIsPrimary(ReplicaRequest request) {
        HybridTimestamp now = hybridClock.now();

        if (request instanceof PrimaryReplicaRequest) {
            Long enlistmentConsistencyToken = ((PrimaryReplicaRequest) request).enlistmentConsistencyToken();

            return placementDriver.getPrimaryReplica(replicationGroupId, now)
                    .thenCompose(primaryReplicaMeta -> {
                        if (primaryReplicaMeta == null) {
                            return failedFuture(new PrimaryReplicaMissException(
                                    localNode.name(),
                                    null,
                                    enlistmentConsistencyToken,
                                    null,
                                    null
                            ));
                        }

                        long currentEnlistmentConsistencyToken = primaryReplicaMeta.getStartTime().longValue();

                        // TODO: https://issues.apache.org/jira/browse/IGNITE-20377
                        if (enlistmentConsistencyToken != currentEnlistmentConsistencyToken
                                || primaryReplicaMeta.getExpirationTime().before(now)) {
                            return failedFuture(new PrimaryReplicaMissException(
                                    localNode.name(),
                                    primaryReplicaMeta.getLeaseholder(),
                                    enlistmentConsistencyToken,
                                    currentEnlistmentConsistencyToken,
                                    null
                            ));
                        }

                        return nullCompletedFuture();
                    });
        } else if (request instanceof ReadOnlyReplicaRequest || request instanceof ReplicaSafeTimeSyncRequest) {
            return placementDriver.getPrimaryReplica(replicationGroupId, now)
                    .thenApply(primaryReplica -> (primaryReplica != null && isLocalPeer(primaryReplica.getLeaseholder())));
        } else {
            return nullCompletedFuture();
        }
    }

    /**
     * Resolves read result to the corresponding binary row. Following rules are used for read result resolution:
     * <ol>
     *     <li>If timestamp is null (RW request), assert that retrieved tx id matches proposed one or that retrieved tx id is null
     *     and return binary row. Currently it's only possible to retrieve write intents if they belong to the same transaction,
     *     locks prevent reading write intents created by others.</li>
     *     <li>If timestamp is not null (RO request), perform write intent resolution if given readResult is a write intent itself
     *     or return binary row otherwise.</li>
     * </ol>
     *
     * @param readResult Read result to resolve.
     * @param txId Nullable transaction id, should be provided if resolution is performed within the context of RW transaction.
     * @param timestamp Timestamp is used in RO transaction only.
     * @param lastCommitted Action to get the latest committed row.
     * @return Future to resolved binary row.
     */
    private CompletableFuture<@Nullable TimedBinaryRow> resolveReadResult(
            ReadResult readResult,
            @Nullable UUID txId,
            @Nullable HybridTimestamp timestamp,
            Supplier<@Nullable TimedBinaryRow> lastCommitted
    ) {
        if (readResult == null) {
            return nullCompletedFuture();
        } else if (!readResult.isWriteIntent()) {
            return completedFuture(new TimedBinaryRow(readResult.binaryRow(), readResult.commitTimestamp()));
        } else {
            // RW write intent resolution.
            if (timestamp == null) {
                UUID retrievedResultTxId = readResult.transactionId();

                if (txId.equals(retrievedResultTxId)) {
                    // Same transaction - return the retrieved value. It may be either a writeIntent or a regular value.
                    return completedFuture(new TimedBinaryRow(readResult.binaryRow()));
                }
            }

            return resolveWriteIntentAsync(readResult, timestamp, lastCommitted);
        }
    }

    /**
     * Resolves a read result to the matched row. If the result does not match any row, the method returns a future to {@code null}.
     *
     * @param readResult Read result.
     * @param timestamp Timestamp.
     * @param lastCommitted Action to get a last committed row.
     * @return Result future.
     */
    private CompletableFuture<@Nullable TimedBinaryRow> resolveWriteIntentAsync(
            ReadResult readResult,
            @Nullable HybridTimestamp timestamp,
            Supplier<@Nullable TimedBinaryRow> lastCommitted
    ) {
        return inBusyLockAsync(busyLock, () ->
                resolveWriteIntentReadability(readResult, timestamp)
                        .thenApply(writeIntentReadable ->
                                inBusyLock(busyLock, () -> {
                                            if (writeIntentReadable) {
                                                // Even though this readResult is still a write intent entry in the storage
                                                // (therefore it contains txId), we already know it relates to a committed transaction
                                                // and will be cleaned up by an asynchronous task
                                                // started in scheduleTransactionRowAsyncCleanup().
                                                // So it's safe to assume that that this is the latest committed entry.
                                                HybridTimestamp commitTimestamp =
                                                        txManager.stateMeta(readResult.transactionId()).commitTimestamp();

                                                return new TimedBinaryRow(readResult.binaryRow(), commitTimestamp);
                                            }

                                            return lastCommitted.get();
                                        }
                                )
                        )
        );
    }

    /**
     * Schedules an async cleanup action for the given write intent.
     *
     * @param txId Transaction id.
     * @param rowId Id of a row that we want to clean up.
     * @param meta Resolved transaction state.
     */
    private void scheduleTransactionRowAsyncCleanup(UUID txId, RowId rowId, TransactionMeta meta) {
        TxState txState = meta.txState();

        assert isFinalState(txState) : "Unexpected state [txId=" + txId + ", txState=" + txState + ']';

        HybridTimestamp commitTimestamp = meta.commitTimestamp();

        // Add the resolved row to the set of write intents the transaction created.
        // If the volatile state was lost on restart, we'll have a single item in that set,
        // otherwise the set already contains this value.
        storageUpdateHandler.handleWriteIntentRead(txId, rowId);

        // If the volatile state was lost and we no longer know which rows were affected by this transaction,
        // it is possible that two concurrent RO transactions start resolving write intents for different rows
        // but created by the same transaction.

        // Both normal cleanup and single row cleanup are using txsPendingRowIds map to store write intents.
        // So we don't need a separate method to handle single row case.
        CompletableFuture<?> future = rowCleanupMap.computeIfAbsent(rowId, k -> {
            // The cleanup for this row has already been triggered. For example, we are resolving a write intent for an RW transaction
            // and a concurrent RO transaction resolves the same row, hence computeIfAbsent.
            return txManager.executeCleanupAsync(() ->
                    inBusyLock(busyLock, () -> storageUpdateHandler.switchWriteIntents(txId, txState == COMMITTED, commitTimestamp))
            ).whenComplete((unused, e) -> {
                if (e != null) {
                    LOG.warn("Failed to complete transaction cleanup command [txId=" + txId + ']', e);
                }
            });
        });

        future.handle((v, e) -> rowCleanupMap.remove(rowId, future));
    }

    /**
     * Check whether we can read from the provided write intent.
     *
     * @param writeIntent Write intent to resolve.
     * @param timestamp Timestamp.
     * @return The future completes with {@code true} when the transaction is committed and commit time <= read time, {@code false}
     *         otherwise (whe the transaction is either in progress, or aborted, or committed and commit time > read time).
     */
    private CompletableFuture<Boolean> resolveWriteIntentReadability(ReadResult writeIntent, @Nullable HybridTimestamp timestamp) {
        UUID txId = writeIntent.transactionId();

        return transactionStateResolver.resolveTxState(
                        txId,
                        new TablePartitionId(writeIntent.commitTableId(), writeIntent.commitPartitionId()),
                        timestamp)
                .thenApply(transactionMeta -> {
                    if (isFinalState(transactionMeta.txState())) {
                        scheduleTransactionRowAsyncCleanup(txId, writeIntent.rowId(), transactionMeta);
                    }

                    return canReadFromWriteIntent(txId, transactionMeta, timestamp);
                });
    }

    /**
     * Check whether we can read write intents created by this transaction.
     *
     * @param txId Transaction id.
     * @param txMeta Transaction meta info.
     * @param timestamp Read timestamp.
     * @return {@code true} if we can read from entries created in this transaction (when the transaction was committed and commit time <=
     *         read time).
     */
    private static Boolean canReadFromWriteIntent(UUID txId, TransactionMeta txMeta, @Nullable HybridTimestamp timestamp) {
        assert isFinalState(txMeta.txState()) || txMeta.txState() == PENDING
                : format("Unexpected state defined by write intent resolution [txId={}, txMeta={}].", txId, txMeta);

        if (txMeta.txState() == COMMITTED) {
            boolean readLatest = timestamp == null;

            return readLatest || txMeta.commitTimestamp().compareTo(timestamp) <= 0;
        } else {
            // Either ABORTED or PENDING.
            return false;
        }
    }

    /**
     * Takes current timestamp and makes schema related validations at this timestamp.
     *
     * @param txId Transaction ID.
     * @return Future that will complete when validation completes.
     */
    private CompletableFuture<Void> validateRwReadAgainstSchemaAfterTakingLocks(UUID txId) {
        HybridTimestamp operationTimestamp = hybridClock.now();

        return schemaSyncService.waitForMetadataCompleteness(operationTimestamp)
                .thenRun(() -> failIfSchemaChangedSinceTxStart(txId, operationTimestamp));
    }

    /**
     * Takes current timestamp and makes schema related validations at this timestamp.
     *
     * @param txId Transaction ID.
     * @return Future that will complete with catalog version associated with given operation though the operation timestamp.
     */
    private CompletableFuture<Integer> validateWriteAgainstSchemaAfterTakingLocks(UUID txId) {
        HybridTimestamp operationTimestamp = hybridClock.now();

        return reliableCatalogVersionFor(operationTimestamp)
                .thenApply(catalogVersion -> {
                    failIfSchemaChangedSinceTxStart(txId, operationTimestamp);

                    return catalogVersion;
                });
    }

    private static UpdateCommand updateCommand(
            TablePartitionId tablePartId,
            UUID rowUuid,
            @Nullable BinaryRow row,
            @Nullable HybridTimestamp lastCommitTimestamp,
            UUID txId,
            boolean full,
            String txCoordinatorId,
            HybridTimestamp safeTimeTimestamp,
            int catalogVersion
    ) {
        UpdateCommandBuilder bldr = MSG_FACTORY.updateCommand()
                .tablePartitionId(tablePartitionId(tablePartId))
                .rowUuid(rowUuid)
                .txId(txId)
                .full(full)
                .safeTimeLong(safeTimeTimestamp.longValue())
                .txCoordinatorId(txCoordinatorId)
                .requiredCatalogVersion(catalogVersion);

        if (lastCommitTimestamp != null || row != null) {
            TimedBinaryRowMessageBuilder rowMsgBldr = MSG_FACTORY.timedBinaryRowMessage();

            if (lastCommitTimestamp != null) {
                rowMsgBldr.timestamp(lastCommitTimestamp.longValue());
            }

            if (row != null) {
                rowMsgBldr.binaryRowMessage(binaryRowMessage(row));
            }

            bldr.messageRowToUpdate(rowMsgBldr.build());
        }

        return bldr.build();
    }

    private static BinaryRowMessage binaryRowMessage(BinaryRow row) {
        return MSG_FACTORY.binaryRowMessage()
                .binaryTuple(row.tupleSlice())
                .schemaVersion(row.schemaVersion())
                .build();
    }

    private static UpdateAllCommand updateAllCommand(
            Map<UUID, TimedBinaryRowMessage> rowsToUpdate,
            TablePartitionIdMessage commitPartitionId,
            UUID transactionId,
            HybridTimestamp safeTimeTimestamp,
            boolean full,
            String txCoordinatorId,
            int catalogVersion
    ) {
        return MSG_FACTORY.updateAllCommand()
                .tablePartitionId(commitPartitionId)
                .messageRowsToUpdate(rowsToUpdate)
                .txId(transactionId)
                .safeTimeLong(safeTimeTimestamp.longValue())
                .full(full)
                .txCoordinatorId(txCoordinatorId)
                .requiredCatalogVersion(catalogVersion)
                .build();
    }

    private void failIfSchemaChangedSinceTxStart(UUID txId, HybridTimestamp operationTimestamp) {
        schemaCompatValidator.failIfSchemaChangedAfterTxStart(txId, operationTimestamp, tableId());
    }

    private CompletableFuture<Integer> reliableCatalogVersionFor(HybridTimestamp ts) {
        return schemaSyncService.waitForMetadataCompleteness(ts)
                .thenApply(unused -> catalogService.activeCatalogVersion(ts.longValue()));
    }

    /**
     * Method to convert from {@link TablePartitionId} object to command-based {@link TablePartitionIdMessage} object.
     *
     * @param tablePartId {@link TablePartitionId} object to convert to {@link TablePartitionIdMessage}.
     * @return {@link TablePartitionIdMessage} object converted from argument.
     */
    public static TablePartitionIdMessage tablePartitionId(TablePartitionId tablePartId) {
        return MSG_FACTORY.tablePartitionIdMessage()
                .tableId(tablePartId.tableId())
                .partitionId(tablePartId.partitionId())
                .build();
    }

    /**
     * Class that stores a list of futures for operations that has happened in a specific transaction. Also, the class has a property
     * {@code state} that represents a transaction state.
     */
    private static class TxCleanupReadyFutureList {
        /**
         * Operation type is mapped operation futures.
         */
        final Map<RequestType, List<CompletableFuture<?>>> futures = new EnumMap<>(RequestType.class);
    }

    @Override
    public void onShutdown() {
        if (!stopGuard.compareAndSet(false, true)) {
            return;
        }

        busyLock.block();
    }

    private int partId() {
        return replicationGroupId.partitionId();
    }

    private int tableId() {
        return replicationGroupId.tableId();
    }

    private boolean isLocalPeer(String nodeName) {
        return localNode.name().equals(nodeName);
    }

    /**
     * Marks the transaction as finished in local tx state map.
     *
     * @param txId Transaction id.
     * @param txState Transaction state, must be either {@link TxState#COMMITTED} or {@link TxState#ABORTED}.
     * @param commitTimestamp Commit timestamp.
     */
    private void markFinished(UUID txId, TxState txState, @Nullable HybridTimestamp commitTimestamp) {
        assert isFinalState(txState) : "Unexpected state [txId=" + txId + ", txState=" + txState + ']';

        txManager.updateTxMeta(txId, old -> old == null
                ? null
                : new TxStateMeta(
                        txState,
                        old.txCoordinatorId(),
                        old.commitPartitionId(),
                        txState == COMMITTED ? commitTimestamp : null
                ));
    }

    // TODO: https://issues.apache.org/jira/browse/IGNITE-20124 Temporary code below
    private static <T extends Comparable<T>> void updateTrackerIgnoringTrackerClosedException(
            PendingComparableValuesTracker<T, Void> tracker,
            T newValue
    ) {
        try {
            tracker.update(newValue, null);
        } catch (TrackerClosedException ignored) {
            // No-op.
        }
    }

    private static BuildIndexCommand toBuildIndexCommand(BuildIndexReplicaRequest request) {
        return MSG_FACTORY.buildIndexCommand()
                .indexId(request.indexId())
                .rowIds(request.rowIds())
                .finish(request.finish())
                .build();
    }

    private static class FuturesCleanupResult {
        private final boolean hadReadFutures;
        private final boolean hadUpdateFutures;

        public FuturesCleanupResult(boolean hadReadFutures, boolean hadUpdateFutures) {
            this.hadReadFutures = hadReadFutures;
            this.hadUpdateFutures = hadUpdateFutures;
        }

        public boolean hadReadFutures() {
            return hadReadFutures;
        }

        public boolean hadUpdateFutures() {
            return hadUpdateFutures;
        }
    }
}
