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
package uk.co.real_logic.artio;

import io.aeron.Aeron;
import org.agrona.IoUtil;
import org.agrona.concurrent.*;
import uk.co.real_logic.artio.session.SessionCustomisationStrategy;
import uk.co.real_logic.artio.session.SessionIdStrategy;
import uk.co.real_logic.artio.timing.HistogramHandler;
import uk.co.real_logic.artio.validation.AuthenticationStrategy;
import uk.co.real_logic.artio.validation.MessageValidationStrategy;

import java.io.File;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static java.lang.Integer.getInteger;
import static java.lang.System.getProperty;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toCollection;
import static uk.co.real_logic.artio.LivenessDetector.SEND_INTERVAL_FRACTION;

/**
 * Common configuration for both the Fix Engine and Library. Some options are configurable via
 * commandline properties. Setters override commandline properties, not the other way around.
 * <p>
 * See setters or properties for documentation of what specific configuration options do.
 *
 * @see uk.co.real_logic.artio.engine.EngineConfiguration
 * @see uk.co.real_logic.artio.library.LibraryConfiguration
 */
public class CommonConfiguration
{
    // ------------------------------------------------
    //          Configuration Properties
    // ------------------------------------------------

    /**
     * Property name for length of the memory mapped buffers for the counters file
     */
    public static final String MONITORING_BUFFERS_LENGTH_PROPERTY = "fix.monitoring.length";
    /**
     * Property name for directory of the conductor buffers
     */
    public static final String MONITORING_FILE_PROPERTY = "fix.monitoring.file";
    /**
     * Property name for the flag to enable or disable debug logging
     */
    public static final String DEBUG_PRINT_MESSAGES_PROPERTY = "fix.core.debug";
    /**
     * Property name for the flag to specify a thread to print
     */
    public static final String DEBUG_PRINT_THREAD_PROPERTY = "fix.core.debug.thread";
    /**
     * Property name for the flag to enable or disable flushing of writes
     */
    public static final String FORCE_WRITES_MESSAGES_PROPERTY = "fix.core.flush";
    /**
     * Property name for the flag to set the maximum number of attempts to claim a message
     * slot on the inbound stream.
     */
    public static final String INBOUND_MAX_CLAIM_ATTEMPTS_PROPERTY = "fix.core.inbound_max_claims";
    /**
     * Property name for the flag to set the maximum number of attempts to claim a message
     * slot on the outbound stream.
     */
    public static final String OUTBOUND_MAX_CLAIM_ATTEMPTS_PROPERTY = "fix.core.outbound_max_claims";
    /**
     * Property name for the flag to enable or disable message timing
     */
    public static final String TIME_MESSAGES_PROPERTY = "fix.core.timing";
    /**
     * Property name for the file to log debug messages to, default is standard output
     */
    public static final String DEBUG_FILE_PROPERTY = "fix.core.debug.file";
    /**
     * Property name for the period at which histogram intervals are polled and logged
     */
    public static final String HISTOGRAM_POLL_PERIOD_IN_MS_PROPERTY = "fix.benchmark.histogram_poll_period";
    /**
     * Property name for the file to which histogram intervals are logged
     */
    public static final String HISTOGRAM_LOGGING_FILE_PROPERTY = "fix.benchmark.histogram_file";

    public static final int DEFAULT_MONITORING_BUFFER_LENGTH = 64 * 1024 * 1024;
    public static final String DEFAULT_DIRECTORY = optimalTmpDirName() + File.separator + "fix-%s";
    public static final String DEFAULT_MONITORING_FILE = DEFAULT_DIRECTORY + File.separator + "monitoring";

    public static final String DEFAULT_HISTOGRAM_LOGGING_FILE = DEFAULT_DIRECTORY + File.separator + "histograms";
    public static final String DEFAULT_NAME_PREFIX = "";
    public static final int DEFAULT_REASONABLE_TRANSMISSION_TIME_IN_S = 3;
    public static final long DEFAULT_REASONABLE_TRANSMISSION_TIME_IN_MS =
        SECONDS.toMillis(DEFAULT_REASONABLE_TRANSMISSION_TIME_IN_S);
    public static final boolean DEFAULT_PRINT_AERON_STREAM_IDENTIFIERS = false;

    private long reasonableTransmissionTimeInMs = DEFAULT_REASONABLE_TRANSMISSION_TIME_IN_MS;
    private boolean printAeronStreamIdentifiers = DEFAULT_PRINT_AERON_STREAM_IDENTIFIERS;
    private NanoClock nanoClock = new SystemNanoClock();

    public static void validateTimeout(final long timeoutInMs)
    {
        if (timeoutInMs <= 0)
        {
            throw new IllegalArgumentException(String.format(
                "Timeout must be > 0, but was configured as %d",
                timeoutInMs));
        }
    }

    // ------------------------------------------------
    //          Static Configuration
    // ------------------------------------------------

    /**
     * These are static final fields in order to give the optimiser more scope
     */
    public static final boolean DEBUG_PRINT_MESSAGES;
    public static final Set<LogTag> DEBUG_TAGS;
    public static final String DEBUG_PRINT_THREAD;

    static
    {
        final String debugPrintMessagesValue = getProperty(DEBUG_PRINT_MESSAGES_PROPERTY);
        boolean debugPrintMessages = false;
        Set<LogTag> debugTags = Collections.emptySet();
        if (debugPrintMessagesValue != null)
        {
            if ("all".equals(debugPrintMessagesValue) || "true".equals(debugPrintMessagesValue))
            {
                debugPrintMessages = true;
                debugTags = EnumSet.allOf(LogTag.class);
            }
            else
            {
                try
                {
                    debugTags = Stream
                        .of(debugPrintMessagesValue.split(","))
                        .map(LogTag::valueOf)
                        .collect(toCollection(() -> EnumSet.noneOf(LogTag.class)));

                    debugPrintMessages = !debugTags.isEmpty();
                }
                catch (final IllegalArgumentException ignore)
                {
                    // parse error in valueOf();
                }
            }
        }

        final String debugPrintThreadValue = getProperty(DEBUG_PRINT_THREAD_PROPERTY);
        DEBUG_PRINT_THREAD = debugPrintThreadValue == null ? null : debugPrintThreadValue + " : ";
        DEBUG_PRINT_MESSAGES = debugPrintMessages;
        DEBUG_TAGS = debugTags;
    }

    public static final String DEBUG_FILE = System.getProperty(DEBUG_FILE_PROPERTY);
    public static final boolean TIME_MESSAGES = Boolean.getBoolean(TIME_MESSAGES_PROPERTY);
    public static final boolean FORCE_WRITES = Boolean.getBoolean(FORCE_WRITES_MESSAGES_PROPERTY);

    public static final int BACKOFF_SPINS = Integer.getInteger("fix.core.spins", 100);
    public static final int BACKOFF_YIELDS = Integer.getInteger("fix.core.yields", 100);

    // ------------------------------------------------
    //          Configuration Defaults
    // ------------------------------------------------

    public static final int DEFAULT_INBOUND_MAX_CLAIM_ATTEMPTS = BACKOFF_SPINS + BACKOFF_YIELDS + 1000;
    public static final int DEFAULT_OUTBOUND_MAX_CLAIM_ATTEMPTS = DEFAULT_INBOUND_MAX_CLAIM_ATTEMPTS;

    public static final int DEFAULT_SESSION_BUFFER_SIZE = 16 * 1024;
    public static final long DEFAULT_SENDING_TIME_WINDOW = MINUTES.toMillis(2);
    public static final int DEFAULT_HEARTBEAT_INTERVAL_IN_S = 10;

    public static final long DEFAULT_REPLY_TIMEOUT_IN_MS = 2_000L;
    public static final long DEFAULT_HISTOGRAM_POLL_PERIOD_IN_MS = MINUTES.toMillis(1);

    private boolean printErrorMessages = true;
    private IdleStrategy monitoringThreadIdleStrategy = new BackoffIdleStrategy(1, 1, 1000, 1_000_000);
    private long sendingTimeWindowInMs = DEFAULT_SENDING_TIME_WINDOW;
    private SessionIdStrategy sessionIdStrategy = SessionIdStrategy.senderAndTarget();
    private AuthenticationStrategy authenticationStrategy = AuthenticationStrategy.none();
    private MessageValidationStrategy messageValidationStrategy = MessageValidationStrategy.none();
    private SessionCustomisationStrategy sessionCustomisationStrategy = SessionCustomisationStrategy.none();
    private int monitoringBuffersLength = getInteger(
        MONITORING_BUFFERS_LENGTH_PROPERTY, DEFAULT_MONITORING_BUFFER_LENGTH);
    private String monitoringFile = null;
    private long replyTimeoutInMs = DEFAULT_REPLY_TIMEOUT_IN_MS;
    private final Aeron.Context aeronContext = new Aeron.Context();
    private int sessionBufferSize = DEFAULT_SESSION_BUFFER_SIZE;
    private int inboundMaxClaimAttempts =
        getInteger(INBOUND_MAX_CLAIM_ATTEMPTS_PROPERTY, DEFAULT_INBOUND_MAX_CLAIM_ATTEMPTS);
    private int outboundMaxClaimAttempts =
        getInteger(OUTBOUND_MAX_CLAIM_ATTEMPTS_PROPERTY, DEFAULT_OUTBOUND_MAX_CLAIM_ATTEMPTS);
    private int defaultHeartbeatIntervalInS = DEFAULT_HEARTBEAT_INTERVAL_IN_S;
    private long histogramPollPeriodInMs =
        Long.getLong(HISTOGRAM_POLL_PERIOD_IN_MS_PROPERTY, DEFAULT_HISTOGRAM_POLL_PERIOD_IN_MS);
    private String histogramLoggingFile = null;
    private HistogramHandler histogramHandler;
    private String agentNamePrefix = DEFAULT_NAME_PREFIX;

    private final AtomicBoolean isConcluded = new AtomicBoolean(false);

    /**
     * Sets the sending time window. The sending time window is the period of acceptance
     * delta between the current time on the Fix Library thread and the sending time
     * received in messages. Sessions are disconnected if the sending time diverges by
     * more than this window and if validation is enabled.
     *
     * @param sendingTimeWindowInMs the current sending time in milliseconds
     * @return this
     */
    public CommonConfiguration sendingTimeWindowInMs(final long sendingTimeWindowInMs)
    {
        this.sendingTimeWindowInMs = sendingTimeWindowInMs;
        return this;
    }

    public long sendingTimeWindowInMs()
    {
        return sendingTimeWindowInMs;
    }

    /**
     * The default interval for heartbeats if not exchanged upon logon. Specified in seconds.
     *
     * @return this
     */
    public CommonConfiguration defaultHeartbeatIntervalInS(final int value)
    {
        defaultHeartbeatIntervalInS = value;
        return this;
    }

    public int defaultHeartbeatIntervalInS()
    {
        return defaultHeartbeatIntervalInS;
    }

    /**
     * Sets the session id strategy.
     *
     * @param sessionIdStrategy the session id strategy.
     * @return this
     * @see SessionIdStrategy
     */
    public CommonConfiguration sessionIdStrategy(final SessionIdStrategy sessionIdStrategy)
    {
        this.sessionIdStrategy = sessionIdStrategy;
        return this;
    }

    /**
     * Sets the authentication strategy of the FIX Library, see {@link AuthenticationStrategy} for details.
     * <p>
     * This only needs to be set if this FIX Library is the acceptor library.
     *
     * @param authenticationStrategy the authentication strategy to use.
     * @return this
     */
    public CommonConfiguration authenticationStrategy(final AuthenticationStrategy authenticationStrategy)
    {
        this.authenticationStrategy = authenticationStrategy;
        return this;
    }

    /**
     * Sets the session customisation strategy of the FIX Library,
     * see {@link SessionCustomisationStrategy} for details.
     * <p>
     * This only needs to be set if this FIX Library is the acceptor library.
     *
     * @param sessionCustomisationStrategy the session customisation strategy to use.
     * @return this
     */
    public CommonConfiguration sessionCustomisationStrategy(
        final SessionCustomisationStrategy sessionCustomisationStrategy)
    {
        this.sessionCustomisationStrategy = sessionCustomisationStrategy;
        return this;
    }

    /**
     * Sets the message validation strategy of the FIX Library,
     * see {@link MessageValidationStrategy} for details.
     *
     * @param messageValidationStrategy the message validation strategy to use.
     * @return this
     */
    public CommonConfiguration messageValidationStrategy(final MessageValidationStrategy messageValidationStrategy)
    {
        this.messageValidationStrategy = messageValidationStrategy;
        return this;
    }

    public CommonConfiguration reasonableTransmissionTimeInMs(final long reasonableTransmissionTimeInMs)
    {
        this.reasonableTransmissionTimeInMs = reasonableTransmissionTimeInMs;
        return this;
    }

    public long reasonableTransmissionTimeInMs()
    {
        return reasonableTransmissionTimeInMs;
    }

    /**
     * Sets the length of the buffer used for monitoring counters.
     *
     * @param monitoringBuffersLength the length of the buffer used for monitoring counters.
     * @return this
     * @see CommonConfiguration#MONITORING_BUFFERS_LENGTH_PROPERTY
     */
    public CommonConfiguration monitoringBuffersLength(final Integer monitoringBuffersLength)
    {
        this.monitoringBuffersLength = monitoringBuffersLength;
        return this;
    }

    /**
     * Sets the location for the monitoring file.
     *
     * @param monitoringFile the location for the monitoring file.
     * @return this
     * @see CommonConfiguration#MONITORING_FILE_PROPERTY
     */
    public CommonConfiguration monitoringFile(final String monitoringFile)
    {
        this.monitoringFile = monitoringFile;
        return this;
    }

    /**
     * Sets the printing of error messages on or off. Error messages are always logged in an error buffer that
     * can be scanned by another diagnostic process, this simply switches on or off the printing these errors on
     * standard out.
     * <p>
     * Default: true
     *
     * @param printErrorMessages the printing of error messages.
     * @return this
     */
    public CommonConfiguration printErrorMessages(final boolean printErrorMessages)
    {
        this.printErrorMessages = printErrorMessages;
        return this;
    }

    /**
     * Sets the idle strategy for the Error Printer thread.
     *
     * @param errorPrinterIdleStrategy the idle strategy for the Error Printer thread.
     * @return this
     */
    public CommonConfiguration monitoringThreadIdleStrategy(final IdleStrategy errorPrinterIdleStrategy)
    {
        this.monitoringThreadIdleStrategy = errorPrinterIdleStrategy;
        return this;
    }

    /**
     * Sets the reply timeout in milliseconds.
     * <p>
     * This is the timeout for control protocol messages between the FIX Gateway and FIX Library instances.
     *
     * @param replyTimeoutInMs the reply timeout in milliseconds.
     * @return this
     */
    public CommonConfiguration replyTimeoutInMs(final long replyTimeoutInMs)
    {
        this.replyTimeoutInMs = replyTimeoutInMs;
        return this;
    }

    /**
     * Sets the inbound max claim attempts.
     *
     * @param inboundMaxClaimAttempts the inbound max claim attempts
     * @return this
     * @see CommonConfiguration#INBOUND_MAX_CLAIM_ATTEMPTS_PROPERTY
     */
    public CommonConfiguration inboundMaxClaimAttempts(final int inboundMaxClaimAttempts)
    {
        this.inboundMaxClaimAttempts = inboundMaxClaimAttempts;
        return this;
    }

    /**
     * Sets the outbound max claim attempts.
     *
     * @param outboundMaxClaimAttempts the outbound max claim attempts
     * @return this
     * @see CommonConfiguration#OUTBOUND_MAX_CLAIM_ATTEMPTS_PROPERTY
     */
    public CommonConfiguration outboundMaxClaimAttempts(final int outboundMaxClaimAttempts)
    {
        this.outboundMaxClaimAttempts = outboundMaxClaimAttempts;
        return this;
    }

    /**
     * Sets the session's encoding buffer size. The session buffer is a buffer used by each Session to encode messages
     * via
     * {@link uk.co.real_logic.artio.session.Session#send(uk.co.real_logic.artio.builder.Encoder)}.
     *
     * This is also used as the size of buffer for messages that are sent by the Session management system itself.
     *
     * @param bufferSize the session's encoding buffer size
     * @return this
     */
    public CommonConfiguration sessionBufferSize(final int bufferSize)
    {
        this.sessionBufferSize = bufferSize;
        return this;
    }

    public CommonConfiguration histogramPollPeriodInMs(final long histogramPollPeriodInMs)
    {
        this.histogramPollPeriodInMs = histogramPollPeriodInMs;
        return this;
    }

    public CommonConfiguration histogramLoggingFile(final String histogramLoggingFile)
    {
        this.histogramLoggingFile = histogramLoggingFile;
        return this;
    }

    public CommonConfiguration histogramHandler(final HistogramHandler histogramHandler)
    {
        this.histogramHandler = histogramHandler;
        return this;
    }

    public CommonConfiguration agentNamePrefix(final String agentNamePrefix)
    {
        this.agentNamePrefix = agentNamePrefix;
        return this;
    }

    public CommonConfiguration printAeronStreamIdentifiers(final boolean printAeronStreamIdentifiers)
    {
        this.printAeronStreamIdentifiers = printAeronStreamIdentifiers;
        return this;
    }

    /**
     * Sets the clock to be used for recording timestamping messages.
     *
     * @param timerClock the clock to be used for recording timestamping messages.
     * @return this
     */
    public CommonConfiguration nanoClock(final NanoClock timerClock)
    {
        this.nanoClock = timerClock;
        return this;
    }

    public Aeron.Context aeronContext()
    {
        return aeronContext;
    }

    public boolean printErrorMessages()
    {
        return printErrorMessages;
    }

    public IdleStrategy monitoringThreadIdleStrategy()
    {
        return monitoringThreadIdleStrategy;
    }

    public SessionIdStrategy sessionIdStrategy()
    {
        return sessionIdStrategy;
    }

    public AuthenticationStrategy authenticationStrategy()
    {
        return authenticationStrategy;
    }

    public SessionCustomisationStrategy sessionCustomisationStrategy()
    {
        return sessionCustomisationStrategy;
    }

    public MessageValidationStrategy messageValidationStrategy()
    {
        return messageValidationStrategy;
    }

    public int monitoringBuffersLength()
    {
        return monitoringBuffersLength;
    }

    public String monitoringFile()
    {
        return monitoringFile;
    }

    public long replyTimeoutInMs()
    {
        return replyTimeoutInMs;
    }

    public long connectAttemptTimeoutInMs()
    {
        return replyTimeoutInMs() / SEND_INTERVAL_FRACTION;
    }

    public long histogramPollPeriodInMs()
    {
        return histogramPollPeriodInMs;
    }

    public int inboundMaxClaimAttempts()
    {
        return inboundMaxClaimAttempts;
    }

    public int outboundMaxClaimAttempts()
    {
        return outboundMaxClaimAttempts;
    }

    public int sessionBufferSize()
    {
        return sessionBufferSize;
    }

    public String histogramLoggingFile()
    {
        return histogramLoggingFile;
    }

    public HistogramHandler histogramHandler()
    {
        return histogramHandler;
    }

    public String agentNamePrefix()
    {
        return agentNamePrefix;
    }

    public boolean printAeronStreamIdentifiers()
    {
        return printAeronStreamIdentifiers;
    }

    protected void conclude(final String fixSuffix)
    {
        if (isConcluded.compareAndSet(false, true))
        {
            if (monitoringFile() == null)
            {
                monitoringFile(getProperty(
                    MONITORING_FILE_PROPERTY, String.format(DEFAULT_MONITORING_FILE, fixSuffix)));
            }

            if (histogramLoggingFile() == null)
            {
                histogramLoggingFile(getProperty(
                    HISTOGRAM_LOGGING_FILE_PROPERTY, String.format(DEFAULT_HISTOGRAM_LOGGING_FILE, fixSuffix)));
            }
        }
        else
        {
            throw new IllegalStateException(
                "This configuration has already been concluded, are you trying to re-use it?");
        }
    }

    /**
     * If shared memory is available, use that as a temporary directory,
     * otherwise use the default temp directory
     *
     * @return the optimal temporary directory
     */
    public static String optimalTmpDirName()
    {
        if ("Linux".equalsIgnoreCase(System.getProperty("os.name")))
        {
            final File devShmDir = new File("/dev/shm");

            if (devShmDir.exists())
            {
                return devShmDir.getAbsolutePath();
            }
        }

        return IoUtil.tmpDirName();
    }

    public static IdleStrategy backoffIdleStrategy()
    {
        return new BackoffIdleStrategy(BACKOFF_SPINS, BACKOFF_YIELDS, 1, 1 << 20);
    }

    public NanoClock nanoClock()
    {
        return nanoClock;
    }

}
