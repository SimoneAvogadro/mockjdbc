package me.avogadro.mockjdbc.boomi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import me.avogadro.mockjdbc.MockJdbcDriver;

/**
 * Table folders chosen by the Boomi process with the Dynamic Process Property <code>mockjdbc#FOLDER</code>.
 */
public final class BoomiTableFoldersTest {

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private File root;
	private Connection connection;

	@Before
	public void setup() throws Exception {
		Class.forName(MockJdbcDriver.class.getCanonicalName());
		MockJdbcDriver.reset();
		FakeExecutionUtil.clear();
		FakeExecutionManager.current = null;
		BoomiExecutionUtil.lookup(FakeExecutionUtil.class.getName());
		root = temporaryFolder.newFolder("mockdb");
		connection = DriverManager.getConnection("any");
	}

	@After
	public void restoreRealDetection() {
		MockJdbcDriver.reset();
		BoomiExecutionUtil.lookup(BoomiExecutionUtil.EXECUTION_UTIL_CLASS);
	}

	private static void write(File folder, String fileName, byte[] content) throws IOException {
		folder.mkdirs();
		OutputStream out = new FileOutputStream(new File(folder, fileName));
		try {
			out.write(content);
		} finally {
			out.close();
		}
	}

	private static void write(File folder, String fileName, String content) throws IOException {
		write(folder, fileName, content.getBytes(Charset.forName("UTF-8")));
	}

	private String select(String table) throws SQLException {
		ResultSet resultSet = connection.createStatement().executeQuery("SELECT * FROM " + table);
		return resultSet.next() ? resultSet.getString("name") : null;
	}

	@Test
	public void testFolderFromDynamicProcessProperty() throws Exception {
		write(new File(root, "T_001"), "users.csv", "name\ntest");
		write(new File(root, "_common"), "countries.csv", "name\nItaly");
		MockJdbcDriver.setTableRoot(root);
		FakeExecutionUtil.PROPERTIES.put("mockjdbc#FOLDER", " T_001 ; _common ");

		Assert.assertEquals("test", select("users"));
		Assert.assertEquals("Italy", select("countries"));
	}

	@Test
	public void testFolderPropertyInLowerCase() throws Exception {
		write(root, "users.csv", "name\nlower");
		FakeExecutionUtil.PROPERTIES.put("mockjdbc#folder", root.getAbsolutePath());

		Assert.assertEquals("lower", select("users"));
	}

	@Test
	public void testPropertyWinsOverJavaFolders() throws Exception {
		write(new File(root, "A"), "users.csv", "name\njava");
		write(new File(root, "B"), "users.csv", "name\nproperty");
		MockJdbcDriver.setTableRoot(root);
		MockJdbcDriver.setTableFolders("A");

		Assert.assertEquals("java", select("users"));
		FakeExecutionUtil.PROPERTIES.put("mockjdbc#FOLDER", "B");
		Assert.assertEquals("property", select("users"));
		// a blank property counts as not set
		FakeExecutionUtil.PROPERTIES.put("mockjdbc#FOLDER", " ");
		Assert.assertEquals("java", select("users"));
	}

	@Test
	public void testFolderChangedDuringTheExecution() throws Exception {
		write(new File(root, "T_001"), "users.csv", "name\nfirst case");
		write(new File(root, "T_002"), "users.csv", "name\nsecond case");
		MockJdbcDriver.setTableRoot(root);

		FakeExecutionUtil.PROPERTIES.put("mockjdbc#FOLDER", "T_001");
		Assert.assertEquals("first case", select("users"));
		FakeExecutionUtil.PROPERTIES.put("mockjdbc#FOLDER", "T_002");
		Assert.assertEquals("second case", select("users"));
	}

	@Test
	public void testDynamicProcessPropertyWinsOverFolder() throws Exception {
		write(root, "users.csv", "name\nfolder");
		write(root, "users#2.csv", "name\nfolder second");
		FakeExecutionUtil.PROPERTIES.put("mockjdbc#FOLDER", root.getAbsolutePath());
		FakeExecutionUtil.PROPERTIES.put("mockjdbc_users", "name\nproperty");

		Assert.assertEquals("property", select("users"));
		Assert.assertEquals("folder second", select("users"));
	}

	@Test
	public void testMissingFolderIsAnError() throws Exception {
		FakeExecutionUtil.PROPERTIES.put("mockjdbc#FOLDER", new File(root, "missing").getAbsolutePath());
		try {
			select("users");
			Assert.fail("missing folder accepted");
		} catch (SQLException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("missing"));
		}
	}

	@Test
	public void testCharsetFromDynamicProcessProperty() throws Exception {
		write(root, "users.csv", "name\ncaffè\n".getBytes(Charset.forName("ISO-8859-1")));
		FakeExecutionUtil.PROPERTIES.put("mockjdbc#FOLDER", root.getAbsolutePath());
		FakeExecutionUtil.PROPERTIES.put("mockjdbc#CHARSET", "ISO-8859-1");

		Assert.assertEquals("caffè", select("users"));

		FakeExecutionUtil.PROPERTIES.put("mockjdbc#CHARSET", "no-such-charset");
		try {
			select("users");
			Assert.fail("unknown charset accepted");
		} catch (SQLException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("no-such-charset"));
		}
	}

	@Test
	public void testParametersWithAtInDynamicProcessProperty() throws Exception {
		FakeExecutionUtil.PROPERTIES.put("mockjdbc_users@smith", "name\nSmith");

		PreparedStatement statement = connection.prepareStatement("SELECT * FROM users WHERE surname = ?");
		statement.setString(1, "Smith");
		ResultSet resultSet = statement.executeQuery();
		Assert.assertTrue(resultSet.next());
		Assert.assertEquals("Smith", resultSet.getString("name"));
	}

	@Test
	public void testWritesStillGoToDynamicProcessProperties() throws Exception {
		write(root, "users.csv", "name\nfolder");
		FakeExecutionUtil.PROPERTIES.put("mockjdbc#FOLDER", root.getAbsolutePath());

		PreparedStatement insert = connection.prepareStatement("INSERT INTO users (id) VALUES (?)");
		insert.setInt(1, 7);
		insert.executeUpdate();
		Assert.assertEquals("7", FakeExecutionUtil.PROPERTIES.get("mockjdbc_users_params"));
		Assert.assertEquals(1, root.list().length);
	}
}
