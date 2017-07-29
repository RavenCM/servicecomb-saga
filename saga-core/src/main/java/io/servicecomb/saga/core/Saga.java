/*
 * Copyright 2017 Huawei Technologies Co., Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.servicecomb.saga.core;

import io.servicecomb.saga.core.dag.ByLevelTraveller;
import io.servicecomb.saga.core.dag.FromLeafTraversalDirection;
import io.servicecomb.saga.core.dag.FromRootTraversalDirection;
import io.servicecomb.saga.core.dag.SingleLeafDirectedAcyclicGraph;
import io.servicecomb.saga.core.dag.TraversalDirection;
import java.lang.invoke.MethodHandles;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Saga {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final EventStore eventStore;

  private final CompletionService<Operation> executorService = new ExecutorCompletionService<>(
      Executors.newFixedThreadPool(5));

  private final Map<Operation, Collection<SagaEvent>> completedOperations;
  private final Set<SagaTask> orphanOperations;

  private final TransactionState transactionState;
  private final CompensationState compensationState;
  private volatile SagaState currentState;


  public Saga(EventStore eventStore, SingleLeafDirectedAcyclicGraph<SagaTask> sagaTaskGraph) {
    this(eventStore, new BackwardRecovery(), sagaTaskGraph);
  }

  public Saga(EventStore eventStore, RecoveryPolicy recoveryPolicy,
      SingleLeafDirectedAcyclicGraph<SagaTask> sagaTaskGraph) {

    this.eventStore = eventStore;
    this.completedOperations = new HashMap<>();
    this.orphanOperations = new HashSet<>();

    this.transactionState = new TransactionState(executorService, new LoggingRecoveryPolicy(recoveryPolicy),
        traveller(sagaTaskGraph, new FromRootTraversalDirection<>()));
    this.compensationState = new CompensationState(completedOperations,
        traveller(sagaTaskGraph, new FromLeafTraversalDirection<>()));

    currentState = transactionState;
  }

  public void run() {
    log.info("Starting Saga");
    do {
      try {
        currentState.run();
      } catch (TransactionFailedException e) {
        log.error("Failed to run operation", e);
        currentState = compensationState;

        gatherEvents(eventStore);

        orphanOperations.forEach(sagaTask -> {
          sagaTask.commit();
          sagaTask.compensation();
        });
      }
    } while (currentState.hasNext());
    log.info("Completed Saga");
  }

  public void play() {
    log.info("Start playing events");
    gatherEvents(eventStore);

    Map<Operation, Collection<SagaEvent>> completedOperationsCopy = new HashMap<>(completedOperations);
    transactionState.replay(completedOperationsCopy);

    // only compensation events left
    if (!completedOperationsCopy.isEmpty()) {
      currentState = compensationState;
      compensationState.replay(completedOperations);
    }

    log.info("Completed playing events");
  }

  private void gatherEvents(Iterable<EventEnvelope> events) {
    for (EventEnvelope event : events) {
      event.event.gatherTo(completedOperations, orphanOperations);
    }
  }

  private ByLevelTraveller<SagaTask> traveller(
      SingleLeafDirectedAcyclicGraph<SagaTask> sagaTaskGraph,
      TraversalDirection<SagaTask> traversalDirection) {

    return new ByLevelTraveller<>(sagaTaskGraph, traversalDirection);
  }
}