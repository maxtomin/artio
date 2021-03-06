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
package uk.co.real_logic.artio.replication;

import io.aeron.Subscription;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.replication.messages.AcknowledgementStatus;
import uk.co.real_logic.artio.replication.messages.Vote;

import java.util.concurrent.atomic.AtomicLong;

import static uk.co.real_logic.artio.LogTag.RAFT;
import static uk.co.real_logic.artio.replication.PositionTranslations.transportToReplicated;
import static uk.co.real_logic.artio.replication.messages.AcknowledgementStatus.MISSING_LOG_ENTRIES;
import static uk.co.real_logic.artio.replication.messages.AcknowledgementStatus.OK;
import static uk.co.real_logic.artio.replication.messages.Vote.AGAINST;
import static uk.co.real_logic.artio.replication.messages.Vote.FOR;

class Follower implements Role, RaftHandler
{
    static final short NO_ONE = -1;

    private final RaftSubscription raftSubscription;

    private final short nodeId;
    private final ClusterAgent clusterNode;
    private final TermState termState;
    private final AtomicLong consensusPosition;
    private final DirectBuffer nodeState;
    private final NodeStateHandler nodeStateHandler;
    private final RandomTimeout replyTimeout;
    private final RaftArchiver raftArchiver;

    private RaftPublication acknowledgementPublication;
    private RaftPublication controlPublication;

    private Subscription controlSubscription;
    private long missingAckedPosition;
    private boolean requiresAcknowledgementResend = false;

    private short votedFor = NO_ONE;
    private long timeInMs;

    Follower(
        final short nodeId,
        final ClusterAgent clusterNode,
        final long timeInMs,
        final long replyTimeoutInMs,
        final TermState termState,
        final RaftArchiver raftArchiver,
        final DirectBuffer nodeState,
        final NodeStateHandler nodeStateHandler)
    {
        this.nodeId = nodeId;
        this.clusterNode = clusterNode;
        this.termState = termState;
        this.raftArchiver = raftArchiver;
        this.consensusPosition = termState.consensusPosition();
        this.nodeState = nodeState;
        this.nodeStateHandler = nodeStateHandler;
        replyTimeout = new RandomTimeout(replyTimeoutInMs, timeInMs);
        raftSubscription = new RaftSubscription(DebugRaftHandler.wrap(nodeId, this));
    }

    public int pollCommands(final int fragmentLimit, final long timeInMs)
    {
        this.timeInMs = timeInMs;

        final int read = controlSubscription.controlledPoll(raftSubscription, fragmentLimit);
        if (read > 0)
        {
            onReplyKeepAlive(this.timeInMs);
        }
        return read;
    }

    public int checkConditions(final long timeInMs)
    {
        if (replyTimeout.hasTimedOut(timeInMs))
        {
            termState.noLeader();

            clusterNode.transitionToCandidate(timeInMs);

            return 1;
        }

        return 0;
    }

    public int readData()
    {
        if (raftArchiver.checkLeaderArchiver())
        {
            return 0;
        }

        final long imageTransportPosition = raftArchiver.archivedTransportPosition();
        final long imagePosition = transportToReplicated(imageTransportPosition, termState.transportPositionDelta());
        if (imagePosition > termState.receivedPosition() && imagePosition > missingAckedPosition)
        {
            if (saveMessageAcknowledgement(MISSING_LOG_ENTRIES) >= 0)
            {
                missingAckedPosition = imagePosition;
            }

            return 1;
        }

        final int bytesRead = raftArchiver.poll();
        if (bytesRead > 0 || requiresAcknowledgementResend)
        {
            termState.moveReceivedPosition(bytesRead);
            saveOkAcknowledgement();
        }

        return bytesRead;
    }

    private long saveMessageAcknowledgement(final AcknowledgementStatus status)
    {
        return acknowledgementPublication.saveMessageAcknowledgement(termState.receivedPosition(), nodeId, status);
    }

    public void closeStreams()
    {
    }

    private void onReplyKeepAlive(final long timeInMs)
    {
        replyTimeout.onKeepAlive(timeInMs);
    }

    public Action onMessageAcknowledgement(
        final long newAckedPosition, final short nodeId, final AcknowledgementStatus status)
    {
        // not interested in this message
        return Action.CONTINUE;
    }

    public Action onRequestVote(
        final short candidateId, final int candidateSessionId, final int leaderShipTerm, final long candidatePosition)
    {
        // Ignore requests from yourself
        if (candidateId != this.nodeId)
        {
            if (canVoteFor(candidateId) && safeToVote(leaderShipTerm, candidatePosition))
            {
                if (controlPublication.saveReplyVote(nodeId, candidateId, leaderShipTerm, FOR, nodeState) > 0)
                {
                    votedFor = candidateId;
                    DebugLogger.log(RAFT, "%d: vote for %d in %d%n", nodeId, candidateId, leaderShipTerm);
                    onReplyKeepAlive(timeInMs);
                }
                else
                {
                    return Action.ABORT;
                }
            }
            else
            {
                DebugLogger.log(RAFT, "%d: vote against %d in %d%n", nodeId, candidateId, leaderShipTerm);

                return Pressure.apply(
                    controlPublication.saveReplyVote(nodeId, candidateId, leaderShipTerm, AGAINST, nodeState));
            }
        }

        return Action.CONTINUE;
    }

    private boolean safeToVote(final int leaderShipTerm, final long candidatePosition)
    {
        // Term has to be strictly greater because a follower has already
        // Voted for someone in its current leaderShipTerm and is electing for the
        // next leaderShipTerm
        return candidatePosition >= termState.receivedPosition() && leaderShipTerm > termState.leadershipTerm();
    }

    private boolean canVoteFor(final short candidateId)
    {
        return votedFor == NO_ONE || votedFor == candidateId;
    }

    public Action onReplyVote(
        final short senderNodeId,
        final short candidateId,
        final int leaderShipTerm,
        final Vote vote,
        final DirectBuffer nodeStateBuffer,
        final int nodeStateLength,
        final int aeronSessionId)
    {
        nodeStateHandler.onNewNodeState(senderNodeId, aeronSessionId, nodeStateBuffer, nodeStateLength);
        return Action.CONTINUE;
    }

    public Action onConsensusHeartbeat(
        final short leaderNodeId,
        final int leaderShipTerm,
        final long position,
        final long transportStartPosition,
        final long transportPosition,
        final int leaderSessionId)
    {
        if (leaderNodeId != this.nodeId)
        {
            if (leaderShipTerm == termState.leadershipTerm())
            {
                consensusPosition.set(position);
                termState.leaderSessionId(leaderSessionId);
            }
            else if (leaderShipTerm > termState.leadershipTerm())
            {
                termState
                    .leadershipTerm(leaderShipTerm)
                    .leaderSessionId(leaderSessionId);

                if (leaderSessionId != termState.leaderSessionId().get())
                {
                    checkLeaderChange();
                }

                follow(this.timeInMs);
            }
        }

        return Action.CONTINUE;
    }

    // TODO: need to be able to overwrite old log entries, test that this happens.
    public Action onResend(
        final int leaderSessionId,
        final int leaderShipTerm,
        final long startPosition,
        final DirectBuffer bodyBuffer,
        final int bodyOffset,
        final int bodyLength)
    {
        if (isValidPosition(leaderSessionId, leaderShipTerm, startPosition))
        {
            if (!raftArchiver.checkLeaderArchiver())
            {
                raftArchiver.patch(bodyBuffer, bodyOffset, bodyLength);
                termState.moveReceivedPosition(bodyLength);
                saveOkAcknowledgement();
            }
        }

        return Action.CONTINUE;
    }

    private void saveOkAcknowledgement()
    {
        requiresAcknowledgementResend = saveMessageAcknowledgement(OK) < 0;
        if (!requiresAcknowledgementResend)
        {
            onReplyKeepAlive(timeInMs);
        }
    }

    private boolean isValidPosition(final int leaderSessionId, final int leaderShipTerm, final long position)
    {
        return position == termState.receivedPosition() &&
            leaderSessionId == termState.leaderSessionId().get() &&
            leaderShipTerm == termState.leadershipTerm();
    }

    Follower follow(final long timeInMs)
    {
        onReplyKeepAlive(timeInMs);
        votedFor = NO_ONE;
        readTermState();
        return this;
    }

    private void readTermState()
    {
        checkLeaderChange();
        missingAckedPosition = 0;
    }

    private void checkLeaderChange()
    {
        if (termState.hasLeader())
        {
            raftArchiver.onLeader();
            clusterNode.onNewLeader();
        }
        else
        {
            raftArchiver.onNoLeader();
            clusterNode.onNoLeader();
        }
    }

    Follower acknowledgementPublication(final RaftPublication acknowledgementPublication)
    {
        this.acknowledgementPublication = acknowledgementPublication;
        return this;
    }

    Follower controlPublication(final RaftPublication controlPublication)
    {
        this.controlPublication = controlPublication;
        return this;
    }

    Follower controlSubscription(final Subscription controlSubscription)
    {
        this.controlSubscription = controlSubscription;
        return this;
    }

    Follower dataSubscription(final Subscription dataSubscription)
    {
        raftArchiver.dataSubscription(dataSubscription);
        return this;
    }

    Follower votedFor(final short votedFor)
    {
        this.votedFor = votedFor;
        return this;
    }
}
