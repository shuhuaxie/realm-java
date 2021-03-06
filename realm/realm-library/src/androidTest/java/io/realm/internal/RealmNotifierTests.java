/*
 * Copyright 2017 Realm Inc.
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
package io.realm.internal;


import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.realm.RealmChangeListener;
import io.realm.RealmConfiguration;
import io.realm.internal.android.AndroidRealmNotifier;
import io.realm.rule.RunInLooperThread;
import io.realm.rule.RunTestInLooperThread;
import io.realm.rule.TestRealmConfigurationFactory;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class RealmNotifierTests {
    @Rule
    public final TestRealmConfigurationFactory configFactory = new TestRealmConfigurationFactory();
    @Rule
    public final RunInLooperThread looperThread = new RunInLooperThread();

    private RealmConfiguration config;
    Capabilities capabilitiesCanDeliver = new Capabilities() {
        @Override
        public boolean canDeliverNotification() {
            return true;
        }

        @Override
        public void checkCanDeliverNotification(String exceptionMessage) {
        }
    };

    @Before
    public void setUp() throws Exception {
        config = configFactory.createConfiguration();
    }

    @After
    public void tearDown() {
    }

    private SharedRealm getSharedRealm(RealmConfiguration config) {
        return SharedRealm.getInstance(config, null, true);
    }

    @Test
    @RunTestInLooperThread
    public void post() {
        RealmNotifier notifier = new AndroidRealmNotifier(null, capabilitiesCanDeliver);
        notifier.post(new Runnable() {
            @Override
            public void run() {
                looperThread.testComplete();
            }
        });
    }

    // Callback is immediately called when commitTransaction for local changes.
    @Test
    @RunTestInLooperThread
    public void addChangeListener_byLocalChanges() {
        final AtomicBoolean commitReturns = new AtomicBoolean(false);
        SharedRealm sharedRealm = getSharedRealm(looperThread.realmConfiguration);
        sharedRealm.realmNotifier.addChangeListener(sharedRealm, new RealmChangeListener<SharedRealm>() {
            @Override
            public void onChange(SharedRealm sharedRealm) {
                // Transaction has been committed in core, but commitTransaction hasn't returned in java.
                assertFalse(commitReturns.get());
                looperThread.testComplete();
                sharedRealm.close();
            }
        });
        sharedRealm.beginTransaction();
        sharedRealm.commitTransaction();
        commitReturns.set(true);
    }

    private void makeRemoteChanges(final RealmConfiguration config) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                SharedRealm sharedRealm = getSharedRealm(config);
                sharedRealm.beginTransaction();
                sharedRealm.commitTransaction();
                sharedRealm.close();
            }
        }).start();
    }

    @Test
    @RunTestInLooperThread
    public void addChangeListener_byRemoteChanges() {
        // To catch https://github.com/realm/realm-java/pull/4037 CI failure.
        // In this case, object store should not send more than 100 notifications.
        final int TIMES = 100;
        final AtomicInteger commitCounter = new AtomicInteger(0);
        final AtomicInteger listenerCounter = new AtomicInteger(0);

        looperThread.realm.close();

        SharedRealm sharedRealm = getSharedRealm(looperThread.realmConfiguration);
        looperThread.keepStrongReference.add(sharedRealm);
        sharedRealm.realmNotifier.addChangeListener(sharedRealm, new RealmChangeListener<SharedRealm>() {
            @Override
            public void onChange(SharedRealm sharedRealm) {
                int commits = commitCounter.get();
                int listenerCount = listenerCounter.addAndGet(1);
                assertEquals(commits, listenerCount);
                if (commits == TIMES) {
                    // FIXME: Enable this after https://github.com/realm/realm-object-store/pull/318 fixed
                    //sharedRealm.close();
                    looperThread.testComplete();
                } else {
                    makeRemoteChanges(looperThread.realmConfiguration);
                    commitCounter.getAndIncrement();
                }
            }
        });
        makeRemoteChanges(looperThread.realmConfiguration);
        commitCounter.getAndIncrement();
    }

    @Test
    @RunTestInLooperThread
    public void removeChangeListeners() {
        SharedRealm sharedRealm = getSharedRealm(looperThread.realmConfiguration);
        Integer dummyObserver = 1;
        looperThread.keepStrongReference.add(dummyObserver);
        sharedRealm.realmNotifier.addChangeListener(dummyObserver, new RealmChangeListener<Integer>() {
            @Override
            public void onChange(Integer dummy) {
                fail();
            }
        });
        sharedRealm.realmNotifier.addChangeListener(sharedRealm, new RealmChangeListener<SharedRealm>() {
            @Override
            public void onChange(SharedRealm sharedRealm) {
                // FIXME: Enable this after https://github.com/realm/realm-object-store/pull/318 fixed
                //sharedRealm.close();
                looperThread.testComplete();
            }
        });

        // This should only remove the listeners related with dummyObserver
        sharedRealm.realmNotifier.removeChangeListeners(dummyObserver);

        makeRemoteChanges(looperThread.realmConfiguration);
    }
}
