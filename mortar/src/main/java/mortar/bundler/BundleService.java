package mortar.bundler;

import android.content.Context;
import mortar.MortarScope;

public interface BundleService {
  class Finder {
    public static BundleService get(Context context) {
      BundleServiceRunner runner = BundleServiceRunner.get(context);
      if (runner == null) {
        throw new IllegalStateException(
            "You forgot to set up a " + BundleServiceRunner.class.getName());
      }
      return runner.getService(MortarScope.Finder.get(context));
    }
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
  void register(Bundler s);
}
