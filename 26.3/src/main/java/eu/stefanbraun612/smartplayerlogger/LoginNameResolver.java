package eu.stefanbraun612.smartplayerlogger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Finds the player name on a login-phase packet listener without compiling against its
 * internals (field names and the profile type have changed between versions): looks for the
 * authenticated GameProfile first, then the plain requested-username string.
 */
public final class LoginNameResolver {
	private LoginNameResolver() {
	}

	public static String resolve(Object loginListener) {
		String requested = null;
		for (Class<?> c = loginListener.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				try {
					if (f.getType().getSimpleName().equals("GameProfile")) {
						f.setAccessible(true);
						Object profile = f.get(loginListener);
						String n = profileName(profile);
						if (n != null) {
							return n;
						}
					} else if (f.getType() == String.class && f.getName().toLowerCase().contains("username")) {
						f.setAccessible(true);
						Object v = f.get(loginListener);
						if (v instanceof String s && !s.isBlank()) {
							requested = s;
						}
					}
				} catch (ReflectiveOperationException | RuntimeException ignored) {
					// try the next field
				}
			}
		}
		return requested;
	}

	private static String profileName(Object profile) {
		if (profile == null) {
			return null;
		}
		for (String m : new String[] {"name", "getName"}) {
			try {
				Method method = profile.getClass().getMethod(m);
				Object v = method.invoke(profile);
				if (v instanceof String s && !s.isBlank()) {
					return s;
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// try the other accessor
			}
		}
		return null;
	}
}
