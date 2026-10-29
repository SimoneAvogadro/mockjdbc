package me.avogadro.mockjdbc.statement.impl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTimeoutException;
import java.util.HashMap;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import me.avogadro.mockjdbc.MockJdbcDriver;

/**
 * An error resource is a mock whose CSV header starts with the name of a java.sql exception: the query that finds it
 * throws that exception, with the message, SQLState and error code of the first data row.
 */
public final class InjectedErrorTest {

	private static final String[] SUPPORTED_CLASSES = { "SQLException", "SQLNonTransientException", "SQLDataException",
			"SQLFeatureNotSupportedException", "SQLIntegrityConstraintViolationException",
			"SQLInvalidAuthorizationSpecException", "SQLNonTransientConnectionException", "SQLSyntaxErrorException",
			"SQLTransientException", "SQLTimeoutException", "SQLTransactionRollbackException",
			"SQLTransientConnectionException", "SQLRecoverableException" };

	private static final String DUPLICATE_ENTRY = "SQLIntegrityConstraintViolationException,SQLState,ErrorCode\n"
			+ "Duplicate entry,23000,1062";

	private Connection connection;

	@Before
	public void setup() throws ClassNotFoundException, SQLException {
		Class.forName(MockJdbcDriver.class.getCanonicalName());
		MockJdbcDriver.reset();
		connection = DriverManager.getConnection("any");
	}

	/** runs the query, which must throw an SQLException */
	private SQLException thrownBy(String sql) {
		try {
			connection.createStatement().executeQuery(sql);
		} catch (SQLException e) {
			return e;
		}
		throw new AssertionError("no exception thrown by: " + sql);
	}

	private int rows(ResultSet resultSet) throws SQLException {
		int rows = 0;
		while (resultSet.next()) {
			rows++;
		}
		return rows;
	}

	@Test
	public void testSelectThrowsTheDeclaredClass() {
		MockJdbcDriver.addInMemoryTableResource("orders", DUPLICATE_ENTRY);

		SQLException error = thrownBy("SELECT * FROM orders");

		Assert.assertSame(SQLIntegrityConstraintViolationException.class, error.getClass());
		Assert.assertEquals("Duplicate entry", error.getMessage());
		Assert.assertEquals("23000", error.getSQLState());
		Assert.assertEquals(1062, error.getErrorCode());
	}

	@Test
	public void testOccurrenceTargetsOneExecution() throws SQLException {
		MockJdbcDriver.addInMemoryTableResource("orders#1", DUPLICATE_ENTRY);
		MockJdbcDriver.addInMemoryTableResource("orders", "id\n1");

		thrownBy("SELECT * FROM orders");
		ResultSet retry = connection.createStatement().executeQuery("SELECT * FROM orders");

		Assert.assertEquals(1, rows(retry));
	}

	@Test
	public void testParamsTargetOneValue() throws SQLException {
		MockJdbcDriver.addInMemoryTableResource("orders?42", DUPLICATE_ENTRY);
		MockJdbcDriver.addInMemoryTableResource("orders", "id\n1\n2");

		PreparedStatement statement = connection.prepareStatement("SELECT * FROM orders WHERE id = ?");
		statement.setInt(1, 42);
		try {
			statement.executeQuery();
			Assert.fail("bound to 42 the query must throw");
		} catch (SQLIntegrityConstraintViolationException expected) {
			Assert.assertEquals("Duplicate entry", expected.getMessage());
		}
		statement.setInt(1, 7);

		Assert.assertEquals(2, rows(statement.executeQuery()));
	}

	@Test
	public void testQueryIdAndStep() {
		MockJdbcDriver.addInMemoryTableResource("q.fail", DUPLICATE_ENTRY);

		Assert.assertSame(SQLIntegrityConstraintViolationException.class,
				thrownBy("-- QUERY_ID: q.fail\nSELECT 1 FROM t").getClass());

		MockJdbcDriver.reset();
		MockJdbcDriver.addInMemoryTableResource(0, DUPLICATE_ENTRY);

		Assert.assertSame(SQLIntegrityConstraintViolationException.class, thrownBy("SELECT 1 FROM t").getClass());
	}

	@Test
	public void testHeaderOnlyGivesDefaultMessage() {
		MockJdbcDriver.addInMemoryTableResource("orders", "SQLTimeoutException");

		SQLException error = thrownBy("SELECT * FROM orders");

		Assert.assertSame(SQLTimeoutException.class, error.getClass());
		Assert.assertEquals("Error injected by mockjdbc for 'orders#1'", error.getMessage());
		Assert.assertNull(error.getSQLState());
		Assert.assertEquals(0, error.getErrorCode());
	}

	@Test
	public void testStepDefaultMessageNamesTheStep() {
		MockJdbcDriver.addInMemoryTableResource(0, "SQLTimeoutException");

		Assert.assertEquals("Error injected by mockjdbc for '##STEP0'", thrownBy("SELECT 1 FROM t").getMessage());
	}

	@Test
	public void testEmptyValuesGiveTheDefaults() {
		MockJdbcDriver.addInMemoryTableResource("orders", "SQLException,SQLState,ErrorCode\n,,");

		SQLException error = thrownBy("SELECT * FROM orders");

		Assert.assertEquals("Error injected by mockjdbc for 'orders#1'", error.getMessage());
		Assert.assertNull(error.getSQLState());
		Assert.assertEquals(0, error.getErrorCode());
	}

	@Test
	public void testOnlyTheFirstRowCounts() {
		MockJdbcDriver.addInMemoryTableResource("orders", "SQLException,ErrorCode\nfirst,1\nsecond,2");

		SQLException error = thrownBy("SELECT * FROM orders");

		Assert.assertEquals("first", error.getMessage());
		Assert.assertEquals(1, error.getErrorCode());
	}

	@Test
	public void testColumnOrderAndTypeDoNotMatter() {
		MockJdbcDriver.addInMemoryTableResource("orders", "SQLException,ErrorCode|integer,SQLState\nboom,5,HY000");

		SQLException error = thrownBy("SELECT * FROM orders");

		Assert.assertEquals("boom", error.getMessage());
		Assert.assertEquals(5, error.getErrorCode());
		Assert.assertEquals("HY000", error.getSQLState());
	}

	@Test
	public void testHeaderCaseAndSpaces() {
		MockJdbcDriver.addInMemoryTableResource("orders", " sqlexception , sqlstate \nboom,HY000");

		SQLException error = thrownBy("SELECT * FROM orders");

		Assert.assertSame(SQLException.class, error.getClass());
		Assert.assertEquals("boom", error.getMessage());
		Assert.assertEquals("HY000", error.getSQLState());
	}

	@Test
	public void testQuotedMessage() {
		MockJdbcDriver.addInMemoryTableResource("orders",
				"SQLException,SQLState\n\"Duplicate entry 'C-1', key 'PRIMARY'\",23000");

		SQLException error = thrownBy("SELECT * FROM orders");

		Assert.assertEquals("Duplicate entry 'C-1', key 'PRIMARY'", error.getMessage());
		Assert.assertEquals("23000", error.getSQLState());
	}

	@Test
	public void testUnknownColumnIsReported() {
		MockJdbcDriver.addInMemoryTableResource("orders", "SQLException,Severity\nboom,high");

		SQLException error = thrownBy("SELECT * FROM orders");

		Assert.assertSame(SQLException.class, error.getClass());
		Assert.assertTrue(error.getMessage(), error.getMessage().startsWith("Invalid error resource 'orders#1'"));
		Assert.assertTrue(error.getMessage(), error.getMessage().contains("Severity"));
	}

	@Test
	public void testNonNumericErrorCodeIsReported() {
		MockJdbcDriver.addInMemoryTableResource("orders", "SQLException,ErrorCode\nboom,abc");

		SQLException error = thrownBy("SELECT * FROM orders");

		Assert.assertTrue(error.getMessage(), error.getMessage().startsWith("Invalid error resource 'orders#1'"));
		Assert.assertTrue(error.getMessage(), error.getMessage().contains("ErrorCode"));
	}

	@Test
	public void testDataColumnsNamedLikeErrorsAreData() throws SQLException {
		MockJdbcDriver.addInMemoryTableResource("orders", "error_code,error_message\n5,boom");

		ResultSet resultSet = connection.createStatement().executeQuery("SELECT * FROM orders");

		Assert.assertTrue(resultSet.next());
		Assert.assertEquals("boom", resultSet.getString("error_message"));
	}

	@Test
	public void testUnsupportedClassNameIsData() throws SQLException {
		MockJdbcDriver.addInMemoryTableResource("orders", "SQLWarning,x\nw,1");

		ResultSet resultSet = connection.createStatement().executeQuery("SELECT * FROM orders");

		Assert.assertTrue(resultSet.next());
		Assert.assertEquals("w", resultSet.getString(1));
	}

	@Test
	public void testAllSupportedClasses() {
		Assert.assertEquals(13, SUPPORTED_CLASSES.length);
		for (String name : SUPPORTED_CLASSES) {
			MockJdbcDriver.addInMemoryTableResource("orders", name + ",SQLState,ErrorCode\nboom,S1000,7");

			SQLException error = thrownBy("SELECT * FROM orders");

			Assert.assertEquals(name, error.getClass().getSimpleName());
			Assert.assertEquals("java.sql", error.getClass().getPackage().getName());
			Assert.assertEquals(name, "boom", error.getMessage());
			Assert.assertEquals(name, "S1000", error.getSQLState());
			Assert.assertEquals(name, 7, error.getErrorCode());
		}
	}

	@Test
	public void testSchemaQualifiedFallsBackToLastPart() {
		MockJdbcDriver.addInMemoryTableResource("orders", DUPLICATE_ENTRY);

		SQLException error = thrownBy("SELECT * FROM dbo.orders");

		Assert.assertEquals("Duplicate entry", error.getMessage());
		Assert.assertEquals("Duplicate entry", MockJdbcDriver.getInMemoryTableResource("dbo.orders_ERROR#1"));
	}

	@Test
	public void testErrorIsRecorded() {
		MockJdbcDriver.addInMemoryTableResource("orders", DUPLICATE_ENTRY);

		thrownBy("SELECT * FROM orders");

		Assert.assertEquals("Duplicate entry", MockJdbcDriver.getInMemoryTableResource("orders_ERROR"));
		Assert.assertEquals("Duplicate entry", MockJdbcDriver.getInMemoryTableResource("orders_ERROR#1"));
	}

	@Test
	public void testSecondFailureIsRecordedOnItsOccurrence() {
		MockJdbcDriver.addInMemoryTableResource("orders", "SQLException");

		thrownBy("SELECT * FROM orders");
		thrownBy("SELECT * FROM orders");

		// orders_ERROR is the latest message, orders_ERROR#n the one of the n-th execution
		Assert.assertEquals("Error injected by mockjdbc for 'orders#1'",
				MockJdbcDriver.getInMemoryTableResource("orders_ERROR#1"));
		Assert.assertEquals("Error injected by mockjdbc for 'orders#2'",
				MockJdbcDriver.getInMemoryTableResource("orders_ERROR#2"));
		Assert.assertEquals("Error injected by mockjdbc for 'orders#2'",
				MockJdbcDriver.getInMemoryTableResource("orders_ERROR"));
	}

	@Test
	public void testStepErrorIsRecordedWithoutOccurrence() {
		MockJdbcDriver.addInMemoryTableResource(0, DUPLICATE_ENTRY);

		thrownBy("SELECT 1 FROM t");

		Assert.assertEquals("Duplicate entry", MockJdbcDriver.getInMemoryTableResource("##STEP0_ERROR"));
		Assert.assertNull(MockJdbcDriver.getInMemoryTableResource("##STEP0_ERROR#1"));
	}

	@Test
	public void testInvalidResourceIsRecordedToo() {
		MockJdbcDriver.addInMemoryTableResource("orders", "SQLException,Severity\nboom,high");

		SQLException error = thrownBy("SELECT * FROM orders");

		Assert.assertEquals(error.getMessage(), MockJdbcDriver.getInMemoryTableResource("orders_ERROR#1"));
	}

	@Test
	public void testNoErrorIsRecordedForData() throws SQLException {
		MockJdbcDriver.addInMemoryTableResource("orders", "id\n1");

		connection.createStatement().executeQuery("SELECT * FROM orders");

		Assert.assertNull(MockJdbcDriver.getInMemoryTableResource("orders_ERROR"));
	}

	@Test
	public void testStatementKeepsTheThrownError() throws SQLException {
		MockJdbcDriver.addInMemoryTableResource("orders#1", DUPLICATE_ENTRY);
		MockJdbcDriver.addInMemoryTableResource("orders", "id\n1");
		CsvStatement statement = new CsvStatement(new HashMap<String, java.io.File>());

		Assert.assertNull(statement.injectedError);
		SQLException error = null;
		try {
			statement.executeQuery("SELECT * FROM orders");
		} catch (SQLException e) {
			error = e;
		}

		Assert.assertNotNull(error);
		Assert.assertSame(error, statement.injectedError);
		// the next query succeeds: the field is cleared at the start of each executeQuery
		statement.executeQuery("SELECT * FROM orders");
		Assert.assertNull(statement.injectedError);
	}

	@Test
	public void testFindInjectedErrorFollowsTheLookupOrder() throws SQLException {
		HashMap<String, java.io.File> noFiles = new HashMap<String, java.io.File>();
		MockJdbcDriver.addInMemoryTableResource("orders?42#2", DUPLICATE_ENTRY);
		MockJdbcDriver.addInMemoryTableResource("orders", "id\n1");

		SQLException found = CsvStatement.findInjectedError(noFiles, "orders", "42", 2);

		Assert.assertEquals("Duplicate entry", found.getMessage());
		// a data resource, or no resource at all, is not an injected error
		Assert.assertNull(CsvStatement.findInjectedError(noFiles, "orders", "42", 1));
		Assert.assertNull(CsvStatement.findInjectedError(noFiles, "missing", null, 1));
	}
}
