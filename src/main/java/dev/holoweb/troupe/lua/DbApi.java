package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.api.Callback;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The server's {@code db} module: one SQL database for every resource (the prelude turns these into calls that wait).
 * <pre>
 * local rows = db.query('SELECT nome, saldo FROM contas WHERE saldo > ?', { 10 })     rows[1].nome
 * local r = db.execute('INSERT INTO contas (nome, saldo) VALUES (?, ?)', { 'Ana', 5 })   r.changes  r.id
 * db.execute('UPDATE contas SET apelido = ? WHERE id = ?', nil, 1)      -- one by one, a nil is a NULL; in a list, db.null
 * db.batch({ { 'UPDATE contas SET saldo = saldo - ? WHERE id = ?', { 5, 1 } }, { 'UPDATE ...', { 5, 2 } } })   -- all or nothing
 * </pre>
 * One connection on one worker thread: statements run in the order they were asked for, off the game thread. There
 * are no transactions across calls (other tasks' statements would land inside them); {@code batch} is the atomic unit.
 * SQLite by default (its driver ships in the jar); any JDBC URL works when its driver is on the classpath.
 * ponytail: one connection; a pool when one busy resource makes the others wait.
 */
public final class DbApi {
    private static final int MAX_ROWS = 10_000;

    private interface Job {
        Object run(Connection c) throws SQLException;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Troupe database");
        t.setDaemon(true);
        return t;
    });
    private volatile String url;
    private volatile String driver;
    private volatile Properties login = new Properties();
    private Connection connection; // worker thread only

    public DbApi(ModuleRegistry modules) {
        modules.global("db")
                .function("query", a -> {
                    String sql = a.string(0);
                    List<Object> params = params(a.get(1));
                    return submit(a.callback(2), c -> query(c, sql, params));
                })
                .function("execute", a -> {
                    String sql = a.string(0);
                    List<Object> params = params(a.get(1));
                    return submit(a.callback(2), c -> execute(c, sql, params));
                })
                .function("batch", a -> {
                    List<Object[]> statements = new ArrayList<>();
                    for (Object entry : a.list(0)) {
                        if (entry instanceof String sql) statements.add(new Object[]{sql, List.of()});
                        else if (entry instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof String sql)
                            statements.add(new Object[]{sql, params(l.size() > 1 ? l.get(1) : null)});
                        else throw new IllegalArgumentException("each statement is 'sql' or { 'sql', { params } }");
                    }
                    return submit(a.callback(1), c -> batch(c, statements));
                });
    }

    /**
     * Where the data is, from the server's start on. {@code driver}: the driver's class when the JVM doesn't find
     * it from the URL (a jar in mods/ isn't visible to DriverManager); null for SQLite and for drivers it does find.
     */
    public void open(String url, String driver, String user, String password) {
        Properties p = new Properties();
        if (user != null) p.setProperty("user", user);
        if (password != null) p.setProperty("password", password);
        this.login = p;
        this.driver = driver != null ? driver : url.startsWith("jdbc:sqlite:") ? "org.sqlite.JDBC" : null;
        this.url = url;
    }

    /** The server is stopping: what was already asked for still runs, then the connection closes. */
    public void close() {
        url = null;
        try {
            worker.submit(this::disconnect).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LuaRuntime.LOG.error("The database didn't finish its work before the server stopped", e);
        }
    }

    private Object submit(Callback done, Job job) {
        String target = url;
        if (target == null) throw new IllegalStateException("there is no database while the server isn't running");
        worker.execute(() -> {
            try {
                done.invoke(null, job.run(connection(target)));
            } catch (Exception | LinkageError e) {
                done.invoke(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
        return null;
    }

    private Connection connection(String target) throws SQLException {
        if (connection != null && connection.isValid(2)) return connection;
        disconnect();
        String d = driver;
        if (d == null) connection = DriverManager.getConnection(target, login);
        else {
            try {
                connection = ((Driver) Class.forName(d, true, DbApi.class.getClassLoader()).getDeclaredConstructor().newInstance()).connect(target, login);
            } catch (ReflectiveOperationException e) {
                throw new SQLException("no database driver '" + d + "' (put its jar in mods/)");
            }
        }
        if (connection == null) throw new SQLException("the driver doesn't open this database URL");
        return connection;
    }

    private void disconnect() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
        connection = null;
    }

    /** The prelude sends a list with no holes, where a table stands for NULL ({@code nil} or {@code db.null} in the script). */
    private static List<Object> params(Object params) {
        if (params == null || params instanceof Map<?, ?> m && m.isEmpty()) return List.of();
        if (!(params instanceof List<?> l)) throw new IllegalArgumentException("parameters are a list: { 1, 'a' }");
        List<Object> out = new ArrayList<>(l.size());
        for (Object v : l) out.add(v instanceof Map || v instanceof List ? null : v);
        return out;
    }

    private static PreparedStatement prepare(Connection c, String sql, List<Object> params, boolean keys) throws SQLException {
        PreparedStatement st = keys ? c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS) : c.prepareStatement(sql);
        for (int i = 0; i < params.size(); i++) st.setObject(i + 1, params.get(i));
        return st;
    }

    private static List<Object> query(Connection c, String sql, List<Object> params) throws SQLException {
        try (PreparedStatement st = prepare(c, sql, params, false); ResultSet rs = st.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            List<Object> rows = new ArrayList<>();
            while (rs.next()) {
                if (rows.size() == MAX_ROWS) throw new SQLException("more than " + MAX_ROWS + " rows: add a LIMIT");
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) row.put(meta.getColumnLabel(i), value(rs.getObject(i)));
                rows.add(row);
            }
            return rows;
        }
    }

    private static Map<String, Object> execute(Connection c, String sql, List<Object> params) throws SQLException {
        try (PreparedStatement st = prepare(c, sql, params, true)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("changes", (long) st.executeUpdate());
            try (ResultSet keys = st.getGeneratedKeys()) {
                if (keys != null && keys.next()) result.put("id", value(keys.getObject(1)));
            } catch (SQLException ignored) { // not every driver or statement has them
            }
            return result;
        }
    }

    private static List<Object> batch(Connection c, List<Object[]> statements) throws SQLException {
        c.setAutoCommit(false);
        try {
            List<Object> changes = new ArrayList<>();
            for (Object[] s : statements) {
                @SuppressWarnings("unchecked") List<Object> params = (List<Object>) s[1];
                try (PreparedStatement st = prepare(c, (String) s[0], params, false)) {
                    changes.add((long) st.executeUpdate());
                }
            }
            c.commit();
            return changes;
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    /** What Lua can hold: whole numbers, decimals, booleans, text. Anything else (dates, blobs) arrives as text. */
    private static Object value(Object v) {
        if (v == null || v instanceof Boolean || v instanceof String) return v;
        if (v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long || v instanceof java.math.BigInteger) return ((Number) v).longValue();
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof byte[] b) return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        return v.toString();
    }
}
