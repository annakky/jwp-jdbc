package core.jdbc;

import core.util.ReflectionUtils;
import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static core.jdbc.ConnectionManager.getConnection;
import static core.util.ReflectionUtils.hasFieldMethod;
import static core.util.StringUtil.upperFirstChar;

// 너무 많은 일을 하고 있는거 같다.. convert를 따로 빼야할까? 어떻게 빼야할까...
public class JdbcApi<T> {
    private static final ParameterNameDiscoverer nameDiscoverer = new LocalVariableTableParameterNameDiscoverer();

    public final Class<T> clazz;

    public JdbcApi(Class<T> clazz) {
        this.clazz = clazz;
    }

    public void execute(final String sql, final Object... values) {
        try(Connection connection = getConnection()) {
            PreparedStatement preparedStatement = prepareStatement(connection, sql, values);

            preparedStatement.execute();
        } catch (Exception e) {
            throw new JdbcApiException("Fail to execute query : " + e.getMessage());
        }
    }

    public List<T> findAll(final String sql, final Object... values) {
        return executeQuery(sql, values);
    }

    public T findOne(final String sql, final Object... values) {
        List<T> results = findAll(sql, values);
        validUnique(results);

        return results.isEmpty() ? null : results.get(0);
    }

    private List<T> executeQuery(final String sql, final Object... values) {
        try(Connection connection = getConnection()) {
            PreparedStatement preparedStatement = prepareStatement(connection, sql, values);

            ResultSet resultSet = executeQuery(preparedStatement);

            return convertToClasses(resultSet);
        } catch (Exception e) {
            throw new JdbcApiException("Fail to execute select query : " + e.getMessage());
        }
    }

    private List<T> convertToClasses(ResultSet resultSet) throws SQLException {
        List<T> classes = new ArrayList<>();

        while (resultSet.next()) {
            classes.add(convert(resultSet));
        }

        return classes;
    }

    private T convert(ResultSet resultSet) {
        try {
            return resolveArgumentInternal(resultSet);
        } catch (Exception e) {
            throw new JdbcApiException("Fail to convert result set to target dao : " + clazz.getName());
        }
    }

    private void validUnique(List<T> classes) {
        if (classes.size() > 1) {
            throw new IllegalArgumentException("Query result not unique");
        }
    }

    private ResultSet executeQuery(PreparedStatement preparedStatement) {
        try {
            return preparedStatement.executeQuery();
        } catch (SQLException throwables) {
            throw new JdbcApiException("Fail to execute query : " + throwables.getMessage());
        }
    }

    private void setValues(PreparedStatement preparedStatement, Object... values) throws SQLException {
        for (int idx = 0 ; idx < values.length ; ++idx) {
            preparedStatement.setObject(idx + 1, values[idx]);
        }
    }

    private PreparedStatement prepareStatement(Connection connection, String sql, Object... values) {
        try {
            PreparedStatement preparedStatement = connection.prepareStatement(sql);
            setValues(preparedStatement, values);

            return preparedStatement;
        } catch (SQLException throwables) {
            throw new JdbcApiException("Fail to connect to db server : " + throwables.getMessage());
        }
    }

    private T resolveArgumentInternal(ResultSet resultSet) throws IllegalAccessException, InstantiationException, InvocationTargetException, NoSuchMethodException, SQLException {
        T dao = getDefaultInstance(resultSet);

        for (Field field : clazz.getDeclaredFields()) {
            populateArgument(dao, clazz, field, resultSet);
        }

        return dao;
    }

    private void populateArgument(Object target, Class<T> clazz, Field field, ResultSet resultSet) throws InvocationTargetException, IllegalAccessException, NoSuchMethodException, SQLException {
        String setterMethod = "set" + upperFirstChar(field.getName());

        if (hasFieldMethod(clazz, setterMethod, field.getType())) {
            Method method = clazz.getDeclaredMethod(setterMethod, field.getType());
            method.invoke(target, ReflectionUtils.convertStringValue(resultSet.getString(field.getName()), field.getType()));
        }
    }

    private T getDefaultInstance(ResultSet resultSet) throws IllegalAccessException, InvocationTargetException, InstantiationException, SQLException {
        for (Constructor constructor : clazz.getConstructors()) {
            Object[] args = extractArguments(resultSet, constructor);

            Object arg = constructor.newInstance(args);

            return clazz.cast(arg);
        }

        throw new IllegalStateException("[" + clazz.getName() + "] supported constructor is empty");
    }

    private Object[] extractArguments(ResultSet resultSet, Constructor constructor) throws SQLException {
        String[] parameterNames = nameDiscoverer.getParameterNames(constructor);
        Class[] parameterTypes = constructor.getParameterTypes();

        Object[] args = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            args[i] = parameterTypes[i].cast(resultSet.getString(parameterNames[i]));
        }

        return args;
    }
}
