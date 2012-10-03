/**
 * Licensed to jclouds, Inc. (jclouds) under one or more
 * contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  jclouds licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.jclouds.abiquo.internal;

import static org.jclouds.abiquo.config.AbiquoProperties.ASYNC_TASK_MONITOR_DELAY;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.fail;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jclouds.abiquo.events.handlers.BlockingEventHandler;
import org.jclouds.abiquo.events.monitor.MonitorEvent;
import org.jclouds.abiquo.features.services.MonitoringService;
import org.jclouds.abiquo.monitor.MonitorStatus;
import org.testng.annotations.Test;

import com.google.common.base.Function;

/**
 * Unit tests for the {@link BaseMonitoringService} class.
 * 
 * @author Ignasi Barrera
 */
// Since these tests block the thread, mark them as failed after the given timeout
@Test(groups = "unit", testName = "BaseMonitoringServiceTest")
public class BaseMonitoringServiceTest extends BaseInjectionTest
{
    // The maximum amount of time (in ms) to wait for each test to complete
    private static final long TEST_TIMEOUT = 10000L;

    // The polling interval used in tests (in ms)
    private static final long TEST_MONITOR_POLLING = 100L;

    // An executor used to control monitor unexpected timeouts
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected Properties buildProperties()
    {
        // Use a small monitor polling interval in tests (in ms)
        Properties props = super.buildProperties();
        props.setProperty(ASYNC_TASK_MONITOR_DELAY, String.valueOf(TEST_MONITOR_POLLING));
        return props;
    }

    public void testAllPropertiesInjected()
    {
        BaseMonitoringService service =
            (BaseMonitoringService) injector.getInstance(MonitoringService.class);

        assertNotNull(service.context);
        assertNotNull(service.scheduler);
        assertNotNull(service.pollingDelay);
        assertNotNull(service.eventBus);
    }

    @Test(expectedExceptions = NullPointerException.class)
    public void testAwaitCompletionWithNullFunction()
    {
        monitoringService().awaitCompletion(null, new Object[] {});
    }

    public void testAwaitCompletionWithoutTasks()
    {
        BaseMonitoringService service = monitoringService();

        service.awaitCompletion(new MockMonitor());
        service.awaitCompletion(new MockMonitor(), (Object[]) null);
        service.awaitCompletion(new MockMonitor(), new Object[] {});
    }

    public void testAwaitCompletion()
    {
        final BaseMonitoringService service = monitoringService();

        Future< ? > future = executor.submit(new Runnable()
        {
            @Override
            public void run()
            {
                service.awaitCompletion(new MockMonitor(), new Object());
            }
        });

        waitForResultOrTimeout(future);
    }

    public void testAwaitCompletionMultipleTasks()
    {
        final BaseMonitoringService service = monitoringService();

        Future< ? > future = executor.submit(new Runnable()
        {
            @Override
            public void run()
            {
                service.awaitCompletion(new MockMonitor(), new Object(), new Object());
            }
        });

        waitForResultOrTimeout(future);
    }

    @Test(expectedExceptions = NullPointerException.class)
    public void testMonitorWithNullCompletecondition()
    {
        monitoringService().monitor(null, (Object[]) null);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testBlockingHandlerWithoutArguments()
    {
        new BlockingEventHandler<Object>();
    }

    public void testMonitor()
    {
        final BaseMonitoringService service = monitoringService();
        final Object monitoredObject = new Object();
        final CountingHandler handler = new CountingHandler(monitoredObject);

        Future< ? > future = executor.submit(new Runnable()
        {
            @Override
            public void run()
            {
                service.register(handler);
                service.monitor(new MockMonitor(), monitoredObject);
                handler.lock();
                service.unregister(handler);
            }
        });

        waitForResultOrTimeout(future);

        assertEquals(handler.numCompletes, 1);
        assertEquals(handler.numFailures, 0);
        assertEquals(handler.numTimeouts, 0);
    }

    public void testMonitorMultipleTasks()
    {
        final BaseMonitoringService service = monitoringService();
        final Object monitoredObject1 = new Object();
        final Object monitoredObject2 = new Object();
        final CountingHandler handler = new CountingHandler(monitoredObject1, monitoredObject2);

        Future< ? > future = executor.submit(new Runnable()
        {
            @Override
            public void run()
            {
                service.register(handler);
                service.monitor(new MockMonitor(), monitoredObject1, monitoredObject2);
                handler.lock();
                service.unregister(handler);
            }
        });

        waitForResultOrTimeout(future);

        assertEquals(handler.numCompletes, 2);
        assertEquals(handler.numFailures, 0);
        assertEquals(handler.numTimeouts, 0);
    }

    public void testMonitorReachesTimeout()
    {
        final BaseMonitoringService service = monitoringService();
        final Object monitoredObject = new Object();
        final CountingHandler handler = new CountingHandler(monitoredObject);

        Future< ? > future = executor.submit(new Runnable()
        {
            @Override
            public void run()
            {
                service.register(handler);
                service.monitor(TEST_MONITOR_POLLING + 10L, TimeUnit.MILLISECONDS,
                    new MockInfiniteMonitor(), monitoredObject);
                handler.lock();
                service.unregister(handler);
            }
        });

        waitForResultOrTimeout(future);

        assertEquals(handler.numCompletes, 0);
        assertEquals(handler.numFailures, 0);
        assertEquals(handler.numTimeouts, 1);
    }

    public void testMonitorMultipleTasksReachesTimeout()
    {
        final BaseMonitoringService service = monitoringService();
        final Object monitoredObject1 = new Object();
        final Object monitoredObject2 = new Object();
        final CountingHandler handler = new CountingHandler(monitoredObject1, monitoredObject2);

        Future< ? > future = executor.submit(new Runnable()
        {
            @Override
            public void run()
            {
                service.register(handler);
                service.monitor(TEST_MONITOR_POLLING + 10L, TimeUnit.MILLISECONDS,
                    new MockInfiniteMonitor(), monitoredObject1, monitoredObject2);
                handler.lock();
                service.unregister(handler);
            }
        });

        waitForResultOrTimeout(future);

        assertEquals(handler.numCompletes, 0);
        assertEquals(handler.numFailures, 0);
        assertEquals(handler.numTimeouts, 2);
    }

    public void testDelegateToVirtualMachineMonitor()
    {
        assertNotNull(monitoringService().getVirtualMachineMonitor());
    }

    public void testDelegateToVirtualApplianceMonitor()
    {
        assertNotNull(monitoringService().getVirtualApplianceMonitor());
    }

    public void testDelegateToAsyncTaskMonitor()
    {
        assertNotNull(monitoringService().getAsyncTaskMonitor());
    }

    public void testDelegateToConversioMonitor()
    {
        assertNotNull(monitoringService().getConversionMonitor());
    }

    private BaseMonitoringService monitoringService()
    {
        return injector.getInstance(BaseMonitoringService.class);
    }

    private void waitForResultOrTimeout(final Future< ? > future)
    {
        try
        {
            future.get(TEST_TIMEOUT, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException ex)
        {
            fail("Test didn't finish within the timeout " + TEST_TIMEOUT);
        }
        catch (Exception ex)
        {
            fail("Failed to process the asynchronous task");
        }
    }

    private static class MockMonitor implements Function<Object, MonitorStatus>
    {
        private int finishAfterCount;

        public MockMonitor()
        {
            this.finishAfterCount = 1; // Simulate task completion after one refresh
        }

        @Override
        public MonitorStatus apply(final Object object)
        {
            return finishAfterCount-- <= 0 ? MonitorStatus.DONE : MonitorStatus.CONTINUE;
        }
    }

    private static class MockInfiniteMonitor implements Function<Object, MonitorStatus>
    {
        @Override
        public MonitorStatus apply(final Object object)
        {
            return MonitorStatus.CONTINUE;
        }
    }

    private static class CountingHandler extends BlockingEventHandler<Object>
    {
        public int numCompletes = 0;

        public int numFailures = 0;

        public int numTimeouts = 0;

        public CountingHandler(final Object... lockedObjects)
        {
            super(lockedObjects);
        }

        @Override
        protected void doBeforeRelease(final MonitorEvent<Object> event)
        {
            switch (event.getType())
            {
                case COMPLETED:
                    numCompletes++;
                    break;
                case FAILED:
                    numFailures++;
                    break;
                case TIMEOUT:
                    numTimeouts++;
                    break;
            }
        }
    }

}
