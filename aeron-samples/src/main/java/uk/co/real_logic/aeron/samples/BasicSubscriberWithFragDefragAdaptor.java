/*
 * Copyright 2014 Real Logic Ltd.
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
package uk.co.real_logic.aeron.samples;

import uk.co.real_logic.aeron.Aeron;
import uk.co.real_logic.aeron.FragmentAssemblyAdapter;
import uk.co.real_logic.aeron.Subscription;
import uk.co.real_logic.agrona.CloseHelper;
import uk.co.real_logic.aeron.common.BackoffIdleStrategy;
import uk.co.real_logic.aeron.common.IdleStrategy;
import uk.co.real_logic.aeron.common.concurrent.SigInt;
import uk.co.real_logic.aeron.common.concurrent.logbuffer.DataHandler;
import uk.co.real_logic.aeron.driver.MediaDriver;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static uk.co.real_logic.aeron.samples.SamplesUtil.printStringMessage;

/**
 * Basic Aeron subscriber application which can receive fragmented messages
 */
public class BasicSubscriberWithFragDefragAdaptor
{
    private static final int STREAM_ID = SampleConfiguration.STREAM_ID;
    private static final int STREAM_ID_2 = SampleConfiguration.STREAM_ID + 1;
    private static final String CHANNEL = SampleConfiguration.CHANNEL;
    private static final int FRAGMENT_COUNT_LIMIT = SampleConfiguration.FRAGMENT_COUNT_LIMIT;
    private static final boolean EMBEDDED_MEDIA_DRIVER = SampleConfiguration.EMBEDDED_MEDIA_DRIVER;

    
    public static void main(final String[] args) throws Exception
    {
        System.out.println("Subscribing to " + CHANNEL + " on stream Id " + STREAM_ID + " and stream Id " + STREAM_ID_2);
        
        // Create shared memory segments
        SamplesUtil.useSharedMemoryOnLinux();

        final MediaDriver driver = EMBEDDED_MEDIA_DRIVER ? MediaDriver.launch() : null;
        
        // Create a context for client
        final Aeron.Context ctx = new Aeron.Context()
            .newConnectionHandler(BasicSubscriberWithFragDefragAdaptor::eventNewConnection) // Callback method when a new producer starts
            .inactiveConnectionHandler(BasicSubscriberWithFragDefragAdaptor::eventInactiveConnection); // Callback when at a producer exits
        
        // dataHandler method is called for every new datagram received
        // When a message is completely reassembled, the delegate method 'printStringMessage' is called
        final FragmentAssemblyAdapter dataHandler = new FragmentAssemblyAdapter(reassembledStringMessage(STREAM_ID));

        // Another Data handler for a different stream
        final FragmentAssemblyAdapter dataHandler2 = new FragmentAssemblyAdapter(reassembledStringMessage(STREAM_ID_2));

        final AtomicBoolean running = new AtomicBoolean(true);
        
        //Register a SIGINT handler
        SigInt.register(() -> running.set(false));

        // Create an Aeron instance with client provided context configuration and connect to media driver
        try (final Aeron aeron = Aeron.connect(ctx);
        	 //Add a subscription to Aeron for a given channel and steam. Also, supply a dataHandler to
        	 // be called when data arrives 
             final Subscription subscription = aeron.addSubscription(CHANNEL, STREAM_ID, dataHandler);
        	 final Subscription subscription2 = aeron.addSubscription(CHANNEL, STREAM_ID_2, dataHandler2))
        {
            // run the subscriber thread from here
            
            //SamplesUtil.subscriberLoop(FRAGMENT_COUNT_LIMIT, running).accept(subscription2);
            //SamplesUtil.subscriberLoop(FRAGMENT_COUNT_LIMIT, running).accept(subscription);
        	final IdleStrategy idleStrategy = new BackoffIdleStrategy(
                    100, 10, TimeUnit.MICROSECONDS.toNanos(1), TimeUnit.MICROSECONDS.toNanos(100));
        	
                try
                {
                    while (running.get())
                    {
                        final int fragmentsRead = subscription.poll(FRAGMENT_COUNT_LIMIT);
                        idleStrategy.idle(fragmentsRead);
                        
                        final int fragmentsRead2 = subscription2.poll(FRAGMENT_COUNT_LIMIT);
                        idleStrategy.idle(fragmentsRead2);
                    }
                }
                catch (final Exception ex)
                {
                    ex.printStackTrace();
                }
            System.out.println("Shutting down...");
        }

        CloseHelper.quietClose(driver);
    }
    /**
     * Print the information for a new connection to stdout.
     *
     * @param channel           for the connection
     * @param streamId          for the stream
     * @param sessionId         for the connection publication
     * @param sourceInformation that is transport specific
     */
    public static void eventNewConnection(
        final String channel, final int streamId, final int sessionId, final String sourceInformation)
    {
        System.out.println(
            String.format(
                "new connection on %s streamId %d sessionId %d from %s",
                channel, streamId, sessionId, sourceInformation));
    }

    /**
     * Print the information for an inactive connection to stdout.
     *
     * @param channel   for the connection
     * @param streamId  for the stream
     * @param sessionId for the connection publication
     */
    public static void eventInactiveConnection(final String channel, final int streamId, final int sessionId)
    {
        System.out.println(
            String.format(
                "inactive connection on %s streamId %d sessionId %d",
                channel, streamId, sessionId));
    }
    
    /**
     * Return a reusable, parameterized {@link DataHandler} that prints to stdout
     *
     * @param streamId to show when printing
     * @return subscription data handler function that prints the message contents
     */
    public static DataHandler reassembledStringMessage(final int streamId)
    {
        return (buffer, offset, length, header) ->
        {
            final byte[] data = new byte[length];
            buffer.getBytes(offset, data);

            System.out.println(
                String.format(
                    "message to stream %d from session %d (%d@%d) <<%s>>",
                    streamId, header.sessionId(), length, offset, new String(data)));
        };
    }
}