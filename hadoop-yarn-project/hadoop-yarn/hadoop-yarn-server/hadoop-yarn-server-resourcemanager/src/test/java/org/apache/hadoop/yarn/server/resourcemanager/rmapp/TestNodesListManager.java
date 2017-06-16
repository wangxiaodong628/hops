/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager.rmapp;

import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

import java.util.ArrayList;

import io.hops.util.DBUtility;
import io.hops.util.RMStorageFactory;
import io.hops.util.YarnAPIStorageFactory;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.event.AbstractEvent;
import org.apache.hadoop.yarn.event.AsyncDispatcher;
import org.apache.hadoop.yarn.event.Dispatcher;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.server.resourcemanager.MockAM;
import org.apache.hadoop.yarn.server.resourcemanager.MockNM;
import org.apache.hadoop.yarn.server.resourcemanager.MockNodes;
import org.apache.hadoop.yarn.server.resourcemanager.MockRM;
import org.apache.hadoop.yarn.server.resourcemanager.NodesListManager;
import org.apache.hadoop.yarn.server.resourcemanager.NodesListManagerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.NodesListManagerEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptState;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNode;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentMatcher;

public class TestNodesListManager {
  // To hold list of application for which event was received
  ArrayList<ApplicationId> applist = new ArrayList<ApplicationId>();

  @Test(timeout = 300000)
  public void testNodeUsableEvent() throws Exception {
    Logger rootLogger = LogManager.getRootLogger();
    rootLogger.setLevel(Level.DEBUG);
    YarnConfiguration conf = new YarnConfiguration();

    RMStorageFactory.setConfiguration(conf);
    YarnAPIStorageFactory.setConfiguration(conf);
    DBUtility.InitializeDB();

    MockRM rm = new MockRM(conf) {
      @Override
      protected Dispatcher createDispatcher() {
        return getDispatcher();
      }
    };
    rm.start();
    MockNM nm1 = rm.registerNode("h1:1234", 28000);
    NodesListManager nodesListManager = rm.getNodesListManager();
    Resource clusterResource = Resource.newInstance(28000, 8);
    RMNode rmnode = MockNodes.newNodeInfo(1, clusterResource);

    // Create killing APP
    RMApp killrmApp = rm.submitApp(200);
    rm.killApp(killrmApp.getApplicationId());
    rm.waitForState(killrmApp.getApplicationId(), RMAppState.KILLED);

    // Create finish APP
    RMApp finshrmApp = rm.submitApp(2000);
    nm1.nodeHeartbeat(true);
    RMAppAttempt attempt = finshrmApp.getCurrentAppAttempt();
    MockAM am = rm.sendAMLaunched(attempt.getAppAttemptId());
    am.registerAppAttempt();
    am.unregisterAppAttempt();
    nm1.nodeHeartbeat(attempt.getAppAttemptId(), 1, ContainerState.COMPLETE);
    am.waitForState(RMAppAttemptState.FINISHED);

    // Create submitted App
    RMApp subrmApp = rm.submitApp(200);

    // Fire Event for NODE_USABLE
    nodesListManager.handle(new NodesListManagerEvent(
        NodesListManagerEventType.NODE_USABLE, rmnode));
    if (applist.size() > 0) {
      Assert.assertTrue(
          "Event based on running app expected " + subrmApp.getApplicationId(),
          applist.contains(subrmApp.getApplicationId()));
      Assert.assertFalse(
          "Event based on finish app not expected "
              + finshrmApp.getApplicationId(),
          applist.contains(finshrmApp.getApplicationId()));
      Assert.assertFalse(
          "Event based on killed app not expected "
              + killrmApp.getApplicationId(),
          applist.contains(killrmApp.getApplicationId()));
    } else {
      Assert.fail("Events received should have beeen more than 1");
    }
    applist.clear();

    // Fire Event for NODE_UNUSABLE
    nodesListManager.handle(new NodesListManagerEvent(
        NodesListManagerEventType.NODE_UNUSABLE, rmnode));
    if (applist.size() > 0) {
      Assert.assertTrue(
          "Event based on running app expected " + subrmApp.getApplicationId(),
          applist.contains(subrmApp.getApplicationId()));
      Assert.assertFalse(
          "Event based on finish app not expected "
              + finshrmApp.getApplicationId(),
          applist.contains(finshrmApp.getApplicationId()));
      Assert.assertFalse(
          "Event based on killed app not expected "
              + killrmApp.getApplicationId(),
          applist.contains(killrmApp.getApplicationId()));
    } else {
      Assert.fail("Events received should have beeen more than 1");
    }

  }

  /*
   * Create dispatcher object
   */
  private Dispatcher getDispatcher() {
    Dispatcher dispatcher = new AsyncDispatcher() {
      @SuppressWarnings({ "rawtypes", "unchecked" })
      @Override
      public EventHandler getEventHandler() {

        class EventArgMatcher extends ArgumentMatcher<AbstractEvent> {
          @Override
          public boolean matches(Object argument) {
            if (argument instanceof RMAppNodeUpdateEvent) {
              ApplicationId appid =
                  ((RMAppNodeUpdateEvent) argument).getApplicationId();
              applist.add(appid);
            }
            return false;
          }
        }

        EventHandler handler = spy(super.getEventHandler());
        doNothing().when(handler).handle(argThat(new EventArgMatcher()));
        return handler;
      }
    };
    return dispatcher;
  }

}