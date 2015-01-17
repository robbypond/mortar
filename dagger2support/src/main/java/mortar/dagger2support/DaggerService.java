package mortar.dagger2support;

import android.content.Context;
import java.lang.reflect.Method;
import mortar.MortarScope;

public class DaggerService {

  /**
   * Caller is required to know the type of the component for this context.
   */
  @SuppressWarnings("unchecked") //
  public static <T> T getDaggerComponent(Context context) {
    return (T) context.getSystemService(DaggerService.class.getName());
  }

  /**
   * Magic method that creates a component with its dependencies set, by reflection. Relies on
   * Dagger2 naming conventions.
   */
  public static <T> void createComponentForScope(MortarScope.Builder scopeBuilder,
      Class<T> componentClass, Object... dependencies) {
    scopeBuilder.withService(DaggerService.class.getName(),
        createComponent(componentClass, dependencies));
  }

  private static <T> T createComponent(Class<T> componentClass, Object... dependencies) {
    String fqn = componentClass.getName();

    String packageName = componentClass.getPackage().getName();
    // Accounts for inner classes, ie MyApplication$Component
    String simpleName = fqn.substring(packageName.length() + 1);
    String generatedName = (packageName + ".Dagger_" + simpleName).replace('$', '_');

    try {
      Class<?> generatedClass = Class.forName(generatedName);
      Object builder = generatedClass.getMethod("builder").invoke(null);

      for (Method method : builder.getClass().getMethods()) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 1) {
          Class<?> dependencyClass = params[0];
          for (Object dependency : dependencies) {
            if (dependencyClass.isAssignableFrom(dependency.getClass())) {
              method.invoke(builder, dependency);
              break;
            }
          }
        }
      }
      //noinspection unchecked
      return (T) builder.getClass().getMethod("build").invoke(builder);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
