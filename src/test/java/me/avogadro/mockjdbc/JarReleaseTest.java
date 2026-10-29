package me.avogadro.mockjdbc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Boomi loads the jars of a Custom Library in a class loader of their own, and on Windows it can delete them (when the
 * library changes) only once nothing keeps them open: otherwise the cleanup logs "Unable to remove un-deployed jar
 * file" until the runtime is restarted, which a cloud runtime cannot be. This test uses the driver as Boomi does and
 * fails when the driver keeps a jar of its library open after the runtime has dropped the library.
 * <p>
 * The library (a jar built from the compiled driver classes, opencsv, the AspectJ runtime and a jar with
 * <code>/tables/</code>) is copied to a temporary directory. Its parent class loader plays the runtime: fake
 * <code>com.boomi.execution</code> classes compiled for this test, and slf4j with its binding. The driver is used on a
 * pooled thread that outlives the library, and the library class loader is dropped without being closed.
 * <p>
 * Usual causes of a failure: a registration in <code>DriverManager</code> inside Boomi; a value of a driver (or
 * library) class in a <code>ThreadLocal</code>, since pooled threads live as long as the runtime; a driver object kept
 * by a static of the runtime or of the JDK (system properties, logging handlers, shutdown hooks, threads started by the
 * driver); a jar resource read through a cached URL connection (<code>URL.openStream()</code>,
 * <code>getResourceAsStream</code>), which the JDK keeps open even after the class loader is gone.
 */
public final class JarReleaseTest {

	private static final String DRIVER_CLASS = "me.avogadro.mockjdbc.MockJdbcDriver";

	private static final Charset UTF_8 = Charset.forName("UTF-8");

	private static final String EXECUTION_UTIL = "package com.boomi.execution;\n"
			+ "public final class ExecutionUtil {\n"
			+ "  public static final java.util.Map<String, String> PROPERTIES =\n"
			+ "      new java.util.concurrent.ConcurrentHashMap<String, String>();\n"
			+ "  public static String getDynamicProcessProperty(String key) { return PROPERTIES.get(key); }\n"
			+ "  public static void setDynamicProcessProperty(String key, String value, boolean persist) {\n"
			+ "    if (value == null) { PROPERTIES.remove(key); } else { PROPERTIES.put(key, value); }\n"
			+ "  }\n"
			+ "  public static String getRuntimeExecutionProperty(String key) {\n"
			+ "    return \"EXECUTION_ID\".equals(key) ? \"execution-1\" : null;\n"
			+ "  }\n"
			+ "}\n";

	private static final String EXECUTION_MANAGER = "package com.boomi.execution;\n"
			+ "public final class ExecutionManager {\n"
			+ "  public static Object getCurrent() { return new Task(); }\n"
			+ "  public static final class Task {\n"
			+ "    public String getTopLevelExecutionId() { return \"execution-1\"; }\n"
			+ "  }\n"
			+ "}\n";

	/** the runtime logs at INFO level, as Boomi does */
	private static final String LOGBACK_CONFIGURATION = "<configuration>\n"
			+ "  <appender name=\"STDOUT\" class=\"ch.qos.logback.core.ConsoleAppender\">\n"
			+ "    <encoder><pattern>runtime %-5level %logger{36} - %msg%n</pattern></encoder>\n"
			+ "  </appender>\n"
			+ "  <root level=\"info\"><appender-ref ref=\"STDOUT\"/></root>\n"
			+ "</configuration>\n";

	/** fake Boomi classes and the logging configuration of the runtime */
	private File runtimeClasses;
	/** the class loader of the runtime: it outlives the library */
	private URLClassLoader runtime;
	/** the jars of the Custom Library */
	private File library;
	/** a pooled thread of the runtime: it outlives the library too */
	private ExecutorService pooledThread;

	@Before
	public void setup() throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		Assume.assumeNotNull(compiler);
		runtimeClasses = Files.createTempDirectory("mockjdbc-runtime").toFile();
		File sources = new File(runtimeClasses, "sources");
		File executionUtil = write(new File(sources, "ExecutionUtil.java"), EXECUTION_UTIL);
		File executionManager = write(new File(sources, "ExecutionManager.java"), EXECUTION_MANAGER);
		Assert.assertEquals(0, compiler.run(null, null, null, "-d", runtimeClasses.getPath(), executionUtil.getPath(),
				executionManager.getPath()));
		write(new File(runtimeClasses, "logback.xml"), LOGBACK_CONFIGURATION);
		runtime = new URLClassLoader(new URL[] { runtimeClasses.toURI().toURL(),
				IsolatedDriver.locationOf("org.slf4j.LoggerFactory"),
				IsolatedDriver.locationOf("ch.qos.logback.classic.Logger"),
				IsolatedDriver.locationOf("ch.qos.logback.core.Appender") }, IsolatedDriver.platformClassLoader());

		library = Files.createTempDirectory("mockjdbc-library").toFile();
		addToLibrary(DRIVER_CLASS, "mockjdbc.jar");
		addToLibrary("au.com.bytecode.opencsv.CSVReader", "opencsv.jar");
		addToLibrary("org.aspectj.lang.JoinPoint", "aspectjrt.jar");
		JarOutputStream tables = new JarOutputStream(new FileOutputStream(new File(library, "mockjdbc-tables.jar")));
		try {
			tables.putNextEntry(new JarEntry("tables/countries.csv"));
			tables.write("code,name\nIT,Italy\n".getBytes(UTF_8));
			tables.closeEntry();
		} finally {
			tables.close();
		}

		pooledThread = Executors.newSingleThreadExecutor();
	}

	@After
	public void cleanup() throws IOException {
		if (pooledThread != null) {
			pooledThread.shutdownNow();
		}
		if (runtime != null) {
			runtime.close();
		}
		delete(library);
		delete(runtimeClasses);
	}

	@Test
	public void testLibraryJarsCanBeDeletedAfterUseInsideBoomi() throws Exception {
		WeakReference<ClassLoader> libraryLoader = pooledThread.submit(new Callable<WeakReference<ClassLoader>>() {
			@Override
			public WeakReference<ClassLoader> call() throws Exception {
				return useLibrary();
			}
		}).get();

		Assert.assertTrue("the class loader of the library is still referenced after use: see the class comment for the"
				+ " usual causes", collected(libraryLoader));
		Assert.assertEquals("jars of the library still open after its class loader was released",
				Collections.<String> emptyList(), jarsStillOpen());
	}

	/**
	 * What the runtime does with the library: loads it in a class loader of its own, uses the driver on the current
	 * (pooled) thread, then drops the class loader without closing it.
	 */
	private WeakReference<ClassLoader> useLibrary() throws Exception {
		Map<String, String> properties = dynamicProcessProperties();
		properties.put("mockjdbc_users", "id,name\n1,John");
		properties.put("mockjdbc_report.by-id", "id,name\n2,Mary");
		properties.put("mockjdbc_calendar", "day\n29-Oct-26");
		properties.put("mockjdbc_myproc", "in_id,out_name\nignored,Mario");
		properties.put("mockjdbc_duplicates", "a,a\n1,2");
		properties.put("mockjdbc_broken", "SQLTimeoutException,SQLState\nTimed out,HYT00");

		URLClassLoader loader = new URLClassLoader(libraryJars(), runtime);
		Thread thread = Thread.currentThread();
		ClassLoader contextClassLoader = thread.getContextClassLoader();
		thread.setContextClassLoader(loader);
		try {
			Driver driver = (Driver) Class.forName(DRIVER_CLASS, true, loader).getDeclaredConstructor().newInstance();
			Connection connection = driver.connect("any", new Properties());
			try {
				useEveryFeature(connection, properties);
			} finally {
				connection.close();
			}
		} finally {
			thread.setContextClassLoader(contextClassLoader);
		}
		return new WeakReference<ClassLoader>(loader);
	}

	/** every part of the driver that reads resources, keeps state or captures values */
	private static void useEveryFeature(Connection connection, Map<String, String> properties) throws Exception {
		Assert.assertEquals("John", firstValue(connection, "SELECT * FROM users", "name"));
		Assert.assertEquals("Mary", firstValue(connection, "-- QUERY_ID: report.by-id\nSELECT * FROM users", "name"));
		Assert.assertEquals("Italy", firstValue(connection, "SELECT * FROM countries", "name"));
		Assert.assertNull(firstValue(connection, "SELECT * FROM missing_table", "name"));

		Statement statement = connection.createStatement();
		try {
			ResultSet calendar = statement.executeQuery("SELECT * FROM calendar");
			Assert.assertTrue(calendar.next());
			Assert.assertEquals(Date.valueOf("2026-10-29"), calendar.getDate("day"));
			calendar.close();
		} finally {
			statement.close();
		}

		PreparedStatement insert = connection.prepareStatement("INSERT INTO orders (id, amount) VALUES (?, ?)");
		try {
			insert.setString(1, "T-1");
			insert.setBigDecimal(2, new BigDecimal("10.5"));
			insert.executeUpdate();
		} finally {
			insert.close();
		}
		// written into a Dynamic Process Property: the driver did detect the (fake) Boomi runtime
		Assert.assertEquals("T-1,10.5", properties.get("mockjdbc_orders_params"));

		PreparedStatement event = connection.prepareStatement("INSERT INTO events (day, at, stamp) VALUES (?, ?, ?)");
		try {
			event.setDate(1, Date.valueOf("2026-10-29"));
			event.setTime(2, Time.valueOf("10:30:00"));
			event.setTimestamp(3, Timestamp.valueOf("2026-10-29 10:30:00"));
			event.executeUpdate();
		} finally {
			event.close();
		}
		Assert.assertEquals("29-Oct-26,10:30,20261029 103000.000", properties.get("mockjdbc_events_params"));

		PreparedStatement select = connection.prepareStatement("SELECT * FROM users WHERE id = ?");
		try {
			select.setInt(1, 1);
			Assert.assertEquals(1, drain(select.executeQuery()));
		} finally {
			select.close();
		}

		properties.put("mockjdbc#RESET", "yes");
		Assert.assertEquals("John", firstValue(connection, "SELECT * FROM users", "name"));
		Assert.assertEquals("", properties.get("mockjdbc#RESET"));

		DatabaseMetaData metaData = connection.getMetaData();
		drain(metaData.getTables(null, null, "%", null));
		drain(metaData.getColumns(null, null, "users", "%"));
		Assert.assertEquals(2, drain(metaData.getProcedureColumns(null, null, "myproc", "%")));

		CallableStatement call = connection.prepareCall("{call myproc(?, ?)}");
		try {
			call.setString(1, "5");
			call.registerOutParameter(2, Types.VARCHAR);
			call.execute();
			Assert.assertEquals("Mario", call.getString(2));
		} finally {
			call.close();
		}

		try {
			firstValue(connection, "SELECT * FROM duplicates", "a");
		} catch (Exception e) {
			// only the error path matters here, not how it is reported
		}

		// an error resource: the driver builds the exception, and records its message as a Dynamic Process Property.
		// The class is checked by name: it is a JDK class, so the comparison does not pin the library class loader
		try {
			firstValue(connection, "SELECT * FROM broken", "a");
			Assert.fail("the error resource must make the query throw");
		} catch (SQLException e) {
			Assert.assertEquals("java.sql.SQLTimeoutException", e.getClass().getName());
		}
		Assert.assertEquals("Timed out", properties.get("mockjdbc_broken_error#1"));
	}

	/** the value of a column in the first row (also read through getObject), null if there is no row */
	private static String firstValue(Connection connection, String sql, String column) throws Exception {
		Statement statement = connection.createStatement();
		try {
			ResultSet resultSet = statement.executeQuery(sql);
			try {
				if (!resultSet.next()) {
					return null;
				}
				resultSet.getObject(column);
				return resultSet.getString(column);
			} finally {
				resultSet.close();
			}
		} finally {
			statement.close();
		}
	}

	/** reads every value of every row, returns the number of rows */
	private static int drain(ResultSet resultSet) throws Exception {
		try {
			int columns = resultSet.getMetaData().getColumnCount();
			int rows = 0;
			while (resultSet.next()) {
				for (int i = 1; i <= columns; i++) {
					resultSet.getString(i);
					resultSet.getObject(i);
				}
				rows++;
			}
			return rows;
		} finally {
			resultSet.close();
		}
	}

	/** the Dynamic Process Properties of the fake runtime */
	@SuppressWarnings("unchecked")
	private Map<String, String> dynamicProcessProperties() throws Exception {
		return (Map<String, String>) runtime.loadClass("com.boomi.execution.ExecutionUtil").getField("PROPERTIES")
				.get(null);
	}

	private URL[] libraryJars() throws IOException {
		List<URL> urls = new ArrayList<URL>();
		for (File jar : jars()) {
			urls.add(jar.toURI().toURL());
		}
		return urls.toArray(new URL[0]);
	}

	private List<File> jars() {
		List<File> jars = new ArrayList<File>();
		File[] files = library.listFiles();
		if (files != null) {
			for (File file : files) {
				if (file.getName().endsWith(".jar")) {
					jars.add(file);
				}
			}
		}
		Collections.sort(jars);
		return jars;
	}

	/** copies into the library the jar of the given class, or jars its directory (the compiled driver classes) */
	private void addToLibrary(String className, String jarName) throws Exception {
		// a Path, unlike a File, also accepts the URI of a network share (file://server/share/...)
		Path location = Paths.get(IsolatedDriver.locationOf(className).toURI());
		File jar = new File(library, jarName);
		if (!Files.isDirectory(location)) {
			Files.copy(location, jar.toPath());
			return;
		}
		JarOutputStream out = new JarOutputStream(new FileOutputStream(jar));
		try {
			addToJar(out, location.toFile(), "");
		} finally {
			out.close();
		}
	}

	private static void addToJar(JarOutputStream out, File directory, String prefix) throws IOException {
		File[] files = directory.listFiles();
		if (files == null) {
			return;
		}
		for (File file : files) {
			if (file.isDirectory()) {
				addToJar(out, file, prefix + file.getName() + "/");
			} else {
				out.putNextEntry(new JarEntry(prefix + file.getName()));
				Files.copy(file.toPath(), out);
				out.closeEntry();
			}
		}
	}

	private static boolean collected(WeakReference<ClassLoader> reference) throws InterruptedException {
		for (int i = 0; i < 100 && reference.get() != null; i++) {
			System.gc();
			Thread.sleep(20);
		}
		return reference.get() == null;
	}

	/**
	 * The jars of the library that this process keeps open: listed from /proc/self/fd on Linux, found by deleting them
	 * on Windows (an open file cannot be deleted there). Elsewhere it cannot be checked, and the list is empty. The JDK
	 * closes the jars of a collected class loader shortly after a GC, so the check is repeated for a while.
	 */
	@SuppressWarnings("deprecation")
	private List<String> jarsStillOpen() throws Exception {
		List<String> open = openJars();
		for (int i = 0; i < 100 && !open.isEmpty(); i++) {
			System.gc();
			System.runFinalization(); // Java 8 closes zip files in finalizers
			Thread.sleep(20);
			open = openJars();
		}
		return open;
	}

	private List<String> openJars() throws IOException {
		List<String> open = new ArrayList<String>();
		List<File> jars = jars();
		Path descriptors = Paths.get("/proc/self/fd");
		if (Files.isDirectory(descriptors)) {
			List<String> paths = new ArrayList<String>();
			for (File jar : jars) {
				paths.add(jar.toPath().toRealPath().toString());
			}
			DirectoryStream<Path> stream = Files.newDirectoryStream(descriptors);
			try {
				for (Path descriptor : stream) {
					try {
						int index = paths.indexOf(Files.readSymbolicLink(descriptor).toString());
						if (index >= 0 && !open.contains(jars.get(index).getName())) {
							open.add(jars.get(index).getName());
						}
					} catch (IOException e) {
						// descriptor closed in the meantime
					}
				}
			} finally {
				stream.close();
			}
		} else if (System.getProperty("os.name", "").startsWith("Windows")) {
			for (File jar : jars) {
				if (!jar.delete()) {
					open.add(jar.getName());
				}
			}
		}
		Collections.sort(open);
		return open;
	}

	private static File write(File file, String content) throws IOException {
		file.getParentFile().mkdirs();
		Files.write(file.toPath(), content.getBytes(UTF_8));
		return file;
	}

	private static void delete(File file) {
		if (file == null) {
			return;
		}
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				delete(child);
			}
		}
		file.delete();
	}
}
