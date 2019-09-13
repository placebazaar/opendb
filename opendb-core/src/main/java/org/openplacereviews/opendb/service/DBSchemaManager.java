package org.openplacereviews.opendb.service;


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openplacereviews.opendb.OpenDBServer.MetadataColumnSpec;
import org.openplacereviews.opendb.OpenDBServer.MetadataDb;
import org.openplacereviews.opendb.SecUtils;
import org.openplacereviews.opendb.dto.RequestIndexBody;
import org.openplacereviews.opendb.ops.OpBlock;
import org.openplacereviews.opendb.ops.OpIndexColumn;
import org.openplacereviews.opendb.ops.OpOperation;
import org.openplacereviews.opendb.ops.de.ColumnDef;
import org.openplacereviews.opendb.ops.de.ColumnDef.IndexType;
import org.openplacereviews.opendb.util.JsonFormatter;
import org.openplacereviews.opendb.util.OUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static org.openplacereviews.opendb.ops.de.ColumnDef.IndexType.*;


@Service
public class DBSchemaManager {

	protected static final Log LOGGER = LogFactory.getLog(DBSchemaManager.class);
	private static final int OPENDB_SCHEMA_VERSION = 3;
	private final String OBJTABLE_PROPERTY_NAME = "opendb.db-schema.objtables";
	
	// //////////SYSTEM TABLES DDL ////////////
	protected static final String SETTINGS_TABLE = "opendb_settings";
	public static final String BOT_STATS_TABLE = "bot_stats";
	protected static final String BLOCKS_TABLE = "blocks";
	protected static final String OPERATIONS_TABLE = "operations";
	protected static final String OBJS_TABLE = "objs";
	protected static final String OPERATIONS_TRASH_TABLE = "operations_trash";
	protected static final String BLOCKS_TRASH_TABLE = "blocks_trash";
	protected static final String EXT_RESOURCE_TABLE = "resources";
	protected static final String OP_OBJ_HISTORY_TABLE = "op_obj_history";

	private static Map<String, List<ColumnDef>> schema = new HashMap<String, List<ColumnDef>>();
	protected static final int MAX_KEY_SIZE = 5;
	public static final String[] INDEX_P = new String[MAX_KEY_SIZE];
	{
		for(int i = 0; i < MAX_KEY_SIZE; i++) {
			INDEX_P[i] = "p" + (i + 1);
		}
	}
	protected static final int HISTORY_USERS_SIZE = 2;
	private static final int BATCH_SIZE = 1000;

	// loaded from config
	private Map<String, Map<String, Object>> objtables;
	private TreeMap<String, ObjectTypeTable> objTableDefs = new TreeMap<String, ObjectTypeTable>();
	private TreeMap<String, String> typeToTables = new TreeMap<String, String>();
	private TreeMap<String, Map<String, OpIndexColumn>> indexes = new TreeMap<>();
	

	@Autowired
	private JsonFormatter formatter;

	@Autowired
	private SettingsManager settingsManager;

	static class ObjectTypeTable {
		public ObjectTypeTable(String tableName, int keySize) {
			this.tableName = tableName;
			this.keySize = keySize;
		}
		String tableName;
		int keySize;
		Set<String> types = new TreeSet<>();
	}

	public TreeMap<String, Map<String, OpIndexColumn>> getIndexes() {
		return indexes;
	}

	private static void registerColumn(String tableName, String colName, String colType, IndexType basicIndexType) {
		ColumnDef cd = new ColumnDef(tableName, colName, colType, basicIndexType);
		registerColumn(tableName, cd);
	}

	private static void registerColumn(String tableName, ColumnDef cd) {
		List<ColumnDef> lst = schema.get(tableName);
		if (lst == null) {
			lst = new ArrayList<ColumnDef>();
			schema.put(tableName, lst);
		}
		
		lst.add(cd);
	}

	static {
		registerColumn(SETTINGS_TABLE, "key", "text PRIMARY KEY", INDEXED);
		registerColumn(SETTINGS_TABLE, "value", "text", NOT_INDEXED);
		registerColumn(SETTINGS_TABLE, "content", "jsonb", NOT_INDEXED);

		registerColumn(BLOCKS_TABLE, "hash", "bytea PRIMARY KEY", INDEXED);
		registerColumn(BLOCKS_TABLE, "phash", "bytea", NOT_INDEXED);
		registerColumn(BLOCKS_TABLE, "blockid", "int", INDEXED);
		registerColumn(BLOCKS_TABLE, "superblock", "bytea", INDEXED);
		registerColumn(BLOCKS_TABLE, "header", "jsonb", NOT_INDEXED);
		registerColumn(BLOCKS_TABLE, "content", "jsonb", NOT_INDEXED);

		registerColumn(BLOCKS_TRASH_TABLE, "hash", "bytea PRIMARY KEY", INDEXED);
		registerColumn(BLOCKS_TRASH_TABLE, "phash", "bytea", NOT_INDEXED);
		registerColumn(BLOCKS_TRASH_TABLE, "blockid", "int", INDEXED);
		registerColumn(BLOCKS_TRASH_TABLE, "time", "timestamp", NOT_INDEXED);
		registerColumn(BLOCKS_TRASH_TABLE, "content", "jsonb", NOT_INDEXED);

		registerColumn(OPERATIONS_TABLE, "dbid", "serial not null", NOT_INDEXED);
		registerColumn(OPERATIONS_TABLE, "hash", "bytea PRIMARY KEY", INDEXED);
		registerColumn(OPERATIONS_TABLE, "type", "text", INDEXED);
		registerColumn(OPERATIONS_TABLE, "superblock", "bytea", INDEXED);
		registerColumn(OPERATIONS_TABLE, "sblockid", "int", INDEXED);
		registerColumn(OPERATIONS_TABLE, "sorder", "int", INDEXED);
		registerColumn(OPERATIONS_TABLE, "blocks", "bytea[]", NOT_INDEXED);
		registerColumn(OPERATIONS_TABLE, "content", "jsonb", NOT_INDEXED);

		registerColumn(OP_OBJ_HISTORY_TABLE, "sorder", "serial not null", NOT_INDEXED);
		registerColumn(OP_OBJ_HISTORY_TABLE, "blockhash", "bytea", INDEXED);
		registerColumn(OP_OBJ_HISTORY_TABLE, "ophash", "bytea", INDEXED);
		registerColumn(OP_OBJ_HISTORY_TABLE, "type", "text", INDEXED);
		for (int i = 1; i <= HISTORY_USERS_SIZE; i++) {
			registerColumn(OP_OBJ_HISTORY_TABLE, "usr_" + i, "text", INDEXED);
			registerColumn(OP_OBJ_HISTORY_TABLE, "login_" + i, "text", INDEXED);
		}
		for (int i = 1; i <= MAX_KEY_SIZE; i++) {
			registerColumn(OP_OBJ_HISTORY_TABLE, "p" + i, "text", INDEXED);
		}
		registerColumn(OP_OBJ_HISTORY_TABLE, "time", "timestamp", NOT_INDEXED);
		registerColumn(OP_OBJ_HISTORY_TABLE, "obj", "jsonb", NOT_INDEXED);
		registerColumn(OP_OBJ_HISTORY_TABLE, "status", "int", NOT_INDEXED);

		registerColumn(OPERATIONS_TRASH_TABLE, "id", "int", INDEXED);
		registerColumn(OPERATIONS_TRASH_TABLE, "hash", "bytea", INDEXED);
		registerColumn(OPERATIONS_TRASH_TABLE, "type", "text", INDEXED);
		registerColumn(OPERATIONS_TRASH_TABLE, "time", "timestamp", NOT_INDEXED);
		registerColumn(OPERATIONS_TRASH_TABLE, "content", "jsonb", NOT_INDEXED);

		registerColumn(EXT_RESOURCE_TABLE, "hash", "bytea PRIMARY KEY", INDEXED);
		registerColumn(EXT_RESOURCE_TABLE, "extension", "text", NOT_INDEXED);
		registerColumn(EXT_RESOURCE_TABLE, "cid", "text", NOT_INDEXED);
		registerColumn(EXT_RESOURCE_TABLE, "active", "bool", NOT_INDEXED);
		registerColumn(EXT_RESOURCE_TABLE, "added", "timestamp", NOT_INDEXED);

		registerColumn(BOT_STATS_TABLE, "id", "serial not null", NOT_INDEXED);
		registerColumn(BOT_STATS_TABLE, "bot", "text", NOT_INDEXED);
		registerColumn(BOT_STATS_TABLE, "start_date", "timestamp", NOT_INDEXED);
		registerColumn(BOT_STATS_TABLE, "end_date", "timestamp", NOT_INDEXED);
		registerColumn(BOT_STATS_TABLE, "total", "int", NOT_INDEXED);
		registerColumn(BOT_STATS_TABLE, "processed", "int", NOT_INDEXED);
		registerColumn(BOT_STATS_TABLE, "status", "text", NOT_INDEXED);

		registerObjTable(OBJS_TABLE, MAX_KEY_SIZE);

	}

	private static void registerObjTable(String tbName, int maxKeySize) {
		registerColumn(tbName, "type", "text", INDEXED);
		for (int i = 1; i <= maxKeySize; i++) {
			registerColumn(tbName, "p" + i, "text", INDEXED);
		}
		registerColumn(tbName, "ophash", "bytea", INDEXED);
		registerColumn(tbName, "superblock", "bytea", INDEXED);
		registerColumn(tbName, "sblockid", "int",  INDEXED);
		registerColumn(tbName, "sorder", "int", INDEXED);
		registerColumn(tbName, "content", "jsonb", NOT_INDEXED);
	}

	public Map<String, Map<String, Object>> getObjtables() {
		if (objtables != null) {
			return objtables;
		} else {
			Map<String, Map<String, Object>> objtable = new TreeMap<>();
			List<SettingsManager.OpendbPreference<?>> preferences = settingsManager.loadContainsPreferencesByKey(OBJTABLE_PROPERTY_NAME);
			for (SettingsManager.OpendbPreference opendbPreference : preferences) {
				String tableName = opendbPreference.getId().substring(opendbPreference.getId().lastIndexOf(".") + 1);
				objtable.put(tableName, (Map<String, Object>) opendbPreference.get());
			}
			objtables = objtable;
			return objtable;
		}
	}

	public Collection<String> getObjectTables() {
		return objTableDefs.keySet();
	}
	
	public String getTableByType(String type) {
		String tableName = typeToTables.get(type);
		if(tableName != null) {
			return tableName;
		}
		return OBJS_TABLE;
	}
	
	public int getKeySizeByType(String type) {
		return getKeySizeByTable(getTableByType(type));
	}
	
	public int getKeySizeByTable(String table) {
		return objTableDefs.get(table).keySize;
	}

	public String generatePKString(String objTable, String mainString, String sep) {
		return repeatString(mainString, sep, getKeySizeByTable(objTable));
	}
	
	public String generatePKString(String objTable, String mainString, String sep, int ks) {
		return repeatString(mainString, sep, ks);
	}
	
	public String repeatString(String mainString, String sep, int ks) {
		String s = "";
		for(int k = 1; k <= ks; k++) {
			if(k > 1) {
				s += sep;
			}
			s += String.format(mainString, k); 
		}
		return s;
	}

	private void migrateDBSchema(JdbcTemplate jdbcTemplate) {
		int dbVersion = getIntSetting(jdbcTemplate, "opendb.version");
		if(dbVersion < OPENDB_SCHEMA_VERSION) {
			if(dbVersion <= 1) {
				setOperationsType(jdbcTemplate, OPERATIONS_TABLE);
				setOperationsType(jdbcTemplate, OPERATIONS_TRASH_TABLE);
			}
			if (dbVersion <= 2) {
				addBlockAdditionalInfo(jdbcTemplate);
			}
			setSetting(jdbcTemplate, "opendb.version", OPENDB_SCHEMA_VERSION + "");
		} else if(dbVersion > OPENDB_SCHEMA_VERSION) {
			throw new UnsupportedOperationException();
		}
	}
	
	private void handleBatch(JdbcTemplate jdbcTemplate, List<Object[]> batchArgs, String batchQuery, boolean force) {
		if(batchArgs.size() >= BATCH_SIZE || (force && batchArgs.size() > 0)) {
			jdbcTemplate.batchUpdate(batchQuery, batchArgs);
			batchArgs.clear();
		}
	}

	private void addBlockAdditionalInfo(JdbcTemplate jdbcTemplate) {
		LOGGER.info("Adding new columns : 'opcount, objdeleted, objedited, objadded' for table: " + BLOCKS_TABLE);
		jdbcTemplate.update("ALTER TABLE " + BLOCKS_TABLE + " add opcount int, add objdeleted int, add objedited int, add objadded int");

		jdbcTemplate.query("SELECT content FROM " + BLOCKS_TABLE, new ResultSetExtractor<Integer>() {
			@Override
			public Integer extractData(ResultSet rs) throws SQLException, DataAccessException {
				int i = 0;
				while (rs.next()) {
					OpBlock opBlock = formatter.parseBlock(rs.getString(1));
					int added = 0, edited = 0, deleted = 0;
					for (OpOperation opOperation : opBlock.getOperations()) {
						added += opOperation.getCreated().size();
						edited += opOperation.getEdited().size();
						deleted += opOperation.getDeleted().size();
					}
					jdbcTemplate.update("UPDATE " + BLOCKS_TABLE + " set opcount = ?, objdeleted = ?, objedited = ?, objadded = ? " +
									" WHERE hash = ?", opBlock.getOperations().size(), deleted, edited, added,
							SecUtils.getHashBytes(opBlock.getRawHash()));
					i++;
				}
				LOGGER.info("Updated " + i + " blocks");
				return null;
			}
		});
	}
	
	private void setOperationsType(JdbcTemplate jdbcTemplate, String table) {
		LOGGER.info("Indexing operation types required for db version 2: " + table);
		List<Object[]> batchArgs = new ArrayList<Object[]>();
		String batchQuery = "update " + table + " set type = ? where hash = ?";
		jdbcTemplate.query("select hash, content from " + table, new RowCallbackHandler() {

			@Override
			public void processRow(ResultSet rs) throws SQLException {
				OpOperation op = formatter.parseOperation(rs.getString(2));
				Object[] args = new Object[2];
				args[0] = op.getType();
				args[1] = rs.getObject(1);
				batchArgs.add(args);
				handleBatch(jdbcTemplate, batchArgs, batchQuery, false);
			}
		});
		handleBatch(jdbcTemplate, batchArgs, batchQuery, true);
	}

	public void initializeDatabaseSchema(MetadataDb metadataDB, JdbcTemplate jdbcTemplate) {
		createTable(metadataDB, jdbcTemplate, SETTINGS_TABLE, schema.get(SETTINGS_TABLE));
		
		prepareObjTableMapping();
		prepareCustomIndices(jdbcTemplate);
		for (String tableName : schema.keySet()) {
			if(tableName.equals(SETTINGS_TABLE))  {
				 continue;
			}
			List<ColumnDef> cls = schema.get(tableName);
			createTable(metadataDB, jdbcTemplate, tableName, cls);
		}
		migrateDBSchema(jdbcTemplate);
		
		migrateObjMappingIfNeeded(jdbcTemplate);
	}

	@SuppressWarnings("unchecked")
	private void migrateObjMappingIfNeeded(JdbcTemplate jdbcTemplate) {
		String objMapping = getSetting(jdbcTemplate, "opendb.mapping");
		String newMapping = formatter.toJsonElement(getObjtables()).toString();
		if (!OUtils.equals(newMapping, objMapping)) {
			LOGGER.info(String.format("Start object mapping migration from '%s' to '%s'", objMapping, newMapping));
			TreeMap<String, String> previousTypeToTable = new TreeMap<>();
			if (objMapping != null && objMapping.length() > 0) {
				TreeMap<String, Object> previousMapping = formatter.fromJsonToTreeMap(objMapping);
				for(String tableName : previousMapping.keySet()) {
					List<String> otypes = ((Map<String, List<String>>) previousMapping.get(tableName)).get("types");
					if(otypes != null) {
						for(String type : otypes) {
							previousTypeToTable.put(type, tableName);
						}
					}
				}
			}

			for (String tableName : objTableDefs.keySet()) {
				ObjectTypeTable ott = objTableDefs.get(tableName);
				for (String type : ott.types) {
					String prevTable = previousTypeToTable.remove(type);
					if (prevTable == null) {
						prevTable = OBJS_TABLE;
					}
					migrateObjDataBetweenTables(type, tableName, prevTable, ott.keySize, jdbcTemplate);
				}
			}
			for (String type : previousTypeToTable.keySet()) {
				String prevTable = previousTypeToTable.get(type);
				int keySize = objTableDefs.get(prevTable).keySize;
				migrateObjDataBetweenTables(type, OBJS_TABLE, prevTable, keySize, jdbcTemplate);
			}
			setSetting(jdbcTemplate, "opendb.mapping", newMapping);
		}
				
	}

	private void migrateObjDataBetweenTables(String type, String tableName, String prevTable, int keySize, JdbcTemplate jdbcTemplate) {
		if(!OUtils.equals(tableName, prevTable)) {
			LOGGER.info(String.format("Migrate objects of type '%s' from '%s' to '%s'...", type, prevTable, tableName));
			// compare existing table
			String pks = "";
			for(int i = 1; i <= keySize; i++) {
				pks += ", p" +i; 
			}
			int update = jdbcTemplate.update(
					"WITH moved_rows AS ( DELETE FROM " + prevTable + " a WHERE type = ? RETURNING a.*) " +
					"INSERT INTO " + tableName + "(type, ophash, superblock, sblockid, sorder, content " + pks + ") " +
					"SELECT type, ophash, superblock, sblockid, sorder, content" + pks + " FROM moved_rows", type);

			
			LOGGER.info(String.format("Migrate %d objects of type '%s'.", update, type));
		}
	}

	private void prepareCustomIndices(JdbcTemplate jdbcTemplate) {
		String customIndices = getSetting(jdbcTemplate, "opendb.indices");
		if (customIndices != null) {
			RequestIndexBody[] objects = formatter.fromJsonToListIndices(customIndices);

			for(RequestIndexBody requestIndexBody : objects) {
				IndexType indexType;
				if(requestIndexBody.index.equals("true")) {
					indexType = INDEXED;
				} else {
					indexType = IndexType.valueOf(requestIndexBody.index);
				}

				ColumnDef columnDef = new ColumnDef(requestIndexBody.tableName, requestIndexBody.colName, requestIndexBody.colType, indexType);

				List<String> types = Arrays.asList(requestIndexBody.types);
				if (requestIndexBody.field != null) {
					generateIndexColumn(requestIndexBody, columnDef, types);
				} else {
					for (String type : types) {
						addIndexCol(new OpIndexColumn(type, requestIndexBody.colName, -1, columnDef));
					}
				}

				registerColumn(requestIndexBody.tableName, columnDef);
			}
		}
	}

	private void generateIndexColumn(RequestIndexBody requestIndexBody, ColumnDef columnDef, List<String> types) {
		for (String typ : types) {
			OpIndexColumn indexColumn = new OpIndexColumn(typ, requestIndexBody.colName, -1, columnDef);
			if (requestIndexBody.cacheRuntimeMax != null) {
				indexColumn.setCacheRuntimeBlocks(requestIndexBody.cacheRuntimeMax);
			}
			if (requestIndexBody.cacheDbIndex != null) {
				indexColumn.setCacheDBBlocks(requestIndexBody.cacheDbIndex);
			}
			indexColumn.setFieldsExpression(requestIndexBody.field);
			addIndexCol(indexColumn);
		}
	}

	@SuppressWarnings("unchecked")
	private void prepareObjTableMapping() {
		for(String tableName : getObjtables().keySet()) {
			Integer i = ((Number)getObjtables().get(tableName).get("keysize")).intValue();
			if(i == null) {
				i = MAX_KEY_SIZE;
			}
			registerObjTable(tableName, i);
			ObjectTypeTable ott = new ObjectTypeTable(tableName, i);
			objTableDefs.put(tableName, ott);
			
			
			List<String> tps = (List<String>) getObjtables().get(tableName).get("types");
			if(tps != null) {
				for(String type : tps) {
					typeToTables.put(type, tableName);
					ott.types.add(type);
					for(ColumnDef c : schema.get(tableName)) {
						for(int indId = 0 ; indId < MAX_KEY_SIZE; indId++) {
							if(c.getColName().equals(INDEX_P[indId])) {
								addIndexCol(new OpIndexColumn(type, INDEX_P[indId], indId, c));
							}
						}
					}
				}
			}
			List<Map<String, Object>> cii = (List<Map<String, Object>>) getObjtables().get(tableName).get("columns");
			if (cii != null) {
				for (Map<String, Object> entry : cii) {
					String name = (String) entry.get("name");
					String colType = (String) entry.get("sqltype");
					String index = (String) entry.get("index");
					Integer cacheRuntime = entry.get("cache-runtime-max") == null ? null : ((Number) entry.get("cache-runtime-max")) .intValue();
					Integer cacheDB = entry.get("cache-db-max") ==  null ? null : ((Number) entry.get("cache-db-max")).intValue();
					IndexType di = null;
					if(index != null) {
						if(index.equalsIgnoreCase("true")) {
							di = INDEXED;
						} else {
							di = IndexType.valueOf(index);
						}
					}
					
					ColumnDef cd = new ColumnDef(tableName, name, colType, di);
					// to be used array
					// String sqlmapping = (String) entry.get("sqlmapping");
					
					List<String> fld = (List<String>) entry.get("field");
					if (fld != null) {
						for (String type : ott.types) {
							OpIndexColumn indexColumn = new OpIndexColumn(type, name, -1,  cd);
							if(cacheRuntime != null) {
								indexColumn.setCacheRuntimeBlocks(cacheRuntime);
							}
							if(cacheDB != null) {
								indexColumn.setCacheDBBlocks(cacheDB);
							}
							indexColumn.setFieldsExpression(fld);
							addIndexCol(indexColumn);
						}
					}
					registerColumn(tableName, cd);
				}
			}
			
		}
		objTableDefs.put(OBJS_TABLE, new ObjectTypeTable(OBJS_TABLE, MAX_KEY_SIZE));
	}

	private void addIndexCol(OpIndexColumn indexColumn) {
		if (!indexes.containsKey(indexColumn.getOpType())) {
			indexes.put(indexColumn.getOpType(), new TreeMap<String, OpIndexColumn>());
		}
		indexes.get(indexColumn.getOpType()).put(indexColumn.getIndexId(), indexColumn);
	}

	public boolean addNewDbIndex(RequestIndexBody requestIndexBody, JdbcTemplate jdbcTemplate) {
		List<ColumnDef> tableDefs = schema.get(requestIndexBody.tableName);
		if (tableDefs == null) {
			return false;
		}

		boolean found = false;
		for (ColumnDef columnDef : tableDefs) {
			if (columnDef.getColName().equals(requestIndexBody.colName)) {
				found = true;
				break;
			}
		}

		if (found) {
			return false;
		}

		IndexType indexType;
		List<String> type = Arrays.asList(requestIndexBody.types);
		if(requestIndexBody.index.equalsIgnoreCase("true")) {
			indexType = INDEXED;
		} else {
			indexType = IndexType.valueOf(requestIndexBody.index);
		}

		ColumnDef columnDef = new ColumnDef(requestIndexBody.tableName, requestIndexBody.colName, requestIndexBody.colType, indexType);
		if (requestIndexBody.field != null) {
			generateIndexColumn(requestIndexBody, columnDef, type);
		} else {
			addIndexCol(new OpIndexColumn(type.get(0), requestIndexBody.colName, -1, columnDef));
		}
		registerColumn(requestIndexBody.tableName, columnDef);

		String alterTable = String.format("alter table %s add column %s %s", requestIndexBody.tableName,
				columnDef.getColName(), columnDef.getColType());
		jdbcTemplate.execute(alterTable);
		if(columnDef.getIndex() != NOT_INDEXED) {
			jdbcTemplate.execute(generateIndexQuery(columnDef));
		}

		String customIndices = getSetting(jdbcTemplate, "opendb.indices");
		List<RequestIndexBody> objects;
		if (customIndices != null) {
			objects = new ArrayList<>(Arrays.asList(formatter.fromJsonToListIndices(customIndices)));
		} else {
			objects = new ArrayList<>();
		}
		objects.add(requestIndexBody);
		setSetting(jdbcTemplate, "opendb.indices", formatter.listIndicesToJson(objects));

		return true;
	}

	private void createTable(MetadataDb metadataDB, JdbcTemplate jdbcTemplate, String tableName, List<ColumnDef> cls) {
		List<MetadataColumnSpec> list = metadataDB.tablesSpec.get(tableName);
		if (list == null) {
			StringBuilder clb = new StringBuilder();
			List<String> indx = new ArrayList<String>();
			for (ColumnDef c : cls) {
				if (clb.length() > 0) {
					clb.append(", ");
				}
				clb.append(c.getColName()).append(" ").append(c.getColType());
				if(c.getIndex() != NOT_INDEXED) {
					indx.add(generateIndexQuery(c));
				}
			}
			String createTable = String.format("create table %s (%s)", tableName, clb.toString());
			jdbcTemplate.execute(createTable);
			for (String ind : indx) {
				jdbcTemplate.execute(ind);
			}
		} else {
			for (ColumnDef c : cls) {
				boolean found = false;
				for (MetadataColumnSpec m : list) {
					if (c.getColName().equals(m.columnName)) {
						found = true;
						break;
						
					}
				}
				if (!found) {
					String alterTable = String.format("alter table %s add column %s %s", tableName, 
							c.getColName(), c.getColType());
					jdbcTemplate.execute(alterTable);
					if(c.getIndex() != NOT_INDEXED) {
						jdbcTemplate.execute(generateIndexQuery(c));
					}
				}
			}
		}
	}

	private String generateIndexQuery(ColumnDef c) {
		if (c.getIndex() == INDEXED) {
			return String.format("create index %s_%s_ind on %s (%s);\n", c.getTableName(), c.getColName(),
					c.getTableName(), c.getColName());
		} else if (c.getIndex() == GIN) {
			return String.format("create index %s_%s_gin_ind on %s using gin (%s);\n", c.getTableName(), c.getColName(),
					c.getTableName(), c.getColName());
		} else if (c.getIndex() == GIST) {
			return String.format("create index %s_%s_gist_ind on %s using gist (tsvector(%s));\n", c.getTableName(),
					c.getColName(), c.getTableName(), c.getColName());
		}

		return null;
	}


	protected boolean setSetting(JdbcTemplate jdbcTemplate, String key, String v) {
		return jdbcTemplate.update("insert into  " + SETTINGS_TABLE + "(key,value) values (?, ?) "
				+ " ON CONFLICT (key) DO UPDATE SET value = ? ", key, v, v) != 0;
	}
	
	private int getIntSetting(JdbcTemplate jdbcTemplate, String key) {
		String s = getSetting(jdbcTemplate, key);
		if(s == null) {
			return 0;
		}
		return Integer.parseInt(s);
	}

	protected String getSetting(JdbcTemplate jdbcTemplate, String key) {
		String s = null;
		try {
			s = jdbcTemplate.query("select value from " + SETTINGS_TABLE + " where key = ?", new ResultSetExtractor<String>() {
				@Override
				public String extractData(ResultSet rs) throws SQLException, DataAccessException {
					boolean next = rs.next();
					if (next) {
						return rs.getString(1);
					}
					return null;
				}
			}, key);
		} catch (DataAccessException e) {
		}
		return s;
	}

	public OpIndexColumn getIndex(String type, String columnId) {
		Map<String, OpIndexColumn> tind = indexes.get(type);
		if(tind != null) {
			return tind.get(columnId);
		}
		return null;
	}
	
	public Collection<OpIndexColumn> getIndicesForType(String type) {
		if(type == null) {
			List<OpIndexColumn> ind = new ArrayList<>();
			Iterator<Map<String, OpIndexColumn>> it = indexes.values().iterator();
			while(it.hasNext()) {
				ind.addAll(it.next().values());
			}
			return ind;
		}
		Map<String, OpIndexColumn> tind = indexes.get(type);
		if(tind != null) {
			return tind.values();
		}
		return Collections.emptyList();
	}

	public void insertObjIntoTableBatch(List<Object[]> args, String table, JdbcTemplate jdbcTemplate, Collection<OpIndexColumn> indexes) {
		StringBuilder extraColumnNames = new StringBuilder();
		for(OpIndexColumn index : indexes) {
			extraColumnNames.append(index.getColumnDef().getColName()).append(",");
		}
		jdbcTemplate.batchUpdate("INSERT INTO " + table
				+ "(type,ophash,superblock,sblockid,sorder,content,"
				+ extraColumnNames.toString()
				+ generatePKString(table, "p%1$d", ",") + ") "
				+ " values(?,?,?,?,?,?," + repeatString("?,", "", indexes.size()) + generatePKString(table, "?", ",") + ")", args);
	}

	public void insertObjIntoHistoryTableBatch(List<Object[]> args, String table, JdbcTemplate jdbcTemplate) {
		jdbcTemplate.batchUpdate("INSERT INTO " + table + "(blockhash, ophash, type, time, obj, status," +
				generatePKString(table, "usr_%1$d, login_%1$d", ",", HISTORY_USERS_SIZE) + "," +
				generatePKString(table, "p%1$d", ",", MAX_KEY_SIZE) + ") VALUES ("+
				generatePKString(table, "?", ",", HISTORY_USERS_SIZE * 2 + MAX_KEY_SIZE + 6 ) + ")", args);
	}

	// Query / insert values
	// select encode(b::bytea, 'hex') from test where b like (E'\\x39')::bytea||'%';
	// insert into test(b) values (decode('39556d070fd95f54b554010207d42605a8d0adfbb3b8b8e134df7df0689d78ab', 'hex'));
	// UPDATE blocks SET superblocks = array_remove(superblocks,
	// decode('39556d070fd95f54b554010207d42605a8d0adfbb3b8b8e134df7df0689d78ab', 'hex'));

	public static void main(String[] args) {

		for (String tableName : schema.keySet()) {
			List<ColumnDef> cls = schema.get(tableName);
			StringBuilder clb = new StringBuilder();
			StringBuilder indx = new StringBuilder();
			for (ColumnDef c : cls) {
				if (clb.length() > 0) {
					clb.append(", ");
				}
				clb.append(c.getColName()).append(" ").append(c.getColType());
				if (c.getIndex() == INDEXED) {
					indx.append(String.format("create index %s_%s_ind on %s (%s);\n", c.getTableName(), c.getColName(),
							c.getTableName(), c.getColName()));
				}
			}
			System.out.println(String.format("create table %s (%s);", tableName, clb.toString()));
			System.out.println(indx.toString());
		}

	}

	

	
	


}
