package me.avogadro.mockjdbc.statement.impl;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLNonTransientException;
import java.sql.SQLRecoverableException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import me.avogadro.mockjdbc.MockJdbcDriver;
import me.avogadro.mockjdbc.boomi.BoomiExecutionUtil;

/**
 * An <b>error resource</b> is a mock whose CSV header starts with the simple name of a supported
 * <code>java.sql</code> exception (case insensitive): the statement that finds it throws that exception instead of
 * returning data.
 * <pre>
 * SQLException,SQLState,ErrorCode
 * Deadlock victim,40001,1205
 * </pre>
 * The first column holds the message, the other columns may only be <code>SQLState</code> and <code>ErrorCode</code>.
 * The first data row gives the values; an empty <code>SQLState</code> is <code>null</code>, an empty
 * <code>ErrorCode</code> is 0. Without a message the message is
 * <code>Error injected by mockjdbc for '&lt;name&gt;'</code>, never empty.
 * <p>
 * The exception is built with the class written in the header (never derived from the SQLState) and
 * <code>(message, SQLState, ErrorCode)</code>. Other names, such as <code>SQLWarning</code>, are not error resources:
 * the header is read as the columns of a table.
 */
final class InjectedError {

	/** suffix of the resource that holds the message of the error thrown for a name: <code>orders_ERROR</code> */
	static final String ERROR_SUFFIX = "_ERROR";

	private static final String SQL_STATE_COLUMN = "SQLState";
	private static final String ERROR_CODE_COLUMN = "ErrorCode";

	/** builds one of the supported exceptions */
	private interface Factory {
		SQLException create(String reason, String sqlState, int vendorCode);
	}

	/** the supported classes: simple name in lower case to constructor (no reflection, no vendor classes) */
	private static final Map<String, Factory> FACTORIES = new HashMap<String, Factory>();

	static {
		FACTORIES.put("sqlexception", SQLException::new);
		FACTORIES.put("sqlnontransientexception", SQLNonTransientException::new);
		FACTORIES.put("sqldataexception", SQLDataException::new);
		FACTORIES.put("sqlfeaturenotsupportedexception", SQLFeatureNotSupportedException::new);
		FACTORIES.put("sqlintegrityconstraintviolationexception", SQLIntegrityConstraintViolationException::new);
		FACTORIES.put("sqlinvalidauthorizationspecexception", SQLInvalidAuthorizationSpecException::new);
		FACTORIES.put("sqlnontransientconnectionexception", SQLNonTransientConnectionException::new);
		FACTORIES.put("sqlsyntaxerrorexception", SQLSyntaxErrorException::new);
		FACTORIES.put("sqltransientexception", SQLTransientException::new);
		FACTORIES.put("sqltimeoutexception", SQLTimeoutException::new);
		FACTORIES.put("sqltransactionrollbackexception", SQLTransactionRollbackException::new);
		FACTORIES.put("sqltransientconnectionexception", SQLTransientConnectionException::new);
		FACTORIES.put("sqlrecoverableexception", SQLRecoverableException::new);
	}

	private InjectedError() {
	}

	/**
	 * @param resultSet the data of a resource
	 * @return <code>true</code> if the first column is the name of a supported exception class, ignoring case and
	 *         spaces (the {@link ResultSetMetaData} gives the names without their <code>|type</code>)
	 * @throws SQLException if the column names cannot be read
	 */
	static boolean isErrorResource(ResultSet resultSet) throws SQLException {
		return factory(resultSet.getMetaData()) != null;
	}

	/**
	 * @param resultSet the data of a resource
	 * @param name the counted name with its occurrence (<code>orders#2</code>) or the step name, used in the default
	 *        message and to report an invalid resource
	 * @return <code>null</code> if the resource is not an error resource; otherwise the declared exception, or an
	 *         {@link SQLException} starting with <code>Invalid error resource '&lt;name&gt;': </code> that names the
	 *         unknown column or the <code>ErrorCode</code> that is not an integer
	 * @throws SQLException if the resource cannot be read
	 */
	static SQLException of(ResultSet resultSet, String name) throws SQLException {
		ResultSetMetaData metaData = resultSet.getMetaData();
		Factory factory = factory(metaData);
		if (factory == null) {
			return null;
		}
		int stateColumn = 0;
		int codeColumn = 0;
		for (int i = 2; i <= metaData.getColumnCount(); i++) {
			String column = metaData.getColumnName(i);
			if (SQL_STATE_COLUMN.equalsIgnoreCase(column)) {
				stateColumn = i;
			} else if (ERROR_CODE_COLUMN.equalsIgnoreCase(column)) {
				codeColumn = i;
			} else {
				return invalid(name, "column '" + column + "' is not allowed, only " + SQL_STATE_COLUMN + " and "
						+ ERROR_CODE_COLUMN + " can follow the message");
			}
		}

		String message = null;
		String sqlState = null;
		int errorCode = 0;
		if (resultSet.next()) {
			message = resultSet.getString(1);
			if (stateColumn > 0) {
				sqlState = resultSet.getString(stateColumn);
			}
			String code = codeColumn > 0 ? resultSet.getString(codeColumn) : null;
			if (code != null && !code.trim().isEmpty()) {
				try {
					errorCode = Integer.parseInt(code.trim());
				} catch (NumberFormatException e) {
					return invalid(name, ERROR_CODE_COLUMN + " '" + code + "' is not an integer");
				}
			}
		}
		if (message == null || message.isEmpty()) {
			message = "Error injected by mockjdbc for '" + name + "'";
		}
		return factory.create(message, sqlState == null || sqlState.isEmpty() ? null : sqlState, errorCode);
	}

	/**
	 * Records the message of an injected error as <code>&lt;name&gt;_ERROR</code> and, for a counted name,
	 * <code>&lt;name&gt;_ERROR#&lt;occurrence&gt;</code>: in memory and, inside Boomi, as Dynamic Process Properties
	 * (like the <code>_PARAMS</code> of a write). They prove that the injection fired.
	 *
	 * @param name the resource name as used for the counter (<code>orders</code>), or the step name
	 * @param occurrence the occurrence number of the name, 0 for a step (steps are not numbered)
	 * @param error the exception thrown
	 */
	static void record(String name, int occurrence, SQLException error) {
		String message = error.getMessage();
		String resource = name + ERROR_SUFFIX;
		capture(resource, message);
		if (occurrence > 0) {
			capture(resource + MockJdbcDriver.OCCURRENCE_SEPARATOR + occurrence, message);
		}
	}

	private static void capture(String resource, String message) {
		MockJdbcDriver.addInMemoryTableResource(resource, message);
		BoomiExecutionUtil.setResourceProperty(resource, message);
	}

	/** the constructor of the class named by the first column, or null */
	private static Factory factory(ResultSetMetaData metaData) throws SQLException {
		if (metaData == null || metaData.getColumnCount() == 0) {
			return null;
		}
		return FACTORIES.get(metaData.getColumnName(1).trim().toLowerCase(Locale.ROOT));
	}

	private static SQLException invalid(String name, String problem) {
		return new SQLException("Invalid error resource '" + name + "': " + problem);
	}
}
