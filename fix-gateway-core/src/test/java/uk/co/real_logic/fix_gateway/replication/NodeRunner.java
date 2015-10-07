/*
 * Copyright 2015 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.replication;

import uk.co.real_logic.aeron.Aeron;
import uk.co.real_logic.aeron.Publication;
import uk.co.real_logic.aeron.Subscription;
import uk.co.real_logic.aeron.driver.MediaDriver;
import uk.co.real_logic.aeron.logbuffer.BlockHandler;
import uk.co.real_logic.agrona.CloseHelper;
import uk.co.real_logic.agrona.collections.IntHashSet;
import uk.co.real_logic.agrona.concurrent.AtomicCounter;
import uk.co.real_logic.agrona.concurrent.NoOpIdleStrategy;
import uk.co.real_logic.fix_gateway.engine.framer.ReliefValve;

import static org.mockito.Mockito.mock;
import static uk.co.real_logic.aeron.CommonContext.AERON_DIR_PROP_DEFAULT;
import static uk.co.real_logic.aeron.driver.ThreadingMode.SHARED;
import static uk.co.real_logic.fix_gateway.replication.AbstractReplicationTest.CONTROL;
import static uk.co.real_logic.fix_gateway.replication.AbstractReplicationTest.DATA;

public class NodeRunner implements AutoCloseable, Role
{

    public static final long TIMEOUT_IN_MS = 100;
    public static final String AERON_GROUP = "aeron:udp?group=224.0.1.1:40456";

    private final BlockHandler handler = (buffer, offset, length, sessionId, termId) -> {
        replicatedPosition = offset + length;
    };
    private final SwitchableLossGenerator lossGenerator = new SwitchableLossGenerator();

    private final MediaDriver mediaDriver;
    private final Aeron aeron;
    private final Replicator replicator;

    private long replicatedPosition = -1;
    private long timeInMs = 0;

    public NodeRunner(final int nodeId, final int... otherNodes)
    {
        final MediaDriver.Context context = new MediaDriver.Context();
        context
            .threadingMode(SHARED)
            .controlLossGenerator(lossGenerator)
            .dataLossGenerator(lossGenerator)
            .dirsDeleteOnStart(true)
            .dirName(AERON_DIR_PROP_DEFAULT + nodeId);

        final IntHashSet otherNodeIds = new IntHashSet(40, -1);
        for (final int node : otherNodes)
        {
            otherNodeIds.add(node);
        }

        mediaDriver = MediaDriver.launch(context);
        final Aeron.Context clientContext = new Aeron.Context();
        clientContext.dirName(context.dirName());
        aeron = Aeron.connect(clientContext);
        replicator = new Replicator(
            (short) nodeId,
            controlPublication(),
            dataPublication(),
            controlSubscription(),
            dataSubscription(),
            otherNodeIds,
            timeInMs,
            TIMEOUT_IN_MS,
            new EntireClusterTermAcknowledgementStrategy(),
            handler
        );
    }

    protected Subscription controlSubscription()
    {
        return aeron.addSubscription(AERON_GROUP, CONTROL);
    }

    protected Subscription dataSubscription()
    {
        return aeron.addSubscription(AERON_GROUP, DATA);
    }

    protected ControlPublication controlPublication()
    {
        return new ControlPublication(
            100,
            new NoOpIdleStrategy(),
            mock(AtomicCounter.class),
            mock(ReliefValve.class),
            aeron.addPublication(AERON_GROUP, CONTROL));
    }

    protected Publication dataPublication()
    {
        return aeron.addPublication(AERON_GROUP, DATA);
    }

    public int poll(final int fragmentLimit, final long timeInMs)
    {
        // NB: ignores other time
        return replicator.poll(fragmentLimit, this.timeInMs);
    }

    public void advanceClock(final long delta)
    {
        timeInMs += delta;
    }

    public void dropFrames(final boolean dropFrames)
    {
        lossGenerator.dropFrames(dropFrames);
    }

    public boolean isLeader()
    {
        return replicator.isLeader();
    }

    public Replicator replicator()
    {
        return replicator;
    }

    public long replicatedPosition()
    {
        return replicatedPosition;
    }

    public void close()
    {
        CloseHelper.close(aeron);
        CloseHelper.close(mediaDriver);
    }
}
