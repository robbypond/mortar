/*
 * Copyright 2013 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mortar;

import android.os.Bundle;
import dagger.Module;
import dagger.ObjectGraph;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import mortar.bundler.BundleService;
import mortar.bundler.BundleServiceRunner;
import mortar.bundler.Bundler;
import mortar.dagger1support.Blueprint;
import mortar.dagger1support.ObjectGraphService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static mortar.bundler.BundleServiceRunner.getBundleServiceRunner;
import static mortar.dagger1support.ObjectGraphService.requireChild;
import static org.fest.assertions.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

// Robolectric allows us to use Bundles.
@RunWith(RobolectricTestRunner.class) @Config(manifest = Config.NONE)
public class IntertwinedBundleServiceAndObjectGraphServiceTest {
  private static class MyBundler implements Bundler {
    final String name;

    MortarScope registered;
    boolean loaded;
    Bundle lastLoaded;
    Bundle lastSaved;
    boolean destroyed;

    public MyBundler(String name) {
      this.name = name;
    }

    void reset() {
      lastSaved = lastLoaded = null;
      loaded = destroyed = false;
    }

    @Override public String getMortarBundleKey() {
      return name;
    }

    @Override public void onEnterScope(MortarScope scope) {
      this.registered = scope;
    }

    @Override public void onLoad(Bundle savedInstanceState) {
      loaded = true;
      lastLoaded = savedInstanceState;
      if (savedInstanceState != null) {
        assertThat(savedInstanceState.get("key")).isEqualTo(name);
      }
    }

    @Override public void onSave(Bundle outState) {
      lastSaved = outState;
      outState.putString("key", name);
    }

    @Override public void onExitScope() {
      destroyed = true;
    }
  }

  static class MyBlueprint implements Blueprint {
    private final String name;

    MyBlueprint(String name) {
      this.name = name;
    }

    @Override public String getMortarScopeName() {
      return name;
    }

    @Override public Object getDaggerModule() {
      return new MyModule();
    }
  }

  @Module static class MyModule {
  }

  @Mock Scoped scoped;

  private MortarScope activityScope;

  @Before public void setUp() {
    initMocks(this);
    resetScope();
  }

  private void resetScope() {
    MortarScope root = ObjectGraphService.createRootScope(ObjectGraph.create(new MyModule()));
    activityScope = ObjectGraphService.requireActivityScope(root, new MyBlueprint("activity"));
  }

  @Test(expected = IllegalArgumentException.class) public void nonNullKeyRequired() {
    BundleService.getBundleService(activityScope).register(mock(Bundler.class));
  }

  @Test(expected = IllegalArgumentException.class) public void nonEmptyKeyRequired() {
    Bundler mock = mock(Bundler.class);
    when(mock.getMortarBundleKey()).thenReturn("");
    BundleService.getBundleService(activityScope).register(mock);
  }

  @Test public void lifeCycle() {
    doLifecycleTest(activityScope);
  }

  @Test public void childLifeCycle() {
    doLifecycleTest(requireChild(activityScope, new MyBlueprint("child")));
  }

  private void doLifecycleTest(MortarScope registerScope) {
    MyBundler able = new MyBundler("able");
    MyBundler baker = new MyBundler("baker");

    registerScope.register(scoped);
    BundleService.getBundleService(registerScope).register(able);
    BundleService.getBundleService(registerScope).register(baker);

    // onEnterScope is called immediately.
    verify(scoped).onEnterScope(registerScope);
    assertThat(able.registered).isSameAs(registerScope);
    assertThat(baker.registered).isSameAs(registerScope);

    // Load is called immediately.
    assertThat(able.loaded).isTrue();
    assertThat(able.lastLoaded).isNull();
    able.reset();
    assertThat(baker.loaded).isTrue();
    assertThat(baker.lastLoaded).isNull();
    baker.reset();

    getBundleServiceRunner(activityScope).onCreate(null);
    // Create loads all registrants.
    assertThat(able.loaded).isTrue();
    assertThat(able.lastLoaded).isNull();
    able.reset();
    assertThat(baker.loaded).isTrue();
    assertThat(baker.lastLoaded).isNull();
    baker.reset();

    // When we save, the bundler gets its own bundle to write to.
    Bundle saved = new Bundle();
    getBundleServiceRunner(activityScope).onSaveInstanceState(saved);
    assertThat(able.lastSaved).isNotNull();
    assertThat(baker.lastSaved).isNotNull();
    assertThat(able.lastSaved).isNotSameAs(baker.lastSaved);

    // If the bundler is re-registered, it loads again.
    able.lastLoaded = null;
    BundleService.getBundleService(registerScope).register(able);
    assertThat(able.lastLoaded).isSameAs(able.lastSaved);

    // A new activity instance appears
    able.reset();
    baker.reset();
    getBundleServiceRunner(activityScope).onSaveInstanceState(saved);
    Bundle fromNewActivity = new Bundle(saved);

    getBundleServiceRunner(activityScope).onCreate(fromNewActivity);
    assertThat(able.lastLoaded).isNotNull();

    verifyNoMoreInteractions(scoped);

    activityScope.destroy();
    assertThat(able.destroyed).isTrue();
    verify(scoped).onExitScope();
  }

  class FauxActivity {
    final MyBundler rootBundler = new MyBundler("core");

    MortarScope childScope;
    MyBundler childBundler = new MyBundler("child");

    void create(Bundle bundle) {
      getBundleServiceRunner(activityScope).onCreate(bundle);
      BundleService.getBundleService(activityScope).register(rootBundler);
      childScope = requireChild(activityScope, new MyBlueprint("child"));
      BundleService.getBundleService(childScope).register(childBundler);
    }
  }

  @Test public void onRegisteredIsDebounced() {
    activityScope.register(scoped);
    activityScope.register(scoped);
    verify(scoped, times(1)).onEnterScope(activityScope);
  }

  @Test public void childInfoSurvivesProcessDeath() {
    FauxActivity activity = new FauxActivity();
    activity.create(null);
    Bundle bundle = new Bundle();
    getBundleServiceRunner(activityScope).onSaveInstanceState(bundle);

    // Process death: new copy of the bundle, new scope and activity instances
    bundle = new Bundle(bundle);
    resetScope();
    activity = new FauxActivity();
    activity.create(bundle);
    assertThat(activity.rootBundler.lastLoaded).isNotNull();
    assertThat(activity.childBundler.lastLoaded).isNotNull();
  }

  @Test public void handlesRegisterFromOnLoadBeforeCreate() {
    final MyBundler bundler = new MyBundler("inner");

    BundleService.getBundleService(activityScope).register(new MyBundler("outer") {
      @Override public void onLoad(Bundle savedInstanceState) {
        super.onLoad(savedInstanceState);
        BundleService.getBundleService(activityScope).register(bundler);
      }
    });

    // The recursive register call loaded immediately.
    assertThat(bundler.loaded).isTrue();

    // And it was registered: a create call reloads it.
    bundler.reset();
    getBundleServiceRunner(activityScope).onCreate(null);

    assertThat(bundler.loaded).isTrue();
  }

  @Test public void handlesRegisterFromOnLoadAfterCreate() {
    final MyBundler bundler = new MyBundler("inner");

    BundleServiceRunner bundleServiceRunner = getBundleServiceRunner(activityScope);
    bundleServiceRunner.onCreate(null);

    final BundleService bundleService = BundleService.getBundleService(activityScope);
    bundleService.register(new MyBundler("outer") {
      @Override public void onLoad(Bundle savedInstanceState) {
        bundleService.register(bundler);
      }
    });

    // The recursive register call loaded immediately.
    assertThat(bundler.loaded).isTrue();

    // And it was registered: the next create call reloads it.
    bundler.reset();
    Bundle b = new Bundle();
    bundleServiceRunner.onSaveInstanceState(b);
    bundleServiceRunner.onCreate(b);

    assertThat(bundler.loaded).isNotNull();
  }

  @Test public void cannotRegisterDuringOnSave() {
    final MyBundler bundler = new MyBundler("inner");
    final AtomicBoolean caught = new AtomicBoolean(false);

    BundleServiceRunner bundleServiceRunner = getBundleServiceRunner(activityScope);
    bundleServiceRunner.onCreate(null);

    final BundleService bundleService = BundleService.getBundleService(activityScope);
    bundleService.register(new MyBundler("outer") {
      @Override public void onSave(Bundle outState) {
        super.onSave(outState);
        try {
          bundleService.register(bundler);
        } catch (IllegalStateException e) {
          caught.set(true);
        }
      }
    });
    assertThat(bundler.loaded).isFalse();

    Bundle bundle = new Bundle();
    bundleServiceRunner.onSaveInstanceState(bundle);
    assertThat(caught.get()).isTrue();
  }

  @Test public void handlesReregistrationBeforeCreate() {
    final AtomicInteger i = new AtomicInteger(0);

    final BundleService bundleService = BundleService.getBundleService(activityScope);
    bundleService.register(new Bundler() {
      @Override public String getMortarBundleKey() {
        return "key";
      }

      @Override public void onEnterScope(MortarScope scope) {
      }

      @Override public void onLoad(Bundle savedInstanceState) {
        if (i.incrementAndGet() < 1) bundleService.register(this);
      }

      @Override public void onSave(Bundle outState) {
        throw new UnsupportedOperationException();
      }

      @Override public void onExitScope() {
        throw new UnsupportedOperationException();
      }
    });

    Bundle b = new Bundle();
    getBundleServiceRunner(activityScope).onCreate(b);

    assertThat(i.get()).isEqualTo(2);
  }

  @Test public void handlesReregistrationAfterCreate() {
    Bundle b = new Bundle();
    getBundleServiceRunner(activityScope).onCreate(b);

    final AtomicInteger i = new AtomicInteger(0);

    final BundleService bundleService = BundleService.getBundleService(activityScope);
    bundleService.register(new Bundler() {
      @Override public String getMortarBundleKey() {
        return "key";
      }

      @Override public void onEnterScope(MortarScope scope) {
      }

      @Override public void onLoad(Bundle savedInstanceState) {
        if (i.incrementAndGet() < 1) bundleService.register(this);
      }

      @Override public void onSave(Bundle outState) {
        throw new UnsupportedOperationException();
      }

      @Override public void onExitScope() {
        throw new UnsupportedOperationException();
      }
    });

    assertThat(i.get()).isEqualTo(1);
  }

  @Test public void handleDestroyFromEarlyLoad() {
    final AtomicInteger loads = new AtomicInteger(0);
    final AtomicInteger destroys = new AtomicInteger(0);

    class Destroyer implements Bundler {
      @Override public String getMortarBundleKey() {
        return "k";
      }

      @Override public void onEnterScope(MortarScope scope) {
      }

      @Override public void onLoad(Bundle savedInstanceState) {
        if (loads.incrementAndGet() > 2) {
          activityScope.destroy();
        }
      }

      @Override public void onSave(Bundle outState) {
        throw new UnsupportedOperationException();
      }

      @Override public void onExitScope() {
        destroys.incrementAndGet();
      }
    }

    BundleService bundleService = BundleService.getBundleService(activityScope);
    bundleService.register(new Destroyer());
    bundleService.register(new Destroyer());

    Bundle b = new Bundle();
    getBundleServiceRunner(activityScope).onCreate(b);

    assertThat(loads.get()).isEqualTo(3);
    assertThat(destroys.get()).isEqualTo(2);
  }

  @Test public void handlesDestroyFromOnSave() {
    final AtomicInteger saves = new AtomicInteger(0);
    final AtomicInteger destroys = new AtomicInteger(0);

    class Destroyer implements Bundler {
      @Override public String getMortarBundleKey() {
        return "k";
      }

      @Override public void onEnterScope(MortarScope scope) {
      }

      @Override public void onLoad(Bundle savedInstanceState) {
      }

      @Override public void onSave(Bundle outState) {
        saves.incrementAndGet();
        activityScope.destroy();
      }

      @Override public void onExitScope() {
        destroys.incrementAndGet();
      }
    }

    BundleService bundleService = BundleService.getBundleService(activityScope);
    bundleService.register(new Destroyer());
    bundleService.register(new Destroyer());

    Bundle b = new Bundle();
    BundleServiceRunner bundleServiceRunner = getBundleServiceRunner(activityScope);
    bundleServiceRunner.onCreate(b);
    bundleServiceRunner.onSaveInstanceState(b);

    assertThat(saves.get()).isEqualTo(1);
    assertThat(destroys.get()).isEqualTo(2);
  }

  @Test(expected = IllegalStateException.class) public void cannotOnCreateDestroyed() {
    activityScope.destroy();
    getBundleServiceRunner(activityScope).onCreate(null);
  }

  @Test(expected = IllegalStateException.class) public void cannotOnSaveDestroyed() {
    activityScope.destroy();
    getBundleServiceRunner(activityScope).onSaveInstanceState(new Bundle());
  }

  @Test public void deliversStateToBundlerWhenRegisterAfterOnCreate() {
    MyBundler bundler = new MyBundler("bundler");
    Bundle bundlerState = new Bundle();
    bundler.onSave(bundlerState);
    Bundle scopeState = new Bundle();
    scopeState.putBundle(bundler.name, bundlerState);

    getBundleServiceRunner(activityScope).onCreate(scopeState);
    BundleService.getBundleService(activityScope).register(bundler);

    assertThat(bundler.lastLoaded).isSameAs(bundlerState);
  }

  @Test public void deliversChildScopeStateWhenRequireChildDuringRegisterAfterOnCreate() {
    final MyBundler childScopeBundler = new MyBundler("childScopeBundler");
    final MyBlueprint childScopeBlueprint = new MyBlueprint("ChildScope");

    // When that bundler is loaded, it creates a child scope and register a bundler on it.
    MyBundler activityScopeBundler = new MyBundler("activityScopeBundler") {
      @Override public void onLoad(Bundle savedInstanceState) {
        super.onLoad(savedInstanceState);
        MortarScope childScope = requireChild(activityScope, childScopeBlueprint);
        BundleService.getBundleService(childScope).register(childScopeBundler);
      }
    };

    Bundle childScopeBundlerState = new Bundle();
    childScopeBundler.onSave(childScopeBundlerState);

    Bundle childScopeState = new Bundle();
    childScopeState.putBundle(childScopeBundler.name, childScopeBundlerState);

    Bundle activityScopeBundlerState = new Bundle();
    activityScopeBundler.onSave(activityScopeBundlerState);

    Bundle activityScopeState = new Bundle();
    activityScopeState.putBundle(childScopeBlueprint.getMortarScopeName(), childScopeState);
    activityScopeState.putBundle(activityScopeBundler.name, activityScopeBundlerState);

    // activityScope doesn't have any child scope or Bundler yet.
    getBundleServiceRunner(activityScope).onCreate(activityScopeState);

    // Loads activityScopeBundler which require a child on activityScope and add a bundler to it.
    BundleService.getBundleService(activityScope).register(activityScopeBundler);

    assertThat(childScopeBundler.lastLoaded).isSameAs(childScopeBundlerState);
  }

  /** <a href="https://github.com/square/mortar/issues/46">Issue 46</a> */
  @Test public void registerWithDescendantScopesCreatedDuringParentOnCreateGetOnlyOneOnLoadCall() {
    final MyBundler childBundler = new MyBundler("child");
    final MyBundler grandChildBundler = new MyBundler("grandChild");

    final AtomicBoolean spawnSubScope = new AtomicBoolean(false);

    BundleService.getBundleService(activityScope).register(new MyBundler("outer") {
      @Override public void onLoad(Bundle savedInstanceState) {
        if (spawnSubScope.get()) {
          MortarScope childScope = requireChild(activityScope, new MyBlueprint("child scope"));
          BundleService.getBundleService(childScope).register(childBundler);
          // 1. We're in the middle of loading, so the usual register > load call doesn't happen.
          assertThat(childBundler.loaded).isFalse();

          MortarScope grandchildScope =
              requireChild(childScope, new MyBlueprint("grandchild scope"));
          BundleService.getBundleService(grandchildScope).register(grandChildBundler);
          assertThat(grandChildBundler.loaded).isFalse();
        }
      }
    });

    spawnSubScope.set(true);
    getBundleServiceRunner(activityScope).onCreate(null);

    // 2. But load is called before the onCreate chain ends.
    assertThat(childBundler.loaded).isTrue();
    assertThat(grandChildBundler.loaded).isTrue();
  }

  @Test public void peerBundlersLoadSynchronouslyButThoseInChildScopesShouldWait() {
    final MyBundler peerBundler = new MyBundler("bro");
    final MyBundler childBundler = new MyBundler("child");
    final MyBundler grandchildBundler = new MyBundler("grandchild");

    final MortarScope childScope = requireChild(activityScope, new MyBlueprint("child scope"));
    final MortarScope grandChildScope =
        requireChild(childScope, new MyBlueprint("grandchild scope"));

    BundleService.getBundleService(activityScope).register(new MyBundler("outer") {
      @Override public void onLoad(Bundle savedInstanceState) {
        BundleService.getBundleService(activityScope).register(peerBundler);
        assertThat(peerBundler.loaded).isTrue();

        BundleService.getBundleService(childScope).register(childBundler);
        assertThat(childBundler.loaded).isFalse();

        BundleService.getBundleService(grandChildScope).register(grandchildBundler);
        assertThat(grandchildBundler.loaded).isFalse();
      }
    });

    assertThat(childBundler.loaded).isTrue();
    assertThat(grandchildBundler.loaded).isTrue();
  }

  @Test
  public void peerBundlersLoadSynchronouslyButThoseInChildScopesShouldWaitEvenInAFreshScope() {
    final MyBundler peerBundler = new MyBundler("bro");
    final MyBundler childBundler = new MyBundler("child");
    final MyBundler grandchildBundler = new MyBundler("grandchild");

    BundleService.getBundleService(activityScope).register(new MyBundler("outer") {
      @Override public void onLoad(Bundle savedInstanceState) {
        BundleService.getBundleService(activityScope).register(peerBundler);
        assertThat(peerBundler.loaded).isTrue();

        MortarScope childScope = requireChild(activityScope, new MyBlueprint("child scope"));
        BundleService.getBundleService(childScope).register(childBundler);
        assertThat(childBundler.loaded).isFalse();

        MortarScope grandchildScope = requireChild(childScope, new MyBlueprint("grandchild scope"));
        BundleService.getBundleService(grandchildScope).register(grandchildBundler);
        assertThat(grandchildBundler.loaded).isFalse();
      }
    });

    assertThat(childBundler.loaded).isTrue();
    assertThat(grandchildBundler.loaded).isTrue();
  }

  /**
   * Happened during first naive fix of
   * <a href="https://github.com/square/mortar/issues/46">Issue 46</a>.
   */
  @Test public void descendantScopesCreatedDuringParentOnLoadAreNotStuckInLoadingMode() {
    final MyBlueprint subscopeBlueprint = new MyBlueprint("subscope");

    BundleService.getBundleService(activityScope).register(new MyBundler("outer") {
      @Override public void onLoad(Bundle savedInstanceState) {
        MortarScope child = requireChild(activityScope, subscopeBlueprint);
        requireChild(child, subscopeBlueprint);
      }
    });

    getBundleServiceRunner(activityScope).onSaveInstanceState(new Bundle());
    // No crash? Victoire!
  }

  /**
   * https://github.com/square/mortar/issues/77
   */
  @Test public void childCreatedDuringMyLoadDoesLoadingAfterMe() {
    getBundleServiceRunner(activityScope).onCreate(null);
    final MyBundler childBundler = new MyBundler("childBundler");

    BundleService.getBundleService(activityScope).register(new MyBundler("root") {
      @Override public void onLoad(Bundle savedInstanceState) {
        super.onLoad(savedInstanceState);

        MortarScope childScope = requireChild(activityScope, new MyBlueprint("childScope"));
        BundleService.getBundleService(childScope).register(childBundler);
        assertThat(childBundler.loaded).isFalse();
      }
    });

    assertThat(childBundler.loaded).isTrue();
  }

  /**
   * https://github.com/square/mortar/issues/77
   */
  @Test public void bundlersInChildScopesLoadAfterBundlersOnParent() {
    getBundleServiceRunner(activityScope).onCreate(null);
    final MyBundler service = new MyBundler("service");

    final MyBundler childBundler = new MyBundler("childBundler") {
      @Override public void onLoad(Bundle savedInstanceState) {
        super.onLoad(savedInstanceState);
        assertThat(service.loaded).isTrue();
      }
    };

    BundleService.getBundleService(activityScope).register(new MyBundler("root") {
      @Override public void onLoad(Bundle savedInstanceState) {
        MortarScope childScope = requireChild(activityScope, new MyBlueprint("childScope"));
        BundleService.getBundleService(childScope).register(childBundler);
        assertThat(childBundler.loaded).isFalse();

        BundleService.getBundleService(activityScope).register(service);
        assertThat(service.loaded).isTrue();
      }
    });
    assertThat(childBundler.loaded).isTrue();
  }
}
