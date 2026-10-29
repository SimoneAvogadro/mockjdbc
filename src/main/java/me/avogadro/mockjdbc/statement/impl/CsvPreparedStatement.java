package me.avogadro.mockjdbc.statement.impl;

import java.io.File;
import java.math.BigDecimal;
import java.sql.BatchUpdateException;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import me.avogadro.mockjdbc.MockJdbcDriver;
import me.avogadro.mockjdbc.boomi.BoomiExecutionUtil;
import me.avogadro.mockjdbc.statement.PreparedStatementAdapter;

/**
 * Wraps the {@link CsvStatement} as a prepared statement.
 *
 * @author Kai Winter
 */
public class CsvPreparedStatement extends PreparedStatementAdapter {

	/** SELECT statement, after optional comment lines: its parameters are not captured */
	private static final Pattern SELECT_PATTERN = Pattern.compile("(?:\\s*--[^\\n]*\\n)*[\\s(]*select\\b.*", Pattern.CASE_INSENSITIVE|Pattern.DOTALL);

	/**
	 * Suffix used for storing the parameters last seen when running a query
	 */
	public static final String PARAMS_SUFFIX = "_PARAMS";
	
	static final int MAX_PARAMS = 500;
	
	/**
	 * Params received during the insert/update operation
	 */
	Object[] params = new Object[MAX_PARAMS];
	
    /**
     * Target table for insert/update operations
     */
    private String targetTable4Updates = null;
    
	private final CsvStatement statement;
	/** the tables of the connection (also used by {@link CsvCallableStatement}) */
	final Map<String, File> tableResources;
	private final String sql;

	/** result set of the last query (also read by {@link CsvCallableStatement}) */
	ResultSet currentResultSet;

	/** true while {@link #execute(String)} runs: the capture reuses the occurrence number of its query */
	private boolean reuseQueryOccurrence = false;

	/** copies of the parameters added with {@link #addBatch()} */
	private final List<Object[]> batch = new ArrayList<Object[]>();
	
	/**
	 * Constructs a new {@link CsvPreparedStatement}.
	 *
	 * @param tableResources {@link Map} of table name to CSV file.
	 * @param sql
	 *            the SQL statement.
	 */
	public CsvPreparedStatement(Map<String, File> tableResources, String sql) {
		this.statement = new CsvStatement(tableResources);
		this.tableResources = tableResources;
		this.sql = sql;
	}

	
	/**
	 * Always reply that 1 row was affected (most common case) if we can identify the target table name
	 * otherwise return 0.
	 * The parameters are captured as <code>&lt;name&gt;_PARAMS</code> for every statement except SELECTs
	 * (INSERT, UPDATE, DELETE, stored procedure calls, statements with a QUERY_ID or TESTCASE comment or a step resource).
	 * <p>
	 * If the target (the table, the QUERY_ID or TESTCASE, the procedure or the step) has an error resource (see
	 * {@link InjectedError}), the parameters are captured as usual and then the error is recorded and thrown. A data
	 * resource is ignored by a write.
	 */
	@Override
	public int executeUpdate(String sql) throws SQLException {
		return write(sql, false);
	}

	/**
	 * @param queryFailed <code>true</code> when {@link #execute(String)} runs it after its query found the error
	 *        resource of this statement and recorded it: only the parameters are captured, the error is not looked up
	 *        (nor recorded) a second time
	 */
	private int write(String sql, boolean queryFailed) throws SQLException {

		targetTable4Updates = null;
		final boolean isSelect = SELECT_PATTERN.matcher(sql).matches();
		boolean isStep = false;
		boolean isTable = false;
		// the occurrence number is computed once: the error lookup and the _PARAMS#n capture share it
		boolean capture = false;
		String paramsString = null;
		int occurrence = 0;
		SQLException injected = null;
		int res;
		if (!reuseQueryOccurrence) {
			MockJdbcDriver.beforeExecution();
		}
		try {
			String stepRes = MockJdbcDriver.getInMemoryTableResourceForCurrentStep();

			if (stepRes!=null) {
				targetTable4Updates = MockJdbcDriver.getStepName();
				isStep = true;
				try {
					res = Integer.parseInt(stepRes);
				} catch (NumberFormatException nfe) {
					// that's ok, it's a simple resultset rather then an update query (or an error resource)
					res = 0;
				}
			} else {
				// Try to check for a special heading comment within SQL
				targetTable4Updates = CsvStatement.matchQueryId(sql);

				if (targetTable4Updates == null) {
					// Try to interpret SQL as an INSERT, UPDATE or DELETE on a table (full name: dbo.users_PARAMS)
					targetTable4Updates = SqlNames.writeTable(sql);
					isTable = targetTable4Updates != null;
				}

				if (targetTable4Updates == null) {
					targetTable4Updates = CsvStatement.matchStoredProcedureCall(sql);
				}
				res = targetTable4Updates == null ? 0 : 1;
			}

			// the parameters of a SELECT are not captured: they only select data. It does not count as a use of the
			// name either, so it cannot target an error
			if (targetTable4Updates != null && !isSelect) {
				paramsString = buildParamsString();
				// steps are not numbered
				occurrence = isStep ? 0 : captureOccurrence();
				capture = true;
				// the lookup of a query, but only an error resource matters: a data resource is ignored
				if (!queryFailed) {
					injected = isTable
							? CsvStatement.findInjectedTableError(tableResources, targetTable4Updates, paramsString, occurrence)
							: CsvStatement.findInjectedError(tableResources, targetTable4Updates, paramsString, occurrence);
				}
			}

		} finally {
			if (capture) {
				captureParams(paramsString, isStep, occurrence);
			}

			params = new Object[MAX_PARAMS];
		}

		// the parameters are captured: now fail
		if (injected != null) {
			InjectedError.record(targetTable4Updates, occurrence, injected);
			throw injected;
		}
		return res;
	}

	/**
	 * Captures the parameters of a write as <code>&lt;name&gt;_PARAMS</code> (the latest) and, but for a step,
	 * <code>&lt;name&gt;_PARAMS#&lt;occurrence&gt;</code>.
	 */
	private void captureParams(String paramsValue, boolean isStep, int occurrence) {
		String paramsResource = targetTable4Updates+PARAMS_SUFFIX;
		// <name>_PARAMS always holds the latest parameters (the common single-write test)
		MockJdbcDriver.addInMemoryTableResource( paramsResource, paramsValue );
		// inside Boomi also expose them as Dynamic Process Properties (e.g. mockjdbc_users_PARAMS and mockjdbc_users_params)
		BoomiExecutionUtil.setResourceProperty( paramsResource, paramsValue );
		// <name>_PARAMS#n keeps the parameters of the n-th use of the name (steps are not numbered)
		if (!isStep) {
			String occurrenceResource = paramsResource + MockJdbcDriver.OCCURRENCE_SEPARATOR + occurrence;
			MockJdbcDriver.addInMemoryTableResource( occurrenceResource, paramsValue );
			BoomiExecutionUtil.setResourceProperty( occurrenceResource, paramsValue );
		}
	}

	/**
	 * Occurrence number of the captured statement: the one of the query run by the same {@link #execute(String)} on the
	 * same name, otherwise one more occurrence of the name.
	 */
	private int captureOccurrence() {
		if (reuseQueryOccurrence && statement.lastOccurrenceName != null
				&& statement.lastOccurrenceName.equalsIgnoreCase(targetTable4Updates)) {
			return statement.lastOccurrence;
		}
		return MockJdbcDriver.nextOccurrence(targetTable4Updates);
	}

	/**
	 * build a string representing the objects which have been received
	 * @return
	 */
	String buildParamsString() {
		return paramsString(params);
	}

	/**
	 * @param params values by SQL parameter index (index 0 unused)
	 * @return the values joined by commas, trailing empty values removed
	 */
	static String paramsString(Object[] params) {
		// 1: build the string (space separated)
		StringBuilder s = new StringBuilder();
		for (int i = 1; i < params.length; i++) { // SQL param index starts from 1 !!
			if (i>1)
				s.append(",");
			if (params[i]!=null) {
				if (params[i] instanceof java.util.Date) {
					if (params[i] instanceof java.sql.Date) {
						s.append(MockJdbcDriver.THREAD_LOCAL_DATEFORMAT.get().format((java.util.Date)params[i]));
					} else if (params[i] instanceof java.sql.Time) {
						s.append(MockJdbcDriver.THREAD_LOCAL_TIMEFORMAT.get().format((java.util.Date)params[i]));
					} else {
						s.append(MockJdbcDriver.THREAD_LOCAL_TIMESTAMPFORMAT.get().format((java.util.Date)params[i]));
					}
				} else {
					s.append(params[i]);									
				}
			}
		}
		
		// 2: trim and replace tailing commas
		String res = s.toString().trim();
		res = res.replaceAll(",*$", "");
		
		return res;
	}
	
	@Override
	public void setObject(int parameterIndex, Object x) throws SQLException {
		params[parameterIndex]=x;
	}

	@Override
	public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
		params[parameterIndex]=x;
	}

	@Override
	public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
		params[parameterIndex]=x;
	}

	@Override
	public void setLong(int parameterIndex, long x) throws SQLException {
		params[parameterIndex]=x;
	}

	@Override
	public void setShort(int parameterIndex, short x) throws SQLException {
		params[parameterIndex]=x;
	}

	@Override
	public void setByte(int parameterIndex, byte x) throws SQLException {
		params[parameterIndex]=x;
	}

	@Override
	public void setFloat(int parameterIndex, float x) throws SQLException {
		params[parameterIndex]=x;
	}

	@Override
	public void setBoolean(int parameterIndex, boolean x) throws SQLException {
		params[parameterIndex]=x;
	}

	/** a NULL parameter is captured as an empty value */
	@Override
	public void setNull(int parameterIndex, int sqlType) throws SQLException {
		params[parameterIndex]=null;
	}

	@Override
	public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
		params[parameterIndex]=null;
	}

	@Override
	public void setString(int parameterIndex, String x) throws SQLException {
		params[parameterIndex]=x;
	}
	
	@Override
	public void setInt(int parameterIndex, int x) throws SQLException {
		params[parameterIndex]=x;
	}

	@Override
	public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
		params[parameterIndex]=x;
	}
	
	@Override
	public void setDouble(int parameterIndex, double x) throws SQLException {
		params[parameterIndex]=x;
	}
	
	@Override
	public void setTime(int parameterIndex, Time x) throws SQLException {
		params[parameterIndex]=x;
	}
	
	@Override
	public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
		params[parameterIndex]=x;
	}
	
	@Override
	public void setDate(int parameterIndex, Date x) throws SQLException {
		params[parameterIndex]=x;
	}
	
	@Override
	public int executeUpdate() throws SQLException {
		return executeUpdate(sql);
	}
	
	@Override
	public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
		return executeUpdate(sql);
	}
	
	@Override
	public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
		return executeUpdate(sql);
	}
	
	@Override
	public int executeUpdate(String sql, String[] columnNames) throws SQLException {
		return executeUpdate(sql);
	}
	
	
	@Override
	public ResultSet executeQuery() throws SQLException {
		execute(sql);
		return currentResultSet;
	}

	@Override
	public ResultSet executeQuery(String sql) throws SQLException {
		execute(sql);
		return currentResultSet;
	}

	@Override
	public boolean execute() throws SQLException {
		return execute(sql);
	}

	@Override
	public boolean execute(String sql) throws SQLException {
		try {
			statement.paramsString = buildParamsString();
			try {
				currentResultSet = statement.executeQuery(sql);
			} catch (SQLException e) {
				if (e != statement.injectedError) {
					throw e;
				}
				// the query found an error resource and recorded it: capture the parameters (counted as the same use of
				// the name as the query, as for a success), then fail with that very exception
				currentResultSet = null;
				reuseQueryOccurrence = true;
				write(sql, true);
				throw e;
			}
			// try up update (generate params), counted as the same use of the name as the query
			reuseQueryOccurrence = true;
			try {
				executeUpdate(sql);
			} catch (SQLException e) {
				// the write found an error resource: a failed execution has no result set
				currentResultSet = null;
				throw e;
			}
			return true;
		} finally {
			reuseQueryOccurrence = false;
			statement.paramsString = null;		
		}
	}

	@Override
	public boolean execute(String sql, int[] columnIndexes) throws SQLException {
		return execute(sql);
	}

	@Override
	public boolean execute(String sql, String[] columnNames) throws SQLException {
		return execute(sql);
	}

	@Override
	public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
		return execute(sql);
	}

	@Override
	public ResultSet getResultSet() throws SQLException {
		return currentResultSet;
	}

	/**
	 * The columns of the result set of the last query (e.g. the Boomi SQL lookup reads them after <code>execute()</code>);
	 * <code>null</code> before the statement is executed.
	 */
	@Override
	public ResultSetMetaData getMetaData() throws SQLException {
		return currentResultSet == null ? null : currentResultSet.getMetaData();
	}

	@Override
	public void setMaxRows(int max) throws SQLException {
		statement.setMaxRows(max);
	}

	@Override
	public int getMaxRows() throws SQLException {
		return statement.getMaxRows();
	}

	@Override
	public ResultSet getGeneratedKeys() throws SQLException {
		return statement.getGeneratedKeys();
	}

	/**
	 * Keeps a copy of the current parameters; {@link #executeBatch()} runs the statement once per copy.
	 */
	@Override
	public void addBatch() throws SQLException {
		batch.add(params.clone());
	}

	@Override
	public void clearBatch() throws SQLException {
		batch.clear();
	}

	/**
	 * Runs {@link #executeUpdate()} once for every set of parameters added with {@link #addBatch()} (each run captures
	 * its parameters as usual). A row that throws (an error resource, see {@link InjectedError}) stops the batch: the
	 * exception is thrown as the cause of a {@link BatchUpdateException}, with the update counts of the rows before it.
	 * The later rows are not run. The batch is cleared in any case.
	 */
	@Override
	public int[] executeBatch() throws SQLException {
		int[] results = new int[batch.size()];
		try {
			for (int i = 0; i < results.length; i++) {
				params = batch.get(i).clone();
				try {
					results[i] = executeUpdate(sql);
				} catch (SQLException e) {
					throw new BatchUpdateException(e.getMessage(), e.getSQLState(), e.getErrorCode(),
							Arrays.copyOf(results, i), e);
				}
			}
		} finally {
			batch.clear();
		}
		return results;
	}

	@Override
	public void clearParameters() throws SQLException {
		params = new Object[MAX_PARAMS];
	}
	

}
