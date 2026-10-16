package me.avogadro.mockjdbc.statement.impl;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.avogadro.mockjdbc.MockJdbcDriver;
import me.avogadro.mockjdbc.resultset.MockResultSet;
import me.avogadro.mockjdbc.resultset.MockResultSetMetaData;
import me.avogadro.mockjdbc.resultset.impl.CSVResultSet;
import me.avogadro.mockjdbc.resources.ResourceNames;
import me.avogadro.mockjdbc.resources.TableLookup;
import me.avogadro.mockjdbc.statement.StatementAdapter;

import au.com.bytecode.opencsv.CSVReader;

/**
 * This class does the actual work of the Generic... classes. It tries to open a CSV file for the table name in the
 * query and parses the contained data.
 *
 * @author Kai Winter
 */
public final class CsvStatement extends StatementAdapter {

	private static final Logger LOGGER = LoggerFactory.getLogger(CsvStatement.class);

	/**
	 * Pattern used to recognize explicitly declared table names inside an heading comment.
	 * The name stops at the end of the first line (\n or \r\n), whatever follows on the next lines.
	 */
	private static final Pattern COMMENT_HEADLINE_PATTERN = Pattern.compile("\\s*--\\s*TESTCASE:[ \\t]*([^\\r\\n]*)\\r?\\n.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	
	/** Pattern to get table name from an SQL statement. */
	private static final Pattern TABLENAME_PATTERN = Pattern.compile(".*from\\s*(\\S*)\\s?.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL );

	/** Pattern to get the name of a stored procedure from an SQL statement. */
	private static final Pattern STORED_PROCEDURE_PATTERN = Pattern.compile(".*(EXEC|EXECUTE) (\\S*)\\s?.*",	Pattern.CASE_INSENSITIVE);

	/** Stored procedure call: EXEC/EXECUTE/CALL name ... or the JDBC escape {[? =] call name(...)}, after optional comment lines */
	private static final Pattern STORED_PROCEDURE_CALL_PATTERN = Pattern.compile("(?:\\s*--[^\\n]*\\n)*\\s*\\{?\\s*(?:\\?\\s*=\\s*)?(?:exec|execute|call)\\s+([^\\s(){};,]+).*", Pattern.CASE_INSENSITIVE|Pattern.DOTALL);

	private static final Pattern PURE_SELECT_PATTERN = Pattern.compile("select .*", Pattern.CASE_INSENSITIVE);

    private final Map<String, File> tableResources;

    /** folder of the connection URL, root of the table folders, may be null */
    private final File connectionRoot;

    /**
     * used to describe the "current" params.
     * Always null when invoked from {@link CsvStatement} and may hold values when used from {@link CsvPreparedStatement}
     */
    String paramsString = null;

    /** resource name and occurrence number used by the last query (see {@link MockJdbcDriver#nextOccurrence}) */
    String lastOccurrenceName = null;
    int lastOccurrence = 0;

    /** see {@link #setMaxRows(int)}: 0 = no limit */
    private int maxRows = 0;

    /** SQL added with {@link #addBatch(String)} */
    private final List<String> batch = new ArrayList<String>();
    
    
    /**
     * Initializer
     * 
     */
    {
    	MockJdbcDriver.nextStep();
    }
    
	/**
	 * Constructs a new {@link CsvStatement}.
	 *
	 * @param tableResources
	 *            {@link Map} of table name to CSV file.
	 */
	public CsvStatement(Map<String, File> tableResources) {
		this(tableResources, null);
	}

	/**
	 * Constructs a new {@link CsvStatement}.
	 *
	 * @param tableResources
	 *            {@link Map} of table name to CSV file.
	 * @param connectionRoot folder of the connection URL, root of the table folders, may be <code>null</code>
	 */
	public CsvStatement(Map<String, File> tableResources, File connectionRoot) {
		this.tableResources = tableResources;
		this.connectionRoot = connectionRoot;
	}

	/**
	 * @return the trimmed resource of the current step (<code>##STEPn</code>) from any source, or <code>null</code>
	 * @throws SQLException if a table folder cannot be read
	 */
	String findStepResource() throws SQLException {
		String stepRes = new TableLookup(tableResources, connectionRoot).find(MockJdbcDriver.getStepName());
		return stepRes == null ? null : stepRes.trim();
	}
	
	@Override
	public ResultSet executeQuery(String sql) throws SQLException {
		MockJdbcDriver.beforeExecution();
		lastOccurrenceName = null;
		lastOccurrence = 0;
		ResultSet resultSet = query(sql);
		if (maxRows > 0 && resultSet instanceof CSVResultSet) {
			((CSVResultSet) resultSet).setMaxRows(maxRows);
		}
		return resultSet;
	}

	@Override
	public void setMaxRows(int max) throws SQLException {
		if (max < 0) {
			throw new SQLException("maxRows must be >= 0: " + max);
		}
		this.maxRows = max;
	}

	@Override
	public int getMaxRows() throws SQLException {
		return maxRows;
	}

	/**
	 * No key is ever generated: always an empty result set (never null, callers iterate it after an INSERT).
	 */
	@Override
	public ResultSet getGeneratedKeys() throws SQLException {
		return CSVResultSet.empty("generatedKeys", "GENERATED_KEY");
	}

	@Override
	public void addBatch(String sql) throws SQLException {
		batch.add(sql);
	}

	@Override
	public void clearBatch() throws SQLException {
		batch.clear();
	}

	@Override
	public int[] executeBatch() throws SQLException {
		int[] results = new int[batch.size()];
		try {
			for (int i = 0; i < results.length; i++) {
				results[i] = executeUpdate(batch.get(i));
			}
		} finally {
			batch.clear();
		}
		return results;
	}

	private ResultSet query(String sql) throws SQLException {

		try {
			String stepRes = findStepResource();
			
			// match based on the current step (sequence number)
			if (stepRes!=null) {
				// steps are already a sequence: no occurrence number
				return createResultSet(tableResources, connectionRoot, MockJdbcDriver.getStepName(), paramsString, 0);
			}
						
			// Try to check for a special heading comment within SQL
			String testCase = matchTestCase(sql);
			if (testCase != null) {
				return createResultSet(testCase);
			}
			
			// Try to interpret SQL as a SELECT on a table
			Matcher tableMatcher = TABLENAME_PATTERN.matcher(sql);
			if (tableMatcher.matches()) {
				String tableName = tableMatcher.group(1);
				return createResultSet(tableName);
			}

			// Try to interpret SQL as call of a stored procedure
			Matcher storedProcedureMatcher = STORED_PROCEDURE_PATTERN.matcher(sql);
			if (storedProcedureMatcher.matches()) {
				String storedProcedureName = storedProcedureMatcher.group(2);
				return createResultSet(storedProcedureName);
			}

			// Try to interpret SQL as a stored procedure call in the other forms (CALL, {call ...}, several lines)
			String procedure = matchStoredProcedureCall(sql);
			if (procedure != null) {
				return createResultSet(procedure);
			}

	        //  Try  to  interpret  SQL  as  a  pure  select
			Matcher  pureSelectMatcher  =  PURE_SELECT_PATTERN.matcher(sql);
			if  (pureSelectMatcher.matches())  {
				return  createPureResultSet();
			}

	        return new MockResultSet();
	        
		} finally {
			// MockJdbcDriver.nextStep();
		}
	}
	
	/**
	 * @param sql the SQL text
	 * @return the name declared by a heading <code>-- TESTCASE: name</code> comment (trimmed), or <code>null</code>
	 */
	static String matchTestCase(String sql) {
		Matcher commentMatcher = COMMENT_HEADLINE_PATTERN.matcher(sql);
		if (commentMatcher.matches()) {
			String testCase = commentMatcher.group(1).trim();
			return testCase.isEmpty() ? null : testCase;
		}
		return null;
	}

	/**
	 * @param sql the SQL text
	 * @return the name of the stored procedure called with EXEC, EXECUTE, CALL or <code>{call ...}</code>, or <code>null</code>
	 */
	static String matchStoredProcedureCall(String sql) {
		Matcher procedureMatcher = STORED_PROCEDURE_CALL_PATTERN.matcher(sql);
		return procedureMatcher.matches() ? procedureMatcher.group(1) : null;
	}

	static String matchTablename(String sql) {
		Matcher tableMatcher = TABLENAME_PATTERN.matcher(sql);
		if (tableMatcher.matches()) {
			return tableMatcher.group(1);
		} else {
			return null;
		}
	}

	/** a query on a named resource (table, test case, procedure): counts one more occurrence of that name */
	private ResultSet createResultSet(String tableName) throws SQLException {
		lastOccurrenceName = tableName;
		lastOccurrence = MockJdbcDriver.nextOccurrence(tableName);
		return createResultSet(tableResources, connectionRoot, tableName, paramsString, lastOccurrence);
	}

	/**
	 * Loads a table exactly as a <code>SELECT * FROM tableName</code> would, without running a statement (the step
	 * counter is not touched): in-memory resource (or Boomi Dynamic Process Property), table folder, registered file,
	 * or <code>/tables/&lt;name&gt;.csv</code>.
	 *
	 * @param tableResources {@link Map} of table name to CSV file of the connection
	 * @param tableName the table name
	 * @return a {@link CSVResultSet}, or an empty {@link MockResultSet} if the table is not defined
	 * @throws SQLException if a table folder cannot be read
	 */
	public static ResultSet loadTable(Map<String, File> tableResources, String tableName) throws SQLException {
		return loadTable(tableResources, null, tableName);
	}

	/**
	 * Same as {@link #loadTable(Map, String)} for a connection whose URL gives the root of the table folders.
	 *
	 * @param tableResources {@link Map} of table name to CSV file of the connection
	 * @param connectionRoot folder of the connection URL, root of the table folders, may be <code>null</code>
	 * @param tableName the table name
	 * @return a {@link CSVResultSet}, or an empty {@link MockResultSet} if the table is not defined
	 * @throws SQLException if a table folder cannot be read
	 */
	public static ResultSet loadTable(Map<String, File> tableResources, File connectionRoot, String tableName)
			throws SQLException {
		return createResultSet(tableResources, connectionRoot, tableName, null, 0);
	}

	/**
	 * Looks up, in every source (see {@link TableLookup}), the most specific name first:
	 * <code>name?params#n</code>, <code>name?params</code>, <code>name#n</code>, <code>name</code>.
	 *
	 * @param occurrence how many times the resource name has been used (1 = first), 0 = not counted
	 */
	private static ResultSet createResultSet(Map<String, File> tableResources, File connectionRoot, String tableName,
			String paramsString, int occurrence) throws SQLException {
		String occurrenceSuffix = occurrence > 0 ? MockJdbcDriver.OCCURRENCE_SEPARATOR + occurrence : null;

		List<String> names = new ArrayList<String>(4);
		if (paramsString != null) {
			String withParams = tableName + ResourceNames.PARAMS_SEPARATOR + paramsString;
			if (occurrenceSuffix != null) {
				names.add(withParams + occurrenceSuffix);
			}
			names.add(withParams);
		}
		if (occurrenceSuffix != null) {
			names.add(tableName + occurrenceSuffix);
		}
		names.add(tableName);

		String csv = new TableLookup(tableResources, connectionRoot).find(names);
		if (csv == null) {
			LOGGER.info("No table definition found for '{}' (tried {}), using MockResultSet.", tableName, names);
			return new MockResultSet();
		}
		return createGenericResultSet(tableName, new StringReader(csv));
	}

	private static ResultSet createGenericResultSet(String tableName, Reader tableDataReader) {

		// Maps table columns to a number of available values.
		Collection<LinkedHashMap<String, String>> entries = new ArrayList<LinkedHashMap<String, String>>();

		CSVReader tableReader = null;
		try {

			tableReader = new CSVReader(tableDataReader);

			// Read header
			String[] header = tableReader.readNext();
			if (header == null) {
				LOGGER.info("Empty table definition for '{}', using MockResultSet.", tableName);
				return new MockResultSet();
			} else {

				String[] data;
				// Read data
				while ((data = tableReader.readNext()) != null) {
					if (header.length == data.length) {
						LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
						for (int i = 0; i < header.length; i++) {
                            final String headerName = resolveHeaderName(header[i]);
							if (map.containsKey(headerName)) {
								String message = MessageFormat.format("Duplicate column in file ''{0}.txt: {1}",
										tableName, header[i]);
								throw new IllegalArgumentException(message);
							}
							map.put(headerName, data[i].trim());

						}
						entries.add(map);
					} else {
						throw new IllegalArgumentException("Length of data does not fit header length.");
					}

				}
			}
			return new CSVResultSet(tableName, new MockResultSetMetaData(tableName, header), entries);

		} catch (IOException e) {
			LOGGER.error("Error while reading data from CSV", e);
		} finally {
			if (tableReader != null) {
				try {
					tableReader.close();
				} catch (IOException e) {
					// ignore
				}
			}
		}

		return new MockResultSet();
	}

	private MockResultSet createPureResultSet() {

        // Maps table columns to a number of available values.
		Collection<LinkedHashMap<String, String>> entries = new ArrayList<LinkedHashMap<String, String>>();

		LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
		map.put("1", "1");

		entries.add(map);

		return new CSVResultSet(null, new MockResultSetMetaData(null, new String[] { "1" }), entries);
	}

	private static String resolveHeaderName(String str) {
	    return str.trim().toUpperCase();
    }


}
