/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.pubsublite.flink.internal.reader;

import com.google.cloud.pubsublite.flink.internal.split.SubscriptionPartitionSplit;
import com.google.cloud.pubsublite.flink.internal.split.SubscriptionPartitionSplitState;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.flink.api.connector.source.SourceReaderContext;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.base.source.reader.RecordEmitter;
import org.apache.flink.connector.base.source.reader.SingleThreadMultiplexSourceReaderBase;
import org.apache.flink.connector.base.source.reader.splitreader.SplitReader;

public class PubsubLiteSourceReader<T>
    extends SingleThreadMultiplexSourceReaderBase<
        Record<T>, T, SubscriptionPartitionSplit, SubscriptionPartitionSplitState> {
  private final CheckpointCursorCommitter checkpointCursorCommitter;

  public PubsubLiteSourceReader(
      RecordEmitter<Record<T>, T, SubscriptionPartitionSplitState> recordEmitter,
      Consumer<SubscriptionPartitionSplit> cursorCommitter,
      Supplier<SplitReader<Record<T>, SubscriptionPartitionSplit>> splitReaderSupplier,
      Configuration config,
      SourceReaderContext context) {
    super(splitReaderSupplier, recordEmitter, config, context);
    this.checkpointCursorCommitter = new CheckpointCursorCommitter(cursorCommitter);
  }

  @Override
  public List<SubscriptionPartitionSplit> snapshotState(long checkpointId) {
    // When a checkpoint is started we intercept the checkpoint call and save the checkpoint.
    // Once the checkpoint has been committed (notifyCheckpointComplete is called) we will propagate
    // the cursors to pubsub lite.
    List<SubscriptionPartitionSplit> checkpoint = super.snapshotState(checkpointId);
    checkpointCursorCommitter.addCheckpoint(checkpointId, checkpoint);
    return checkpoint;
  }

  @Override
  public void notifyCheckpointComplete(long checkpointId) {
    checkpointCursorCommitter.notifyCheckpointComplete(checkpointId);
  }

  @Override
  protected SubscriptionPartitionSplitState initializedState(
      SubscriptionPartitionSplit sourceSplit) {
    return new SubscriptionPartitionSplitState(sourceSplit);
  }

  @Override
  protected SubscriptionPartitionSplit toSplitType(
      String splitState, SubscriptionPartitionSplitState state) {
    return state.toSplit();
  }

  @Override
  protected void onSplitFinished(Map<String, SubscriptionPartitionSplitState> map) {
    // When splits are saved, we track the final position of the split so we can commit it to
    // pubsub lite. This is necessary because the split will not appear in future checkpoints.
    checkpointCursorCommitter.notifySplitFinished(
        map.values().stream()
            .map(SubscriptionPartitionSplitState::toSplit)
            .collect(Collectors.toList()));
  }
}
