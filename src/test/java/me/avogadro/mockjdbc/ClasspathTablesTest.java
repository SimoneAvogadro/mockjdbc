package me.avogadro.mockjdbc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tables found on the classpath as /tables/&lt;name&gt;.csv, in a directory or inside a jar.
 */
public final class ClasspathTablesTest {

	private File directory;
	private File jar;

	@Before
	public void setup() throws IOException {
		directory = Files.createTempDirectory("mockjdbc-tables-dir").toFile();
		File tables = new File(directory, "tables");
		Assert.assertTrue(tables.mkdir());
		Writer writer = new FileWriter(new File(tables, "dirtable.csv"));
		writer.write("name\nfrom directory\n");
		writer.close();

		jar = File.createTempFile("mockjdbc-tables", ".jar");
		JarOutputStream out = new JarOutputStream(new FileOutputStream(jar));
		out.putNextEntry(new JarEntry("tables/jartable.csv"));
		out.write("name\nfrom jar\n".getBytes(Charset.defaultCharset()));
		out.closeEntry();
		out.putNextEntry(new JarEntry("tables/jartable#2.csv"));
		out.write("name\nsecond from jar\n".getBytes(Charset.defaultCharset()));
		out.closeEntry();
		out.putNextEntry(new JarEntry("tables/jartable@x.csv"));
		out.write("name\nx from jar\n".getBytes(Charset.defaultCharset()));
		out.closeEntry();
		out.close();
	}

	@After
	public void cleanup() {
		new File(new File(directory, "tables"), "dirtable.csv").delete();
		new File(directory, "tables").delete();
		directory.delete();
		jar.delete();
	}

	private static String firstName(Connection connection, String table) throws Exception {
		ResultSet resultSet = connection.createStatement().executeQuery("SELECT * FROM " + table);
		return resultSet.next() ? resultSet.getString("name") : null;
	}

	/** open file descriptors of this process on the given file, -1 if they cannot be listed (not Linux) */
	private static int openHandles(File file) throws IOException {
		Path fds = Paths.get("/proc/self/fd");
		if (!Files.isDirectory(fds)) {
			return -1;
		}
		int count = 0;
		DirectoryStream<Path> stream = Files.newDirectoryStream(fds);
		try {
			for (Path fd : stream) {
				try {
					if (Files.readSymbolicLink(fd).toString().equals(file.getAbsolutePath())) {
						count++;
					}
				} catch (IOException e) {
					// descriptor closed in the meantime
				}
			}
		} finally {
			stream.close();
		}
		return count;
	}

	@Test
	public void testOccurrencesAndParametersInAJar() throws Exception {
		URLClassLoader loader = IsolatedDriver.newClassLoader(jar.toURI().toURL());
		try {
			Class<?> driverClass = Class.forName("me.avogadro.mockjdbc.MockJdbcDriver", true, loader);
			Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
			Connection connection = driver.connect("any", new Properties());

			Assert.assertEquals("from jar", firstName(connection, "jartable"));
			Assert.assertEquals("second from jar", firstName(connection, "jartable"));

			PreparedStatement statement = connection.prepareStatement("SELECT * FROM jartable WHERE a = ?");
			statement.setString(1, "x");
			ResultSet resultSet = statement.executeQuery();
			Assert.assertTrue(resultSet.next());
			Assert.assertEquals("x from jar", resultSet.getString("name"));

			driverClass.getMethod("deregister").invoke(null);
		} finally {
			loader.close();
		}
	}

	@Test
	public void testTablesInADirectoryAndInAJar() throws Exception {
		URLClassLoader loader = IsolatedDriver.newClassLoader(directory.toURI().toURL(), jar.toURI().toURL());
		try {
			Class<?> driverClass = Class.forName("me.avogadro.mockjdbc.MockJdbcDriver", true, loader);
			Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
			Connection connection = driver.connect("any", new Properties());

			Assert.assertEquals("from directory", firstName(connection, "dirtable"));
			Assert.assertEquals("from jar", firstName(connection, "JarTable"));

			driverClass.getMethod("deregister").invoke(null);
		} finally {
			loader.close();
		}

		// once its class loader is closed the jar must not stay open: reading the table must not cache it
		int handles = openHandles(jar);
		if (handles >= 0) {
			Assert.assertEquals("open handles on the jar with /tables/", 0, handles);
		}
		Assert.assertTrue("the jar with /tables/ can be deleted", jar.delete());
	}
}
