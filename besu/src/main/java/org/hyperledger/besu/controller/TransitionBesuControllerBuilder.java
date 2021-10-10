/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.controller;

import org.hyperledger.besu.config.GenesisConfigFile;
import org.hyperledger.besu.consensus.merge.PostMergeContext;
import org.hyperledger.besu.consensus.merge.TransitionContext;
import org.hyperledger.besu.consensus.merge.TransitionProtocolSchedule;
import org.hyperledger.besu.consensus.merge.blockcreation.TransitionCoordinator;
import org.hyperledger.besu.consensus.qbft.pki.PkiBlockCreationConfiguration;
import org.hyperledger.besu.crypto.NodeKey;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.ConsensusContext;
import org.hyperledger.besu.ethereum.GasLimitCalculator;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.blockcreation.MiningCoordinator;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.MiningParameters;
import org.hyperledger.besu.ethereum.core.PrivacyParameters;
import org.hyperledger.besu.ethereum.eth.EthProtocolConfiguration;
import org.hyperledger.besu.ethereum.eth.manager.EthProtocolManager;
import org.hyperledger.besu.ethereum.eth.sync.SynchronizerConfiguration;
import org.hyperledger.besu.ethereum.eth.sync.state.SyncState;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPool;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolConfiguration;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.storage.StorageProvider;
import org.hyperledger.besu.ethereum.worldstate.DataStorageConfiguration;
import org.hyperledger.besu.ethereum.worldstate.PrunerConfiguration;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.metrics.ObservableMetricsSystem;
import org.hyperledger.besu.plugin.services.permissioning.NodeMessagePermissioningProvider;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public class TransitionBesuControllerBuilder extends BesuControllerBuilder {
  private final BesuControllerBuilder preMergeBesuControllerBuilder;
  private final MergeBesuControllerBuilder mergeBesuControllerBuilder;

  public TransitionBesuControllerBuilder(
      final BesuControllerBuilder preMergeBesuControllerBuilder,
      final MergeBesuControllerBuilder mergeBesuControllerBuilder) {
    this.preMergeBesuControllerBuilder = preMergeBesuControllerBuilder;
    this.mergeBesuControllerBuilder = mergeBesuControllerBuilder;
  }

  @Override
  protected void prepForBuild() {
    preMergeBesuControllerBuilder.prepForBuild();
    mergeBesuControllerBuilder.prepForBuild();
  }

  @Override
  protected MiningCoordinator createMiningCoordinator(
      final ProtocolSchedule protocolSchedule,
      final ProtocolContext protocolContext,
      final TransactionPool transactionPool,
      final MiningParameters miningParameters,
      final SyncState syncState,
      final EthProtocolManager ethProtocolManager) {

    // cast to transition schedule for explicit access to pre and post objects:
    final TransitionProtocolSchedule tps = (TransitionProtocolSchedule) protocolSchedule;

    final TransitionCoordinator composedCoordinator =
        new TransitionCoordinator(
            preMergeBesuControllerBuilder.createMiningCoordinator(
                tps.getPreMergeSchedule(),
                protocolContext,
                transactionPool,
                new MiningParameters.Builder(miningParameters).enabled(false).build(),
                syncState,
                ethProtocolManager),
            mergeBesuControllerBuilder.createMiningCoordinator(
                tps.getPostMergeSchedule(),
                protocolContext,
                transactionPool,
                miningParameters,
                syncState,
                ethProtocolManager));
    initTransitionWatcher(protocolContext, composedCoordinator);
    return composedCoordinator;
  }

  @Override
  protected ProtocolSchedule createProtocolSchedule() {
    return new TransitionProtocolSchedule(
        preMergeBesuControllerBuilder.createProtocolSchedule(),
        mergeBesuControllerBuilder.createProtocolSchedule());
  }

  @Override
  protected ConsensusContext createConsensusContext(
      final Blockchain blockchain,
      final WorldStateArchive worldStateArchive,
      final ProtocolSchedule protocolSchedule) {
    return new TransitionContext(
        preMergeBesuControllerBuilder.createConsensusContext(
            blockchain, worldStateArchive, protocolSchedule),
        mergeBesuControllerBuilder.createConsensusContext(
            blockchain, worldStateArchive, protocolSchedule));
  }

  @Override
  protected PluginServiceFactory createAdditionalPluginServices(
      final Blockchain blockchain, final ProtocolContext protocolContext) {
    return new NoopPluginServiceFactory();
  }

  private void initTransitionWatcher(
      final ProtocolContext protocolContext, final TransitionCoordinator composedCoordinator) {

    PostMergeContext postMergeContext = protocolContext.getConsensusContext(PostMergeContext.class);
    postMergeContext.observeNewIsPostMergeState(
        newIsPostMergeState -> {
          if (newIsPostMergeState) {
            // if we transitioned to post-merge, stop mining
            composedCoordinator.getPreMergeObject().disable();
            composedCoordinator.getPreMergeObject().stop();
          } else {
            // if we transitioned to pre-merge, start mining
            composedCoordinator.getPreMergeObject().enable();
            composedCoordinator.getPreMergeObject().start();
          }
        });

    // initialize our merge context merge status before we would start either
    Blockchain blockchain = protocolContext.getBlockchain();
    blockchain
        .getTotalDifficultyByHash(blockchain.getChainHeadHash())
        .ifPresent(postMergeContext::setIsPostMerge);
  }

  @Override
  public BesuControllerBuilder storageProvider(final StorageProvider storageProvider) {
    super.storageProvider(storageProvider);
    return propagateConfig(z -> z.storageProvider(storageProvider));
  }

  @Override
  public BesuController build() {
    BesuController controller = super.build();
    PostMergeContext.get().setSyncState(controller.getSyncState());
    return controller;
  }

  @Override
  public BesuControllerBuilder genesisConfigFile(final GenesisConfigFile genesisConfig) {
    super.genesisConfigFile(genesisConfig);
    return propagateConfig(z -> z.genesisConfigFile(genesisConfig));
  }

  @Override
  public BesuControllerBuilder synchronizerConfiguration(
      final SynchronizerConfiguration synchronizerConfig) {
    super.synchronizerConfiguration(synchronizerConfig);
    return propagateConfig(z -> z.synchronizerConfiguration(synchronizerConfig));
  }

  @Override
  public BesuControllerBuilder ethProtocolConfiguration(
      final EthProtocolConfiguration ethProtocolConfiguration) {
    super.ethProtocolConfiguration(ethProtocolConfiguration);
    return propagateConfig(z -> z.ethProtocolConfiguration(ethProtocolConfiguration));
  }

  @Override
  public BesuControllerBuilder networkId(final BigInteger networkId) {
    super.networkId(networkId);
    return propagateConfig(z -> z.networkId(networkId));
  }

  @Override
  public BesuControllerBuilder miningParameters(final MiningParameters miningParameters) {
    super.miningParameters(miningParameters);
    return propagateConfig(z -> z.miningParameters(miningParameters));
  }

  @Override
  public BesuControllerBuilder messagePermissioningProviders(
      final List<NodeMessagePermissioningProvider> messagePermissioningProviders) {
    super.messagePermissioningProviders(messagePermissioningProviders);
    return propagateConfig(z -> z.messagePermissioningProviders(messagePermissioningProviders));
  }

  @Override
  public BesuControllerBuilder nodeKey(final NodeKey nodeKey) {
    super.nodeKey(nodeKey);
    return propagateConfig(z -> z.nodeKey(nodeKey));
  }

  @Override
  public BesuControllerBuilder metricsSystem(final ObservableMetricsSystem metricsSystem) {
    super.metricsSystem(metricsSystem);
    return propagateConfig(z -> z.metricsSystem(metricsSystem));
  }

  @Override
  public BesuControllerBuilder privacyParameters(final PrivacyParameters privacyParameters) {
    super.privacyParameters(privacyParameters);
    return propagateConfig(z -> z.privacyParameters(privacyParameters));
  }

  @Override
  public BesuControllerBuilder pkiBlockCreationConfiguration(
      final Optional<PkiBlockCreationConfiguration> pkiBlockCreationConfiguration) {
    super.pkiBlockCreationConfiguration(pkiBlockCreationConfiguration);
    return propagateConfig(z -> z.pkiBlockCreationConfiguration(pkiBlockCreationConfiguration));
  }

  @Override
  public BesuControllerBuilder dataDirectory(final Path dataDirectory) {
    super.dataDirectory(dataDirectory);
    return propagateConfig(z -> z.dataDirectory(dataDirectory));
  }

  @Override
  public BesuControllerBuilder clock(final Clock clock) {
    super.clock(clock);
    return propagateConfig(z -> z.clock(clock));
  }

  @Override
  public BesuControllerBuilder transactionPoolConfiguration(
      final TransactionPoolConfiguration transactionPoolConfiguration) {
    super.transactionPoolConfiguration(transactionPoolConfiguration);
    return propagateConfig(z -> z.transactionPoolConfiguration(transactionPoolConfiguration));
  }

  @Override
  public BesuControllerBuilder isRevertReasonEnabled(final boolean isRevertReasonEnabled) {
    super.isRevertReasonEnabled(isRevertReasonEnabled);
    return propagateConfig(z -> z.isRevertReasonEnabled(isRevertReasonEnabled));
  }

  @Override
  public BesuControllerBuilder isPruningEnabled(final boolean isPruningEnabled) {
    super.isPruningEnabled(isPruningEnabled);
    return propagateConfig(z -> z.isPruningEnabled(isPruningEnabled));
  }

  @Override
  public BesuControllerBuilder pruningConfiguration(final PrunerConfiguration prunerConfiguration) {
    super.pruningConfiguration(prunerConfiguration);
    return propagateConfig(z -> z.pruningConfiguration(prunerConfiguration));
  }

  @Override
  public BesuControllerBuilder genesisConfigOverrides(
      final Map<String, String> genesisConfigOverrides) {
    super.genesisConfigOverrides(genesisConfigOverrides);
    return propagateConfig(z -> z.genesisConfigOverrides(genesisConfigOverrides));
  }

  @Override
  public BesuControllerBuilder gasLimitCalculator(final GasLimitCalculator gasLimitCalculator) {
    super.gasLimitCalculator(gasLimitCalculator);
    return propagateConfig(z -> z.gasLimitCalculator(gasLimitCalculator));
  }

  @Override
  public BesuControllerBuilder requiredBlocks(final Map<Long, Hash> requiredBlocks) {
    super.requiredBlocks(requiredBlocks);
    return propagateConfig(z -> z.requiredBlocks(requiredBlocks));
  }

  @Override
  public BesuControllerBuilder reorgLoggingThreshold(final long reorgLoggingThreshold) {
    super.reorgLoggingThreshold(reorgLoggingThreshold);
    return propagateConfig(z -> z.reorgLoggingThreshold(reorgLoggingThreshold));
  }

  @Override
  public BesuControllerBuilder dataStorageConfiguration(
      final DataStorageConfiguration dataStorageConfiguration) {
    super.dataStorageConfiguration(dataStorageConfiguration);
    return propagateConfig(z -> z.dataStorageConfiguration(dataStorageConfiguration));
  }

  private BesuControllerBuilder propagateConfig(final Consumer<BesuControllerBuilder> toPropogate) {
    toPropogate.accept(preMergeBesuControllerBuilder);
    toPropogate.accept(mergeBesuControllerBuilder);
    return this;
  }
}