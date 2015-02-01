package mortar.bundler;

import android.content.Context;
import android.os.Bundle;
import java.util.LinkedHashMap;
import java.util.Map;
import mortar.MortarScope;
import mortar.Presenter;

public class BundleServiceRunner {
  public static BundleServiceRunner getBundleServiceRunner(Context context) {
    return (BundleServiceRunner) context.getSystemService(BundleServiceRunner.class.getName());
  }

  public static BundleServiceRunner getBundleServiceRunner(MortarScope scope) {
    return scope.getService(BundleServiceRunner.class.getName());
  }

  public static void inNewScope(MortarScope.Builder builder) {
    builder.withService(BundleServiceRunner.class.getName(), new BundleServiceRunner());
  }

  final Map<String, BundleService> scopedServices = new LinkedHashMap<>();

  Bundle rootBundle;

  enum State {
    IDLE, LOADING, SAVING
  }

  State state = State.IDLE;

  public BundleService getBundleService(MortarScope scope) {
    // TODO(ray) assert that the given scope is a child of the one this service runner occupies.
    // Maybe give MortarScope.Builder a getPath method, and
    // if (!scope.getPath().beginsWith(myPath + MortarScope.DIVIDER)) {
    //   throw new IllegalArgumentException()
    // }

    BundleService service = scopedServices.get(scope.getPath());
    if (service == null) {
      service = new BundleService(this, scope);
      scopedServices.put(scope.getPath(), service);
      scope.register(service);
    }
    return service;
  }

  /**
   * To be called from the host {@link android.app.Activity}'s {@link
   * android.app.Activity#onCreate}. Calls the registered {@link Bundler}'s {@link Bundler#onLoad}
   * methods. To avoid redundant calls to {@link Presenter#onLoad} it's best to call this before
   * {@link android.app.Activity#setContentView}.
   */
  public void onCreate(Bundle savedInstanceState) {
    rootBundle = savedInstanceState;

    for (Map.Entry<String, BundleService> entry : scopedServices.entrySet()) {
      BundleService scopedService = entry.getValue();
      scopedService.loadFromRootBundleOnCreate();
    }
    finishLoading();
  }

  /**
   * To be called from the host {@link android.app.Activity}'s {@link
   * android.app.Activity#onSaveInstanceState}. Calls the registrants' {@link Bundler#onSave}
   * methods.
   */
  public void onSaveInstanceState(Bundle outState) {
    if (state != State.IDLE) {
      throw new IllegalStateException("Cannot handle onSaveInstanceState while " + state);
    }
    rootBundle = outState;

    state = State.SAVING;
    for (Map.Entry<String, BundleService> entry : scopedServices.entrySet()) {
      entry.getValue().saveToRootBundle();
    }
    state = State.IDLE;
  }

  void finishLoading() {
    if (state != State.IDLE) throw new AssertionError("Unexpected state " + state);
    state = State.LOADING;

    boolean someoneLoaded;
    do {
      someoneLoaded = false;
      for (BundleService scopedService : scopedServices.values()) {
        someoneLoaded |= scopedService.doLoading();
      }
    } while (someoneLoaded);

    state = State.IDLE;
  }
}
