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

public class BundleService implements Scoped {
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
          "You forgot to set up a " + BundleServiceRunner.class.getName());
    }
    return runner.getBundleService(MortarScope.Finder.getScope(context));
  }

  public static BundleService getBundleService(MortarScope scope) {
    BundleServiceRunner runner = BundleServiceRunner.getBundleServiceRunner(scope);
    if (runner == null) {
      throw new IllegalStateException(
          "You forgot to set up a " + BundleServiceRunner.class.getName());
    }
    return runner.getBundleService(scope);
  }

  @Override public void onEnterScope(MortarScope scope) {
    // Nothing to do, we were just created and can't have any registrants yet.
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
        runner.finishLoading();
        break;
      case LOADING:
        if (!toBeLoaded.contains(bundler)) toBeLoaded.add(bundler);
        break;

      default:
        throw new AssertionError("Unexpected state " + runner.state);
    }
  }

  @Override public void onExitScope() {
    for (Bundler b : bundlers) b.onExitScope();
    runner.scopedServices.remove(scope.getPath());
  }

  /**
   * Load any {@link Bundler}s that still need it.
   *
   * @return true if we did some loading
   */
  boolean doLoading() {
    if (toBeLoaded.isEmpty()) return false;
    while (!toBeLoaded.isEmpty()) {
      Bundler next = toBeLoaded.remove(0);
      Bundle leafBundle =
          scopeBundle == null ? null : scopeBundle.getBundle(next.getMortarBundleKey());
      next.onLoad(leafBundle);
    }
    return true;
  }

  void loadFromRootBundleOnCreate() {
    scopeBundle = findScopeBundle(runner.rootBundle);
    toBeLoaded.addAll(bundlers);
  }

  private Bundle findScopeBundle(Bundle root) {
    return root == null ? null : root.getBundle(scope.getPath());
  }

  void saveToRootBundle() {
    scopeBundle = new Bundle();
    runner.rootBundle.putBundle(scope.getPath(), scopeBundle);

    for (Bundler bundler : bundlers) {
      Bundle childBundle = new Bundle();
      scopeBundle.putBundle(bundler.getMortarBundleKey(), childBundle);
      bundler.onSave(childBundle);
    }
  }
}
