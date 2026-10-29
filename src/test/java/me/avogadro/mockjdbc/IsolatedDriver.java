package me.avogadro.mockjdbc;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Loads the driver and its dependencies again in a class loader of their own, as a runtime such as Boomi does.
 */
final class IsolatedDriver {

	private IsolatedDriver() {
	}

	/** location (directory or jar) of the classes of the given class, null if not available */
	static URL locationOf(String className) {
		try {
			return Class.forName(className).getProtectionDomain().getCodeSource().getLocation();
		} catch (ClassNotFoundException e) {
			return null;
		}
	}

	/**
	 * @param extraUrls more classpath entries for the new class loader (e.g. directories or jars with /tables/)
	 * @return a class loader with the driver, opencsv, slf4j, the AspectJ runtime (when present) and the extra URLs
	 */
	static URLClassLoader newClassLoader(URL... extraUrls) {
		List<URL> urls = new ArrayList<URL>();
		for (String className : new String[] { "me.avogadro.mockjdbc.MockJdbcDriver", "au.com.bytecode.opencsv.CSVReader",
				"org.slf4j.LoggerFactory", "org.aspectj.lang.JoinPoint" }) {
			URL location = locationOf(className);
			if (location != null && !urls.contains(location)) {
				urls.add(location);
			}
		}
		urls.addAll(Arrays.asList(extraUrls));
		// parent = platform class loader: the driver classes are loaded again, isolated from the test's copy
		return new URLClassLoader(urls.toArray(new URL[0]), platformClassLoader());
	}

	/** the parent of an isolated class loader: sees the JDK (java.sql included) but not the classpath */
	static ClassLoader platformClassLoader() {
		try {
			return (ClassLoader) ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null);
		} catch (Exception e) {
			return null; // Java 8: bootstrap class loader, which also provides java.sql
		}
	}
}
