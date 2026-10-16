package me.avogadro.mockjdbc.resources;

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
import java.sql.Statement;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import me.avogadro.mockjdbc.MockJdbcDriver;

/**
 * Tables read from folders on the filesystem: same names and variants as the in-memory resources.
 */
public final class TableFoldersTest {

	private static final Charset UTF_8 = Charset.forName("UTF-8");

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private File root;
	private Connection connection;

	@Before
	public void setup() throws Exception {
		Class.forName(MockJdbcDriver.class.getCanonicalName());
		MockJdbcDriver.reset();
		System.clearProperty(TableLookup.ROOT_SYSTEM_PROPERTY);
		root = temporaryFolder.newFolder("mockdb");
		connection = DriverManager.getConnection("any");
	}

	@After
	public void cleanup() {
		MockJdbcDriver.reset();
		System.clearProperty(TableLookup.ROOT_SYSTEM_PROPERTY);
	}

	static File write(File folder, String fileName, String content) throws IOException {
		return write(folder, fileName, content.getBytes(UTF_8));
	}

	static File write(File folder, String fileName, byte[] content) throws IOException {
		folder.mkdirs();
		File file = new File(folder, fileName);
		OutputStream out = new FileOutputStream(file);
		try {
			out.write(content);
		} finally {
			out.close();
		}
		return file;
	}

	private static String first(ResultSet resultSet) throws SQLException {
		return resultSet.next() ? resultSet.getString("name") : null;
	}

	private String select(String table) throws SQLException {
		return first(connection.createStatement().executeQuery("SELECT * FROM " + table));
	}

	private String selectWith(String table, Object... params) throws SQLException {
		PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table + " WHERE a = ? AND b = ?");
		for (int i = 0; i < params.length; i++) {
			statement.setObject(i + 1, params[i]);
		}
		return first(statement.executeQuery());
	}

	@Test
	public void testAbsoluteFolder() throws Exception {
		write(root, "users.csv", "name\nfrom folder");
		MockJdbcDriver.setTableFolders(root.getAbsolutePath());

		Assert.assertEquals("from folder", select("users"));
	}

	@Test
	public void testRelativeFoldersInOrder() throws Exception {
		write(new File(root, "T_001"), "users.csv", "name\ntest users");
		write(new File(root, "_common"), "users.csv", "name\ncommon users");
		write(new File(root, "_common"), "countries.csv", "name\nItaly");
		MockJdbcDriver.setTableRoot(root);
		MockJdbcDriver.setTableFolders("T_001", "_common");

		Assert.assertEquals("test users", select("users"));
		Assert.assertEquals("Italy", select("countries"));
	}

	@Test
	public void testRootAloneIsReadWhenNoFolderIsSet() throws Exception {
		write(root, "users.csv", "name\nroot");
		MockJdbcDriver.setTableRoot(root);

		Assert.assertEquals("root", select("users"));
	}

	@Test
	public void testRootFromSystemProperty() throws Exception {
		write(new File(root, "T_001"), "users.csv", "name\nproperty");
		System.setProperty(TableLookup.ROOT_SYSTEM_PROPERTY, root.getAbsolutePath());
		MockJdbcDriver.setTableFolders("T_001");

		Assert.assertEquals("property", select("users"));
	}

	@Test
	public void testJavaRootWinsOverSystemProperty() throws Exception {
		File other = temporaryFolder.newFolder("other");
		write(new File(root, "T_001"), "users.csv", "name\njava");
		write(new File(other, "T_001"), "users.csv", "name\nproperty");
		System.setProperty(TableLookup.ROOT_SYSTEM_PROPERTY, other.getAbsolutePath());
		MockJdbcDriver.setTableRoot(root);
		MockJdbcDriver.setTableFolders("T_001");

		Assert.assertEquals("java", select("users"));
	}

	@Test
	public void testConnectionUrlWinsOverJavaRoot() throws Exception {
		File other = temporaryFolder.newFolder("other");
		write(root, "users.csv", "name\nurl");
		write(other, "users.csv", "name\njava");
		MockJdbcDriver.setTableRoot(other);

		connection = DriverManager.getConnection("jdbc::mock::" + root.getAbsolutePath());
		Assert.assertEquals("url", select("users"));
		Assert.assertEquals("jdbc::mock::" + root.getAbsolutePath(), connection.getMetaData().getURL());

		// relative folders are resolved against the folder of the URL
		write(new File(root, "T_001"), "users.csv", "name\nurl test");
		MockJdbcDriver.setTableFolders("T_001");
		Assert.assertEquals("url test", select("users"));
	}

	@Test
	public void testMissingUrlFolderIsAnError() throws Exception {
		try {
			DriverManager.getConnection("jdbc::mock::" + new File(root, "missing").getAbsolutePath());
			Assert.fail("missing folder accepted");
		} catch (SQLException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("missing"));
		}
	}

	@Test
	public void testMissingFolderIsAnError() throws Exception {
		MockJdbcDriver.setTableRoot(root);
		MockJdbcDriver.setTableFolders("T_404");
		try {
			select("users");
			Assert.fail("missing folder accepted");
		} catch (SQLException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("T_404"));
		}
	}

	@Test
	public void testRelativeFolderWithoutRootIsAnError() throws Exception {
		MockJdbcDriver.setTableFolders("T_001");
		try {
			select("users");
			Assert.fail("relative folder without root accepted");
		} catch (SQLException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("no root"));
		}
	}

	@Test
	public void testNamesAreCaseInsensitive() throws Exception {
		write(root, "Users.CSV", "name\nmixed case");
		MockJdbcDriver.setTableFolders(root.getAbsolutePath());

		Assert.assertEquals("mixed case", select("USERS"));
		Assert.assertEquals("mixed case", select("users"));
	}

	@Test
	public void testSameResourceTwiceIsAnError() throws Exception {
		write(root, "users.csv", "name\na");
		write(root, "USERS.csv", "name\nb");
		// case-insensitive filesystems (Windows, macOS) cannot hold both files
		Assume.assumeTrue(root.list().length == 2);
		MockJdbcDriver.setTableFolders(root.getAbsolutePath());
		try {
			select("users");
			Assert.fail("ambiguous files accepted");
		} catch (SQLException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("same resource"));
		}
	}

	@Test
	public void testQuestionMarkAndAtAreTheSameResource() throws Exception {
		try {
			write(root, "users?x.csv", "name\na");
		} catch (IOException e) {
			Assume.assumeNoException("? not allowed in file names", e);
		}
		write(root, "users@x.csv", "name\nb");
		MockJdbcDriver.setTableFolders(root.getAbsolutePath());
		try {
			select("users");
			Assert.fail("ambiguous files accepted");
		} catch (SQLException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("same resource"));
		}
	}

	@Test
	public void testOccurrences() throws Exception {
		write(root, "users.csv", "name\nany");
		write(root, "users#2.csv", "name\nsecond");
		MockJdbcDriver.setTableFolders(root.getAbsolutePath());

		Assert.assertEquals("any", select("users"));
		Assert.assertEquals("second", select("users"));
		Assert.assertEquals("any", select("users"));
	}

	@Test
	public void testParameters() throws Exception {
		write(root, "users.csv", "name\nany");
		write(root, "users@Smith,34.csv", "name\nJohn Smith");
		// one counter per name, whatever the parameters: the third query on users is #3
		write(root, "users@Smith,35#3.csv", "name\nthird Smith");
		MockJdbcDriver.setTableFolders(root.getAbsolutePath());

		Assert.assertEquals("John Smith", selectWith("users", "smith", 34));
		Assert.assertEquals("any", selectWith("users", "Smith", 35));
		Assert.assertEquals("third Smith", selectWith("users", "Smith", 35));
		Assert.assertEquals("any", selectWith("users", "Rossi", 1));
	}

	@Test
	public void testParametersWithAtAndEscapes() throws Exception {
		write(root, "users@mario@example.com,1.csv", "name\nMario");
		write(root, "events@10%3A30,1.csv", "name\nmorning");
		MockJdbcDriver.setTableFolders(root.getAbsolutePath());

		Assert.assertEquals("Mario", selectWith("users", "mario@example.com", 1));
		Assert.assertEquals("morning", selectWith("events", "10:30", 1));
	}

	@Test
	public void testTestCaseAndProcedure() throws Exception {
		write(root, "T_014a.csv", "name\ntest case");
		write(root, "my_proc.csv", "name\nprocedure");
		MockJdbcDriver.setTableFolders(root.getAbsolutePath());

		Assert.assertEquals("test case",
				first(connection.createStatement().executeQuery("-- TESTCASE: T_014a\nSELECT * FROM users")));
		Assert.assertEquals("procedure", first(connection.createStatement().executeQuery("{call my_proc(1)}")));
	}

	@Test
	public void testStepsFromFolder() throws Exception {
		write(root, "##STEP0.csv", "name\nfirst step");
		write(root, "##step1.csv", "5\n");
		MockJdbcDriver.setTableFolders(root.getAbsolutePath());

		Assert.assertEquals("first step", first(connection.createStatement().executeQuery("anything")));
		Assert.assertEquals(5, connection.prepareStatement("UPDATE users SET a = ?").executeUpdate());
		// the step resource is captured as the step parameters, as in memory
		Assert.assertNotNull(MockJdbcDriver.getInMemoryTableResource("##STEP1_PARAMS"));
	}

	@Test
	public void testWritesAreStillCapturedInMemory() throws Exception {
		write(root, "users.csv", "name\nany");
		MockJdbcDriver.setTableFolders(root.getAbsolutePath());

		PreparedStatement insert = connection.prepareStatement("INSERT INTO users (id, name) VALUES (?, ?)");
		insert.setInt(1, 3);
		insert.setString(2, "Anna");
		insert.executeUpdate();
		Assert.assertEquals("3,Anna", MockJdbcDriver.getInMemoryTableResource("users_PARAMS"));
		Assert.assertEquals(1, root.list().length);
	}

	@Test
	public void testColumnsFromFolder() throws Exception {
		write(root, "payments.csv", "id|integer, amount|double, note\n");
		MockJdbcDriver.setTableFolders(root.getAbsolutePath());

		ResultSet columns = connection.getMetaData().getColumns(null, null, "PAYMENTS", null);
		Assert.assertTrue(columns.next());
		Assert.assertEquals("id", columns.getString("COLUMN_NAME"));
		Assert.assertTrue(columns.next());
		Assert.assertTrue(columns.next());
		Assert.assertFalse(columns.next());
	}

	@Test
	public void testMoreSpecificNameWinsOverSource() throws Exception {
		write(root, "users#2.csv", "name\nfolder second");
		MockJdbcDriver.addInMemoryTableResource("users", "name\nmemory");
		MockJdbcDriver.setTableFolders(root.getAbsolutePath());

		Assert.assertEquals("memory", select("users"));
		Assert.assertEquals("folder second", select("users"));
		Assert.assertEquals("memory", select("users"));
	}

	@Test
	public void testMemoryWinsOverFolderAndFolderOverRegisteredFile() throws Exception {
		File registered = write(temporaryFolder.newFolder("registered"), "anything.csv", "name\nregistered");
		write(root, "users.csv", "name\nfolder");
		write(root, "orders.csv", "name\nfolder orders");
		MockJdbcDriver.setTableFolders(root.getAbsolutePath());
		MockJdbcDriver.addTableResource("users", registered);
		MockJdbcDriver.addTableResource("orders", registered);
		MockJdbcDriver.addTableResource("cities", registered);
		MockJdbcDriver.addInMemoryTableResource("orders", "name\nmemory orders");

		Assert.assertEquals("folder", select("users"));
		Assert.assertEquals("memory orders", select("orders"));
		Assert.assertEquals("registered", select("cities"));
	}

	@Test
	public void testMemoryWinsOverRegisteredFile() throws Exception {
		File registered = write(root, "file.csv", "name\nfile");
		MockJdbcDriver.addTableResource("users", registered);
		MockJdbcDriver.addInMemoryTableResource("users#2", "name\nmemory second");

		Assert.assertEquals("file", select("users"));
		Assert.assertEquals("memory second", select("users"));

		MockJdbcDriver.addInMemoryTableResource("users", "name\nmemory");
		Assert.assertEquals("memory", select("users"));
	}

	@Test
	public void testRegisteredFileWithVariants() throws Exception {
		MockJdbcDriver.addTableResource("users", write(root, "a.csv", "name\nany"));
		MockJdbcDriver.addTableResource("users@Smith,1", write(root, "b.csv", "name\nSmith"));

		Assert.assertEquals("Smith", selectWith("users", "Smith", 1));
		Assert.assertEquals("any", selectWith("users", "Rossi", 1));
	}

	@Test
	public void testUtf8WithByteOrderMark() throws Exception {
		byte[] bom = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
		byte[] text = "name, city\ncaffè, €\n".getBytes(UTF_8);
		byte[] content = new byte[bom.length + text.length];
		System.arraycopy(bom, 0, content, 0, bom.length);
		System.arraycopy(text, 0, content, bom.length, text.length);
		write(root, "users.csv", content);
		MockJdbcDriver.setTableFolders(root.getAbsolutePath());

		ResultSet resultSet = connection.createStatement().executeQuery("SELECT * FROM users");
		Assert.assertTrue(resultSet.next());
		Assert.assertEquals("caffè", resultSet.getString("name"));
		Assert.assertEquals("€", resultSet.getString("city"));
		// the byte order mark is not part of the first column name
		Assert.assertEquals("name", resultSet.getMetaData().getColumnName(1));
	}

	@Test
	public void testConfiguredCharset() throws Exception {
		Charset latin1 = Charset.forName("ISO-8859-1");
		write(root, "users.csv", "name\ncaffè\n".getBytes(latin1));
		MockJdbcDriver.setTableFolders(root.getAbsolutePath());
		MockJdbcDriver.setTableCharset(latin1);

		Assert.assertEquals("caffè", select("users"));
	}

	@Test
	public void testFilesAddedAndChangedWhileRunning() throws Exception {
		MockJdbcDriver.setTableFolders(root.getAbsolutePath());
		Assert.assertNull(select("users"));

		write(root, "users.csv", "name\nadded");
		Assert.assertEquals("added", select("users"));

		write(root, "users.csv", "name\nchanged");
		Assert.assertEquals("changed", select("users"));
	}

	@Test
	public void testResetClearsTheConfiguration() throws Exception {
		write(root, "users.csv", "name\nfolder");
		MockJdbcDriver.setTableRoot(root);
		MockJdbcDriver.setTableFolders(".");
		MockJdbcDriver.setTableCharset(UTF_8);

		MockJdbcDriver.reset();

		Assert.assertNull(MockJdbcDriver.getTableRoot());
		Assert.assertTrue(MockJdbcDriver.getTableFolders().isEmpty());
		Assert.assertNull(MockJdbcDriver.getTableCharset());
		Assert.assertNull(select("users"));
	}

	@Test
	public void testInMemoryAtEqualsQuestionMark() throws Exception {
		MockJdbcDriver.addInMemoryTableResource("users@Smith,1", "name\nSmith");

		Assert.assertEquals("Smith", selectWith("users", "Smith", 1));
		Assert.assertEquals("name\nSmith", MockJdbcDriver.getInMemoryTableResource("USERS?smith,1"));
	}

	@Test
	public void testClasspathDatabaseIsCaseInsensitiveAndKeptAfterReset() throws Exception {
		connection = DriverManager.getConnection("jdbc::mock::casedb");
		Assert.assertEquals("mixed", select("mixed_case"));
		Assert.assertEquals("mixed", select("MIXED_CASE"));

		MockJdbcDriver.reset();
		Assert.assertEquals("mixed", select("mixed_case"));
	}

	@Test
	public void testStatementCreatedBeforeTheFolderIsSet() throws Exception {
		write(root, "users.csv", "name\nlate");
		Statement statement = connection.createStatement();
		MockJdbcDriver.setTableFolders(root.getAbsolutePath());

		Assert.assertEquals("late", first(statement.executeQuery("SELECT * FROM users")));
	}
}
