/*
 * Copyright 2015-2017 Real Logic Ltd.
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
package uk.co.real_logic.artio.engine.framer;

import org.agrona.ErrorHandler;
import org.agrona.collections.LongHashSet;
import uk.co.real_logic.artio.FixCounters;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.logger.SequenceNumberIndexReader;
import uk.co.real_logic.artio.messages.ConnectionType;
import uk.co.real_logic.artio.messages.SequenceNumberType;
import uk.co.real_logic.artio.protocol.GatewayPublication;

import java.io.IOException;

class EndPointFactory
{
    private final EngineConfiguration configuration;
    private final SessionContexts sessionContexts;
    private final GatewayPublication inboundLibraryPublication;
    private final GatewayPublication inboundClusterablePublication;
    private final FixCounters fixCounters;
    private final ErrorHandler errorHandler;
    private final LongHashSet replicatedConnectionIds;
    private final GatewaySessions gatewaySessions;

    private SlowPeeker replaySlowPeeker;

    EndPointFactory(
        final EngineConfiguration configuration,
        final SessionContexts sessionContexts,
        final GatewayPublication inboundLibraryPublication,
        final GatewayPublication inboundClusterablePublication,
        final FixCounters fixCounters,
        final ErrorHandler errorHandler,
        final LongHashSet replicatedConnectionIds,
        final GatewaySessions gatewaySessions)
    {
        this.configuration = configuration;
        this.sessionContexts = sessionContexts;
        this.inboundLibraryPublication = inboundLibraryPublication;
        this.inboundClusterablePublication = inboundClusterablePublication;
        this.fixCounters = fixCounters;
        this.errorHandler = errorHandler;
        this.replicatedConnectionIds = replicatedConnectionIds;
        this.gatewaySessions = gatewaySessions;
    }

    ReceiverEndPoint receiverEndPoint(
        final TcpChannel channel,
        final long connectionId,
        final long sessionId,
        final int sequenceIndex,
        final int libraryId,
        final Framer framer,
        final SequenceNumberIndexReader sentSequenceNumberIndex,
        final SequenceNumberIndexReader receivedSequenceNumberIndex,
        final SequenceNumberType sequenceNumberType,
        final ConnectionType connectionType) throws IOException
    {
        return new ReceiverEndPoint(
            channel,
            configuration.receiverBufferSize(),
            inboundLibraryPublication,
            inboundClusterablePublication,
            connectionId,
            sessionId,
            sequenceIndex,
            sessionContexts,
            sentSequenceNumberIndex,
            receivedSequenceNumberIndex,
            fixCounters.messagesRead(connectionId, channel.remoteAddress()),
            framer,
            errorHandler,
            libraryId,
            sequenceNumberType,
            connectionType,
            replicatedConnectionIds,
            gatewaySessions
        );
    }

    SenderEndPoint senderEndPoint(
        final TcpChannel channel,
        final long connectionId,
        final int libraryId,
        final BlockablePosition libraryBlockablePosition,
        final Framer framer) throws IOException
    {
        final String remoteAddress = channel.remoteAddress();
        return new SenderEndPoint(
            connectionId,
            libraryId,
            libraryBlockablePosition,
            replaySlowPeeker,
            channel,
            fixCounters.bytesInBuffer(connectionId, remoteAddress),
            fixCounters.invalidLibraryAttempts(connectionId, remoteAddress),
            errorHandler,
            framer,
            configuration.senderMaxBytesInBuffer(),
            configuration.slowConsumerTimeoutInMs(),
            System.currentTimeMillis()
        );
    }

    void replaySlowPeeker(final SlowPeeker replaySlowPeeker)
    {
        this.replaySlowPeeker = replaySlowPeeker;
    }
}
