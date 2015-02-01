package mortar.bundler;

import android.content.Context;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import mortar.MortarScope;
import mortar.Scoped;

import static java.lang.String.format;

public class BundleService {
  final BundleServiceRunner runner;
  final MortarScope scope;
  final Set<Bundler> bundlers = new LinkedHashSet<>();

  Bundle scopeBundle;
  private List<Bundler> toBeLoaded = new ArrayList<>();

  BundleService(BundleServiceRunner runner, MortarScope scope) {
    this.runner = runner;
    this.scope = scope;
    scopeBundle = findScopeBundle(runner.rootBundle);
  }

  public static BundleService getBundleService(Context context) {
    BundleServiceRunner runner = BundleServiceRunner.getBundleServiceRunner(context);
    if (runner == null) {
      throw new IllegalStateException(
          "You forgot to set up a " + BundleServiceRunner.class.getName() + " in your activity");
    }
    return runner.requireBundleService(MortarScope.Finder.getScope(context));
  }

  public static BundleService getBundleService(MortarScope scope) {
    BundleServiceRunner runner = BundleServiceRunner.getBundleServiceRunner(scope);
    if (runner == null) {
      throw new IllegalStateException(
          "You forgot to set up a " + BundleServiceRunner.class.getName() + " in your activity");
    }
    return runner.requireBundleService(scope);
  }

  /**
   * <p>Registers {@link Bundler} instances to have {@link Bundler#onLoad} and
   * {@link Bundler#onSave} called from {@link BundleServiceRunner#onCreate} and {@link
   * BundleServiceRunner#onSaveInstanceState},
   * respectively.
   *
   * <p>In addition to the calls from {@link BundleServiceRunner#onCreate}, {@link
   * Bundler#onLoad} is
   * triggered by registration. In most cases that initial {@link Bundler#onLoad} is made
   * synchronously during registration. However, if a {@link Bundler} is registered while an
   * ancestor scope is loading its own {@link Bundler}s, its {@link Bundler#onLoad} will be
   * deferred until all ancestor scopes have completed loading. This ensures that a {@link Bundler}
   * can assume that any dependency registered with a higher-level scope will have been initialized
   * before its own {@link Bundler#onLoad} method fires.
   */
  public void register(Bundler bundler) {
    if (bundler == null) throw new NullPointerException("Cannot register null bundler.");

    if (runner.state == BundleServiceRunner.State.SAVING) {
      throw new IllegalStateException("Cannot register during onSave");
    }

    if (bundlers.add(bundler)) bundler.onEnterScope(scope);
    String mortarBundleKey = bundler.getMortarBundleKey();
    if (mortarBundleKey == null || mortarBundleKey.trim().equals("")) {
      throw new IllegalArgumentException(format("%s has null or empty bundle key", bundler));
    }

    switch (runner.state) {
      case IDLE:
        toBeLoaded.add(bundler);
        runner.servicesToBeLoaded.add(this);
        runner.finishLoading();
        break;
      case LOADING:
        if (!toBeLoaded.contains(bundler)) {
          toBeLoaded.add(bundler);
          runner.servicesToBeLoaded.add(this);
        }
        break;

      default:
        throw new AssertionError("Unexpected state " + runner.state);
    }
  }

  void init() {
    scope.register(new Scoped() {
      @Override public void onEnterScope(MortarScope scope) {
        runner.scopedServices.put(scope.getPath(), BundleService.this);
      }

      @Override public void onExitScope() {
        for (Bundler b : bundlers) b.onExitScope();
        runner.scopedServices.remove(scope.getPath());
        runner.servicesToBeLoaded.remove(BundleService.this);
      }
    });
  }

  boolean needsLoading() {
    return !toBeLoaded.isEmpty();
  }

  void loadOne() {
    if (toBeLoaded.isEmpty()) return;

    Bundler next = toBeLoaded.remove(0);
    Bundle leafBundle =
        scopeBundle == null ? null : scopeBundle.getBundle(next.getMortarBundleKey());
    next.onLoad(leafBundle);
  }

  /** @return true if we have clients that now need to be loaded */
  boolean updateScopedBundleOnCreate(Bundle rootBundle) {
    scopeBundle = findScopeBundle(rootBundle);
    toBeLoaded.addAll(bundlers);
    return !toBeLoaded.isEmpty();
  }

  private Bundle findScopeBundle(Bundle root) {
    return root == null ? null : root.getBundle(scope.getPath());
  }

  void saveToRootBundle(Bundle rootBundle) {
    scopeBundle = rootBundle.getBundle(scope.getPath());

    if (scopeBundle == null) {
      scopeBundle = new Bundle();
      rootBundle.putBundle(scope.getPath(), scopeBundle);
    }

    for (Bundler bundler : bundlers) {
      Bundle childBundle = scopeBundle.getBundle(bundler.getMortarBundleKey());
      if (childBundle == null) {
        childBundle = new Bundle();
        scopeBundle.putBundle(bundler.getMortarBundleKey(), childBundle);
      }

      bundler.onSave(childBundle);
    }
  }
}
